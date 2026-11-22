package com.mindtravel.reduction.feature.chat.ui

import com.mindtravel.reduction.core.settings.AppSettings
import com.mindtravel.reduction.core.VoiceTrace

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.CancelSignal
import com.mindtravel.reduction.core.Logs
import com.mindtravel.reduction.core.events.EventSink
import com.mindtravel.reduction.core.settings.SettingsGateway
import com.mindtravel.reduction.feature.chat.model.ChatCallbacks
import com.mindtravel.reduction.feature.chat.model.ChatEngine
import com.mindtravel.reduction.feature.chat.model.ChatSession
import com.mindtravel.reduction.feature.chat.model.tools.ChatToolFactory
import com.mindtravel.reduction.core.NetworkException
import com.mindtravel.reduction.feature.voice.Cue
import com.mindtravel.reduction.feature.voice.SpeechController
import com.mindtravel.reduction.model.chat.ChatMessage
import com.mindtravel.reduction.model.chat.ChatRequest
import com.mindtravel.reduction.model.chat.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Told when a reply begins and when it is completely over (generated and spoken). */
interface ReplyObserver {
    fun onReplyStarted(id: String)
    fun onReplyEnded(id: String, ok: Boolean)
}

/**
 * Runs one reply at a time: streams the model's text to the UI and, in step,
 * into speech. Starting a new reply or calling [cancel] abandons the current one.
 */
class ChatController(
    private val settingsRepository: SettingsGateway,
    private val chatEngine: ChatEngine,
    private val toolFactory: ChatToolFactory,
    private val speech: SpeechController,
    /** Says a ready-made cue. A reply that cannot be given is still heard about. */
    private val say: (Cue) -> Unit,
    private val emitter: EventSink,
    private val scope: CoroutineScope,
    private val chatModel: (AppSettings) -> String = { it.model }
) {
    var observer: ReplyObserver? = null

    private class Reply(val id: String) {
        val cancellation = CancelSignal()
        var generated = false
        var spoken = false
        var closed = false
        var job: Job? = null
    }

    private val lock = Any()
    private var current: Reply? = null

    /**
     * Starts a reply to [messages]. [onCompleted] is given the finished text once, when the
     * reply has been generated in full.
     */
    fun send(requestId: String, messages: List<ChatMessage>, onCompleted: (String) -> Unit = {}): Boolean {
        val settings = settingsRepository.load()
        val model = chatModel(settings)
        if (model != com.mindtravel.reduction.model.chat.SuperAgentGateway.DOT && settings.apiKey.isBlank()) {
            emitter.emit("onChatError", requestId, "请先在设置里填写 API Key")
            return false
        }

        val boundedMessages = if (messages.size <= MAX_MESSAGES) {
            messages
        } else {
            val system = messages.firstOrNull { it.role == Role.SYSTEM }
            listOfNotNull(system) + messages.filter { it.role != Role.SYSTEM }.takeLast(MAX_MESSAGES - 1)
        }

        val registry = toolFactory.create(settings)
        val reply = Reply(requestId)
        val request = ChatRequest(
            apiKey = settings.apiKey,
            model = model,
            messages = boundedMessages,
            tools = registry.specs(),
            cancellation = reply.cancellation
        )

        cancel()
        synchronized(lock) { current = reply }
        speech.open(requestId) {
            synchronized(lock) { reply.spoken = true }
            settle(reply, ok = true)
        }
        observer?.onReplyStarted(requestId)

        Logs.add("chat request model=${settings.model} tools=${registry.specs().size}")
        emitter.emit("onStatus", requestId, "思考中")

        reply.job = scope.launch(Dispatchers.IO) {
            try {
                chatEngine.run(request, registry, callbacksFor(reply, onCompleted))
            } catch (t: CancellationException) {
                // A new message, a wake word or a stop: the old reply is no longer wanted.
                Logs.add("chat cancelled")
            } catch (t: NetworkException) {
                Logs.add("chat failed after ${ChatSession.ATTEMPTS} attempts: ${t.message}")
                fail(reply, "试了 ${ChatSession.ATTEMPTS} 次都没连上：${t.message ?: "网络不通"}", Cue.NO_NETWORK)
            } catch (t: ApiException) {
                fail(reply, t.message ?: "请求失败")
            } catch (t: Throwable) {
                Logs.add("chat crashed: ${t.message}")
                fail(reply, "请求失败：${t.message ?: "未知错误"}")
            }
        }
        return true
    }

    /** A reply is being generated or spoken. */
    fun isBusy(): Boolean = synchronized(lock) { current != null }

    /** Abandons the reply in progress, if any. The UI is told through onChatAborted. */
    fun cancel() {
        val reply = synchronized(lock) {
            val active = current ?: return
            current = null
            active.closed = true
            active
        }
        reply.cancellation.cancel()
        reply.job?.cancel()
        speech.stop(notify = false)
        Logs.add("chat aborted by ${Throwable().stackTrace.getOrNull(1)?.let { it.className.substringAfterLast('.') + "." + it.methodName }}")
        emitter.emit("onChatAborted", reply.id)
    }

    private fun callbacksFor(reply: Reply, onCompleted: (String) -> Unit) = object : ChatCallbacks {
        override fun onToken(text: String) {
            if (!isCurrent(reply)) return
            if (text.isNotEmpty()) VoiceTrace.mark(reply.id, "token")
            emitter.emit("onChatToken", reply.id, text)
            speech.push(reply.id, text)
        }

        override fun onToolStarted(name: String, summary: String, arguments: String, announce: Boolean) {
            if (!isCurrent(reply)) return
            Logs.add("chat tool started=$name announce=$announce")
            // Text before a tool call is a preamble the UI discards; stop saying it.
            speech.restart(reply.id)
            if (announce) speech.push(reply.id, "$summary。\n")
            emitter.emit("onToolStarted", reply.id, name, summary, arguments)
        }

        override fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String) {
            if (!isCurrent(reply)) return
            Logs.add("chat tool finished=$name ${durationMs}ms success=$success")
            emitter.emit("onToolFinished", reply.id, name, durationMs, success, result.take(TOOL_RESULT_SHOWN))
        }

        override fun onRetrying(attempt: Int, attempts: Int) {
            if (!isCurrent(reply)) return
            emitter.emit("onStatus", reply.id, "网络不通，重试第 ${attempt - 1} 次")
            // Said once: the user knows why it is taking long, without being told twice.
            if (attempt == 2 && speaks()) say(Cue.RETRYING)
        }

        override fun onCompleted(text: String) {
            if (!isCurrent(reply)) return
            Logs.add("chat completed chars=${text.length}")
            emitter.emit("onChatDone", reply.id, text)
            onCompleted(text)
            synchronized(lock) { reply.generated = true }
            speech.close(reply.id)
            settle(reply, ok = true)
        }

        override fun onFailed(message: String) {
            Logs.add("chat failed: $message")
            fail(reply, message)
        }
    }

    private fun fail(reply: Reply, message: String, cue: Cue = Cue.FAILED) {
        if (!isCurrent(reply)) return
        VoiceTrace.mark(reply.id, "modelfailed")
        emitter.emit("onChatError", reply.id, message)
        settle(reply, ok = false)
        speech.stop(notify = false)
        if (speaks()) say(cue)
    }

    /** Replies are read aloud, so their absence is announced aloud too. */
    private fun speaks(): Boolean = settingsRepository.load().autoSpeak

    /** Reports the end of [reply] once: on failure at once, otherwise when generated and spoken. */
    private fun settle(reply: Reply, ok: Boolean) {
        synchronized(lock) {
            if (reply.closed) return
            if (ok && !(reply.generated && reply.spoken)) return
            reply.closed = true
            if (current === reply) current = null
        }
        observer?.onReplyEnded(reply.id, ok)
    }

    private fun isCurrent(reply: Reply): Boolean = synchronized(lock) { current === reply && !reply.closed }

    companion object {
        private const val MAX_MESSAGES = 40

        /** How much of a tool result the tool card can show. */
        private const val TOOL_RESULT_SHOWN = 12_000
    }
}
