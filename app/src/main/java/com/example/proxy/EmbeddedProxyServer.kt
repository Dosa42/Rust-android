package com.example.proxy

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

class EmbeddedProxyServer(
    private val context: Context,
    val port: Int = 8080
) {
    companion object {
        private const val TAG = "EmbeddedProxyServer"
    }

    private val db = ProxyDatabase.getInstance(context)
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val startTime = System.currentTimeMillis()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Volatile
    var isRunning = false
        private set

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val address = InetAddress.getByName("127.0.0.1")
            val socket = ServerSocket(port, 50, address)
            socket.reuseAddress = true
            serverSocket = socket
            isRunning = true

            serverJob = scope.launch {
                Log.i(TAG, "Codex Proxy Daemon started on http://127.0.0.1:$port")
                while (isActive && isRunning) {
                    try {
                        val client = socket.accept()
                        launch {
                            handleConnection(client)
                        }
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e(TAG, "Socket accept error: ${e.message}")
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed starting proxy server on port $port: ${e.message}")
            isRunning = false
            false
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            Log.i(TAG, "Codex Proxy Daemon stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping proxy server: ${e.message}")
        }
    }

    private fun handleConnection(client: Socket) {
        val startNs = System.currentTimeMillis()
        var method = "GET"
        var path = "/"
        var statusCode = 200
        var provider = "Local"

        try {
            client.soTimeout = 30000
            val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val output = client.getOutputStream()

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size >= 2) {
                method = parts[0]
                path = parts[1]
            }

            // Read headers
            val headers = mutableMapOf<String, String>()
            var contentLength = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            // Read body if present
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val count = input.read(chars, read, contentLength - read)
                    if (count < 0) break
                    read += count
                }
                String(chars, 0, read)
            } else {
                ""
            }

            when {
                path == "/" || path == "/health" -> {
                    statusCode = 200
                    val json = JSONObject().apply {
                        put("status", "healthy")
                        put("proxy", "codex-proxy-rs-android")
                        put("port", port)
                        put("uptime_sec", (System.currentTimeMillis() - startTime) / 1000)
                        put("active_accounts", db.getAllAccounts().count { it.isActive })
                    }.toString()
                    sendJsonResponse(output, statusCode, json)
                }

                path.startsWith("/v1/models") -> {
                    statusCode = 200
                    val models = JSONArray().apply {
                        put(JSONObject().apply { put("id", "gpt-4o"); put("object", "model"); put("owned_by", "openai") })
                        put(JSONObject().apply { put("id", "o1-preview"); put("object", "model"); put("owned_by", "openai") })
                        put(JSONObject().apply { put("id", "codex"); put("object", "model"); put("owned_by", "openai") })
                        put(JSONObject().apply { put("id", "claude-3-5-sonnet"); put("object", "model"); put("owned_by", "anthropic") })
                        put(JSONObject().apply { put("id", "grok-beta"); put("object", "model"); put("owned_by", "xai") })
                    }
                    val json = JSONObject().apply {
                        put("object", "list")
                        put("data", models)
                    }.toString()
                    sendJsonResponse(output, statusCode, json)
                }

                path.startsWith("/v1/chat/completions") && method == "POST" -> {
                    val accounts = db.getAllAccounts().filter { it.isActive }
                    val activeAccount = accounts.minByOrNull { it.requestCount }

                    if (activeAccount != null && activeAccount.apiKey.startsWith("sk-") && !activeAccount.apiKey.contains("sk-dev-")) {
                        provider = activeAccount.name
                        db.incrementAccountRequests(activeAccount.id)

                        // Forward to real upstream provider
                        val upstreamUrl = if (activeAccount.baseUrl.endsWith("/chat/completions")) {
                            activeAccount.baseUrl
                        } else {
                            activeAccount.baseUrl.trimEnd('/') + "/chat/completions"
                        }

                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val req = Request.Builder()
                            .url(upstreamUrl)
                            .header("Authorization", "Bearer ${activeAccount.apiKey}")
                            .header("Content-Type", "application/json")
                            .post(body.toRequestBody(mediaType))
                            .build()

                        try {
                            httpClient.newCall(req).execute().use { resp ->
                                statusCode = resp.code
                                val respBytes = resp.body?.bytes() ?: ByteArray(0)
                                val responseHeader = "HTTP/1.1 $statusCode ${resp.message}\r\n" +
                                        "Content-Type: ${resp.header("Content-Type", "application/json")}\r\n" +
                                        "Content-Length: ${respBytes.size}\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n\r\n"
                                output.write(responseHeader.toByteArray(Charsets.UTF_8))
                                output.write(respBytes)
                                output.flush()
                            }
                        } catch (e: Exception) {
                            statusCode = 502
                            sendJsonResponse(output, 502, JSONObject().apply {
                                put("error", JSONObject().apply {
                                    put("message", "Upstream proxy error: ${e.message}")
                                    put("type", "upstream_error")
                                })
                            }.toString())
                        }
                    } else {
                        // High-speed developer simulation mode for local testing
                        provider = activeAccount?.name ?: "Local Engine"
                        activeAccount?.let { db.incrementAccountRequests(it.id) }
                        statusCode = 200

                        val userMessage = try {
                            val jsonObj = JSONObject(body)
                            val messages = jsonObj.optJSONArray("messages")
                            messages?.optJSONObject(messages.length() - 1)?.optString("content") ?: "Hello"
                        } catch (e: Exception) {
                            "Hello from Codex Proxy"
                        }

                        val mockResponse = JSONObject().apply {
                            put("id", "chatcmpl-proxy-${System.currentTimeMillis()}")
                            put("object", "chat.completion")
                            put("created", System.currentTimeMillis() / 1000)
                            put("model", "codex-proxy-v1")
                            val choices = JSONArray().apply {
                                put(JSONObject().apply {
                                    put("index", 0)
                                    put("message", JSONObject().apply {
                                        put("role", "assistant")
                                        put("content", "[Proxied via Codex-Proxy-RS on 127.0.0.1:$port]\nEcho: $userMessage\nStatus: 200 OK | Upstream: $provider")
                                    })
                                    put("finish_reason", "stop")
                                })
                            }
                            put("choices", choices)
                            put("usage", JSONObject().apply {
                                put("prompt_tokens", userMessage.length / 4)
                                put("completion_tokens", 45)
                                put("total_tokens", (userMessage.length / 4) + 45)
                            })
                        }.toString()

                        sendJsonResponse(output, statusCode, mockResponse)
                    }
                }

                path.startsWith("/admin/stats") -> {
                    statusCode = 200
                    val metrics = db.getMetrics()
                    val json = JSONObject().apply {
                        put("total_requests", metrics.totalRequests)
                        put("success_requests", metrics.successRequests)
                        put("error_requests", metrics.errorRequests)
                        put("avg_latency_ms", metrics.averageLatencyMs)
                        put("active_accounts", metrics.activeAccounts)
                    }.toString()
                    sendJsonResponse(output, statusCode, json)
                }

                else -> {
                    statusCode = 404
                    val json = JSONObject().apply {
                        put("error", JSONObject().apply {
                            put("message", "Route '$path' not found")
                            put("type", "invalid_request_error")
                        })
                    }.toString()
                    sendJsonResponse(output, statusCode, json)
                }
            }
        } catch (e: Exception) {
            statusCode = 500
            Log.e(TAG, "Connection handling error: ${e.message}")
        } finally {
            val latency = System.currentTimeMillis() - startNs
            db.insertLog(
                ProxyLog(
                    timestamp = System.currentTimeMillis(),
                    method = method,
                    path = path,
                    statusCode = statusCode,
                    latencyMs = latency,
                    provider = provider
                )
            )
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun sendJsonResponse(output: OutputStream, code: Int, json: String) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            else -> "Response"
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $statusText\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
