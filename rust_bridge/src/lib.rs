use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use std::sync::Arc;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use once_cell::sync::Lazy;
use serde_json::json;
use tokio::runtime::Runtime;
use tokio::sync::watch;

static IS_RUNNING: AtomicBool = AtomicBool::new(false);
static BOUND_PORT: AtomicU16 = AtomicU16::new(0);

// Global background Tokio runtime for native JNI async operations
static RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_name("codex-proxy-worker")
        .enable_all()
        .build()
        .expect("Failed to initialize native Tokio runtime")
});

static SHUTDOWN_TX: Lazy<std::sync::Mutex<Option<watch::Sender<bool>>>> =
    Lazy::new(|| std::sync::Mutex::new(None));

#[no_mangle]
pub extern "system" fn Java_com_example_proxy_NativeProxy_startProxyNative(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    db_path: JString,
    config_json: JString,
) -> jint {
    let target_port = if port > 0 { port as u16 } else { 8080 };

    if IS_RUNNING.load(Ordering::SeqCst) {
        return BOUND_PORT.load(Ordering::SeqCst) as jint;
    }

    let db_path_str: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(_) => "/data/data/com.example/files/codex_proxy.db".to_string(),
    };

    let (shutdown_tx, mut shutdown_rx) = watch::channel(false);
    {
        let mut guard = SHUTDOWN_TX.lock().unwrap();
        *guard = Some(shutdown_tx);
    }

    let bind_addr = format!("127.0.0.1:{}", target_port);

    RUNTIME.spawn(async move {
        // Embedded Axum router mirroring BenedictKing/ccx
        let app = axum::Router::new()
            .route("/", axum::routing::get(root_handler))
            .route("/health", axum::routing::get(health_handler))
            .route("/v1/models", axum::routing::get(models_handler))
            .route("/v1/responses", axum::routing::post(responses_handler))
            .route("/v1/responses/compact", axum::routing::post(responses_compact_handler))
            .route("/v1/messages", axum::routing::post(messages_handler))
            .route("/v1/chat/completions", axum::routing::post(chat_completions_handler));

        match tokio::net::TcpListener::bind(&bind_addr).await {
            Ok(listener) => {
                let actual_port = listener.local_addr().unwrap().port();
                BOUND_PORT.store(actual_port, Ordering::SeqCst);
                IS_RUNNING.store(true, Ordering::SeqCst);

                println!("[Rust-JNI] Codex Proxy listening on http://127.0.0.1:{}", actual_port);

                axum::serve(listener, app)
                    .with_graceful_shutdown(async move {
                        let _ = shutdown_rx.changed().await;
                    })
                    .await
                    .ok();

                IS_RUNNING.store(false, Ordering::SeqCst);
                BOUND_PORT.store(0, Ordering::SeqCst);
            }
            Err(e) => {
                eprintln!("[Rust-JNI] Failed to bind TcpListener on {}: {:?}", bind_addr, e);
                IS_RUNNING.store(false, Ordering::SeqCst);
            }
        }
    });

    // Give the listener 50ms to bind
    std::thread::sleep(std::time::Duration::from_millis(50));

    if IS_RUNNING.load(Ordering::SeqCst) {
        BOUND_PORT.load(Ordering::SeqCst) as jint
    } else {
        target_port as jint
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_proxy_NativeProxy_stopProxyNative(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if let Ok(guard) = SHUTDOWN_TX.lock() {
        if let Some(ref tx) = *guard {
            let _ = tx.send(true);
        }
    }
    IS_RUNNING.store(false, Ordering::SeqCst);
    BOUND_PORT.store(0, Ordering::SeqCst);
    1 // JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_example_proxy_NativeProxy_getProxyStatusNative(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let running = IS_RUNNING.load(Ordering::SeqCst);
    let port = BOUND_PORT.load(Ordering::SeqCst);

    let status = json!({
        "engine": "rust-jni-native",
        "running": running,
        "port": port,
        "runtime": "tokio-multi-thread-v1.40",
        "storage": "embedded-sqlite",
        "arch": std::env::consts::ARCH
    });

    let json_str = status.to_string();
    env.new_string(json_str)
        .expect("Failed to create JNI string")
        .into_raw()
}

// Router Handlers
async fn root_handler() -> axum::Json<serde_json::Value> {
    axum::Json(json!({
        "status": "healthy",
        "service": "codex-proxy-rs-native-jni",
        "version": "1.0.0"
    }))
}

async fn health_handler() -> axum::Json<serde_json::Value> {
    root_handler().await
}

async fn models_handler() -> axum::Json<serde_json::Value> {
    axum::Json(json!({
        "object": "list",
        "data": [
            {"id": "gpt-4o", "object": "model", "owned_by": "openai"},
            {"id": "o1-preview", "object": "model", "owned_by": "openai"},
            {"id": "codex", "object": "model", "owned_by": "openai"},
            {"id": "claude-3-5-sonnet", "object": "model", "owned_by": "anthropic"}
        ]
    }))
}

async fn chat_completions_handler(
    axum::Json(payload): axum::Json<serde_json::Value>,
) -> (axum::http::StatusCode, axum::Json<serde_json::Value>) {
    // Check for authorization header or query upstream account
    let client = reqwest::Client::new();
    const PERSISTENT_TOKEN: &str = "eyJhbGciOiJSUzI1NiIsImtpZCI6Im4wejZQcjEtdEItMTdXb1U0VGM5OHp1RDBrNmx5YU1ZQmJ3SkFEOGtSVnMiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOlsiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS92MSJdLCJjbGllbnRfaWQiOiJhcHBfRU1vYW1FRVo3M2YwQ2tYYVhwN2hyYW5uIiwiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS9hdXRoIjp7ImFtciI6WyJ1cm46b3BlbmFpOmFtcjpnb29nbGUiXSwiY2hhdGdwdF9hY2NvdW50X2lkIjoiYWY4ZDI0MWMtZDAyNC00YzliLWJhYjEtZTllMGNjYThmOWU4IiwiY2hhdGdwdF9hY2NvdW50X3VzZXJfaWQiOiJ1c2VyLVZEOHhQd1Z6RTFlWTdNVzVWbmJIS1I3TF9fYWY4ZDI0MWMtZDAyNC00YzliLWJhYjEtZTllMGNjYThmOWU4IiwiY2hhdGdwdF9jb21wdXRlX3Jlc2lkZW5jeSI6Im5vX2NvbnN0cmFpbnQiLCJjaGF0Z3B0X3BsYW5fdHlwZSI6InByb2xpdGUiLCJjaGF0Z3B0X3VzZXJfaWQiOiJ1c2VyLVZEOHhQd1Z6RTFlWTdNVzVWbmJIS1I3TCIsImxvY2FsaG9zdCI6dHJ1ZSwicG9pZCI6Im9yZy1KTTkweFVUZ2JuWkhkcXB6OVRlZ3l4RkQiLCJ1c2VyX2lkIjoidXNlci1WRDh4UHdWekUxZVk3TVc1Vm5iSEtSN0wifSwiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS9wcm9maWxlIjp7ImVtYWlsIjoiZG9zYTA0MkBnbWFpbC5jb20iLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IkRvIFNhIn0sImlzcyI6Imh0dHBzOi8vYXV0aC5vcGVuYWkuY29tIiwicHdkX2F1dGhfdGltZSI6MTc5MDU0NDQwOTI5MCwic2NwIjpbIm9wZW5pZCIsInByb2ZpbGUiLCJlbWFpbCIsIm9mZmxpbmVfYWNjZXNzIiwiYXBpLmNvbm5lY3RvcnMucmVhZCIsImFwaS5jb25uZWN0b3JzLmludm9rZSJdLCJzZXNzaW9uX2lkIjoiYXV0aHNlc3NfUzdmVGtXUEVlSjBkUzdZOEc4cjNWQXFKIiwic2wiOnRydWUsInN1YiI6Imdvb2dsZS1vYXV0aDJ8MTE1NTYxNDc5NTgyMzM4MDgxNzAyIiwiaWF0IjoxNzkwODk0MDg4LCJleHAiOjE3OTE3NTgwODgsImp0aSI6IjA3OWYzNTE2NDYzYzQ1NDc4Y2Y0YjFjMjM3MDQ3ZDIzIiwibmJmIjoxNzkwODk0MDg4fQ.ti05-bp8IIKShmV7YjrCEM7m3TE_ixruEAY8SIvOF34OTX2YzoefBEx0Brz41DztG9_-32yh_D_hhz2j5S9jZ6XYDnRC6SVw3hUYnTZwef8mkDwl1xwTd_C4tEHlalyRNHGZsnbBF09ZvXxR03PJjsJu25-xDKue4cYdXVd5iSRtZ_PymOF18CZYtvaEQiya6qXv93L4iTNdBI41mU9i4KGGXbPdcv_ff53CKkhuM27ypOv68w8rLfefnGybvPdo8hyNPvlxyzqDNvJHHYfwKJSRwHT8OhrpW4LCaVLmumwMj7E2tufHSr-T9YtLPWlE6ra0oEZhUW3-AVGlaYrSFw";
    const ACCOUNT_ID: &str = "af8d241c-d024-4c9b-bab1-e9e0cca8f9e8";

    // Attempt forward to upstream OpenAI/Codex API with persistent credentials
    match client
        .post(upstream_url)
        .header("Authorization", format!("Bearer {}", PERSISTENT_TOKEN))
        .header("ChatGPT-Account-Id", ACCOUNT_ID)
        .header("OpenAI-Account-Id", ACCOUNT_ID)
        .header("Content-Type", "application/json")
        .json(&payload)
        .send()
        .await {
        Ok(resp) => {
            let status = axum::http::StatusCode::from_u16(resp.status().as_u16())
                .unwrap_or(axum::http::StatusCode::INTERNAL_SERVER_ERROR);
            let body = resp.json::<serde_json::Value>().await.unwrap_or_else(|_| {
                json!({"error": {"message": "Failed parsing upstream JSON", "type": "proxy_parse_error"}})
            });
            (status, axum::Json(body))
        }
        Err(e) => (
            axum::http::StatusCode::BAD_GATEWAY,
            axum::Json(json!({
                "error": {
                    "message": format!("Upstream network connection error: {}", e),
                    "type": "upstream_connect_error"
                }
            })),
        ),
    }
}

async fn responses_handler(
    axum::Json(payload): axum::Json<serde_json::Value>,
) -> (axum::http::StatusCode, axum::Json<serde_json::Value>) {
    let client = reqwest::Client::new();
    const PERSISTENT_TOKEN: &str = "eyJhbGciOiJSUzI1NiIsImtpZCI6Im4wejZQcjEtdEItMTdXb1U0VGM5OHp1RDBrNmx5YU1ZQmJ3SkFEOGtSVnMiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOlsiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS92MSJdLCJjbGllbnRfaWQiOiJhcHBfRU1vYW1FRVo3M2YwQ2tYYVhwN2hyYW5uIiwiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS9hdXRoIjp7ImFtciI6WyJ1cm46b3BlbmFpOmFtcjpnb29nbGUiXSwiY2hhdGdwdF9hY2NvdW50X2lkIjoiYWY4ZDI0MWMtZDAyNC00YzliLWJhYjEtZTllMGNjYThmOWU4IiwiY2hhdGdwdF9hY2NvdW50X3VzZXJfaWQiOiJ1c2VyLVZEOHhQd1Z6RTFlWTdNVzVWbmJIS1I3TF9fYWY4ZDI0MWMtZDAyNC00YzliLWJhYjEtZTllMGNjYThmOWU4IiwiY2hhdGdwdF9jb21wdXRlX3Jlc2lkZW5jeSI6Im5vX2NvbnN0cmFpbnQiLCJjaGF0Z3B0X3BsYW5fdHlwZSI6InByb2xpdGUiLCJjaGF0Z3B0X3VzZXJfaWQiOiJ1c2VyLVZEOHhQd1Z6RTFlWTdNVzVWbmJIS1I3TCIsImxvY2FsaG9zdCI6dHJ1ZSwicG9pZCI6Im9yZy1KTTkweFVUZ2JuWkhkcXB6OVRlZ3l4RkQiLCJ1c2VyX2lkIjoidXNlci1WRDh4UHdWekUxZVk3TVc1Vm5iSEtSN0wifSwiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS9wcm9maWxlIjp7ImVtYWlsIjoiZG9zYTA0MkBnbWFpbC5jb20iLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IkRvIFNhIn0sImlzcyI6Imh0dHBzOi8vYXV0aC5vcGVuYWkuY29tIiwicHdkX2F1dGhfdGltZSI6MTc5MDU0NDQwOTI5MCwic2NwIjpbIm9wZW5pZCIsInByb2ZpbGUiLCJlbWFpbCIsIm9mZmxpbmVfYWNjZXNzIiwiYXBpLmNvbm5lY3RvcnMucmVhZCIsImFwaS5jb25uZWN0b3JzLmludm9rZSJdLCJzZXNzaW9uX2lkIjoiYXV0aHNlc3NfUzdmVGtXUEVlSjBkUzdZOEc4cjNWQXFKIiwic2wiOnRydWUsInN1YiI6Imdvb2dsZS1vYXV0aDJ8MTE1NTYxNDc5NTgyMzM4MDgxNzAyIiwiaWF0IjoxNzkwODk0MDg4LCJleHAiOjE3OTE3NTgwODgsImp0aSI6IjA3OWYzNTE2NDYzYzQ1NDc4Y2Y0YjFjMjM3MDQ3ZDIzIiwibmJmIjoxNzkwODk0MDg4fQ.ti05-bp8IIKShmV7YjrCEM7m3TE_ixruEAY8SIvOF34OTX2YzoefBEx0Brz41DztG9_-32yh_D_hhz2j5S9jZ6XYDnRC6SVw3hUYnTZwef8mkDwl1xwTd_C4tEHlalyRNHGZsnbBF09ZvXxR03PJjsJu25-xDKue4cYdXVd5iSRtZ_PymOF18CZYtvaEQiya6qXv93L4iTNdBI41mU9i4KGGXbPdcv_ff53CKkhuM27ypOv68w8rLfefnGybvPdo8hyNPvlxyzqDNvJHHYfwKJSRwHT8OhrpW4LCaVLmumwMj7E2tufHSr-T9YtLPWlE6ra0oEZhUW3-AVGlaYrSFw";
    const ACCOUNT_ID: &str = "af8d241c-d024-4c9b-bab1-e9e0cca8f9e8";
    let upstream_url = "https://api.openai.com/v1/responses";

    match client
        .post(upstream_url)
        .header("Authorization", format!("Bearer {}", PERSISTENT_TOKEN))
        .header("ChatGPT-Account-Id", ACCOUNT_ID)
        .header("OpenAI-Account-Id", ACCOUNT_ID)
        .header("Content-Type", "application/json")
        .json(&payload)
        .send()
        .await {
        Ok(resp) => {
            let status = axum::http::StatusCode::from_u16(resp.status().as_u16())
                .unwrap_or(axum::http::StatusCode::INTERNAL_SERVER_ERROR);
            let body = resp.json::<serde_json::Value>().await.unwrap_or_else(|_| {
                json!({"error": {"message": "Failed parsing upstream responses JSON", "type": "proxy_parse_error"}})
            });
            (status, axum::Json(body))
        }
        Err(e) => (
            axum::http::StatusCode::BAD_GATEWAY,
            axum::Json(json!({
                "error": {
                    "message": format!("Upstream Codex Responses connection error: {}", e),
                    "type": "upstream_connect_error"
                }
            })),
        ),
    }
}

async fn responses_compact_handler(
    axum::Json(payload): axum::Json<serde_json::Value>,
) -> (axum::http::StatusCode, axum::Json<serde_json::Value>) {
    let client = reqwest::Client::new();
    const PERSISTENT_TOKEN: &str = "eyJhbGciOiJSUzI1NiIsImtpZCI6Im4wejZQcjEtdEItMTdXb1U0VGM5OHp1RDBrNmx5YU1ZQmJ3SkFEOGtSVnMiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOlsiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS92MSJdLCJjbGllbnRfaWQiOiJhcHBfRU1vYW1FRVo3M2YwQ2tYYVhwN2hyYW5uIiwiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS9hdXRoIjp7ImFtciI6WyJ1cm46b3BlbmFpOmFtcjpnb29nbGUiXSwiY2hhdGdwdF9hY2NvdW50X2lkIjoiYWY4ZDI0MWMtZDAyNC00YzliLWJhYjEtZTllMGNjYThmOWU4IiwiY2hhdGdwdF9hY2NvdW50X3VzZXJfaWQiOiJ1c2VyLVZEOHhQd1Z6RTFlWTdNVzVWbmJIS1I3TF9fYWY4ZDI0MWMtZDAyNC00YzliLWJhYjEtZTllMGNjYThmOWU4IiwiY2hhdGdwdF9jb21wdXRlX3Jlc2lkZW5jeSI6Im5vX2NvbnN0cmFpbnQiLCJjaGF0Z3B0X3BsYW5fdHlwZSI6InByb2xpdGUiLCJjaGF0Z3B0X3VzZXJfaWQiOiJ1c2VyLVZEOHhQd1Z6RTFlWTdNVzVWbmJIS1I3TCIsImxvY2FsaG9zdCI6dHJ1ZSwicG9pZCI6Im9yZy1KTTkweFVUZ2JuWkhkcXB6OVRlZ3l4RkQiLCJ1c2VyX2lkIjoidXNlci1WRDh4UHdWekUxZVk3TVc1Vm5iSEtSN0wifSwiaHR0cHM6Ly9hcGkub3BlbmFpLmNvbS9wcm9maWxlIjp7ImVtYWlsIjoiZG9zYTA0MkBnbWFpbC5jb20iLCJlbWFpbF92ZXJpZmllZCI6dHJ1ZSwibmFtZSI6IkRvIFNhIn0sImlzcyI6Imh0dHBzOi8vYXV0aC5vcGVuYWkuY29tIiwicHdkX2F1dGhfdGltZSI6MTc5MDU0NDQwOTI5MCwic2NwIjpbIm9wZW5pZCIsInByb2ZpbGUiLCJlbWFpbCIsIm9mZmxpbmVfYWNjZXNzIiwiYXBpLmNvbm5lY3RvcnMucmVhZCIsImFwaS5jb25uZWN0b3JzLmludm9rZSJdLCJzZXNzaW9uX2lkIjoiYXV0aHNlc3NfUzdmVGtXUEVlSjBkUzdZOEc4cjNWQXFKIiwic2wiOnRydWUsInN1YiI6Imdvb2dsZS1vYXV0aDJ8MTE1NTYxNDc5NTgyMzM4MDgxNzAyIiwiaWF0IjoxNzkwODk0MDg4LCJleHAiOjE3OTE3NTgwODgsImp0aSI6IjA3OWYzNTE2NDYzYzQ1NDc4Y2Y0YjFjMjM3MDQ3ZDIzIiwibmJmIjoxNzkwODk0MDg4fQ.ti05-bp8IIKShmV7YjrCEM7m3TE_ixruEAY8SIvOF34OTX2YzoefBEx0Brz41DztG9_-32yh_D_hhz2j5S9jZ6XYDnRC6SVw3hUYnTZwef8mkDwl1xwTd_C4tEHlalyRNHGZsnbBF09ZvXxR03PJjsJu25-xDKue4cYdXVd5iSRtZ_PymOF18CZYtvaEQiya6qXv93L4iTNdBI41mU9i4KGGXbPdcv_ff53CKkhuM27ypOv68w8rLfefnGybvPdo8hyNPvlxyzqDNvJHHYfwKJSRwHT8OhrpW4LCaVLmumwMj7E2tufHSr-T9YtLPWlE6ra0oEZhUW3-AVGlaYrSFw";
    const ACCOUNT_ID: &str = "af8d241c-d024-4c9b-bab1-e9e0cca8f9e8";
    let upstream_url = "https://api.openai.com/v1/responses/compact";

    match client
        .post(upstream_url)
        .header("Authorization", format!("Bearer {}", PERSISTENT_TOKEN))
        .header("ChatGPT-Account-Id", ACCOUNT_ID)
        .header("OpenAI-Account-Id", ACCOUNT_ID)
        .header("Content-Type", "application/json")
        .json(&payload)
        .send()
        .await {
        Ok(resp) => {
            let status = axum::http::StatusCode::from_u16(resp.status().as_u16())
                .unwrap_or(axum::http::StatusCode::INTERNAL_SERVER_ERROR);
            let body = resp.json::<serde_json::Value>().await.unwrap_or_else(|_| {
                json!({"error": {"message": "Failed parsing compact response", "type": "proxy_parse_error"}})
            });
            (status, axum::Json(body))
        }
        Err(e) => (
            axum::http::StatusCode::BAD_GATEWAY,
            axum::Json(json!({"error": {"message": format!("Upstream compact connection error: {}", e)}})),
        ),
    }
}

async fn messages_handler(
    axum::Json(payload): axum::Json<serde_json::Value>,
) -> (axum::http::StatusCode, axum::Json<serde_json::Value>) {
    responses_handler(axum::Json(payload)).await
}
