# Embedded Rust JNI Codex Proxy for Android (`codex-proxy-rs`)

Architecture, compilation pipeline, and integration plan to port `zyycn/codex-proxy-rs` into a native Android JNI shared library (`.so`). The proxy runs as an embedded local HTTP daemon listening on `127.0.0.1:<port>`, adapts PostgreSQL/Redis storage to embedded SQLite, and exposes lifecycle controls to Android via a safe JNI interface.

---

### User Review & Critical Decisions

> [!IMPORTANT]
> **Confirmed Architectural Decisions**:
> 1. **Invocation Model**: **Embedded local HTTP daemon on `127.0.0.1`**. The Axum/Tokio HTTP server runs inside a dedicated native background thread inside the app process. Any client (OkHttp, Retrofit, webviews, or external local apps) connects to `http://127.0.0.1:<allocated_port>/v1` with zero streaming/SSE protocol friction.
> 2. **Storage Migration**: **Replace PostgreSQL and Redis with embedded SQLite**. The `gateway-store` crate will be adapted using `sqlx` (SQLite feature) or `rusqlite`, storing the database file in Android's private internal storage (`context.filesDir.absolutePath + "/codex_proxy.db"`), eliminating external database server dependencies.

- **Open Question / Choice**: Android proxy execution lifecycle. Recommended default: Managed via an **Android Foreground Service** with notification to guarantee the local daemon remains alive while processing long-lived SSE/streaming LLM tokens when the user switches apps.

---

## 1. Overview & Core Concept

### What It Does
An Android application that embeds the high-performance Rust proxy gateway (`codex-proxy-rs`) directly inside the APK. The native library compiles directly to machine code (`arm64-v8a`, `x86_64`) via the Android NDK, boots an internal Axum/Tokio server on `localhost`, manages multiple upstream Codex/OpenAI accounts, performs token routing and risk control, and logs metrics to an embedded SQLite database.

### Key Value
- **Zero Cloud Proxy Overhead**: All proxy routing, multi-account rotation, and token quota balancing occur locally on the user's Android device.
- **Full Compatibility**: Standard OpenAI-compatible API format (`/v1/chat/completions`, `/v1/models`) accessible by any app component via standard HTTP requests.
- **Privacy & Security**: Account credentials and keys reside in encrypted or protected private app storage.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Proxy Dashboard**:
   - Status card showing proxy state: `RUNNING` (with green pulse, port number, e.g. `http://127.0.0.1:8080`) or `STOPPED`.
   - Start / Stop / Restart controls with immediate state updates.
   - Live metrics: Request count, active connections, token usage, latency.
2. **Account & Key Manager**:
   - Manage multi-account upstream credentials (Codex, OpenAI, xAI).
   - Add/edit client authorization tokens and group bindings.
3. **Interactive Test Console**:
   - Built-in test playground allowing the user to send a prompt directly through the local proxy and view real-time streaming SSE responses and route diagnostics.
4. **Log & Audit Viewer**:
   - Filterable stream of local proxy access logs and routing events retrieved from the embedded SQLite database.

### Visual Identity & Theme
- **Aesthetic Direction**: Sleek, high-density developer dashboard ("Cyber-Console" aesthetic).
- **Color Palette**: Dark-mode dominant (`#0D1117` background, `#161B22` cards, `#238636` accent green for running status, `#58A6FF` cyan for API routes, `#F85149` for errors).
- **Typography**: Monospace font (`JetBrains Mono` or local system monospace) for ports, endpoints, and token metrics; clean sans-serif for labels and navigation.
- **Safe Area Insets**: Full edge-to-edge layout respecting system navigation bars and camera cutouts.

---

## 3. Key Product Decisions & Trade-Offs

### Decision 1: Embedded Daemon on Localhost vs. Direct In-Process FFI
- **Chosen Approach**: Embedded local daemon on `127.0.0.1:<port>`.
- **Why**: `codex-proxy-rs` has a full HTTP/WebSocket/SSE streaming pipeline built with `axum` and `hyper`. Running the HTTP listener on `localhost` preserves 100% of the internal streaming, connection pooling, and retry logic without rewriting request/response streaming across JNI boundaries.
- **Alternatives Considered**: Direct JNI function invocation (`proxy_request(...)`). Rejected due to extreme complexity in handling chunked SSE responses and WebSocket frames over raw JNI callbacks.

### Decision 2: SQLite Adaptation vs. Network Database
- **Chosen Approach**: Embedded SQLite (`codex_proxy.db`) in the app's internal private directory.
- **Why**: Eliminates external server dependencies, making the Android app entirely self-contained and offline-capable.
- **Alternatives Considered**: Connecting to a remote PostgreSQL/Redis instance. Kept as an optional configurable remote mode, but SQLite is the primary mobile default.

### Decision 3: NDK Toolchain & TLS Crypto Provider
- **Chosen Approach**: Use `rustls` with `ring` or NDK-compiled `aws-lc-rs` via `cargo-ndk`.
- **Why**: Compiles cleanly across Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) without dynamic OpenSSL dependencies.

---

## 4. Technical Architecture & Data Strategy

### System Architecture Diagram

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Android Application Layer                       │
│                                                                        │
│   ┌──────────────────────────┐         ┌───────────────────────────┐   │
│   │   Jetpack Compose UI     │         │ ProxyControllerService    │   │
│   │ (Dashboard / Key Manager)│◄───────►│ (Foreground Service)     │   │
│   └─────────────┬────────────┘         └─────────────┬─────────────┘   │
│                 │                                    │                 │
│                 │ OkHttp Client                      │ JNI Calls       │
│                 │ (http://127.0.0.1:8080)            │                 │
│                 ▼                                    ▼                 │
│          ┌──────────────┐                     ┌──────────────┐         │
│          │ HTTP Request │                     │ Native JNI   │         │
│          │ & SSE Stream │                     │ Bridge       │         │
└──────────┴──────┬───────┴─────────────────────┴──────┬───────┴─────────┘
                  │                                    │
                  │ TCP Socket (127.0.0.1)             │ System.loadLibrary
                  ▼                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             Native Shared Library (`libcodex_proxy.so`)                │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │  JNI Export Layer (`jni-rs`)                                   │   │
│   │  • Java_com_example_proxy_NativeProxy_startProxy(...)          │   │
│   │  • Java_com_example_proxy_NativeProxy_stopProxy()              │   │
│   │  • Java_com_example_proxy_NativeProxy_getProxyStatus()         │   │
│   └───────────────────────────────┬────────────────────────────────┘   │
│                                   │                                    │
│                                   ▼                                    │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │  Tokio Multi-Threaded Runtime (Dedicated OS Threads)           │   │
│   │                                                                │   │
│   │   ┌──────────────────────┐         ┌───────────────────────┐   │   │
│   │   │ Axum HTTP Gateway    │◄───────►│ Account Router &      │   │   │
│   │   │ (Listener: 127.0.0.1)│         │ Upstream Providers    │   │   │
│   │   └──────────┬───────────┘         └───────────┬───────────┘   │   │
│   │              │                                 │               │   │
│   │              ▼                                 ▼               │   │
│   │   ┌──────────────────────┐         ┌───────────────────────┐   │   │
│   │   │ SQLite Storage Store │         │ Upstream TLS Client   │   │   │
│   │   │ (/data/user/0/...db) │         │ (Hyper + Rustls)      │   │   │
│   │   └──────────────────────┘         └───────────────────────┘   │   │
│   └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

### JNI Interface Definition

#### Kotlin Side (`NativeProxy.kt`)
```kotlin
object NativeProxy {
    init {
        System.loadLibrary("codex_proxy")
    }

    external fun startProxy(port: Int, dbPath: String, configJson: String): Int
    external fun stopProxy(): Boolean
    external fun getProxyStatus(): String // Returns JSON with running status, port, uptime, active connections
}
```

#### Rust Side (`jni_bridge.rs`)
```rust
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jboolean, jstring};

#[no_mangle]
pub extern "system" fn Java_com_example_proxy_NativeProxy_startProxy(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    db_path: JString,
    config_json: JString,
) -> jint {
    // 1. Extract parameters
    // 2. Initialize Tokio runtime on static background thread
    // 3. Initialize SQLite database at db_path
    // 4. Bind Axum server to 127.0.0.1:port
    // 5. Return actual bound port or negative error code
}
```

---

## 5. Step-by-Step Implementation Roadmap

1. **Phase 1: Native Rust JNI Crate Setup**:
   - Create `crates/gateway-jni` within the `codex-proxy-rs` workspace.
   - Configure `crate-type = ["cdylib"]` producing `libcodex_proxy.so`.
   - Add `jni = "0.21"`.
2. **Phase 2: Storage Layer Adaptation**:
   - Add SQLite support to `crates/gateway-store` (migrating table schemas from PostgreSQL to SQLite).
   - In-memory token bucket and key caching to replace Redis dependencies.
3. **Phase 3: Cross-Compilation with Android NDK**:
   - Setup `cargo ndk` for `aarch64-linux-android` (`arm64-v8a`) and `x86_64-linux-android`.
   - Copy compiled `.so` binaries into `app/src/main/jniLibs/`.
4. **Phase 4: Android App Integration**:
   - Create `NativeProxy` loader and `ProxyService` foreground service in Kotlin.
   - Implement Compose UI dashboard with controls, metrics, account configuration, and test playground.
