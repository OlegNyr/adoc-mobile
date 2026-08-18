package io.github.olegnyr.adocmobile.diagram

import io.github.olegnyr.adocmobile.render.DiagramOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-4`, `TC-17`: срок загрузки срабатывает.
 *
 * Единственный кейс фичи, живущий вне `commonTest`, и причина одна — часы.
 * `kotlinx-coroutines-test` в проект не заведён (новая зависимость — развилка
 * владельца), а без виртуального времени срок надо ждать по-настоящему. Здесь
 * он короткий, устройство не нужно.
 *
 * Условие переноса, а не пожелание: перенесённый в `commonTest` кейс обязан
 * проверять *то же самое* — что срок **сработал**, а не что он настроен.
 * Виртуальное время делает подмену особенно лёгкой: тест, продвинувший часы и
 * убедившийся, что `withTimeoutOrNull` позвали с нужным числом, проверяет
 * конфигурацию, а не поведение. Оракул обязан остаться прежним: загрузка
 * кончилась, в хранилище ничего не легло, исход записан как истечение срока.
 */
class DiagramTimeoutTest {

    private val server = "https://kroki.example"
    private val options = DiagramOptions(krokiEnabled = true, serverUrl = server)

    private fun address(payload: String) =
        checkNotNull(parseKrokiAddress("$server/plantuml/svg/$payload", server))

    @Test
    fun TC_17_silentServerEndsOnTimeout() {
        val store = DiagramImageStore()
        val records = mutableListOf<DiagramLoadRecord>()
        val silent = DiagramTransport { CompletableDeferred<ByteArray?>().await() }
        val resolver = DiagramResolver(
            options = { options },
            store = store,
            transport = { silent },
            inflate = { null },
            timeoutMillis = TIMEOUT_MILLIS,
            log = { records += it },
        )

        val startedAt = System.nanoTime()
        runBlocking { resolver.load(listOf(address("AAAA"))) }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        assertFalse(store.contains(address("AAAA")), "молчащая загрузка всё же что-то положила")
        assertEquals(DiagramLoadOutcome.TimedOut, records.single().outcome, "исход записан не как истечение срока")
        // Запас втрое, а не в сто раз: тест обязан падать при сроке, выросшем на
        // порядок, иначе он зелен при любом сломанном таймауте.
        assertTrue(
            elapsedMillis < TIMEOUT_MILLIS * 3,
            "загрузка держалась $elapsedMillis мс при сроке $TIMEOUT_MILLIS мс",
        )
    }

    @Test
    fun TC_17_defaultTimeoutIsTheOneRequirementsName() {
        // Число из NFR-1 закрепляется тестом, иначе умолчание может уехать
        // незаметно: во всех прочих кейсах срок подставляется свой, короткий.
        assertEquals(10_000, DIAGRAM_REQUEST_TIMEOUT_MILLIS)
        assertEquals(4, DIAGRAM_PARALLEL_REQUESTS)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 100L
    }
}
