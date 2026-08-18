package io.github.olegnyr.adocmobile.git

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Модели Git-слоя и контракт подделки — слайс `SL-1` фичи 007-git-sync.
 *
 * `TC-3` здесь закрыт в части моделей — арифметика `CloneProgressTracker`,
 * продуктовый код которой считает процент и скорость для событий прогресса;
 * `TC-4` — в части текстов ошибок (`GitSyncError.userMessage`, `FR-7`).
 * Экранная половина обоих кейсов («события доходят до модели экрана»,
 * «состояние ошибки, повтор возможен») закрывается слайсом `SL-3` на модели
 * экрана клонирования — разнесение отражено в реестре плана.
 *
 * Приём тот же, что в `EditorScreenModelTest`: `Dispatchers.Unconfined`
 * исполняет корутину синхронно, подделка эмитит сценарий без приостановок.
 */
class GitSyncContractTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sync = FakeGitSync()

    private val request = CloneRequest(
        url = "https://github.com/olegnyr/adoc-mobile.git",
        directoryName = "adoc-mobile",
    )

    private val snapshot = RepositorySnapshot(
        directoryName = "adoc-mobile",
        branch = "master",
        remoteUrl = "https://github.com/olegnyr/adoc-mobile.git",
    )

    private fun collectClone(): List<CloneEvent> {
        val events = mutableListOf<CloneEvent>()
        scope.launch { sync.clone(request).collect { events += it } }
        return events
    }

    // ------------------------------------------------------------ модели TC-3

    @Test
    fun TC_3_trackerComputesPercentAndSpeedFromRawMonitorCallbacks() {
        var now = 0L
        val tracker = CloneProgressTracker(clock = { now })

        val begun = tracker.beginPhase("Приём объектов", totalWork = 200)
        assertEquals("Приём объектов", begun.phase)
        assertEquals(0, begun.completedObjects)
        assertEquals(200, begun.totalObjects)
        assertEquals(0, begun.percent)

        now = 500
        val half = tracker.advance(100)
        assertEquals(100, half.completedObjects)
        assertEquals(50, half.percent)
        assertEquals(200, half.objectsPerSecond, "100 объектов за полсекунды — 200 об/с")

        now = 1000
        val full = tracker.advance(100)
        assertEquals(100, full.percent)
        assertEquals(200, full.objectsPerSecond)
    }

    @Test
    fun TC_3_trackerWithoutTotalReportsNoPercentAndNextPhaseStartsFresh() {
        var now = 0L
        val tracker = CloneProgressTracker(clock = { now })

        // JGit передаёт UNKNOWN (0) для фаз без известного объёма.
        val unknown = tracker.beginPhase("Подсчёт объектов", totalWork = 0)
        assertEquals(null, unknown.totalObjects)
        assertEquals(null, unknown.percent, "без объёма процент не выдумывается")

        now = 100
        val moved = tracker.advance(30)
        assertEquals(30, moved.completedObjects)
        assertEquals(null, moved.percent)

        // Новая фаза начинается с нуля — контракт CloneProgress.percent:
        // процент монотонен внутри фазы, между фазами счёт заново.
        val next = tracker.beginPhase("Приём объектов", totalWork = 10)
        assertEquals(0, next.completedObjects)
        assertEquals(10, next.totalObjects)
        assertEquals(0, next.percent)
    }

    @Test
    fun TC_3_trackerSpeedIsNullUntilTimePasses() {
        val tracker = CloneProgressTracker(clock = { 0L })
        tracker.beginPhase("Приём объектов", totalWork = 10)
        val progress = tracker.advance(5)
        assertEquals(null, progress.objectsPerSecond, "при нулевом времени скорость не делит на ноль")
    }

    @Test
    fun TC_3_trackerClampsOverachievedPhaseAtHundredPercent() {
        val tracker = CloneProgressTracker(clock = { 0L })
        tracker.beginPhase("Приём объектов", totalWork = 10)
        val over = tracker.advance(15)
        assertEquals(100, over.percent, "монитор перешагнул заявленный объём — процент зажат контрактом 0–100")
    }

    @Test
    fun TC_3_trackerTreatsNegativeTotalAsUnknown() {
        val tracker = CloneProgressTracker(clock = { 0L })
        val begun = tracker.beginPhase("Странная фаза", totalWork = -5)
        assertEquals(null, begun.totalObjects, "отрицательный объём — это «объём неизвестен», не мусор в UI")
        assertEquals(null, begun.percent)
    }

    // ------------------------------------------------------------ модели TC-4

    @Test
    fun TC_4_everyErrorHasDistinctRussianMessage() {
        val messages = GitSyncError.entries.map { it.userMessage("https://example.org/repo.git") }
        assertEquals(
            GitSyncError.entries.size,
            messages.toSet().size,
            "у каждого класса ошибки свой текст, тексты не слиплись (FR-7)",
        )
        val russianWord = Regex("[а-яёА-ЯЁ]{3,}")
        assertTrue(
            messages.all { russianWord.containsMatchIn(it) },
            "каждое сообщение написано по-русски — язык интерфейса продукта",
        )
        // InvalidDirectory — про имя папки, а не про адрес: URL там не нужен.
        val aboutUrl = GitSyncError.entries.filterNot { it == GitSyncError.InvalidDirectory }
        assertTrue(
            aboutUrl.all { "https://example.org/repo.git" in it.userMessage("https://example.org/repo.git") },
            "сообщение называет репозиторий, о котором речь",
        )
    }

    @Test
    fun TC_4_messagesStripCredentialsEmbeddedInUrl() {
        val leaky = "https://user:secret-token@example.org/repo.git"
        GitSyncError.entries.forEach { error ->
            val message = error.userMessage(leaky)
            assertTrue("secret-token" !in message, "токен из URL не попадает в сообщение (NFR-8): $message")
        }
        // Адрес остаётся узнаваемым там, где сообщение вообще о нём:
        // InvalidDirectory говорит об имени папки и URL не подставляет.
        GitSyncError.entries.filterNot { it == GitSyncError.InvalidDirectory }.forEach { error ->
            assertTrue(
                "https://example.org/repo.git" in error.userMessage(leaky),
                "адрес остаётся узнаваемым: ${error.userMessage(leaky)}",
            )
        }
    }

    @Test
    fun TC_4_sanitizerLeavesCleanUrlsUntouched() {
        assertEquals(
            "https://example.org/repo.git",
            sanitizedGitUrl("https://example.org/repo.git"),
            "адрес без кредов не искажается",
        )
        assertEquals(
            "ssh://host/repo.git",
            sanitizedGitUrl("ssh://git@host/repo.git"),
            "ssh-пользователь — тоже учётные данные в адресе",
        )
    }

    // ------------------------------------------------------- контракт FR-4

    @Test
    fun TC_2_shallowIsTheDefaultOfCloneRequest() {
        // FR-4: `depth 1` — главная митигация памяти; умолчание — решение
        // спеки, и случайная правка конструктора обязана уронить тест.
        assertTrue(CloneRequest(url = "https://example.org/r.git", directoryName = "r").shallow)
    }

    // --------------------------------------------- инфраструктура подделки

    /**
     * Дымовой тест инфраструктуры `commonTest` — без идентификатора `TC-*`
     * намеренно: проверяется не продукт, а подделка, на которую обопрутся
     * тесты модели экрана `SL-3`. Подделка обязана доставлять события в
     * порядке сценария и отвергать сценарии, нарушающие контракт
     * `GitSync.clone` («сколько угодно Progress, затем ровно один терминал»),
     * — иначе тест экрана можно молча построить на невозможном потоке.
     */
    @Test
    fun fakeDeliversScriptInOrderAndRejectsMalformedScripts() {
        val script = listOf(
            CloneEvent.Progress(CloneProgress("Приём объектов", 10, 40, 25, null)),
            CloneEvent.Progress(CloneProgress("Приём объектов", 40, 40, 100, 20)),
            CloneEvent.Finished(snapshot),
        )
        sync.scripts += script
        assertEquals(script, collectClone(), "события доходят в порядке сценария, без потерь")
        assertEquals(listOf(request), sync.requests)

        sync.scripts += listOf(CloneEvent.Progress(CloneProgress("Приём объектов", 1, 2, 50, null)))
        assertFailsWith<IllegalArgumentException>("сценарий без терминального события отвергается") {
            collectCloneRethrowing()
        }

        // Каждая подписка съедает свой сценарий, включая отвергнутый выше, —
        // третий сценарий добавляется следом.
        sync.scripts += listOf(
            CloneEvent.Finished(snapshot),
            CloneEvent.Progress(CloneProgress("Приём объектов", 1, 2, 50, null)),
        )
        assertFailsWith<IllegalArgumentException>("события после терминального отвергаются") {
            collectCloneRethrowing()
        }
    }

    private fun collectCloneRethrowing(): List<CloneEvent> {
        val events = mutableListOf<CloneEvent>()
        var failure: Throwable? = null
        scope.launch {
            try {
                sync.clone(request).collect { events += it }
            } catch (e: IllegalArgumentException) {
                failure = e
            }
        }
        failure?.let { throw it }
        return events
    }
}
