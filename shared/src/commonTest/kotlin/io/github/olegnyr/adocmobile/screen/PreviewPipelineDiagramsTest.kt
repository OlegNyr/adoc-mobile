package io.github.olegnyr.adocmobile.screen

import io.github.olegnyr.adocmobile.diagram.DiagramImageStore
import io.github.olegnyr.adocmobile.diagram.DiagramResolver
import io.github.olegnyr.adocmobile.diagram.DiagramTransport
import io.github.olegnyr.adocmobile.diagram.Inflate
import io.github.olegnyr.adocmobile.render.AdocRenderer
import io.github.olegnyr.adocmobile.render.DiagramOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-3`: проводка двух публикаций.
 *
 * Транспорт проверен отдельно, политика — отдельно; здесь проверяется то, что
 * обычно теряется *между* ними: доходит ли отмена до загрузки и не ложится ли
 * поздний ответ поверх свежего текста. Оба дефекта воспроизводятся редко и
 * ищутся долго, поэтому им нужен тест на стыке, а не на концах.
 */
class PreviewPipelineDiagramsTest {

    private var now = 0L
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val waits = FakeWaits()
    private val transport = FakeTransport()
    private val server = "https://kroki.example"

    private val diagram = """<img src="https://kroki.example/plantuml/svg/AAAA" alt="схема">"""

    /** Настоящее начало SVG: с SL-4 байты проверяются, и «1,2,3» больше не картинка. */
    private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()

    private var source = "= Документ"

    private fun pipeline(): PreviewPipeline = PreviewPipeline(
        renderer = DiagramRenderer(),
        scope = scope,
        clock = { now },
        sourceText = { source },
        page = { fragment -> "<page>$fragment</page>" },
        delayUntil = waits::wait,
        diagrams = DiagramResolver(
            options = { DiagramOptions(krokiEnabled = true, serverUrl = server) },
            store = DiagramImageStore(),
            transport = { transport },
            inflate = { Inflate { _, _ -> "a -> b".encodeToByteArray() } },
        ),
    )

    /** `TC-7`, `TC-20` — покрывает `FR-8`, `FR-9`: сначала плейсхолдер, потом картинка. */
    @Test
    fun TC_20_pageIsPublishedTwiceFirstWithPlaceholder() {
        val pipeline = pipeline()

        pipeline.previewShown()
        val withPlaceholder = pipeline.html
        transport.answerAll(svg)

        assertTrue("kroki-pending" in withPlaceholder.orEmpty(), "первая публикация без плейсхолдера: $withPlaceholder")
        assertTrue("<img" in pipeline.html.orEmpty(), "картинка не подставлена: ${pipeline.html}")
        assertFalse("kroki-pending" in pipeline.html.orEmpty(), "плейсхолдер остался: ${pipeline.html}")
        assertFalse("kroki.example" in pipeline.html.orEmpty(), "внешний адрес дошёл до страницы")
    }

    /**
     * `TC-20` — покрывает `FR-9`, `FR-15` фичи 003: поздние картинки не ложатся
     * на страницу другого поколения.
     *
     * Пользователь правит текст, пока диаграмма грузится. К моменту ответа
     * показана уже другая страница, и класть на неё картинки от прошлой значит
     * показать прошлое.
     */
    @Test
    fun TC_20_lateImagesDoNotLandOnANewerPage() {
        val pipeline = pipeline()
        pipeline.previewShown()

        source = "= Другой документ"
        pipeline.textEdited()
        now += 1_000
        waits.releaseAll()

        // Отвечает *первая* загрузка — та, что была запущена для уже
        // выброшенной страницы. Её ответ не имеет права ничего опубликовать:
        // показан другой документ.
        transport.answerFirst(svg)

        assertTrue("Другой документ" in pipeline.html.orEmpty(), "показана не свежая страница: ${pipeline.html}")
        assertTrue("kroki-pending" in pipeline.html.orEmpty(), "поздний ответ подставил картинку на свежую страницу")
        assertTrue(transport.cancelled > 0, "загрузка прошлой страницы не была отменена правкой текста")
    }

    /**
     * `TC-21` — покрывает `NFR-5`: уход с превью отменяет загрузку.
     *
     * Отмена обязана доходить до транспорта. Проверяется наблюдаемо: после
     * ухода с вкладки ожидающий ответа запрос отменён, и его поздний ответ
     * ничего не публикует.
     */
    @Test
    fun TC_21_leavingPreviewCancelsPendingLoads() {
        val pipeline = pipeline()
        pipeline.previewShown()
        val withPlaceholder = pipeline.html

        pipeline.previewHidden()

        assertTrue(transport.cancelled > 0, "загрузка не была отменена уходом с превью")
        transport.answerAll(svg)
        assertEquals(withPlaceholder, pipeline.html, "отменённая загрузка всё же опубликовалась")
    }

    /**
     * `TC-8`, `TC-17` — покрывает `FR-19`: отказ загрузки превращает плейсхолдер
     * в исходник с пометкой.
     *
     * Плейсхолдер обещает картинку. Если картинки не будет, обещание надо снять
     * — иначе документ без сети остаётся с обещаниями до конца сеанса.
     *
     * Триггером здесь отказ транспорта, а не истечение срока, и это сознательно:
     * дальше первого проходят оба одинаково — резолвер отдаёт «не загрузилось».
     * Сам срок проверяется там, где ему нужны часы (`DiagramTimeoutTest`), а
     * здесь — то, что пайплайн делает с этим исходом.
     */
    @Test
    fun TC_8_failedLoadTurnsPlaceholderIntoSource() {
        val pipeline = pipeline()

        pipeline.previewShown()
        transport.failAll()

        assertFalse("kroki-pending" in pipeline.html.orEmpty(), "плейсхолдер остался навсегда: ${pipeline.html}")
        assertTrue("ДИАГРАММА НЕ ЗАГРУЖЕНА" in pipeline.html.orEmpty(), "нет пометки: ${pipeline.html}")
        assertTrue("a -&gt; b" in pipeline.html.orEmpty(), "исходник диаграммы не показан: ${pipeline.html}")
    }

    /**
     * `TC-30` — покрывает `NFR-8`: то, что не похоже на картинку, картинкой не
     * становится.
     *
     * Сервер ответил `200` и прислал страницу входа в прокси — обычное дело в
     * корпоративной сети. Отдать её в `+<img>+` под видом `image/svg+xml` значит
     * попросить `WebView` разобрать чужой HTML как SVG.
     */
    @Test
    fun TC_30_foreignBytesAreRefusedAndBlockDegrades() {
        val pipeline = pipeline()

        pipeline.previewShown()
        transport.answerAll("<!DOCTYPE html><html><body>Вход в сеть</body></html>".encodeToByteArray())

        assertFalse("<img" in pipeline.html.orEmpty(), "чужие байты подставлены как картинка: ${pipeline.html}")
        assertTrue("ДИАГРАММА НЕ ЗАГРУЖЕНА" in pipeline.html.orEmpty(), "нет пометки: ${pipeline.html}")
    }

    /**
     * Свои ожидания, а не общие с `PreviewPipelineTest`.
     *
     * Тот держит их приватными, и вытаскивать их в общий помощник ради одного
     * поля значило бы править чужой файл в чужой сейчас зоне.
     */
    private class FakeWaits {
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun wait(dueAt: Long) {
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }

        fun releaseAll() {
            gates.toList().forEach { it.complete(Unit) }
            gates.clear()
        }
    }

    /** Рендерер, отдающий фрагмент с одной диаграммой. */
    private inner class DiagramRenderer : AdocRenderer {
        override suspend fun render(source: String, diagrams: DiagramOptions): String =
            "$source\n$diagram"
    }

    /** Транспорт, чей ответ задаётся тестом; считает отмены. */
    private class FakeTransport : DiagramTransport {
        private val pending = mutableListOf<CompletableDeferred<ByteArray?>>()
        var cancelled = 0
            private set

        override suspend fun fetch(url: String): ByteArray? {
            val answer = CompletableDeferred<ByteArray?>()
            pending += answer
            return try {
                answer.await()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancelled++
                throw cancellation
            }
        }

        fun answerFirst(bytes: ByteArray) {
            pending.removeFirstOrNull()?.complete(bytes)
        }

        fun answerAll(bytes: ByteArray) {
            pending.toList().forEach { it.complete(bytes) }
            pending.clear()
        }

        fun failAll() {
            pending.toList().forEach { it.complete(null) }
            pending.clear()
        }
    }
}
