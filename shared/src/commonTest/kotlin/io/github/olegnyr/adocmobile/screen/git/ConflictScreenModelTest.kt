package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.git.CommitAuthor
import io.github.olegnyr.adocmobile.git.CommitResult
import io.github.olegnyr.adocmobile.git.ConflictChoice
import io.github.olegnyr.adocmobile.git.ConflictHunk
import io.github.olegnyr.adocmobile.git.FakeGitSync
import io.github.olegnyr.adocmobile.git.GitCommitError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Модель экрана слияния — слайс `SL-12` фичи 007-git-sync: `TC-21` (список
 * участков и счётчик), `TC-23` (предпросмотр пересобирается сразу),
 * `TC-24` (завершение недоступно с неразрешёнными участками), `TC-25`
 * (merge-коммит после разрешения), `TC-26` (отмена слияния).
 */
class ConflictScreenModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sync = FakeGitSync()
    private val path = "docs/vision.adoc"
    private val author = CommitAuthor("Инженер", "engineer@example.com")

    private val twoHunks = listOf(
        ConflictHunk(
            ours = listOf(":status: черновик"),
            theirs = listOf(":status: на ревью"),
            oursLabel = "HEAD",
            theirsLabel = "origin/main",
        ),
        ConflictHunk(
            ours = listOf(":toc: macro"),
            theirs = listOf(":sectnums:"),
            oursLabel = "HEAD",
            theirsLabel = "origin/main",
        ),
    )

    private fun model(): ConflictScreenModel {
        sync.hunks = mapOf(path to twoHunks)
        return ConflictScreenModel(sync = sync, path = path, scope = scope).also { it.start() }
    }

    @Test
    fun TC_21_screenShowsHunksWithCounterAndSideLabels() {
        val model = model()

        assertIs<ConflictScreenPhase.Resolving>(model.phase)
        assertEquals(2, model.hunks.size)
        assertEquals("1/2", model.hunkCounter, "счётчик участков из макета «04» (FR-24)")
        assertEquals("HEAD", model.hunks.first().oursLabel)
        assertEquals("origin/main", model.hunks.first().theirsLabel)

        model.nextHunk()
        assertEquals("2/2", model.hunkCounter)
        model.nextHunk()
        assertEquals("2/2", model.hunkCounter, "на последнем участке «далее» не уводит за границы")
    }

    @Test
    fun TC_23_previewRebuildsOnEveryChoice() {
        val model = model()

        model.choose(ConflictChoice.Ours)
        val afterOurs = model.preview
        assertTrue(":status: черновик" in afterOurs)

        model.choose(ConflictChoice.Theirs)
        val afterTheirs = model.preview
        assertTrue(":status: на ревью" in afterTheirs, "предпросмотр пересобран сразу (TC-23)")
        assertTrue(":status: черновик" !in afterTheirs)

        model.choose(ConflictChoice.Both)
        val afterBoth = model.preview
        assertTrue(":status: черновик" in afterBoth && ":status: на ревью" in afterBoth, "«ОБА» — обе версии")
        assertTrue(
            listOf("<<<<<<<", "=======", ">>>>>>>").none { it in afterBoth },
            "маркеров конфликта в предпросмотре нет",
        )
    }

    @Test
    fun TC_24_finishIsRefusedWhileAnyHunkIsUnresolved() {
        val model = model()
        model.choose(ConflictChoice.Ours) // только первый участок

        assertTrue(!model.allResolved, "второй участок не разрешён (FR-24)")
        model.finishRequested(author)

        val phase = assertIs<ConflictScreenPhase.Resolving>(model.phase)
        assertNotNull(phase.failure, "завершение объясняет отказ, а не молчит")
        assertEquals(0, sync.finishCount, "до шва запрос не дошёл")
        assertTrue(sync.resolved.isEmpty(), "файл не переписан")
    }

    @Test
    fun TC_25_finishWritesResolvedFileAndCreatesMergeCommit() {
        val model = model()
        model.choose(ConflictChoice.Theirs)
        model.nextHunk()
        model.choose(ConflictChoice.Ours)

        assertTrue(model.allResolved)
        model.finishRequested(author)

        assertIs<ConflictScreenPhase.Merged>(model.phase, "слияние завершено merge-коммитом (TC-25)")
        val written = assertNotNull(sync.resolved[path], "разрешённый текст записан")
        assertEquals(listOf(":status: на ревью", ":toc: macro"), written.lines(), "выборы применены поучастково")
        assertTrue(
            listOf("<<<<<<<", "=======", ">>>>>>>").none { it in written },
            "маркеров конфликта в файле не осталось (TC-25)",
        )
        assertEquals(1, sync.finishCount)
    }

    @Test
    fun TC_25_mergeFailureKeepsScreenWithMessage() {
        val model = model()
        model.choose(ConflictChoice.Ours)
        model.nextHunk()
        model.choose(ConflictChoice.Ours)

        sync.finishResult = CommitResult.Failed(GitCommitError.IndexLocked)
        model.finishRequested(author)

        val phase = assertIs<ConflictScreenPhase.Resolving>(model.phase, "отказ оставляет экран открытым")
        assertEquals(GitCommitError.IndexLocked.userMessage(), phase.failure)
    }

    @Test
    fun TC_26_abortReturnsRepositoryToPrePullState() {
        val model = model()
        model.choose(ConflictChoice.Ours)

        model.abortRequested()

        assertIs<ConflictScreenPhase.Aborted>(model.phase, "слияние отменено (TC-26)")
        assertEquals(1, sync.abortCount)
        assertEquals(0, sync.finishCount, "отмена не коммитит")
        assertTrue(sync.resolved.isEmpty(), "отмена не переписывает файл")
    }
}
