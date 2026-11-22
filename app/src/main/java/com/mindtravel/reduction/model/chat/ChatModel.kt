package com.mindtravel.reduction.model.chat

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.CancelSignal
import com.mindtravel.reduction.core.objects
import com.mindtravel.reduction.core.TextSanitizer
import com.mindtravel.reduction.core.stringOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    companion object {
        fun fromWire(value: String): Role =
            entries.firstOrNull { it.wire == value }
                ?: throw ApiException("未知消息角色：$value")
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
) {
    init {
        if (id.isBlank()) throw ApiException("工具调用 id 为空")
        if (name.isBlank()) throw ApiException("工具调用名称为空")
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        })
    }
}

/** A picture handed to the model to look at: its media type and the file itself, in base64. */
data class ChatImage(val name: String, val mediaType: String, val base64: String) {
    companion object {
        const val MAX_PER_MESSAGE = 4
        private const val MAX_BASE64_CHARS = 6 * 1024 * 1024
        private val KINDS = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

        /**
         * Pictures as the page hands them over: a JSON list of {name, mediaType, base64}.
         * What is not a picture of a kind the model takes, or is too large, is left out.
         */
        fun listFromJson(raw: String): List<ChatImage> = runCatching {
            val list = JSONArray(raw)
            (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val type = item.optString("mediaType")
                val data = item.optString("base64")
                if (type !in KINDS || data.isEmpty() || data.length > MAX_BASE64_CHARS) null
                else ChatImage(item.optString("name").ifBlank { "图片 ${index + 1}" }, type, data)
            }.take(MAX_PER_MESSAGE)
        }.getOrDefault(emptyList())
    }
}

data class ChatMessage(
    val role: Role,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** Pictures that go with a user message. The model is given the pictures themselves. */
    val images: List<ChatImage> = emptyList()
) {
    init {
        when (role) {
            Role.SYSTEM, Role.USER -> if (content.isNullOrBlank()) {
                throw ApiException("${role.wire} 消息缺少 content")
            }
            Role.TOOL -> if (toolCallId.isNullOrBlank() || content == null) {
                throw ApiException("tool 消息缺少 toolCallId 或 content")
            }
            Role.ASSISTANT -> if (content == null && toolCalls.isEmpty()) {
                throw ApiException("assistant 消息缺少 content 和 tool_calls")
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role.wire)
        when (role) {
            Role.ASSISTANT -> {
                if (content == null) put("content", JSONObject.NULL) else put("content", content)
                if (toolCalls.isNotEmpty()) {
                    put("tool_calls", JSONArray().apply {
                        toolCalls.forEach { put(it.toJson()) }
                    })
                }
            }
            Role.TOOL -> {
                put("content", content)
                put("tool_call_id", toolCallId)
            }
            else -> if (images.isEmpty()) {
                put("content", content)
            } else {
                // Text and pictures side by side, as the chat API takes them.
                put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", content))
                    for (image in images) {
                        put(
                            JSONObject().put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:${image.mediaType};base64,${image.base64}"))
                        )
                    }
                })
            }
        }
    }

    companion object {
        fun fromJsonArray(raw: String): List<ChatMessage> {
            val array = try {
                JSONArray(raw)
            } catch (t: Throwable) {
                throw ApiException("消息格式错误", t)
            }
            val items = array.objects()
            if (items.isEmpty()) throw ApiException("消息为空")
            return items.map { item ->
                val role = Role.fromWire(item.stringOrNull("role") ?: throw ApiException("消息缺少 role"))
                when (role) {
                    Role.SYSTEM, Role.USER -> {
                        val content = item.stringOrNull("content")
                            ?.takeIf { it.isNotBlank() }
                            ?: throw ApiException("${role.wire} 消息缺少 content")
                        ChatMessage(role = role, content = content)
                    }
                    Role.ASSISTANT -> {
                        val content = item.stringOrNull("content")
                            ?.let(TextSanitizer::stripRepeatedNullPrefix)
                            ?.takeIf { it.isNotBlank() }
                        ChatMessage(role = role, content = content)
                    }
                    Role.TOOL -> throw ApiException("客户端不能提交 tool 消息")
                }
            }
        }
    }
}

/** One argument of a tool. [choices], when given, are the only values allowed. */
data class ToolParam(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val choices: List<String> = emptyList(),
    val jsonType: String = "string",
    val fields: List<ToolParam> = emptyList()
)

data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: List<ToolParam>
) {
    /** A tool with a single required text argument. */
    constructor(name: String, description: String, parameterName: String, parameterDescription: String) :
        this(name, description, listOf(ToolParam(parameterName, parameterDescription)))

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    for (parameter in parameters) {
                        put(parameter.name, JSONObject().apply {
                            put("type", parameter.jsonType)
                            put("description", parameter.description)
                            if (parameter.fields.isNotEmpty()) put("properties", JSONObject().apply {
                                for (field in parameter.fields) put(field.name, JSONObject()
                                    .put("type",field.jsonType).put("description",field.description))
                            })
                            if (parameter.choices.isNotEmpty()) put("enum", JSONArray(parameter.choices))
                        })
                    }
                })
                put("required", JSONArray(parameters.filter { it.required }.map { it.name }))
            })
        })
    }
}

data class ChatRequest(
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double = 0.7,
    /** Carries "the user no longer wants this reply" to the transport; not part of the wire format. */
    val cancellation: CancelSignal = CancelSignal()
)

sealed interface ChatStreamEvent {
    data class Content(val text: String) : ChatStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String?
    ) : ChatStreamEvent
    data class Failure(val message: String) : ChatStreamEvent
    data class Finished(val reason: String?) : ChatStreamEvent
    data object Done : ChatStreamEvent
}
