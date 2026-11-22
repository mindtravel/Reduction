package com.mindtravel.reduction.model.chat

import com.mindtravel.reduction.model.chat.ChatGateway
import com.mindtravel.reduction.model.chat.ChatRequest
import com.mindtravel.reduction.model.chat.ChatStreamEvent
import com.mindtravel.reduction.model.DeepSeekHttp
import org.json.JSONArray
import org.json.JSONObject

class DeepSeekChatGateway(
    private val http: DeepSeekHttp,
    private val parser: ChatStreamParser = ChatStreamParser()
) : ChatGateway {

    override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
        val body = JSONObject().apply {
            put("model", request.model)
            put("messages", JSONArray().apply {
                request.messages.forEach { put(it.toJson()) }
            })
            put("stream", true)
            put("temperature", request.temperature)
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { put(it.toJson()) }
                })
                put("tool_choice", "auto")
            }
        }

        http.stream("/chat/completions", request.apiKey, body, request.cancellation) { line ->
            parser.parse(line).forEach(onEvent)
        }
    }
}
