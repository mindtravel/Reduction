package com.mindtravel.reduction.model

import com.mindtravel.reduction.core.CancelSignal
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The blocking read in the transport must stop when the reply is cancelled.
 * A bare server socket is enough: the Android unit-test classpath has no
 * com.sun.net.httpserver, and the only thing needed is one endless SSE response.
 */
class DeepSeekHttpCancelTest {

    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        runCatching { server?.close() }
    }

    @Test
    fun `cancelling a reply closes the streaming connection`() {
        val firstLine = CountDownLatch(1)
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { server = it }
        Thread({
            runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = "data: {\"choices\":[{\"delta\":{\"content\":\"半句\"}}]}\n\n".toByteArray()
                    val out = client.getOutputStream()
                    out.write(
                        (
                            "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/event-stream\r\n" +
                                "Transfer-Encoding: chunked\r\n" +
                                "Connection: keep-alive\r\n\r\n"
                            ).toByteArray()
                    )
                    out.write("${Integer.toHexString(body.size)}\r\n".toByteArray())
                    out.write(body)
                    out.write("\r\n".toByteArray())
                    out.flush()
                    firstLine.countDown()
                    Thread.sleep(30_000)
                }
            }
        }, "test-stream").apply { isDaemon = true }.start()

        val http = DeepSeekHttp(baseUrl = "http://127.0.0.1:${socket.localPort}")
        val signal = CancelSignal()
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            try {
                http.stream("/stream", "sk-test", JSONObject(), signal) { }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        reader.start()

        assertTrue("the first line never arrived", firstLine.await(5, TimeUnit.SECONDS))
        signal.cancel()
        reader.join(3_000)

        assertFalse("the stream is still reading after cancel", reader.isAlive)
        assertTrue("expected CancellationException, got ${failure.get()}", failure.get() is CancellationException)
    }
}
