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
            name = name.ifBlank { "Account-${System.currentTimeMillis() % 1000}" },
            provider = provider,
            apiKey = apiKey.ifBlank { "sk-test-${System.currentTimeMillis()}" },
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

    fun sendTestPrompt(prompt: String) {
        if (!_isProxyRunning.value) {
            _testResponse.value = "Error: Proxy is stopped. Start proxy first."
            return
        }

        scope.launch {
            _isTesting.value = true
            _testResponse.value = "Sending request to http://127.0.0.1:${_proxyPort.value}/v1/chat/completions..."

            val requestJson = JSONObject().apply {
                put("model", "gpt-4o")
                val messages = JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
                put("messages", messages)
                put("temperature", 0.7)
            }.toString()

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url("http://127.0.0.1:${_proxyPort.value}/v1/chat/completions")
                .header("Authorization", "Bearer sk-codex-test-client")
                .post(requestJson.toRequestBody(mediaType))
                .build()

            val start = System.currentTimeMillis()
            try {
                httpClient.newCall(request).execute().use { resp ->
                    val latency = System.currentTimeMillis() - start
                    val body = resp.body?.string() ?: ""
                    val code = resp.code

                    if (resp.isSuccessful) {
                        val parsed = try {
                            val json = JSONObject(body)
                            val choice = json.optJSONArray("choices")?.optJSONObject(0)
                            choice?.optJSONObject("message")?.optString("content") ?: body
                        } catch (e: Exception) {
                            body
                        }
                        _testResponse.value = "Status $code OK (${latency}ms):\n\n$parsed"
                    } else {
                        _testResponse.value = "Status $code Error (${latency}ms):\n$body"
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
}
