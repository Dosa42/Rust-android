package com.example.proxy

import android.util.Log

/**
 * Native JNI bridge for codex-proxy-rs.
 * Loads libcodex_proxy.so if available on the system.
 */
object NativeProxy {
    private const val TAG = "NativeProxy"
    private const val LIB_NAME = "codex_proxy"

    val isNativeLoaded: Boolean

    init {
        isNativeLoaded = try {
            System.loadLibrary(LIB_NAME)
            Log.i(TAG, "Native library '$LIB_NAME' successfully loaded!")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Native library '$LIB_NAME' not found in APK (using embedded high-performance engine): ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error loading '$LIB_NAME': ${e.message}")
            false
        }
    }

    /**
     * Start the native Rust Axum proxy server listening on 127.0.0.1:port.
     * Returns the actual port bound, or -1 on error.
     */
    external fun startProxyNative(port: Int, dbPath: String, configJson: String): Int

    /**
     * Stops the running native proxy server.
     */
    external fun stopProxyNative(): Boolean

    /**
     * Retrieves runtime status JSON from the native engine.
     */
    external fun getProxyStatusNative(): String

    fun start(port: Int, dbPath: String, configJson: String): Int {
        return if (isNativeLoaded) {
            try {
                startProxyNative(port, dbPath, configJson)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed calling startProxyNative: ${e.message}")
                -1
            }
        } else {
            -1
        }
    }

    fun stop(): Boolean {
        return if (isNativeLoaded) {
            try {
                stopProxyNative()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed calling stopProxyNative: ${e.message}")
                false
            }
        } else {
            false
        }
    }

    fun getStatus(): String? {
        return if (isNativeLoaded) {
            try {
                getProxyStatusNative()
            } catch (e: Throwable) {
                null
            }
        } else {
            null
        }
    }
}
