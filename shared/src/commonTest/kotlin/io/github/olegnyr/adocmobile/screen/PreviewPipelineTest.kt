package io.github.olegnyr.adocmobile.screen

import io.github.olegnyr.adocmobile.preview.PreviewPolicy
import io.github.olegnyr.adocmobile.preview.PreviewStatus
import io.github.olegnyr.adocmobile.render.AdocRenderer
import io.github.olegnyr.adocmobile.render.DiagramOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Слайс `SL-2` фичи 005-editor-screen: исполнитель действий [PreviewPolicy].
 *
 * Сама политика проверена своими тестами в фиче 003 (`PreviewPolicyTest`,
 * `PreviewFailureTest`); здесь проверяется *связка*: что действия политики
 * доезжают до рендерера, отмена — до корутины рендера, а публикация — до
 * наблюдаемого состояния. Приём тот же, что в `AutosaveRunnerTest`: часы —
 * переменная, ожидание — ворота, рендерер — подделка интерфейса (ради этого
 * `AdocRenderer` и сделан интерфейсом).
 *
 * Кейсы `TC-8`/`TC-9` названы по фиче 003 и унаследованы ею на уровень экрана
 * (тот же приём, что `TC-2` этой фичи наследует `TC-3` фичи 004): спека 005
 * своего кейса на отказ рендера не заводит, поведение задано решениями `OQ-2`
 * фичи 003 — пробел назван в отчёте слайса.
 */
class PreviewPipelineTest {

    private var now = 0L
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val renderer = FakeRenderer()
    private val waits = FakeWaits()
    private var source = "= Документ"

    private fun pipeline(): PreviewPipeline = PreviewPipeline(
        renderer = renderer,
        scope = scope,
        clock = { now },
        sourceText = { source },
        page = { fragment -> "<page>$fragment</page>" },
        delayUntil = waits::wait,
    )

    @Test
    fun TC_11_hiddenPreviewSendsNoRenderRequests() {
        val pipeline = pipeline()

        pipeline.textEdited()
        now = PreviewPolicy.DEFAULT_DEBOUNCE_MILLIS + 1
        waits.releaseAll()

        assertEquals(0, renderer.requests.size, "при скрытом превью движок молчит (FR-13, OQ-4 фичи 003)")
        assertEquals(PreviewStatus.Hidden, pipeline.status)
    }

    @Test
    fun TC_11_showingPreviewRendersImmediatelyWithoutDebounce() {
        val pipeline = pipeline()

        pipeline.previewShown()

        assertEquals(listOf("= Документ"), renderer.requests, "показ вкладки рендерит сразу")
        assertEquals(0, waits.requested.size, "без дебаунса (решение OQ-4 фичи 003)")
        assertEquals("<page>HTML(= Документ)</page>", pipeline.html, "фрагмент завёрнут в страницу (FR-14)")
        assertEquals(PreviewStatus.Content, pipeline.status)
    }

    @Test
    fun TC_11_secondShowWithUnchangedTextDoesNotRender() {
        val pipeline = pipeline()
        pipeline.previewShown()

        pipeline.previewHidden()
        pipeline.previewShown()

        assertEquals(1, renderer.requests.size, "неизменённый текст не рендерится заново")
        assertEquals(PreviewStatus.Content, pipeline.status, "последний HTML показывается без мигания")
    }

    @Test
    fun TC_12_editWhileVisibleRendersOncePerPause() {
        val pipeline = pipeline()
        pipeline.previewShown()

        source = "= Документ!"
        pipeline.textEdited()
        now = 100
        source = "= Документ!!"
        pipeline.textEdited()

        assertEquals(1, renderer.requests.size, "правки до паузы не рендерятся (FR-13)")

        now = 100 + PreviewPolicy.DEFAULT_DEBOUNCE_MILLIS
        waits.releaseLast()

        assertEquals(2, renderer.requests.size, "пауза даёт ровно один рендер")
        assertEquals("= Документ!!", renderer.requests.last(), "рендерится снимок на момент паузы")
        assertEquals("<page>HTML(= Документ!!)</page>", pipeline.html)
    }

    @Test
    fun TC_12_editDuringRenderCancelsStaleAndPublishesLatest() {
        val pipeline = pipeline()
        val gate = CompletableDeferred<String>()
        renderer.gate = gate
        pipeline.previewShown()
        assertEquals(PreviewStatus.FirstRender, pipeline.status, "первому рендеру — индикатор (OQ-1 фичи 003)")

        // Правка, пока первый рендер висит: по паузе стартует новый рендер,
        // старый прерывается самим действием Render.
        renderer.gate = null
        source = "= Свежий"
        pipeline.textEdited()
        now = PreviewPolicy.DEFAULT_DEBOUNCE_MILLIS + 1
        waits.releaseLast()

        assertEquals(1, renderer.cancelled, "устаревший рендер прерван, а не брошен доделываться")
        assertEquals("<page>HTML(= Свежий)</page>", pipeline.html, "опубликован только последний")
        assertEquals(PreviewStatus.Content, pipeline.status)

        // Опоздавший результат первого рендера ничего не перетирает.
        gate.complete("HTML(устаревший)")
        assertEquals("<page>HTML(= Свежий)</page>", pipeline.html)
    }

    @Test
    fun TC_11_hidingPreviewCancelsRenderInFlight() {
        val pipeline = pipeline()
        val gate = CompletableDeferred<String>()
        renderer.gate = gate
        pipeline.previewShown()

        pipeline.previewHidden()

        assertEquals(1, renderer.cancelled, "уход с превью прерывает рендер (OQ-4 фичи 003)")
        assertEquals(PreviewStatus.Hidden, pipeline.status)
        gate.complete("HTML(поздний)")
        assertNull(pipeline.html, "поздний результат прерванного рендера не публикуется")
    }

    @Test
    fun TC_8_engineFailureRaisesBannerAndKeepsLastHtml() {
        val pipeline = pipeline()
        pipeline.previewShown()
        val shown = pipeline.html

        source = "= Сломанный"
        renderer.failure = RuntimeException("движок цитирует документ: = Сломанный")
        pipeline.textEdited()
        now = PreviewPolicy.DEFAULT_DEBOUNCE_MILLIS + 1
        waits.releaseLast()

        val failure = assertNotNull(pipeline.failure, "отказ виден плашкой (FR-9 фичи 003)")
        assertEquals(PreviewPolicy.STALE_BANNER_TEXT, failure.userMessage)
        assertTrue(
            !failure.userMessage.contains("Сломанный") && !failure.logRecord.contains("Сломанный"),
            "содержимое документа не попадает в тексты отказа (TC-29 фичи 003)",
        )
        assertEquals(shown, pipeline.html, "последний удачный HTML остаётся на экране")
        assertEquals(PreviewStatus.Content, pipeline.status)
    }

    @Test
    fun TC_9_retryAfterFailureRendersAgainAndClearsBanner() {
        val pipeline = pipeline()
        pipeline.previewShown()
        source = "= Починенный"
        renderer.failure = RuntimeException("отказ")
        pipeline.textEdited()
        now = PreviewPolicy.DEFAULT_DEBOUNCE_MILLIS + 1
        waits.releaseLast()
        assertNotNull(pipeline.failure)

        renderer.failure = null
        pipeline.retryRequested()

        assertEquals("= Починенный", renderer.requests.last(), "повтор рендерит сразу, без дебаунса")
        assertNull(pipeline.failure, "удачный рендер снимает плашку")
        assertEquals("<page>HTML(= Починенный)</page>", pipeline.html)
    }

    /**
     * Рендерер-подделка: запросы копятся, исход задаётся тестом, отмена считается.
     *
     * Переопределяется двухаргументная форма — обязательная с решения `OQ-10`
     * фичи 008. Пайплайн диаграмм пока не передаёт (это `SL-6` фичи 008), но
     * контракт требует именно её.
     */
    private class FakeRenderer : AdocRenderer {
        val requests = mutableListOf<String>()
        var failure: Throwable? = null
        var gate: CompletableDeferred<String>? = null
        var cancelled = 0

        override suspend fun render(source: String, diagrams: DiagramOptions): String {
            requests += source
            failure?.let { throw it }
            gate?.let { gate ->
                try {
                    return gate.await()
                } catch (cancellation: CancellationException) {
                    cancelled++
                    throw cancellation
                }
            }
            return "HTML($source)"
        }
    }

    /** Подставное ожидание паузы: сроки записываются, продолжение — за тестом. */
    private class FakeWaits {
        val requested = mutableListOf<Long>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun wait(dueAt: Long) {
            requested += dueAt
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }

        fun releaseLast() {
            gates.last().complete(Unit)
        }

        fun releaseAll() {
            gates.forEach { it.complete(Unit) }
        }
    }
}
