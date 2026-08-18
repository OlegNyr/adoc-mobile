package io.github.olegnyr.adocmobile.diagram

import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-3`: транспорт против настоящего сокета.
 *
 * Дом — host-тест, а не устройство: сервер поднимается на петле (`127.0.0.1`),
 * то есть проверяется настоящий `HttpURLConnection` с настоящими кодами ответа
 * и таймаутами, но без телефона и без единого обращения наружу. Тот же приём,
 * что с распаковкой: платить устройством за то, что проверяется на JVM, правила
 * проекта запрещают.
 */
class HttpDiagramTransportTest {

    private val transport = HttpDiagramTransport(connectTimeoutMillis = 2_000, readTimeoutMillis = 2_000)

    @Test
    fun TC_43_imageIsFetchedFromTheServer() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()

        FakeServer(status = "200 OK", body = svg).use { server ->
            val bytes = runBlocking { transport.fetch(server.urlFor("/plantuml/svg/AAAA")) }

            assertContentEquals(svg, bytes)
        }
    }

    @Test
    fun TC_43_serverErrorGivesNoImage() {
        FakeServer(status = "500 Internal Server Error", body = "boom".encodeToByteArray()).use { server ->
            assertNull(runBlocking { transport.fetch(server.urlFor("/plantuml/svg/AAAA")) })
        }
    }

    @Test
    fun TC_43_redirectIsNotFollowed() {
        // Сервер пользователя не должен уметь увести запрос на чужой хост.
        FakeServer(
            status = "302 Found",
            body = ByteArray(0),
            extraHeaders = listOf("Location: https://evil.example/steal"),
        ).use { server ->
            assertNull(runBlocking { transport.fetch(server.urlFor("/plantuml/svg/AAAA")) })
        }
    }

    @Test
    fun TC_43_oversizedResponseIsRefused() {
        val limited = HttpDiagramTransport(readTimeoutMillis = 2_000, maxBytes = 1024)

        FakeServer(status = "200 OK", body = ByteArray(64 * 1024)).use { server ->
            assertNull(runBlocking { limited.fetch(server.urlFor("/plantuml/svg/AAAA")) })
        }
    }

    @Test
    fun TC_43_unreachableServerGivesNoImage() {
        // Порт занят закрытым сокетом: соединение отвергается сразу, без ожидания.
        val port = ServerSocket(0).use { it.localPort }

        assertNull(runBlocking { transport.fetch("http://127.0.0.1:$port/plantuml/svg/AAAA") })
    }

    @Test
    fun TC_43_silentServerEndsOnTimeoutInsteadOfHanging() {
        FakeServer(status = null, body = ByteArray(0)).use { server ->
            val started = System.nanoTime()

            val bytes = runBlocking { transport.fetch(server.urlFor("/plantuml/svg/AAAA")) }

            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertNull(bytes)
            assertTrue(elapsedMillis < 10_000, "молчащий сервер держал запрос $elapsedMillis мс")
        }
    }

    /**
     * Минимальный HTTP-сервер на петле.
     *
     * `status = null` означает «принять соединение и молчать» — так проверяется
     * таймаут чтения.
     */
    private class FakeServer(
        private val status: String?,
        private val body: ByteArray,
        private val extraHeaders: List<String> = emptyList(),
    ) : Closeable {

        private val socket = ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())
        private val worker = Executors.newSingleThreadExecutor()

        init {
            worker.submit {
                runCatching {
                    socket.accept().use { client ->
                        client.getInputStream().read(ByteArray(4096))
                        if (status == null) {
                            Thread.sleep(30_000)
                            return@use
                        }
                        val header = buildString {
                            append("HTTP/1.1 ").append(status).append("\r\n")
                            append("Content-Length: ").append(body.size).append("\r\n")
                            extraHeaders.forEach { append(it).append("\r\n") }
                            append("\r\n")
                        }
                        client.getOutputStream().apply {
                            write(header.encodeToByteArray())
                            write(body)
                            flush()
                        }
                    }
                }
            }
        }

        fun urlFor(path: String): String = "http://127.0.0.1:${socket.localPort}$path"

        override fun close() {
            worker.shutdownNow()
            runCatching { socket.close() }
        }
    }
}
