package io.github.olegnyr.adocmobile.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 003-render-preview, слайс `SL-5` — устойчивость.
 *
 * Здесь проверяемая без движка половина `TC-8` и `TC-9`: как отказ рендера
 * отражается в состоянии превью (`FR-9`, `OQ-2`). Вторая половина — что движок
 * действительно не роняет процесс и что таймаут прерывает конвертацию — живёт
 * в платформенном коде и требует прогона движка; см. журнал слайса.
 *
 * Решение `OQ-2`, зашитое в оракулы: последний удачный HTML остаётся, сверху
 * плашка «превью устарело» с повтором; текст человеческий, техническая причина
 * по касанию; содержимое документа в тексты не попадает никогда (`TC-29`).
 */
class PreviewFailureTest {

    private val policy = PreviewPolicy()

    private fun shownAndPublished(source: String, html: String, at: Long) {
        val start = policy.previewShown(source, at)
        assertIs<PreviewAction.Render>(start)
        assertIs<PreviewUpdate.Publish>(policy.renderCompleted(start.generation, html))
    }

    private fun failedRender(source: String, cause: Throwable, editAt: Long): PreviewAction.Render {
        policy.textEdited(editAt)
        val render = policy.pauseElapsed(source, editAt + 300)
        assertIs<PreviewAction.Render>(render)
        assertIs<PreviewUpdate.Failed>(policy.renderFailed(render.generation, cause))
        return render
    }

    // ---- TC-8: отказ не теряет превью и не мешает следующему рендеру ----

    @Test
    fun TC_8_failureKeepsLastGoodHtmlAndRaisesStaleBanner() {
        shownAndPublished("v1", "html-v1", at = 0)
        failedRender("v2", RuntimeException("боль"), editAt = 1_000)

        // OQ-2: последний удачный HTML остаётся, а не сменяется сообщением.
        assertEquals("html-v1", policy.html)
        assertEquals(PreviewStatus.Content, policy.status)
        assertNotNull(policy.failure, "плашка «превью устарело» обязана появиться")
    }

    @Test
    fun TC_8_nextRenderAfterFailureProceedsAndClearsBanner() {
        shownAndPublished("v1", "html-v1", at = 0)
        failedRender("v2", RuntimeException("боль"), editAt = 1_000)

        // FR-9: следующая правка запускает рендер как ни в чём не бывало.
        policy.textEdited(2_000)
        val retry = policy.pauseElapsed("v3", 2_300)
        assertIs<PreviewAction.Render>(retry)

        assertIs<PreviewUpdate.Publish>(policy.renderCompleted(retry.generation, "html-v3"))
        assertEquals("html-v3", policy.html)
        assertNull(policy.failure, "удачный рендер обязан снимать плашку")
    }

    @Test
    fun TC_8_retryButtonRendersImmediately() {
        shownAndPublished("v1", "html-v1", at = 0)
        failedRender("v2", RuntimeException("боль"), editAt = 1_000)

        // Кнопка повтора — явное действие: рендер сразу, без дебаунса.
        val retry = policy.retryRequested("v2", 5_000)
        assertIs<PreviewAction.Render>(retry)
        assertEquals("v2", retry.source)

        assertIs<PreviewUpdate.Publish>(policy.renderCompleted(retry.generation, "html-v2"))
        assertNull(policy.failure)
        assertEquals("html-v2", policy.html)
    }

    @Test
    fun TC_8_retryWhileHiddenIsIdle() {
        shownAndPublished("v1", "html-v1", at = 0)
        failedRender("v2", RuntimeException("боль"), editAt = 1_000)
        policy.previewHidden()

        // OQ-4 сильнее кнопки: при скрытом превью движок молчит.
        assertIs<PreviewAction.Idle>(policy.retryRequested("v2", 5_000))
    }

    @Test
    fun TC_8_failureOfOutdatedGenerationIsIgnored() {
        shownAndPublished("v1", "html-v1", at = 0)

        policy.textEdited(1_000)
        val old = policy.pauseElapsed("v2", 1_300)
        policy.textEdited(2_000)
        val fresh = policy.pauseElapsed("v3", 2_300)
        assertIs<PreviewAction.Render>(old)
        assertIs<PreviewAction.Render>(fresh)

        // Отказ прерванного рендера — не отказ превью: актуальный ещё идёт.
        assertIs<PreviewUpdate.Stale>(policy.renderFailed(old.generation, RuntimeException("боль")))
        assertNull(policy.failure, "плашку поднимает только отказ актуального рендера")

        assertIs<PreviewUpdate.Publish>(policy.renderCompleted(fresh.generation, "html-v3"))
    }

    // ---- TC-9: таймаут — тот же отказ, а не зависание ----

    @Test
    fun TC_9_timeoutIsAnOrdinaryFailureWithItsTypeInDetail() {
        // Половина кейса уровня политики: таймаут приходит отказом с типом
        // причины в технической детали. Что конвертация при этом действительно
        // прервана — обязанность движка (evaluationTimeoutMillis), проверяемая
        // только его прогоном.
        class RenderTimeoutException : Exception("timed out")

        shownAndPublished("v1", "html-v1", at = 0)
        failedRender("v2", RenderTimeoutException(), editAt = 1_000)

        val failure = assertNotNull(policy.failure)
        assertTrue(
            failure.technicalDetail.contains("RenderTimeoutException"),
            "техническая причина обязана называть тип отказа: ${failure.technicalDetail}",
        )
        assertEquals("html-v1", policy.html, "таймаут не отбирает последний удачный HTML")
    }

    @Test
    fun TC_9_firstRenderFailureLeavesRetryNotSpinner() {
        // Первый рендер не удался: показывать нечего, но и крутить индикатор
        // вечно нельзя — состояние различимо: html нет, отказ есть.
        val first = policy.previewShown("v1", 0)
        assertIs<PreviewAction.Render>(first)
        assertIs<PreviewUpdate.Failed>(policy.renderFailed(first.generation, RuntimeException("боль")))

        assertNull(policy.html)
        assertNotNull(policy.failure)
        assertEquals(PreviewStatus.FirstRender, policy.status)
    }

    // ---- TC-29: содержимое документа не попадает в диагностику ----

    @Test
    fun TC_29_documentContentNeverAppearsInFailureTexts() {
        val secret = "СЕКРЕТНОЕ_СОДЕРЖИМОЕ_ДОКУМЕНТА"
        val source = "= Заголовок\n\n$secret\n"
        shownAndPublished("v0", "html-v0", at = 0)

        // Худший случай: движок процитировал документ в сообщении исключения.
        val chatty = RuntimeException("SyntaxError near: $secret")
        failedRender(source, chatty, editAt = 1_000)

        val failure = assertNotNull(policy.failure)
        assertTrue(secret !in failure.userMessage, "содержимое утекло в текст пользователю")
        assertTrue(secret !in failure.technicalDetail, "содержимое утекло в техническую деталь")
        assertTrue(secret !in failure.logRecord, "содержимое утекло в диагностическую запись")
    }

    @Test
    fun TC_29_logRecordCarriesKindAndSizeOnly() {
        shownAndPublished("v0", "html-v0", at = 0)
        val source = "= Документ\n\nсодержимое\n"
        failedRender(source, IllegalStateException("движку плохо"), editAt = 1_000)

        val failure = assertNotNull(policy.failure)
        // Признак отказа — тип причины; размер — длина исходника. Ничего сверх.
        assertTrue(
            "IllegalStateException" in failure.logRecord,
            "в записи нет признака отказа: ${failure.logRecord}",
        )
        assertTrue(
            "${source.length}" in failure.logRecord,
            "в записи нет размера документа: ${failure.logRecord}",
        )
        assertTrue("движку плохо" !in failure.logRecord, "сообщение исключения не для записи: оно может цитировать документ")
    }

    @Test
    fun TC_29_userMessageIsHumanAndFixed() {
        shownAndPublished("v0", "html-v0", at = 0)
        failedRender("v1", RuntimeException("internal: 0xDEADBEEF"), editAt = 1_000)

        val failure = assertNotNull(policy.failure)
        // Человеческий текст без внутренностей; техническое — отдельным полем.
        assertTrue("0xDEADBEEF" !in failure.userMessage)
        assertTrue(failure.userMessage.isNotBlank())
        assertTrue("устарело" in failure.userMessage.lowercase(), "плашка по OQ-2 говорит именно об устаревании")
    }
}
