package com.example.proxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cross-protocol converter ported from BenedictKing/ccx internal/converters.
 * Handles bidirectional translation between Codex Responses, Claude Messages, and OpenAI Chat.
 */
object CCXProtocolConverter {

    /**
     * Converts Codex /v1/responses payload to OpenAI /v1/chat/completions payload.
     */
    fun responsesToChat(responsesBody: String): String {
        val root = try {
            JSONObject(responsesBody)
        } catch (_: Exception) {
            return responsesBody
        }

        val chatJson = JSONObject()
        val model = root.optString("model", "gpt-4o")
        chatJson.put("model", model)

        val stream = root.optBoolean("stream", false)
        chatJson.put("stream", stream)

        val chatMessages = JSONArray()

        // Extract input items from Codex responses request
        val input = root.optJSONArray("input")
        if (input != null) {
            for (i in 0 until input.length()) {
                val item = input.optJSONObject(i) ?: continue
                val role = item.optString("role", "user")
                val contentObj = item.opt("content")

                var textContent = ""
                when (contentObj) {
                    is String -> {
                        textContent = contentObj
                    }
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (j in 0 until contentObj.length()) {
                            val part = contentObj.optJSONObject(j)
                            if (part != null && part.optString("type") == "text") {
                                sb.append(part.optString("text"))
                            }
                        }
                        textContent = sb.toString()
                    }
                }

                if (textContent.isNotEmpty()) {
                    val msg = JSONObject().apply {
                        put("role", role)
                        put("content", textContent)
                    }
                    chatMessages.put(msg)
                }
            }
        }

        chatJson.put("messages", chatMessages)

        if (root.has("temperature")) {
            chatJson.put("temperature", root.optDouble("temperature", 0.7))
        }
        if (root.has("max_tokens")) {
            chatJson.put("max_tokens", root.optInt("max_tokens", 4096))
        }

        return chatJson.toString()
    }

    /**
     * Converts OpenAI /v1/chat/completions payload to Codex /v1/responses payload.
     */
    fun chatToResponses(chatBody: String): String {
        val root = try {
            JSONObject(chatBody)
        } catch (_: Exception) {
            return chatBody
        }

        val responsesJson = JSONObject()
        responsesJson.put("model", root.optString("model", "gpt-4o"))
        responsesJson.put("stream", root.optBoolean("stream", false))

        val inputArr = JSONArray()
        val messages = root.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                val role = msg.optString("role", "user")
                val content = msg.optString("content", "")

                val item = JSONObject().apply {
                    put("type", "message")
                    put("role", role)
                    val contentArr = JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", content)
                        })
                    }
                    put("content", contentArr)
                }
                inputArr.put(item)
            }
        }

        responsesJson.put("input", inputArr)
        return responsesJson.toString()
    }

    /**
     * Converts Claude /v1/messages payload to Codex /v1/responses payload.
     */
    fun claudeToResponses(claudeBody: String): String {
        val root = try {
            JSONObject(claudeBody)
        } catch (_: Exception) {
            return claudeBody
        }

        val responsesJson = JSONObject()
        responsesJson.put("model", root.optString("model", "claude-3-5-sonnet"))
        responsesJson.put("stream", root.optBoolean("stream", false))

        val inputArr = JSONArray()

        // Optional system prompt in Claude
        val system = root.optString("system")
        if (system.isNotEmpty()) {
            inputArr.put(JSONObject().apply {
                put("type", "message")
                put("role", "system")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", system)
                    })
                })
            })
        }

        val messages = root.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                val role = msg.optString("role", "user")
                val content = msg.opt("content")

                var text = ""
                when (content) {
                    is String -> text = content
                    is JSONArray -> {
                        val sb = StringBuilder()
                        for (j in 0 until content.length()) {
                            val part = content.optJSONObject(j)
                            if (part != null && part.optString("type") == "text") {
                                sb.append(part.optString("text"))
                            }
                        }
                        text = sb.toString()
                    }
                }

                inputArr.put(JSONObject().apply {
                    put("type", "message")
                    put("role", role)
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", text)
                        })
                    })
                })
            }
        }

        responsesJson.put("input", inputArr)
        return responsesJson.toString()
    }
}
