package com.mindtravel.reduction.feature.chat.model

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.CancelSignal
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.NetworkException
import com.mindtravel.reduction.core.TextSanitizer
import com.mindtravel.reduction.feature.chat.model.tools.ToolRegistry
import com.mindtravel.reduction.model.chat.ChatGateway
import com.mindtravel.reduction.model.chat.ChatImage
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.ChatRequest
import com.mindtravel.reduction.model.chat.ChatStreamEvent
import com.mindtravel.reduction.model.chat.Role
import java.util.concurrent.CancellationException

class ChatSession(
    private val gateway: ChatGateway,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    /** Waits between attempts; replaceable so tests do not wait. */
    private val pause: (Long) -> Unit = { Thread.sleep(it) }
) : ChatEngine {

    companion object {
        /** Room for several dispatch → verify → redo cycles in one reply. */
        private const val DEFAULT_MAX_TOOL_ROUNDS = 60

        /** How many times a request is sent before the network is declared down. */
        const val ATTEMPTS = 3
        private const val RETRY_PAUSE_MS = 1_200L

        /** How many pictures the tools of one round may show. */
        const val MAX_PICTURES_SHOWN = 8
    }

    override fun run(
        request: ChatRequest,
        tools: ToolRegistry,
        callbacks: ChatCallbacks
    ) {
        val messages = request.messages.toMutableList()
        val cancellation = request.cancellation

        repeat(maxToolRounds) {
            cancellation.ensureActive()
            val content = StringBuilder()
            var toolCalls = ToolCallAccumulator()
            var failure: String? = null
            var finishReason: String? = null
            var sawContent = false
            var droppedNulls = 0

            // A request that does not get through is sent again, as long as nothing of its answer
            // has been shown or said yet. After the last attempt the failure is the caller's to report.
            var attempt = 1
            while (true) {
                cancellation.ensureActive()
                try {
                    gateway.stream(request.copy(messages = messages.toList(), tools = tools.specs())) { event ->
                        when (event) {
                            is ChatStreamEvent.Content -> {
                                val text = if (sawContent) {
                                    event.text
                                } else {
                                    TextSanitizer.stripLeadingNullChunks(event.text)
                                }
                                if (!sawContent && text != event.text) droppedNulls++
                                if (text.isBlank()) return@stream
                                sawContent = true
                                content.append(text)
                                callbacks.onToken(text)
                            }
                            is ChatStreamEvent.ToolCallDelta -> toolCalls.apply(event)
                            is ChatStreamEvent.Failure -> failure = event.message
                            is ChatStreamEvent.Finished -> finishReason = event.reason
                            ChatStreamEvent.Done -> Unit
                        }
                    }
                    break
                } catch (t: NetworkException) {
                    if (cancellation.isCancelled) throw CancellationException("回复已取消")
                    if (sawContent || attempt >= ATTEMPTS) throw t
                    attempt++
                    Logs.add("Chat request did not get through, attempt $attempt of $ATTEMPTS")
                    callbacks.onRetrying(attempt, ATTEMPTS)
                    toolCalls = ToolCallAccumulator()
                    failure = null
                    finishReason = null
                    rest(cancellation)
                }
            }

            cancellation.ensureActive()

            if (droppedNulls > 0) {
                Logs.add("chat dropped null tokens=$droppedNulls")
            }

            failure?.let {
                callbacks.onFailed(it)
                return
            }

            val calls = try {
                toolCalls.build()
            } catch (t: ApiException) {
                callbacks.onFailed(t.message ?: "工具调用格式错误")
                return
            }

            if (calls.isEmpty()) {
                val text = content.toString()
                if (text.isBlank()) {
                    val message = when (finishReason) {
                        "content_filter" -> "内容被安全策略拦截"
                        "length" -> "回复超长被截断"
                        else -> "DeepSeek 没有返回内容"
                    }
                    callbacks.onFailed(message)
                } else {
                    callbacks.onCompleted(text)
                }
                return
            }

            Logs.add("Chat tool round tools=${calls.size}")
            messages += ChatMessage(
                role = Role.ASSISTANT,
                content = content.toString().takeIf { it.isNotBlank() },
                toolCalls = calls
            )

            val shown = ArrayList<ChatImage>()
            for (call in calls) {
                cancellation.ensureActive()
                callbacks.onToolStarted(
                    call.name,
                    tools.summary(call.name, call.arguments),
                    call.arguments,
                    tools.announces(call.name, call.arguments)
                )
                cancellation.ensureActive()
                val startedAt = System.nanoTime()
                val result = tools.execute(call.name, call.arguments)
                val durationMs = (System.nanoTime() - startedAt) / 1_000_000
                val success = !result.startsWith("工具执行失败") && !result.startsWith("未知工具")
                callbacks.onToolFinished(call.name, durationMs, success, result)
                messages += ChatMessage(
                    role = Role.TOOL,
                    content = result,
                    toolCallId = call.id
                )
                shown += tools.pictures(call.name)
            }
            // What the tools had to show comes after all their answers: a message of pictures, which is
            // the only kind that can carry them. It says whose it is, so it is not taken for the user's.
            if (shown.isNotEmpty()) {
                messages += ChatMessage(
                    role = Role.USER,
                    content = "［程序补充，不是用户说的话］上面工具结果提到的画面，按顺序是：" + shown.joinToString("、") { it.name },
                    images = shown.take(MAX_PICTURES_SHOWN)
                )
            }
        }

        cancellation.ensureActive()
        callbacks.onFailed("工具调用次数过多，请换个问法")
    }

    /** Waits between attempts in small steps, so a cancellation does not wait out the whole pause. */
    private fun rest(cancellation: CancelSignal) {
        var left = RETRY_PAUSE_MS
        while (left > 0) {
            cancellation.ensureActive()
            val step = minOf(left, 100L)
            pause(step)
            left -= step
        }
        cancellation.ensureActive()
    }
}
