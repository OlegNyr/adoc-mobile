package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.git.CommitAuthor
import io.github.olegnyr.adocmobile.git.CommitResult
import io.github.olegnyr.adocmobile.git.ConflictChoice
import io.github.olegnyr.adocmobile.git.FakeGitSync
import io.github.olegnyr.adocmobile.git.GitCommitError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Модель экрана слияния — слайс `SL-12` фичи 007-git-sync: `TC-21` (участки и
 * счётчик), `TC-23` (предпросмотр пересобирается), `TC-24` (завершение
 * недоступно с неразрешёнными), `TC-25` (merge-коммит), `TC-26` (отмена),
 * `TC-42` (неконфликтный текст сохраняется), `TC-45` (двойной тап и
 * подтверждение отмены — дозаявлены ревью E3).
 */
class ConflictScreenModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sync = FakeGitSync()
    private val path = "docs/vision.adoc"
    private val author = CommitAuthor("Инженер", "engineer@example.com")

    /** Файл с заголовком, преамбулой и двумя участками — как в жизни. */
    private val conflictedFile = listOf(
        "= Документ",
        "",
        ":toc: macro",
        "",
        "<<<<<<< HEAD",
        ":status: черновик",
        "=======",
        ":status: на ревью",
        ">>>>>>> origin/main",
        "",
        "== Раздел",
        "",
        "<<<<<<< HEAD",
        "моё два",
        "=======",
        "их два",
        ">>>>>>> origin/main",
        "",
        "Хвост.",
    ).joinToString("\n")

    private fun model(): ConflictScreenModel {
        sync.conflictedTexts = mapOf(path to conflictedFile)
        return ConflictScreenModel(sync = sync, path = path, scope = scope).also { it.start() }
    }

    private fun resolveBoth(model: ConflictScreenModel, first: ConflictChoice, second: ConflictChoice) {
        model.choose(first)
        model.nextHunk()
        model.choose(second)
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
    fun TC_23_previewShowsCurrentHunkFragmentNotWholeFile() {
        // Макет «04»: блок РЕЗУЛЬТАТ — фрагмент участка. Целый документ на
        // каждой рекомпозиции пересобирался бы (замечание ревью E3).
        val model = model()
        model.choose(ConflictChoice.Ours)

        assertEquals(":status: черновик", model.preview, "предпросмотр — текущий участок")
        assertTrue("= Документ" !in model.preview, "весь файл в предпросмотр не пересобирается")

        model.nextHunk()
        model.choose(ConflictChoice.Theirs)
        assertEquals("их два", model.preview, "фрагмент следует за текущим участком и выбором")
    }

    @Test
    fun TC_23_previewRebuildsOnEveryChoice() {
        val model = model()

        model.choose(ConflictChoice.Ours)
        assertTrue(":status: черновик" in model.preview)

        model.choose(ConflictChoice.Theirs)
        assertTrue(":status: на ревью" in model.preview, "предпросмотр пересобран сразу (TC-23)")
        assertTrue(":status: черновик" !in model.preview)

        model.choose(ConflictChoice.Both)
        assertTrue(":status: черновик" in model.preview && ":status: на ревью" in model.preview)
        assertTrue(
            listOf("<<<<<<<", "=======", ">>>>>>>").none { marker ->
                model.preview.lines().any { it.startsWith(marker) }
            },
            "маркеров конфликта в предпросмотре нет",
        )
    }

    @Test
    fun TC_24_finishIsRefusedWhileAnyHunkIsUnresolved() {
        val model = model()
        model.choose(ConflictChoice.Ours)

        assertTrue(!model.allResolved, "второй участок не разрешён (FR-24)")
        model.finishRequested(author)

        val phase = assertIs<ConflictScreenPhase.Resolving>(model.phase)
        assertNotNull(phase.failure, "завершение объясняет отказ")
        assertEquals(0, sync.finishCount, "до шва запрос не дошёл")
        assertTrue(sync.resolved.isEmpty(), "файл не переписан")
    }

    @Test
    fun TC_25_finishWritesWholeFileAndCreatesMergeCommit() {
        val model = model()
        resolveBoth(model, ConflictChoice.Theirs, ConflictChoice.Ours)

        model.finishRequested(author)

        assertIs<ConflictScreenPhase.Merged>(model.phase, "слияние завершено merge-коммитом (TC-25)")
        val written = assertNotNull(sync.resolved[path], "разрешённый текст записан")
        assertEquals(
            listOf(
                "= Документ",
                "",
                ":toc: macro",
                "",
                ":status: на ревью",
                "",
                "== Раздел",
                "",
                "моё два",
                "",
                "Хвост.",
            ),
            written.lines(),
            "на диск уходит ВЕСЬ файл: выборы участков плюс неконфликтный текст (TC-42)",
        )
        assertEquals(1, sync.finishCount)
    }

    @Test
    fun TC_25_mergeFailureKeepsScreenWithMessage() {
        val model = model()
        resolveBoth(model, ConflictChoice.Ours, ConflictChoice.Ours)

        sync.finishResult = CommitResult.Failed(GitCommitError.IndexLocked)
        model.finishRequested(author)

        val phase = assertIs<ConflictScreenPhase.Resolving>(model.phase, "отказ оставляет экран открытым")
        assertEquals(GitCommitError.IndexLocked.userMessage(), phase.failure)
    }

    @Test
    fun TC_26_abortAsksForConfirmationAndOnlyThenReturnsRepository() {
        val model = model()
        model.choose(ConflictChoice.Ours)

        model.abortRequested()
        assertIs<ConflictScreenPhase.ConfirmingAbort>(model.phase, "отмена спрашивает подтверждение (ревью E3)")
        assertEquals(0, sync.abortCount, "без подтверждения репозиторий не трогается")

        model.abortDismissed()
        assertIs<ConflictScreenPhase.Resolving>(model.phase, "передумал — вернулись к разрешению")

        model.abortRequested()
        model.abortConfirmed()
        assertIs<ConflictScreenPhase.Aborted>(model.phase, "слияние отменено (TC-26)")
        assertEquals(1, sync.abortCount)
        assertEquals(0, sync.finishCount, "отмена не коммитит")
        assertTrue(sync.resolved.isEmpty(), "отмена не переписывает файл")
    }

    @Test
    fun TC_45_doubleTapOnFinishCreatesOneMerge() {
        val model = model()
        resolveBoth(model, ConflictChoice.Ours, ConflictChoice.Ours)

        val gate = Channel<Unit>()
        sync.beforeResolve = { gate.receive() }

        model.finishRequested(author)
        assertIs<ConflictScreenPhase.Merging>(model.phase, "фаза занята синхронно, до точки приостановки")
        model.finishRequested(author)
        model.abortRequested()

        assertTrue(gate.trySend(Unit).isSuccess)
        assertIs<ConflictScreenPhase.Merged>(model.phase)
        assertEquals(1, sync.finishCount, "двойной тап — одно слияние (TC-45)")
        assertEquals(0, sync.abortCount, "отмена во время операции не проходит")
    }

    @Test
    fun TC_48_finishIsRefusedWhenResolvedTextStillHasMarkers() {
        // Файл с литеральным примером разметки: участок разобран, но в
        // собранном тексте маркеры остаются — на диск он не уходит.
        // Маркеры с отступом: разбор их не распознаёт (Git пишет с начала
        // строки), поэтому они остаются в тексте — их обязан поймать сторож.
        val withLiteralExample = listOf(
            "= Документация",
            "",
            "  <<<<<<< HEAD",
            "  пример",
            "  =======",
            "  их пример",
            "  >>>>>>> origin/main",
            "",
            "<<<<<<< HEAD",
            ":status: черновик",
            "=======",
            ":status: на ревью",
            ">>>>>>> origin/main",
        ).joinToString("\n")
        sync.conflictedTexts = mapOf(path to withLiteralExample)
        val model = ConflictScreenModel(sync = sync, path = path, scope = scope)
        model.start()
        repeat(model.hunks.size) { index ->
            model.choose(ConflictChoice.Both)
            if (index < model.hunks.lastIndex) model.nextHunk()
        }

        model.finishRequested(author)

        val phase = assertIs<ConflictScreenPhase.Resolving>(model.phase, "коммита с маркерами не случается")
        assertNotNull(phase.failure)
        assertTrue(sync.resolved.isEmpty(), "файл не переписан")
        assertEquals(0, sync.finishCount, "merge-коммит не создан")
    }

    @Test
    fun TC_44_malformedMarkupIsRefusedWithMessageInsteadOfGuessing() {
        sync.conflictedTexts = mapOf(path to "<<<<<<< HEAD\nмоё\n=======\nих\n")
        val model = ConflictScreenModel(sync = sync, path = path, scope = scope)
        model.start()

        val phase = assertIs<ConflictScreenPhase.Resolving>(model.phase)
        assertNotNull(phase.failure, "непонятная разметка — честный отказ, а не испорченный файл")
        assertEquals(emptyList(), model.hunks)
    }
}
