package io.github.olegnyr.adocmobile.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Разбор конфликтной разметки и сборка результата — слайс `SL-11` фичи
 * 007-git-sync: `TC-22` (выбор стороны собирает соответствующий текст) и
 * `TC-23` (результат пересобирается сразу при смене выбора).
 *
 * Логика живёт в `commonMain` и проверяется без устройства: разметку
 * конфликта пишет Git, но читать её умеет общий код (`NFR-2`, `NFR-10`).
 */
class ConflictHunksTest {

    private val conflicted = listOf(
        "= Документ",
        "",
        "<<<<<<< HEAD",
        ":status: черновик концепции",
        ":toc: macro",
        "=======",
        ":status: на ревью",
        ":sectnums:",
        ">>>>>>> origin/main",
        "",
        "Хвост общий.",
    ).joinToString("\n")

    @Test
    fun TC_22_parseSplitsFileIntoSharedTextAndConflictHunks() {
        val file = parseConflictFile(conflicted)

        assertEquals(1, file.hunks.size, "один конфликтный участок")
        val hunk = file.hunks.single()
        assertEquals(listOf(":status: черновик концепции", ":toc: macro"), hunk.ours)
        assertEquals(listOf(":status: на ревью", ":sectnums:"), hunk.theirs)
        assertEquals(
            listOf("= Документ", "", "", "Хвост общий."),
            file.segments.filterIsInstance<ConflictSegment.Shared>().flatMap { it.lines },
            "общий текст сохраняется дословно, включая пустые строки",
        )
    }

    @Test
    fun TC_22_resolutionsBuildTextFromChosenSide() {
        val file = parseConflictFile(conflicted)

        val ours = file.resolvedText(listOf(ConflictChoice.Ours))
        assertTrue(":status: черновик концепции" in ours && ":toc: macro" in ours)
        assertTrue(":status: на ревью" !in ours, "локальный выбор не тянет удалённые строки")

        val theirs = file.resolvedText(listOf(ConflictChoice.Theirs))
        assertTrue(":status: на ревью" in theirs && ":sectnums:" in theirs)
        assertTrue(":toc: macro" !in theirs)

        val both = file.resolvedText(listOf(ConflictChoice.Both))
        assertEquals(
            listOf("= Документ", "", ":status: черновик концепции", ":toc: macro", ":status: на ревью", ":sectnums:", "", "Хвост общий."),
            both.lines(),
            "«ОБА» — сначала локальная версия, затем удалённая (FR-23)",
        )

        assertTrue(
            listOf("<<<<<<<", "=======", ">>>>>>>").none { it in both },
            "маркеры конфликта не остаются в результате (TC-25)",
        )
    }

    @Test
    fun TC_23_previewRebuildsImmediatelyOnEveryChoiceChange() {
        val file = parseConflictFile(conflicted)

        // Предпросмотр — та же чистая функция: смена выбора сразу даёт
        // другой текст, промежуточного состояния «устаревший предпросмотр» нет.
        val sequence = listOf(ConflictChoice.Ours, ConflictChoice.Theirs, ConflictChoice.Both, ConflictChoice.Ours)
            .map { choice -> file.resolvedText(listOf(choice)) }

        assertEquals(sequence[0], sequence[3], "тот же выбор — тот же результат")
        assertEquals(3, sequence.toSet().size, "три разных выбора дают три разных результата")
    }

    @Test
    fun TC_22_multipleHunksResolveIndependently() {
        val two = listOf(
            "начало",
            "<<<<<<< HEAD",
            "моё раз",
            "=======",
            "их раз",
            ">>>>>>> origin/main",
            "середина",
            "<<<<<<< HEAD",
            "моё два",
            "=======",
            "их два",
            ">>>>>>> origin/main",
            "конец",
        ).joinToString("\n")

        val file = parseConflictFile(two)
        assertEquals(2, file.hunks.size)

        val mixed = file.resolvedText(listOf(ConflictChoice.Theirs, ConflictChoice.Ours))
        assertEquals(
            listOf("начало", "их раз", "середина", "моё два", "конец"),
            mixed.lines(),
            "каждый участок разрешается своим выбором (FR-23)",
        )
    }

    @Test
    fun TC_22_fileWithoutMarkersHasNoHunksAndSurvivesRoundTrip() {
        val plain = "= Документ\n\nБез конфликтов.\n"
        val file = parseConflictFile(plain)

        assertEquals(emptyList(), file.hunks, "нет разметки — нет участков")
        assertEquals(plain, file.resolvedText(emptyList()), "текст не искажается разбором — round-trip дословный")
    }

    @Test
    fun TC_22_emptySidesAndMissingChoicesAreHandled() {
        // Удаление против правки: одна сторона пуста — это нормальный конфликт.
        val emptySide = "<<<<<<< HEAD\n=======\nих строка\n>>>>>>> origin/main"
        val file = parseConflictFile(emptySide)
        assertEquals(emptyList(), file.hunks.single().ours, "пустая сторона — не ошибка разбора")

        assertEquals("", file.resolvedText(listOf(ConflictChoice.Ours)), "выбор пустой стороны даёт пустой участок")

        // Выбор не сделан: участок остаётся неразрешённым, текст без него
        // не собирается — экран не даст завершить слияние (FR-24).
        assertEquals("их строка", file.resolvedText(listOf(ConflictChoice.Theirs)))
    }
}
