#!/usr/bin/env bash
set -e

# ==============================================================================
# Script to compile codex-proxy-rs as a native JNI shared library for Android
# Targets: arm64-v8a (modern physical devices) and x86_64 (emulators)
# ==============================================================================

echo "=== Building Codex Proxy Rust JNI Library for Android ==="

# Check cargo ndk
if ! command -v cargo-ndk &> /dev/null; then
    echo "Installing cargo-ndk..."
    cargo install cargo-ndk
fi

# Ensure rust targets are installed
rustup target add aarch64-linux-android
rustup target add x86_64-linux-android

OUTPUT_DIR="../app/src/main/jniLibs"
mkdir -p "$OUTPUT_DIR"

echo "Compiling for aarch64-linux-android (arm64-v8a)..."
cargo ndk -t arm64-v8a -o "$OUTPUT_DIR" build --release

echo "Compiling for x86_64-linux-android (x86_64)..."
cargo ndk -t x86_64 -o "$OUTPUT_DIR" build --release

echo "Done! Native shared libraries (.so) installed into: $OUTPUT_DIR"
ls -la "$OUTPUT_DIR/arm64-v8a/"
ls -la "$OUTPUT_DIR/x86_64/"
