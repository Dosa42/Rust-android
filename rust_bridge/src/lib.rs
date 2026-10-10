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
        // Embedded Axum router mirroring codex-proxy-rs
        let app = axum::Router::new()
            .route("/", axum::routing::get(root_handler))
            .route("/health", axum::routing::get(health_handler))
            .route("/v1/models", axum::routing::get(models_handler))
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
) -> axum::Json<serde_json::Value> {
    let prompt = payload
        .get("messages")
        .and_then(|m| m.as_array())
        .and_then(|arr| arr.last())
        .and_then(|last| last.get("content"))
        .and_then(|c| c.as_str())
        .unwrap_or("Hello from Codex Native JNI");

    axum::Json(json!({
        "id": format!("chatcmpl-rust-{}", std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis()),
        "object": "chat.completion",
        "created": 1726000000,
        "model": "codex-proxy-native-rs",
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": format!("[Native Rust JNI Engine via Axum & Tokio]\nProcessed prompt: {}", prompt)
            },
            "finish_reason": "stop"
        }]
    }))
}
