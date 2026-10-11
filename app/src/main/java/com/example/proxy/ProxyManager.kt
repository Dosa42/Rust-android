package com.example.proxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ProxyManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ProxyManager"

        @Volatile
        private var INSTANCE: ProxyManager? = null

        fun getInstance(context: Context): ProxyManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProxyManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val db = ProxyDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var embeddedServer: EmbeddedProxyServer? = null

    val openClaw = com.example.openclaw.OpenClawServerManager(context)

    private val _isProxyRunning = MutableStateFlow(false)
    val isProxyRunning: StateFlow<Boolean> = _isProxyRunning.asStateFlow()

    private val _isNativeActive = MutableStateFlow(NativeProxy.isNativeLoaded)
    val isNativeActive: StateFlow<Boolean> = _isNativeActive.asStateFlow()

    private val _proxyPort = MutableStateFlow(8080)
    val proxyPort: StateFlow<Int> = _proxyPort.asStateFlow()

    private val _accounts = MutableStateFlow<List<ProxyAccount>>(emptyList())
    val accounts: StateFlow<List<ProxyAccount>> = _accounts.asStateFlow()

    private val _logs = MutableStateFlow<List<ProxyLog>>(emptyList())
    val logs: StateFlow<List<ProxyLog>> = _logs.asStateFlow()

    private val _metrics = MutableStateFlow(ProxyMetrics())
    val metrics: StateFlow<ProxyMetrics> = _metrics.asStateFlow()

    private val _bootstrapInstalling = MutableStateFlow(false)
    val bootstrapInstalling: StateFlow<Boolean> = _bootstrapInstalling.asStateFlow()

    private val _bootstrapProgress = MutableStateFlow("")
    val bootstrapProgress: StateFlow<String> = _bootstrapProgress.asStateFlow()

    private val _testResponse = MutableStateFlow<String?>(null)
    val testResponse: StateFlow<String?> = _testResponse.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        refreshData()
        // Auto-refresh metrics & logs periodically
        scope.launch {
            while (isActive) {
                delay(2000)
                if (_isProxyRunning.value) {
                    refreshData()
                }
            }
        }
        // Auto-start proxy by default for instant developer ease of use!
        startProxy(8080)
    }

    fun refreshData() {
        _accounts.value = db.getAllAccounts()
        _logs.value = db.getRecentLogs(50)
        _metrics.value = db.getMetrics()
    }

    fun startProxy(port: Int = _proxyPort.value): Boolean {
        _proxyPort.value = port
        val dbPath = context.filesDir.absolutePath + "/codex_proxy.db"

        if (NativeProxy.isNativeLoaded) {
            val boundPort = NativeProxy.start(port, dbPath, "{}")
            if (boundPort > 0) {
                _isNativeActive.value = true
                _isProxyRunning.value = true
                _proxyPort.value = boundPort
                refreshData()
                return true
            }
        }

        // Use embedded high-performance engine
        embeddedServer?.stop()
        val server = EmbeddedProxyServer(context, port)
        val started = server.start()
        if (started) {
            embeddedServer = server
            _isProxyRunning.value = true
            _isNativeActive.value = false
            refreshData()
            return true
        } else {
            _isProxyRunning.value = false
            return false
        }
    }

    fun stopProxy() {
        if (NativeProxy.isNativeLoaded) {
            NativeProxy.stop()
        }
        embeddedServer?.stop()
        embeddedServer = null
        _isProxyRunning.value = false
        refreshData()
    }

    fun restartProxy(port: Int) {
        stopProxy()
        startProxy(port)
    }

    fun addAccount(name: String, provider: String, apiKey: String, baseUrl: String) {
        val account = ProxyAccount(
            name = name.ifBlank { "$provider Account" },
            provider = provider,
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.ifBlank { "https://api.openai.com/v1" },
            isActive = true
        )
        db.insertAccount(account)
        refreshData()
    }

    fun deleteAccount(id: Long) {
        db.deleteAccount(id)
        refreshData()
    }

    fun toggleAccount(id: Long, active: Boolean) {
        db.setAccountActive(id, active)
        refreshData()
    }

    fun clearLogs() {
        db.clearLogs()
        refreshData()
    }

    fun sendTestPrompt(prompt: String, endpoint: String = "/v1/responses") {
        if (!_isProxyRunning.value) {
            _testResponse.value = "Error: Proxy is stopped. Start proxy first."
            return
        }

        scope.launch {
            _isTesting.value = true
            val targetUrl = "http://127.0.0.1:${_proxyPort.value}$endpoint"
            _testResponse.value = "Routing $endpoint request to $targetUrl..."

            val requestJson = when {
                endpoint.contains("/v1/responses") -> {
                    // Codex Responses schema
                    JSONObject().apply {
                        put("model", "gpt-4o")
                        val inputArr = JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "message")
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", prompt)
                                    })
                                })
                            })
                        }
                        put("input", inputArr)
                        put("stream", false)
                    }.toString()
                }
                endpoint.contains("/v1/messages") -> {
                    // Claude Messages schema
                    JSONObject().apply {
                        put("model", "claude-3-5-sonnet")
                        val msgs = JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            })
                        }
                        put("messages", msgs)
                        put("max_tokens", 1024)
                    }.toString()
                }
                else -> {
                    // OpenAI Chat schema
                    JSONObject().apply {
                        put("model", "gpt-4o")
                        val msgs = JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            })
                        }
                        put("messages", msgs)
                    }.toString()
                }
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(targetUrl)
                .header("Authorization", "Bearer sk-ccx-client-relay")
                .header("Content-Type", "application/json")
                .post(requestJson.toRequestBody(mediaType))
                .build()

            val start = System.currentTimeMillis()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    val body = resp.body?.string() ?: ""
                    val code = resp.code

                    if (resp.isSuccessful) {
                        _testResponse.value = "HTTP $code OK (${latency}ms) via $endpoint:\n\n$body"
                    } else {
                        _testResponse.value = "HTTP $code Error (${latency}ms) via $endpoint:\n\n$body"
                    }
                }
            } catch (e: Exception) {
                _testResponse.value = "Connection failed (${e.javaClass.simpleName}): ${e.message}"
            } finally {
                _isTesting.value = false
                refreshData()
            }
        }
    }

    // ── OpenClaw Operations ──────────────────────────────────────────

    fun installBootstrap(onComplete: (Boolean) -> Unit = {}) {
        if (_bootstrapInstalling.value) return
        scope.launch {
            _bootstrapInstalling.value = true
            _bootstrapProgress.value = "Starting installation…"
            try {
                com.example.openclaw.BootstrapInstaller.install(context) { msg ->
                    _bootstrapProgress.value = msg
                }
                openClaw.ensureBionicCompat()
                openClaw.ensureFullAccessConfig()
                openClaw.ensureDefaultWorkspace()
                openClaw.syncCodexAuthToOpenClaw()
                _bootstrapProgress.value = "Installation completed successfully!"
                onComplete(true)
            } catch (e: Exception) {
                _bootstrapProgress.value = "Installation failed: ${e.message}"
                onComplete(false)
            } finally {
                _bootstrapInstalling.value = false
            }
        }
    }

    fun syncOpenClawToken(): Boolean {
        return openClaw.syncCodexAuthToOpenClaw()
    }

    fun startOpenClawGateway(): Boolean {
        return openClaw.startOpenClawGateway()
    }

    fun stopOpenClawGateway() {
        openClaw.stopOpenClawGateway()
    }

    fun startOpenClawControlUi(): Boolean {
        return openClaw.startOpenClawControlUiServer()
    }

    fun stopOpenClawControlUi() {
        openClaw.stopOpenClawControlUiServer()
    }

    fun startConnectProxy(): Boolean {
        return openClaw.startProxy()
    }

    fun stopConnectProxy() {
        openClaw.stopProxy()
    }
}
