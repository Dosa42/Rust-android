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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
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
                Log.i(TAG, "CCX Multi-Protocol Proxy Daemon started on http://127.0.0.1:$port")
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
            Log.i(TAG, "CCX Proxy Daemon stopped.")
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
            client.soTimeout = 60000
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

            // Normalize path (handle optional :routePrefix like /myroute/v1/responses -> /v1/responses)
            val normalizedPath = when {
                path.contains("/v1/") -> "/v1/" + path.substringAfter("/v1/")
                path.contains("/v1beta/") -> "/v1beta/" + path.substringAfter("/v1beta/")
                else -> path
            }

            val accounts = db.getAllAccounts().filter { it.isActive }
            val activeAccount = accounts.minByOrNull { it.requestCount }

            when {
                // Health Check
                normalizedPath == "/" || normalizedPath == "/health" -> {
                    statusCode = 200
                    val json = JSONObject().apply {
                        put("status", "healthy")
                        put("service", "ccx-multiplexed-gateway-android")
                        put("port", port)
                        put("uptime_sec", (System.currentTimeMillis() - startTime) / 1000)
                        put("active_accounts", accounts.size)
                        put("endpoints", JSONObject().apply {
                            put("codex_responses", "POST /v1/responses")
                            put("claude_messages", "POST /v1/messages")
                            put("openai_chat", "POST /v1/chat/completions")
                            put("models_list", "GET /v1/models")
                            put("gemini_native", "POST /v1beta/models/*")
                            put("responses_compact", "POST /v1/responses/compact")
                        })
                    }.toString()
                    sendJsonResponse(output, statusCode, json)
                }

                // Models Discovery
                normalizedPath.startsWith("/v1/models") && method == "GET" -> {
                    if (activeAccount != null) {
                        provider = activeAccount.name
                        val upstreamUrl = if (activeAccount.baseUrl.endsWith("/models")) {
                            activeAccount.baseUrl
                        } else {
                            activeAccount.baseUrl.trimEnd('/') + "/models"
                        }

                        val reqBuilder = Request.Builder()
                            .url(upstreamUrl)
                            .header("Authorization", "Bearer ${activeAccount.apiKey}")
                            .get()

                        if (activeAccount.accountId.isNotBlank()) {
                            reqBuilder.header("ChatGPT-Account-Id", activeAccount.accountId)
                            reqBuilder.header("OpenAI-Account-Id", activeAccount.accountId)
                        }

                        statusCode = executeForwardRequest(reqBuilder.build(), output)
                    } else {
                        statusCode = 400
                        sendJsonResponse(output, 400, missingAccountError("No active upstream account configured."))
                    }
                }

                // CCX Codex Responses API (POST /v1/responses)
                normalizedPath.startsWith("/v1/responses") && method == "POST" && !normalizedPath.contains("/compact") -> {
                    if (activeAccount != null) {
                        provider = activeAccount.name
                        db.incrementAccountRequests(activeAccount.id)

                        val mediaType = "application/json; charset=utf-8".toMediaType()

                        if (activeAccount.provider.equals("Claude", ignoreCase = true)) {
                            // Cross-protocol: Convert Codex Responses payload to Claude Messages
                            val chatPayload = CCXProtocolConverter.responsesToChat(body)
                            val upstreamUrl = activeAccount.baseUrl.trimEnd('/') + "/messages"
                            val req = Request.Builder()
                                .url(upstreamUrl)
                                .header("x-api-key", activeAccount.apiKey)
                                .header("anthropic-version", "2023-06-01")
                                .header("Content-Type", "application/json")
                                .post(chatPayload.toRequestBody(mediaType))
                                .build()
                            statusCode = executeForwardRequest(req, output)
                        } else {
                            // Native Codex / OpenAI Responses API
                            val upstreamUrl = if (activeAccount.baseUrl.endsWith("/responses")) {
                                activeAccount.baseUrl
                            } else {
                                activeAccount.baseUrl.trimEnd('/') + "/responses"
                            }

                            val reqBuilder = Request.Builder()
                                .url(upstreamUrl)
                                .header("Authorization", "Bearer ${activeAccount.apiKey}")
                                .header("Content-Type", "application/json")
                                .post(body.toRequestBody(mediaType))

                            if (activeAccount.accountId.isNotBlank()) {
                                reqBuilder.header("ChatGPT-Account-Id", activeAccount.accountId)
                                reqBuilder.header("OpenAI-Account-Id", activeAccount.accountId)
                            }

                            statusCode = executeForwardRequest(reqBuilder.build(), output)
                        }
                    } else {
                        statusCode = 401
                        sendJsonResponse(output, 401, missingAccountError("No active account for Codex /v1/responses."))
                    }
                }

                // CCX Codex Context Compaction (POST /v1/responses/compact)
                normalizedPath.startsWith("/v1/responses/compact") && method == "POST" -> {
                    if (activeAccount != null) {
                        provider = activeAccount.name
                        val upstreamUrl = activeAccount.baseUrl.trimEnd('/') + "/responses/compact"
                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val reqBuilder = Request.Builder()
                            .url(upstreamUrl)
                            .header("Authorization", "Bearer ${activeAccount.apiKey}")
                            .header("Content-Type", "application/json")
                            .post(body.toRequestBody(mediaType))

                        if (activeAccount.accountId.isNotBlank()) {
                            reqBuilder.header("ChatGPT-Account-Id", activeAccount.accountId)
                            reqBuilder.header("OpenAI-Account-Id", activeAccount.accountId)
                        }
                        statusCode = executeForwardRequest(reqBuilder.build(), output)
                    } else {
                        statusCode = 401
                        sendJsonResponse(output, 401, missingAccountError("No active account for /v1/responses/compact."))
                    }
                }

                // CCX Claude Messages API (POST /v1/messages)
                normalizedPath.startsWith("/v1/messages") && method == "POST" -> {
                    if (activeAccount != null) {
                        provider = activeAccount.name
                        db.incrementAccountRequests(activeAccount.id)

                        val mediaType = "application/json; charset=utf-8".toMediaType()

                        if (activeAccount.provider.equals("Claude", ignoreCase = true)) {
                            // Native Claude passthrough
                            val upstreamUrl = if (activeAccount.baseUrl.endsWith("/messages")) {
                                activeAccount.baseUrl
                            } else {
                                activeAccount.baseUrl.trimEnd('/') + "/messages"
                            }
                            val req = Request.Builder()
                                .url(upstreamUrl)
                                .header("x-api-key", activeAccount.apiKey)
                                .header("anthropic-version", "2023-06-01")
                                .header("Content-Type", "application/json")
                                .post(body.toRequestBody(mediaType))
                                .build()
                            statusCode = executeForwardRequest(req, output)
                        } else {
                            // Cross-protocol: Convert Claude Messages to Codex Responses or OpenAI Chat
                            val responsesPayload = CCXProtocolConverter.claudeToResponses(body)
                            val upstreamUrl = activeAccount.baseUrl.trimEnd('/') + "/responses"
                            val reqBuilder = Request.Builder()
                                .url(upstreamUrl)
                                .header("Authorization", "Bearer ${activeAccount.apiKey}")
                                .header("Content-Type", "application/json")
                                .post(responsesPayload.toRequestBody(mediaType))

                            if (activeAccount.accountId.isNotBlank()) {
                                reqBuilder.header("ChatGPT-Account-Id", activeAccount.accountId)
                                reqBuilder.header("OpenAI-Account-Id", activeAccount.accountId)
                            }
                            statusCode = executeForwardRequest(reqBuilder.build(), output)
                        }
                    } else {
                        statusCode = 401
                        sendJsonResponse(output, 401, missingAccountError("No active account for /v1/messages."))
                    }
                }

                // OpenAI Chat Completions API (POST /v1/chat/completions)
                normalizedPath.startsWith("/v1/chat/completions") && method == "POST" -> {
                    if (activeAccount != null) {
                        provider = activeAccount.name
                        db.incrementAccountRequests(activeAccount.id)

                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val upstreamUrl = if (activeAccount.baseUrl.endsWith("/chat/completions")) {
                            activeAccount.baseUrl
                        } else {
                            activeAccount.baseUrl.trimEnd('/') + "/chat/completions"
                        }

                        val reqBuilder = Request.Builder()
                            .url(upstreamUrl)
                            .header("Authorization", "Bearer ${activeAccount.apiKey}")
                            .header("Content-Type", "application/json")
                            .post(body.toRequestBody(mediaType))

                        if (activeAccount.accountId.isNotBlank()) {
                            reqBuilder.header("ChatGPT-Account-Id", activeAccount.accountId)
                            reqBuilder.header("OpenAI-Account-Id", activeAccount.accountId)
                        }

                        statusCode = executeForwardRequest(reqBuilder.build(), output)
                    } else {
                        statusCode = 401
                        sendJsonResponse(output, 401, missingAccountError("No active account for /v1/chat/completions."))
                    }
                }

                // Codex Alpha Layer Passthrough (POST /v1/alpha/*)
                normalizedPath.startsWith("/v1/alpha/") && method == "POST" -> {
                    if (activeAccount != null) {
                        provider = activeAccount.name
                        val upstreamUrl = activeAccount.baseUrl.trimEnd('/') + normalizedPath
                        val mediaType = "application/json; charset=utf-8".toMediaType()
                        val reqBuilder = Request.Builder()
                            .url(upstreamUrl)
                            .header("Authorization", "Bearer ${activeAccount.apiKey}")
                            .header("Content-Type", "application/json")
                            .post(body.toRequestBody(mediaType))

                        if (activeAccount.accountId.isNotBlank()) {
                            reqBuilder.header("ChatGPT-Account-Id", activeAccount.accountId)
                            reqBuilder.header("OpenAI-Account-Id", activeAccount.accountId)
                        }
                        statusCode = executeForwardRequest(reqBuilder.build(), output)
                    } else {
                        statusCode = 401
                        sendJsonResponse(output, 401, missingAccountError("No active account for /v1/alpha/."))
                    }
                }

                // Admin Stats
                normalizedPath.startsWith("/admin/stats") -> {
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
                            put("message", "Route '$normalizedPath' not found in CCX multiplexer.")
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

    private fun executeForwardRequest(req: Request, output: OutputStream): Int {
        return try {
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val contentType = resp.header("Content-Type", "application/json; charset=utf-8")
                val isStreaming = contentType?.contains("text/event-stream") == true

                if (isStreaming) {
                    val responseHeader = "HTTP/1.1 $code ${resp.message}\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Transfer-Encoding: chunked\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                    output.write(responseHeader.toByteArray(Charsets.UTF_8))

                    val source = resp.body?.source()
                    if (source != null) {
                        val buffer = ByteArray(4096)
                        while (!source.exhausted()) {
                            val read = source.read(buffer)
                            if (read <= 0) break
                            val chunkHeader = Integer.toHexString(read) + "\r\n"
                            output.write(chunkHeader.toByteArray(Charsets.UTF_8))
                            output.write(buffer, 0, read)
                            output.write("\r\n".toByteArray(Charsets.UTF_8))
                            output.flush()
                        }
                        output.write("0\r\n\r\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                } else {
                    val respBytes = resp.body?.bytes() ?: ByteArray(0)
                    val responseHeader = "HTTP/1.1 $code ${resp.message}\r\n" +
                            "Content-Type: $contentType\r\n" +
                            "Content-Length: ${respBytes.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n\r\n"
                    output.write(responseHeader.toByteArray(Charsets.UTF_8))
                    output.write(respBytes)
                    output.flush()
                }
                code
            }
        } catch (e: Exception) {
            sendJsonResponse(output, 502, JSONObject().apply {
                put("error", JSONObject().apply {
                    put("message", "Upstream connection failed: ${e.message}")
                    put("type", "upstream_error")
                })
            }.toString())
            502
        }
    }

    private fun missingAccountError(msg: String): String {
        return JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", msg)
                put("type", "missing_upstream_account")
            })
        }.toString()
    }

    private fun sendJsonResponse(output: OutputStream, code: Int, json: String) {
        val statusText = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
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
