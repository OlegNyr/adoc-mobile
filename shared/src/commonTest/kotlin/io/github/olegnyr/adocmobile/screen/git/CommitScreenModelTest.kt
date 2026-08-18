package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.git.CommitAuthor
import io.github.olegnyr.adocmobile.git.CommitResult
import io.github.olegnyr.adocmobile.git.FakeGitSync
import io.github.olegnyr.adocmobile.git.FileStatus
import io.github.olegnyr.adocmobile.git.GitCommitError
import io.github.olegnyr.adocmobile.git.GitPushError
import io.github.olegnyr.adocmobile.git.PushResult
import io.github.olegnyr.adocmobile.git.RepoStatus
import io.github.olegnyr.adocmobile.git.RepositorySnapshot
import io.github.olegnyr.adocmobile.git.StatusEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Модель экрана коммита — слайс `SL-6` фичи 007-git-sync: экранные половины
 * `TC-15` (в коммит уходят только отмеченные), `TC-16` (неотслеживаемый
 * снят), `TC-17` (счётчик первой строки не блокирует ввод) и `FR-22`
 * (авторство запросом при первом коммите — решение OQ-8).
 *
 * Приём тот же, что в `CloneScreenModelTest`: `Dispatchers.Unconfined`,
 * подделка шва, наблюдаемое состояние модели.
 */
class CommitScreenModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sync = FakeGitSync()

    private val author = CommitAuthor(name = "Инженер", email = "engineer@example.com")

    private fun readySync() {
        sync.repository = RepositorySnapshot("platform-docs", "main", "https://example.org/r.git")
        sync.status = RepoStatus(
            ahead = 0,
            behind = 0,
            entries = listOf(
                StatusEntry("docs/vision.adoc", FileStatus.Modified, 41, 6),
                StatusEntry("docs/api.adoc", FileStatus.Added, 128, 0),
                StatusEntry("diagrams/flow.puml", FileStatus.Untracked),
            ),
        )
        sync.author = author
    }

    private fun model(): CommitScreenModel =
        CommitScreenModel(sync = sync, scope = scope).also { it.start() }

    @Test
    fun TC_16_untrackedFilesArriveUncheckedAndTrackedChecked() {
        readySync()
        val model = model()

        assertEquals(
            setOf("docs/vision.adoc", "docs/api.adoc"),
            model.checkedPaths,
            "M и A отмечены, неотслеживаемый снят по умолчанию (FR-18, TC-16)",
        )
        assertEquals("ИНДЕКС · 2 ИЗ 3", model.indexLabel, "подпись индекса как в макете «03»")
    }

    @Test
    fun TC_15_commitReceivesOnlyCheckedPaths() {
        readySync()
        val model = model()
        model.toggle("docs/api.adoc")
        model.message = "docs: раздел «Проблема»"

        model.commitRequested()

        assertIs<CommitScreenPhase.Done>(model.phase)
        val call = sync.commits.single()
        assertEquals(listOf("docs/vision.adoc"), call.paths, "снятый файл в коммит не попал (TC-15)")
        assertEquals("docs: раздел «Проблема»", call.message)
        assertEquals(author, call.author)
    }

    @Test
    fun TC_17_counterCountsFirstLineAndNeverBlocksInput() {
        readySync()
        val model = model()

        model.message = "docs: раздел «Проблема» и черновик\napi-guide — детали вторым абзацем"
        assertEquals(
            "34 / 72 симв.",
            messageCounterLabel(model.message),
            "счётчик считает первую строку сообщения (FR-19)",
        )

        val long = "x".repeat(90)
        model.message = long
        assertEquals(long, model.message, "ввод сверх 72 не блокируется (FR-19)")
        assertEquals("90 / 72 симв.", messageCounterLabel(model.message))
        assertEquals("0 / 72 симв.", messageCounterLabel(""))
    }

    @Test
    fun TC_39_firstCommitAsksForAuthorAndRemembersIt() {
        readySync()
        sync.author = null
        val model = model()
        model.message = "первый коммит"

        model.commitRequested()
        assertIs<CommitScreenPhase.AwaitingAuthor>(model.phase, "авторства нет — диалог до коммита (FR-22, OQ-8)")
        assertTrue(sync.commits.isEmpty(), "коммит не создаётся, пока авторство не подтверждено")

        model.authorName = "  Инженер  "
        model.authorEmail = " engineer@example.com "
        model.authorConfirmed()

        assertIs<CommitScreenPhase.Done>(model.phase)
        assertEquals(author, sync.commits.single().author, "введённое авторство обрезано и ушло в коммит")
        assertEquals(author, sync.author, "авторство запомнено — второй коммит диалога не увидит")
    }

    @Test
    fun TC_40_emptySelectionAndEmptyMessageFailFastWithoutSeam() {
        readySync()
        val model = model()

        model.message = "сообщение"
        model.toggle("docs/vision.adoc")
        model.toggle("docs/api.adoc")
        model.commitRequested()
        val noFiles = assertIs<CommitScreenPhase.Editing>(model.phase)
        assertNotNull(noFiles.failure, "пустой индекс — понятный отказ, а не пустой коммит")

        model.toggle("docs/vision.adoc")
        model.message = "   "
        model.commitRequested()
        val noMessage = assertIs<CommitScreenPhase.Editing>(model.phase)
        assertNotNull(noMessage.failure, "пустое сообщение — понятный отказ")

        assertTrue(sync.commits.isEmpty(), "до шва ни один невалидный запрос не дошёл")
    }

    @Test
    fun TC_18_commitAndPushRunAsOneActionWhenToggleIsOn() {
        readySync()
        val model = model()
        model.message = "commit & push"

        model.commitRequested()

        val done = assertIs<CommitScreenPhase.Done>(model.phase)
        assertEquals(null, done.pushFailure, "push прошёл — отказа нет")
        assertEquals(1, sync.commits.size)
        assertEquals(1, sync.pushCount, "включённый переключатель объединяет commit и push (FR-20, TC-18)")
    }

    @Test
    fun TC_18_pushIsSkippedWhenToggleIsOff() {
        readySync()
        val model = model()
        model.message = "только commit"
        model.pushAfterCommit = false

        model.commitRequested()

        assertIs<CommitScreenPhase.Done>(model.phase)
        assertEquals(0, sync.pushCount, "выключенный переключатель — push не зовётся")
    }

    @Test
    fun TC_19_pushFailureDoesNotUndoCommitAndIsReportedSeparately() {
        readySync()
        val model = model()
        model.message = "с отказом push"
        sync.pushResult = PushResult.Failed(GitPushError.NonFastForward)

        model.commitRequested()

        val done = assertIs<CommitScreenPhase.Done>(model.phase, "коммит состоялся, фаза — Done, не отказ (FR-21)")
        assertEquals(GitPushError.NonFastForward.userMessage(), done.pushFailure, "отказ push — отдельным сообщением")
        assertEquals(1, sync.commits.size, "локальный коммит не откатан (FR-21)")
        assertTrue("коммит сохранён локально" in done.pushFailure.orEmpty(), "текст успокаивает: работа не потеряна")
    }

    @Test
    fun TC_40_doubleTapInAuthorLookupWindowCreatesOneCommit() {
        readySync()
        val model = model()
        model.message = "один коммит"

        // Ворота держат IO-хоп sync.author(): раньше второй тап в это окно
        // проходил guard и рождал второй commit+push (находка ревью E2).
        val gate = kotlinx.coroutines.channels.Channel<Unit>()
        sync.beforeAuthor = { gate.receive() }

        model.commitRequested()
        assertIs<CommitScreenPhase.Committing>(model.phase, "фаза занята синхронно, до точки приостановки")

        model.commitRequested()
        model.toggle("docs/vision.adoc") // правка отметок в окно не меняет запрошенный коммит

        assertTrue(gate.trySend(kotlin.Unit).isSuccess)
        assertIs<CommitScreenPhase.Done>(model.phase)
        assertEquals(1, sync.commits.size, "двойной тап — один коммит (TC-40)")
        assertEquals(1, sync.pushCount, "и один push")
        assertEquals(
            setOf("docs/vision.adoc", "docs/api.adoc"),
            sync.commits.single().paths.toSet(),
            "коммит взял снимок отметок на момент запроса, не позднейшую правку",
        )
    }

    @Test
    fun TC_40_statusReadFailureShowsMessageInsteadOfCrashing() {
        val broken = object : io.github.olegnyr.adocmobile.git.GitSync by sync {
            override suspend fun status(): RepoStatus =
                throw RuntimeException("диск сломан")
        }
        val model = CommitScreenModel(sync = broken, scope = scope)
        model.start()

        val editing = assertIs<CommitScreenPhase.Editing>(model.phase)
        assertNotNull(editing.failure, "отказ чтения индекса — текст на форме, не падение процесса")
    }

    @Test
    fun TC_40_failedCommitReturnsFormWithMessageAndRetryWorks() {
        readySync()
        val model = model()
        model.message = "сообщение"

        sync.commitResult = CommitResult.Failed(GitCommitError.IndexLocked)
        model.commitRequested()
        val failed = assertIs<CommitScreenPhase.Editing>(model.phase)
        assertEquals(GitCommitError.IndexLocked.userMessage(), failed.failure)

        sync.commitResult = CommitResult.Committed
        model.commitRequested()
        assertIs<CommitScreenPhase.Done>(model.phase, "после отказа повтор возможен")
        assertEquals(2, sync.commits.size)
    }
}
