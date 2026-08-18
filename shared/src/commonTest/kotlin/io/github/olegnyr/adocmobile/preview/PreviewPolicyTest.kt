package io.github.olegnyr.adocmobile.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 003-render-preview, слайс `SL-4` — живое превью.
 *
 * Политика — чистый автомат по образцу `AutosavePolicy`: часов и корутин внутри
 * нет, время приходит параметром, рендер исполняет вызывающий по действию
 * [PreviewAction.Render]. Так `TC-11`…`TC-14` проверяются без устройства и без
 * реального времени; спека называла инструментом `kotlinx-coroutines-test`, но
 * автомату не нужен и он — время здесь обычный аргумент.
 *
 * Решения владельца, зашитые в оракулы: `OQ-1` — во время рендера видно
 * предыдущий HTML, индикатор только у первого рендера; `OQ-4` — движок работает
 * только при видимом превью, переключение на вкладку рендерит сразу, без
 * дебаунса.
 */
class PreviewPolicyTest {

    private val policy = PreviewPolicy()

    /** Показ с публикацией: превью открыто и уже отрендерено с текстом [source]. */
    private fun shownAndPublished(source: String, html: String, at: Long) {
        val start = policy.previewShown(source, at)
        assertIs<PreviewAction.Render>(start, "показ нового текста обязан начать рендер")
        assertIs<PreviewUpdate.Publish>(policy.renderCompleted(start.generation, html))
    }

    // ---- TC-10: незавершённый рендер прерывается, а не дожидается ----

    @Test
    fun TC_10_newRenderReplacesUnfinishedOne() {
        val first = policy.previewShown("v1", 0)
        assertIs<PreviewAction.Render>(first)

        // Правка пришла, пока первый рендер ещё идёт.
        policy.textEdited(1_000)
        val second = policy.pauseElapsed("v2", 1_300)

        // Новый рендер стартует сразу, не дожидаясь первого (FR-10).
        assertIs<PreviewAction.Render>(second)
        assertEquals("v2", second.source)

        // Достартовавший первый — устаревший, его результат в превью не попадает.
        assertIs<PreviewUpdate.Stale>(policy.renderCompleted(first.generation, "html-v1"))
        assertIs<PreviewUpdate.Publish>(policy.renderCompleted(second.generation, "html-v2"))
        assertEquals("html-v2", policy.html)
    }

    @Test
    fun TC_10_hidingPreviewCancelsUnfinishedRender() {
        val render = policy.previewShown("v1", 0)
        assertIs<PreviewAction.Render>(render)

        // Уход с экрана прекращает рендер, не дожидаясь завершения (FR-10, OQ-4).
        assertIs<PreviewAction.CancelRender>(policy.previewHidden())

        // Если результат всё же дополз (гонка с отменой) — он не публикуется.
        assertIs<PreviewUpdate.Stale>(policy.renderCompleted(render.generation, "html-v1"))
    }

    // ---- TC-11: серия правок даёт ровно один рендер — по последней ----

    @Test
    fun TC_11_tenEditsInHundredMillisYieldExactlyOneRender() {
        shownAndPublished("v0", "html-v0", at = 0)

        // Десять правок за 100 мс: каждая только сдвигает срок, рендера нет.
        val actions = (0 until 10).map { i -> policy.textEdited(1_000 + i * 10L) }
        actions.forEachIndexed { i, action ->
            assertIs<PreviewAction.WaitUntil>(action, "правка №$i обязана лишь сдвинуть срок")
            assertEquals(1_000 + i * 10L + 300, action.dueAt, "срок — пауза от последней правки")
        }

        // Пауза истекла — единственный рендер, и он по последнему тексту.
        val render = policy.pauseElapsed("v10", 1_390)
        assertIs<PreviewAction.Render>(render)
        assertEquals("v10", render.source)
    }

    @Test
    fun TC_11_earlyTimerFireOnlyReschedules() {
        shownAndPublished("v0", "html-v0", at = 0)
        policy.textEdited(1_000)

        // Таймер сработал раньше срока (срок продлён правкой после взвода) —
        // политика возвращает новый срок, а не рендерит раньше времени.
        val action = policy.pauseElapsed("v1", 1_200)
        assertIs<PreviewAction.WaitUntil>(action)
        assertEquals(1_300, action.dueAt)
    }

    @Test
    fun TC_11_inputNeverWaitsForRender() {
        // FR-16: единственные ответы политики на правку — «ждать» и «ничего»;
        // действия, останавливающего ввод, в типе действий просто нет.
        shownAndPublished("v0", "html-v0", at = 0)
        val whileIdle = policy.textEdited(1_000)
        assertIs<PreviewAction.Render>(policy.pauseElapsed("v1", 1_300))
        val whileRendering = policy.textEdited(1_400)
        assertIs<PreviewAction.WaitUntil>(whileIdle)
        assertIs<PreviewAction.WaitUntil>(whileRendering)
    }

    // ---- TC-12: устаревший результат не перетирает свежий ----

    @Test
    fun TC_12_lateResultOfOlderRenderIsDiscarded() {
        shownAndPublished("v1", "html-v1", at = 0)

        policy.textEdited(1_000)
        val second = policy.pauseElapsed("v2", 1_300)
        policy.textEdited(2_000)
        val third = policy.pauseElapsed("v3", 2_300)
        assertIs<PreviewAction.Render>(second)
        assertIs<PreviewAction.Render>(third)

        // Третий закончил раньше второго: порядок гарантируется, а не выходит
        // случайно (FR-15) — опоздавший второй отбрасывается.
        assertIs<PreviewUpdate.Publish>(policy.renderCompleted(third.generation, "html-v3"))
        assertIs<PreviewUpdate.Stale>(policy.renderCompleted(second.generation, "html-v2"))
        assertEquals("html-v3", policy.html, "содержимое превью обязано соответствовать последнему тексту")
    }

    @Test
    fun TC_12_previousHtmlIsHeldWhileRerendering() {
        // OQ-1: во время рендера превью держит предыдущий HTML, без индикатора.
        shownAndPublished("v1", "html-v1", at = 0)
        policy.textEdited(1_000)
        policy.pauseElapsed("v2", 1_300)

        assertEquals("html-v1", policy.html, "прошлый HTML не сбрасывается на время рендера")
        assertEquals(PreviewStatus.Content, policy.status, "индикатор положен только первому рендеру")
    }

    @Test
    fun TC_12_editBackToPublishedTextCancelsInFlightRender() {
        shownAndPublished("v1", "html-v1", at = 0)
        policy.textEdited(1_000)
        val stale = policy.pauseElapsed("v2", 1_300)
        assertIs<PreviewAction.Render>(stale)

        // Пользователь вернул текст к уже показанному: рендерить нечего, а
        // начатый рендер устаревшего текста обязан быть прерван — его результат
        // перетёр бы актуальное содержимое.
        policy.textEdited(1_500)
        assertIs<PreviewAction.CancelRender>(policy.pauseElapsed("v1", 1_800))
        assertIs<PreviewUpdate.Stale>(policy.renderCompleted(stale.generation, "html-v2"))
        assertEquals("html-v1", policy.html)
    }

    // ---- TC-13: дебаунс рендера ни от чего не зависит и ничего не задерживает ----

    @Test
    fun TC_13_inFlightRenderDoesNotShiftTheDebounce() {
        // FR-14 в границах этого пакета: идущий рендер не сдвигает срок паузы —
        // срок считается от правки, и только от неё. Дебаунс подсветки живёт в
        // фиче 001 отдельным механизмом; общего состояния у них нет вовсе, так
        // что задержать друг друга им нечем.
        val slow = policy.previewShown("v1", 0)
        assertIs<PreviewAction.Render>(slow)

        val first = policy.textEdited(1_000)
        val second = policy.textEdited(1_100)
        assertIs<PreviewAction.WaitUntil>(first)
        assertEquals(1_300, first.dueAt)
        assertIs<PreviewAction.WaitUntil>(second)
        assertEquals(1_400, second.dueAt)
    }

    // ---- TC-14: первый рендер и переключение вкладки — без паузы ----

    @Test
    fun TC_14_firstShowRendersImmediately() {
        // FR-17: открывшему превью нечего ждать 300 мс на неизменённом тексте.
        val action = policy.previewShown("текст", 0)
        assertIs<PreviewAction.Render>(action)
        assertEquals("текст", action.source)
    }

    @Test
    fun TC_14_showWithUnchangedTextDoesNotRerender() {
        // OQ-4: содержимое не менялось — повторный показ не стоит ни рендера.
        shownAndPublished("v1", "html-v1", at = 0)
        policy.previewHidden()
        assertIs<PreviewAction.Idle>(policy.previewShown("v1", 5_000))
        assertEquals("html-v1", policy.html)
    }

    @Test
    fun TC_14_showWithChangedTextRendersWithoutDebounce() {
        // OQ-4: смена вкладки — отдельное событие с немедленным рендером;
        // задержку покрывает анимация переключения, дебаунсу тут делать нечего.
        shownAndPublished("v1", "html-v1", at = 0)
        policy.previewHidden()
        policy.textEdited(2_000)
        val action = policy.previewShown("v2", 3_000)
        assertIs<PreviewAction.Render>(action)
        assertEquals("v2", action.source)
    }

    @Test
    fun TC_14_nothingRendersWhileHidden() {
        // OQ-4: движок работает только при видимом превью — ни правка, ни
        // истёкшая пауза при скрытом превью рендера не запускают.
        assertIs<PreviewAction.Idle>(policy.textEdited(1_000))
        assertIs<PreviewAction.Idle>(policy.pauseElapsed("v1", 1_300))
    }

    @Test
    fun TC_14_statusDistinguishesFirstRenderFromContent() {
        // OQ-1: индикатор только пока показывать нечего.
        assertEquals(PreviewStatus.Hidden, policy.status)

        val first = policy.previewShown("v1", 0)
        assertIs<PreviewAction.Render>(first)
        assertEquals(PreviewStatus.FirstRender, policy.status, "первый рендер — единственный случай индикатора")

        policy.renderCompleted(first.generation, "html-v1")
        assertEquals(PreviewStatus.Content, policy.status)

        // Дальше индикатор не возвращается: держится предыдущий HTML.
        policy.textEdited(1_000)
        policy.pauseElapsed("v2", 1_300)
        assertEquals(PreviewStatus.Content, policy.status)
    }

    // ---- Инварианты пауз ----

    @Test
    fun TC_11_pauseElapsedWithoutPendingEditIsIdle() {
        shownAndPublished("v1", "html-v1", at = 0)
        // Таймер выстрелил без взведённого срока (двойное срабатывание).
        assertIs<PreviewAction.Idle>(policy.pauseElapsed("v1", 9_000))
    }

    @Test
    fun TC_11_debounceIsConfigurableAndValidated() {
        val custom = PreviewPolicy(debounceMillis = 500)
        custom.previewShown("v", 0).let { assertIs<PreviewAction.Render>(it) }
        val wait = custom.textEdited(1_000)
        assertIs<PreviewAction.WaitUntil>(wait)
        assertEquals(1_500, wait.dueAt)

        val failed = runCatching { PreviewPolicy(debounceMillis = 0) }
        assertTrue(failed.isFailure, "нулевой дебаунс — ошибка конфигурации, а не «рендер на каждый символ»")
    }

    /**
     * `TC-20` фичи 008: поздние картинки принимаются только на ту страницу, для
     * которой грузились.
     *
     * Второй рубеж после отмены корутины. Отмена снимает подавляющее
     * большинство случаев, но она — свойство вызывающего; политика обязана
     * отказать сама, иначе однажды забытая отмена превратится в «превью иногда
     * показывает старое».
     */
    @Test
    fun TC_20_diagramsOfAnOlderGenerationAreRefused() {
        val policy = PreviewPolicy()
        val first = assertIs<PreviewAction.Render>(policy.previewShown("= Первый", now = 0))
        policy.renderCompleted(first.generation, "<page>первый</page>")

        policy.textEdited(now = 10)
        val second = assertIs<PreviewAction.Render>(policy.pauseElapsed("= Второй", now = 1000))
        policy.renderCompleted(second.generation, "<page>второй</page>")

        val late = policy.diagramsResolved(first.generation, "<page>первый с картинками</page>")

        assertIs<PreviewUpdate.Stale>(late, "картинки прошлого поколения приняты")
        assertEquals("<page>второй</page>", policy.html, "страница подменена прошлым поколением")
    }

    /** Картинки своего поколения публикуются: ради этого вход и заведён. */
    @Test
    fun TC_20_diagramsOfTheShownGenerationArePublished() {
        val policy = PreviewPolicy()
        val render = assertIs<PreviewAction.Render>(policy.previewShown("= Документ", now = 0))
        policy.renderCompleted(render.generation, "<page>с плейсхолдером</page>")

        val update = policy.diagramsResolved(render.generation, "<page>с картинкой</page>")

        assertIs<PreviewUpdate.Publish>(update)
        assertEquals("<page>с картинкой</page>", policy.html)
    }
}
