# Codex Proxy Native JNI Bridge (Rust)

This module provides the native Android JNI wrapper for `codex-proxy-rs`.

## Architecture
- **Language**: Rust (2021 Edition)
- **Library Output**: `libcodex_proxy.so`
- **JNI Interface**: `Java_com_example_proxy_NativeProxy_*`
- **Async Runtime**: `tokio` multi-threaded runtime
- **HTTP Engine**: `axum` listening directly on `127.0.0.1:<port>`
- **Storage**: Embedded SQLite

## Quick Compilation
Run the build script with Android NDK configured:
```bash
chmod +x build_android_ndk.sh
./build_android_ndk.sh
```

Or run `cargo-ndk` manually:
```bash
cargo ndk -t arm64-v8a -o ../app/src/main/jniLibs build --release
```

The resulting `libcodex_proxy.so` files are placed directly into `app/src/main/jniLibs/` and automatically bundled into the Android APK.
