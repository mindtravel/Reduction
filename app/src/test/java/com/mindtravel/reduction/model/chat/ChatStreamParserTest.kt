package com.mindtravel.reduction.model.chat

import com.mindtravel.reduction.core.ApiException
import com.mindtravel.reduction.core.CancelSignal
import com.mindtravel.reduction.core.NetworkException
import com.mindtravel.reduction.feature.chat.model.ChatCallbacks
import com.mindtravel.reduction.feature.chat.model.ChatSession
import com.mindtravel.reduction.feature.chat.model.ToolCallAccumulator
import com.mindtravel.reduction.feature.chat.model.tools.CalculatorTool
import com.mindtravel.reduction.feature.chat.model.tools.ChatTool
import com.mindtravel.reduction.feature.chat.model.tools.ToolRegistry
import com.mindtravel.reduction.model.chat.ChatStreamParser
import com.mindtravel.reduction.model.chat.ToolSpec
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatStreamParserTest {

    private val parser = ChatStreamParser()

    @Test
    fun `normal content is emitted`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}"""
        )
        assertEquals(listOf(ChatStreamEvent.Content("你好")), events)
    }

    @Test
    fun `null content is ignored`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"role":"assistant","content":null}}]}"""
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `empty content is ignored`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"content":""}}]}"""
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `tool call delta is typed`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"calculator","arguments":"{\"expression\":\"1+1\"}"}}]}}]}"""
        )
        assertEquals(
            listOf(
                ChatStreamEvent.ToolCallDelta(
                    index = 0,
                    id = "call_1",
                    name = "calculator",
                    arguments = """{"expression":"1+1"}"""
                )
            ),
            events
        )
    }

    @Test
    fun `finish reason is emitted`() {
        val events = parser.parse(
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        )
        assertEquals(listOf(ChatStreamEvent.Finished("stop")), events)
    }

    @Test
    fun `error chunk is a failure`() {
        val events = parser.parse(
            """data: {"error":{"message":"The model failed to generate a complete response."}}"""
        )
        assertEquals(
            listOf(ChatStreamEvent.Failure("The model failed to generate a complete response.")),
            events
        )
    }

    @Test
    fun `done marker is typed`() {
        assertEquals(listOf(ChatStreamEvent.Done), parser.parse("data: [DONE]"))
    }

    @Test
    fun `non data lines are ignored`() {
        assertTrue(parser.parse("event: message").isEmpty())
        assertTrue(parser.parse("").isEmpty())
    }
}

class ToolCallAccumulatorTest {

    @Test
    fun `arguments are appended across deltas`() {
        val accumulator = ToolCallAccumulator()
        accumulator.apply(ChatStreamEvent.ToolCallDelta(0, "call_1", "calculator", "{\"expr"))
        accumulator.apply(ChatStreamEvent.ToolCallDelta(0, null, null, "ession\":\"1+1\"}"))
        assertEquals(
            listOf(ToolCall("call_1", "calculator", "{\"expression\":\"1+1\"}")),
            accumulator.build()
        )
    }

    @Test
    fun `missing id fails`() {
        val accumulator = ToolCallAccumulator()
        accumulator.apply(ChatStreamEvent.ToolCallDelta(0, null, "calculator", "{}"))
        try {
            accumulator.build()
            fail("expected ApiException")
        } catch (_: ApiException) {
        }
    }
}

class ChatSessionTest {

    private class FakeChatGateway(private val rounds: List<List<ChatStreamEvent>>) : ChatGateway {
        val requests = mutableListOf<ChatRequest>()
        private var index = 0

        override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
            requests += request
            rounds[index++].forEach(onEvent)
        }
    }

    private open class RecordingCallbacks : ChatCallbacks {
        val tokens = mutableListOf<String>()
        val tools = mutableListOf<String>()
        var completed: String? = null
        var failed: String? = null

        override fun onToken(text: String) {
            tokens += text
        }

        override fun onToolStarted(name: String, summary: String, arguments: String, announce: Boolean) {
            tools += "$name｜$summary｜$announce"
        }

        override fun onToolFinished(name: String, durationMs: Long, success: Boolean, result: String) = Unit

        override fun onCompleted(text: String) {
            completed = text
        }

        override fun onFailed(message: String) {
            failed = message
        }
    }

    @Test
    fun `leading literal null tokens are dropped`() {
        val api = FakeChatGateway(
            listOf(
                listOf(
                    ChatStreamEvent.Content("null"),
                    ChatStreamEvent.Content("nullnull"),
                    ChatStreamEvent.Content("你好"),
                    ChatStreamEvent.Finished("stop")
                )
            )
        )
        val callbacks = RecordingCallbacks()
        ChatSession(api).run(
            ChatRequest(
                apiKey = "test",
                model = "deepseek-flash",
                messages = listOf(ChatMessage(Role.USER, "你好")),
                tools = emptyList()
            ),
            ToolRegistry(emptyList()),
            callbacks
        )
        assertEquals("你好", callbacks.completed)
        assertEquals(listOf("你好"), callbacks.tokens)
    }

    @Test
    fun `tool round appends assistant and tool messages`() {
        val api = FakeChatGateway(
            listOf(
                listOf(
                    ChatStreamEvent.ToolCallDelta(
                        0,
                        "call_1",
                        "calculator",
                        """{"expression":"1+1"}"""
                    ),
                    ChatStreamEvent.Finished("tool_calls")
                ),
                listOf(
                    ChatStreamEvent.Content("答案"),
                    ChatStreamEvent.Content("是 2"),
                    ChatStreamEvent.Finished("stop")
                )
            )
        )
        val callbacks = RecordingCallbacks()
        val session = ChatSession(api)
        session.run(
            ChatRequest(
                apiKey = "test",
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage(Role.SYSTEM, "system"),
                    ChatMessage(Role.USER, "1+1")
                ),
                tools = emptyList()
            ),
            ToolRegistry(listOf(CalculatorTool())),
            callbacks
        )

        assertEquals("答案是 2", callbacks.completed)
        assertEquals(listOf("答案", "是 2"), callbacks.tokens)
        assertEquals(listOf("calculator｜计算：1+1｜false"), callbacks.tools)
        assertEquals(2, api.requests.size)
        assertEquals(4, api.requests[1].messages.size)
        assertEquals(Role.ASSISTANT, api.requests[1].messages[2].role)
        assertEquals("calculator", api.requests[1].messages[2].toolCalls.single().name)
        assertEquals(Role.TOOL, api.requests[1].messages[3].role)
        assertEquals("2", api.requests[1].messages[3].content)
    }

    /** Fails with a network error the first [failures] times it is asked, then answers. */
    private class FlakyGateway(private val failures: Int, private val failAfterContent: Boolean = false) : ChatGateway {
        var asked = 0
        override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
            if (++asked <= failures) {
                if (failAfterContent) onEvent(ChatStreamEvent.Content("半句"))
                throw com.mindtravel.reduction.core.NetworkException("网络错误：断了")
            }
            onEvent(ChatStreamEvent.Content("通了"))
            onEvent(ChatStreamEvent.Finished("stop"))
        }
    }

    private fun ask(gateway: ChatGateway, callbacks: ChatCallbacks) = ChatSession(gateway, pause = {}).run(
        ChatRequest(apiKey = "test", model = "deepseek-flash", messages = listOf(ChatMessage(Role.USER, "你好")), tools = emptyList()),
        ToolRegistry(emptyList()),
        callbacks
    )

    @Test
    fun `a request that does not get through is sent again`() {
        val gateway = FlakyGateway(failures = 2)
        val retries = mutableListOf<Int>()
        val callbacks = object : RecordingCallbacks() {
            override fun onRetrying(attempt: Int, attempts: Int) { retries += attempt }
        }
        ask(gateway, callbacks)
        assertEquals("通了", callbacks.completed)
        assertEquals(3, gateway.asked)
        assertEquals(listOf(2, 3), retries)
    }

    @Test
    fun `after three attempts the network error is the caller's to report`() {
        val gateway = FlakyGateway(failures = 5)
        val callbacks = RecordingCallbacks()
        try {
            ask(gateway, callbacks)
            fail("expected NetworkException")
        } catch (_: com.mindtravel.reduction.core.NetworkException) {
        }
        assertEquals(ChatSession.ATTEMPTS, gateway.asked)
        assertEquals(null, callbacks.completed)
    }

    @Test
    fun `an answer that broke off half way is not started over`() {
        val gateway = FlakyGateway(failures = 1, failAfterContent = true)
        val callbacks = RecordingCallbacks()
        try {
            ask(gateway, callbacks)
            fail("expected NetworkException")
        } catch (_: com.mindtravel.reduction.core.NetworkException) {
        }
        assertEquals(1, gateway.asked)
        assertEquals(listOf("半句"), callbacks.tokens)
    }

    /** Emits a tool call, then cancels the reply before the tool can run. */
    private class CancellingAfterToolCall : ChatGateway {
        override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
            onEvent(ChatStreamEvent.ToolCallDelta(0, "call_1", "touch", "{}"))
            onEvent(ChatStreamEvent.Finished("tool_calls"))
            request.cancellation.cancel()
        }
    }

    /** Cancels the reply and then reports a network error, as a cancelled call does. */
    private class CancellingFailure : ChatGateway {
        var asked = 0

        override fun stream(request: ChatRequest, onEvent: (ChatStreamEvent) -> Unit) {
            asked++
            request.cancellation.cancel()
            throw NetworkException("网络错误：断了")
        }
    }

    private class CountingTool : ChatTool {
        var executions = 0
        override val spec = ToolSpec("touch", "test tool", emptyList())

        override fun execute(argumentsJson: String): String {
            executions++
            return "done"
        }
    }

    @Test
    fun `a cancelled reply does not call the model`() {
        val api = FakeChatGateway(listOf(listOf(ChatStreamEvent.Content("不应该出现"))))
        val callbacks = RecordingCallbacks()
        val signal = CancelSignal().apply { cancel() }

        try {
            ChatSession(api).run(
                ChatRequest(
                    apiKey = "test",
                    model = "deepseek-flash",
                    messages = listOf(ChatMessage(Role.USER, "你好")),
                    tools = emptyList(),
                    cancellation = signal
                ),
                ToolRegistry(emptyList()),
                callbacks
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }

        assertEquals(0, api.requests.size)
        assertEquals(null, callbacks.completed)
        assertEquals(null, callbacks.failed)
    }

    @Test
    fun `a cancelled reply stops before the tool it just asked for`() {
        val tool = CountingTool()
        val callbacks = RecordingCallbacks()

        try {
            ChatSession(CancellingAfterToolCall()).run(
                ChatRequest(
                    apiKey = "test",
                    model = "deepseek-flash",
                    messages = listOf(ChatMessage(Role.USER, "请动手")),
                    tools = emptyList()
                ),
                ToolRegistry(listOf(tool)),
                callbacks
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }

        assertEquals("the tool must not run after the reply was cancelled", 0, tool.executions)
        assertEquals(null, callbacks.completed)
        assertEquals(null, callbacks.failed)
    }

    @Test
    fun `a cancelled reply does not retry the request`() {
        val gateway = CancellingFailure()
        val callbacks = RecordingCallbacks()

        try {
            ChatSession(gateway, pause = {}).run(
                ChatRequest(
                    apiKey = "test",
                    model = "deepseek-flash",
                    messages = listOf(ChatMessage(Role.USER, "你好")),
                    tools = emptyList()
                ),
                ToolRegistry(emptyList()),
                callbacks
            )
            fail("expected CancellationException")
        } catch (_: CancellationException) {
        }

        assertEquals(1, gateway.asked)
        assertEquals(null, callbacks.failed)
    }
}
