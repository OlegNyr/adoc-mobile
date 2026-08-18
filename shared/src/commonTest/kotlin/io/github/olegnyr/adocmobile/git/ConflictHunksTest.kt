package io.github.olegnyr.adocmobile.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Разбор конфликтной разметки и сборка результата — слайс `SL-11` фичи
 * 007-git-sync: `TC-22` (выбор стороны), `TC-23` (предпросмотр),
 * `TC-42` (неконфликтный текст цел), `TC-43` (переводы строк и пустая
 * сторона), `TC-44` (разметка, которую разбирать нельзя).
 *
 * Логика живёт в `commonMain` и проверяется без устройства.
 */
class ConflictHunksTest {

    private val conflicted = listOf(
        "= Документ",
        "",
        ":toc: macro",
        "",
        "<<<<<<< HEAD",
        ":status: черновик концепции",
        "=======",
        ":status: на ревью",
        ">>>>>>> origin/main",
        "",
        "Хвост общий.",
    ).joinToString("\n")

    private fun parsed(text: String): ConflictFile =
        assertIs<ConflictParseResult.Parsed>(parseConflictFile(text)).file

    @Test
    fun TC_42_resolvedTextKeepsEverythingOutsideHunks() {
        val file = parsed(conflicted)

        assertEquals(
            listOf(
                "= Документ",
                "",
                ":toc: macro",
                "",
                ":status: черновик концепции",
                "",
                "Хвост общий.",
            ),
            file.resolvedText(listOf(ConflictChoice.Ours)).lines(),
            "заголовок, преамбула и текст после участка остаются в файле (FR-23, NFR-6)",
        )
    }

    @Test
    fun TC_22_choicesPickSidesAndBothKeepsOrder() {
        val file = parsed(conflicted)

        assertTrue(":status: на ревью" in file.resolvedText(listOf(ConflictChoice.Theirs)))

        val both = file.resolvedText(listOf(ConflictChoice.Both)).lines()
        assertEquals(
            listOf(":status: черновик концепции", ":status: на ревью"),
            both.filter { it.startsWith(":status:") },
            "«ОБА» — сначала локальная версия, затем удалённая (FR-23)",
        )
        assertTrue(
            listOf("<<<<<<<", "=======", ">>>>>>>").none { marker -> both.any { it.startsWith(marker) } },
            "маркеров конфликта в результате нет (TC-25)",
        )
    }

    @Test
    fun TC_23_previewRebuildsImmediatelyOnEveryChoiceChange() {
        val file = parsed(conflicted)
        val variants = listOf(ConflictChoice.Ours, ConflictChoice.Theirs, ConflictChoice.Both)
            .map { file.resolvedText(listOf(it)) }

        assertEquals(3, variants.toSet().size, "каждый выбор даёт свой результат")
        assertEquals(variants[0], file.resolvedText(listOf(ConflictChoice.Ours)), "тот же выбор — тот же текст")
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

        val file = parsed(two)

        assertEquals(2, file.hunks.size)
        assertEquals(
            listOf("начало", "их раз", "середина", "моё два", "конец"),
            file.resolvedText(listOf(ConflictChoice.Theirs, ConflictChoice.Ours)).lines(),
            "каждый участок разрешается своим выбором, общий текст между ними цел",
        )
    }

    @Test
    fun TC_43_lineEndingsAndTrailingNewlineSurviveRoundTrip() {
        val crlf = "= Документ\r\n\r\n<<<<<<< HEAD\r\nмоё\r\n=======\r\nих\r\n>>>>>>> origin/main\r\n"

        assertEquals(
            "= Документ\r\n\r\nмоё\r\n",
            parsed(crlf).resolvedText(listOf(ConflictChoice.Ours)),
            "CRLF и хвостовой перевод строки сохранены (NFR-6)",
        )

        // Хвост файла без перевода строки: терминатора нет — и в результате
        // он не появляется (дописать значило бы править чужой файл).
        val noTrailing = "<<<<<<< HEAD\nмоё\n=======\nих\n>>>>>>> origin/main\nхвост"
        assertEquals(
            "моё\nхвост",
            parsed(noTrailing).resolvedText(listOf(ConflictChoice.Ours)),
            "перевод строки не дописывается к последней строке",
        )
    }

    @Test
    fun TC_43_mixedLineEndingsAreNotRewrittenAcrossTheFile() {
        // Живой случай: файл CRLF, а маркеры Git написал через LF. Прежняя
        // нормализация переводила ВЕСЬ файл, включая нетронутые строки.
        val mixed = "= Документ\r\nпреамбула\r\n<<<<<<< HEAD\nмоё\n=======\nих\n>>>>>>> origin/main\nхвост\r\n"

        assertEquals(
            "= Документ\r\nпреамбула\r\nмоё\nхвост\r\n",
            parsed(mixed).resolvedText(listOf(ConflictChoice.Ours)),
            "каждая строка сохраняет свой терминатор — чужие строки не переписаны (NFR-6)",
        )
    }

    @Test
    fun TC_43_emptySideIsPreservedForDeleteVersusEditConflict() {
        // Штатный случай «удаление против правки»: одна сторона пуста.
        val emptySide = "начало\n<<<<<<< HEAD\n=======\nих строка\n>>>>>>> origin/main\nконец\n"
        val file = parsed(emptySide)

        assertEquals(emptyList(), file.hunks.single().oursText(), "пустая сторона — не ошибка разбора")
        assertEquals(
            "начало\nконец\n",
            file.resolvedText(listOf(ConflictChoice.Ours)),
            "выбор пустой стороны удаляет участок, общий текст цел",
        )
        assertEquals("начало\nих строка\nконец\n", file.resolvedText(listOf(ConflictChoice.Theirs)))
    }

    @Test
    fun TC_43_fileWithoutMarkersSurvivesRoundTripExactly() {
        val plain = "= Документ\n\nБез конфликтов.\n"
        val file = parsed(plain)

        assertEquals(emptyList(), file.hunks, "нет разметки — нет участков")
        assertEquals(plain, file.resolvedText(emptyList()), "round-trip дословный")
    }

    @Test
    fun TC_44_diff3MarkupIsParsedWithoutLeakingBaseIntoOurs() {
        val diff3 = listOf(
            "<<<<<<< HEAD",
            "моё",
            "||||||| base",
            "общий предок",
            "=======",
            "их",
            ">>>>>>> origin/main",
        ).joinToString("\n")

        val hunk = parsed(diff3).hunks.single()

        assertEquals(listOf("моё"), hunk.oursText(), "база не утекает в локальную сторону (diff3)")
        assertEquals(listOf("их"), hunk.theirsText())
        assertEquals(listOf("общий предок"), hunk.base.map { it.text }, "база разобрана отдельно")
        assertTrue(
            "|||||||" !in parsed(diff3).resolvedText(listOf(ConflictChoice.Ours)),
            "маркер базы не попадает в результат",
        )
    }

    @Test
    fun TC_48_markersInsideListingAreParsedAndNeverLeakIntoResult() {
        // Регрессия третьего раунда: частный случай «не разбирать внутри
        // листинга» прятал НАСТОЯЩИЕ маркеры, и файл с примером в листинге
        // плюс конфликтом ниже собирался вместе с разметкой.
        val document = listOf(
            "= Как разрешать конфликты",
            "",
            "----",
            "<<<<<<< HEAD",
            "пример моей версии",
            "=======",
            "пример их версии",
            ">>>>>>> origin/main",
            "----",
            "",
            "<<<<<<< HEAD",
            ":status: черновик",
            "=======",
            ":status: на ревью",
            ">>>>>>> origin/main",
        ).joinToString("\n")

        val file = parsed(document)

        assertEquals(2, file.hunks.size, "маркеры разбираются везде — литеральные неотличимы от настоящих")
        val resolved = file.resolvedText(listOf(ConflictChoice.Ours, ConflictChoice.Ours))
        assertTrue(
            !containsConflictMarkers(resolved),
            "в собранном тексте маркеров не остаётся: $resolved",
        )
    }

    @Test
    fun TC_48_guardCatchesMarkersLeftInResult() {
        assertTrue(containsConflictMarkers("текст\n<<<<<<< HEAD\nещё\n"), "сторож видит открывающий маркер")
        assertTrue(containsConflictMarkers(">>>>>>> origin/main\n"), "и закрывающий")
        assertTrue(
            containsConflictMarkers("  <<<<<<< HEAD\n"),
            "маркер с отступом разбор не видит, а сторож обязан поймать",
        )
        assertTrue(containsConflictMarkers("\t>>>>>>> ветка\n"), "отступ табом — тоже маркер")
        assertTrue(containsConflictMarkers("<<<<<<<<<<< HEAD\n"), "ряд длиннее семи")
        assertTrue(!containsConflictMarkers("= Документ\n\nОбычный текст.\n"), "чистый текст сторож пропускает")
    }

    @Test
    fun TC_48_guardDoesNotRejectLegalAsciidoc() {
        // Блокер независимой проверки: сторож считал маркером любой ряд «=»,
        // и на легальном AsciiDoc пользователь навсегда получал отказ, хотя
        // настоящих маркеров в тексте нет. Проверяются все три вектора.
        val nestedExample = "[NOTE]\n====\nВнешний блок.\n\n=======\nВложенный блок.\n=======\n====\n"
        assertTrue(!containsConflictMarkers(nestedExample), "вложенный example-блок — легальный AsciiDoc")

        val legacyHeading = "Раздел\n=========\n\nТекст.\n"
        assertTrue(!containsConflictMarkers(legacyHeading), "легаси-подчёркивание заголовка — не маркер")

        val insideListing = "----\n=========\n----\n"
        assertTrue(!containsConflictMarkers(insideListing), "девять «=» внутри листинга — не маркер")

        // Разделитель без пары не остаётся: Git пишет маркеры тройкой, и
        // отсутствие «<<<<<<<» и «>>>>>>>» означает, что конфликта нет.
        assertTrue(!containsConflictMarkers("=======\n"), "одинокий разделитель сторожем не считается")
        assertTrue(!containsConflictMarkers("||||||| base\n"), "маркер базы — тоже не сторожевой")
    }

    @Test
    fun TC_48_unbalancedAsciidocDelimiterDoesNotHideHunks() {
        // Легаси-подчёркивание заголовка открывало «блок», который никогда не
        // закрывался, и участки всего файла исчезали с экрана.
        val legacy = listOf(
            "Раздел",
            "------",
            "",
            "<<<<<<< HEAD",
            "моё",
            "=======",
            "их",
            ">>>>>>> origin/main",
        ).joinToString("\n")

        assertEquals(1, parsed(legacy).hunks.size, "участок виден, несмотря на несбалансированный делимитер")
    }

    @Test
    fun TC_44_asciidocExampleDelimiterOutsideHunkStaysText() {
        val document = listOf(
            "= Документ",
            "",
            "[NOTE]",
            "=======",
            "Текст примечания.",
            "=======",
            "",
            "Хвост.",
        ).joinToString("\n")

        val file = parsed(document)

        assertEquals(emptyList(), file.hunks, "делимитер AsciiDoc вне конфликта участком не считается")
        assertEquals(document, file.resolvedText(emptyList()), "документ не искажён разбором")
    }

    @Test
    fun TC_44_malformedMarkupIsRefusedInsteadOfGuessed() {
        val unclosed = "<<<<<<< HEAD\nмоё\n=======\nих\n"
        assertIs<ConflictParseResult.Malformed>(
            parseConflictFile(unclosed),
            "незакрытая разметка — отказ разбора",
        )

        val nested = "<<<<<<< HEAD\n<<<<<<< HEAD\nмоё\n=======\nих\n>>>>>>> origin/main\n"
        assertIs<ConflictParseResult.Malformed>(parseConflictFile(nested), "вложенный маркер — отказ")

        // Дискриминирующий вход ревью E3: девять «=» внутри стороны участка.
        // Разделитель встречается дважды — разметка непарная, разбор отказывает.
        val nineEquals = "<<<<<<< HEAD\nмоё\n=========\nещё моё\n=======\nих\n>>>>>>> origin/main\n"
        assertIs<ConflictParseResult.Malformed>(parseConflictFile(nineEquals), "непарная разметка — отказ")

        val separatorOutside = "текст\n=======\nещё текст\n"
        val file = assertIs<ConflictParseResult.Parsed>(parseConflictFile(separatorOutside)).file
        assertEquals(emptyList(), file.hunks, "разделитель вне участка — обычный текст")
        assertEquals(separatorOutside, file.resolvedText(emptyList()), "и он не теряется")
    }
}
