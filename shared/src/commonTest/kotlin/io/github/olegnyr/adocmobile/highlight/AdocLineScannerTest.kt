package io.github.olegnyr.adocmobile.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 001-syntax-highlighting, слайс `SL-2` — строчные
 * конструкции и маскарад блока.
 *
 * Оракул везде выписан руками из требования либо из прогона эталонного
 * Asciidoctor 2.0.26 от 2026-08-17, но никогда из реализации.
 *
 * Главный факт, снятый прогоном и не описанный ни в требованиях, ни в разведке:
 * *строчная конструкция распознаётся только в начале блока*. После строки текста
 * заголовок, маркер списка, запись атрибута, заголовок блока, метка admonition и
 * оба разрыва уезжают в абзац — эталон склеивает их с предыдущей строкой.
 * Исключения ровно два: список атрибутов `[…]` и строчный комментарий `//`
 * работают где угодно. Тесты ниже написаны против этого поведения, потому что
 * иначе подсветка разошлась бы с превью на каждом абзаце, начинающемся со звёздочки.
 *
 * Имена тестов начинаются с идентификатора тест-кейса, как требуют правила
 * разработки. У `FR-13`…`FR-16` собственного тест-кейса в спеке нет — матрица
 * трассируемости покрывает их диапазоном `TC-9`…`TC-16`, но ни один из этих
 * кейсов о комментариях, директивах, разрывах и литеральном абзаце не говорит.
 * Такие тесты названы по требованию (`FR_15_…`), а не по случайно подходящему
 * кейсу: пробел в спеке должен быть виден, а не замазан правдоподобным номером.
 */
class AdocLineScannerTest {

    private fun Source(vararg lines: String) = HighlightSource(*lines)

    // region заголовки — FR-7

    @Test
    fun TC_9_headingLevelsFollowTheNumberOfSigns() {
        val src = Source(
            "= Документ", // 0  уровень 0
            "", // 1
            "== Раздел", // 2  уровень 1
            "", // 3
            "=== Подраздел", // 4  уровень 2
            "", // 5
            "====== Шестой знак", // 6  уровень 5 — предел
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.Heading0),
                AdocSpan(src.line(2), AdocStyle.Heading1),
                AdocSpan(src.line(4), AdocStyle.Heading2),
                AdocSpan(src.line(6), AdocStyle.Heading5),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_9_markdownHashFormGivesTheSameLevels() {
        val src = Source(
            "# Документ",
            "",
            "## Раздел",
            "",
            "###### Шестой знак",
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.Heading0),
                AdocSpan(src.line(2), AdocStyle.Heading1),
                AdocSpan(src.line(4), AdocStyle.Heading5),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_10_headingRequiresASpaceAfterTheMarker() {
        val src = Source(
            "==Заголовок",
            "",
            "#Заголовок",
            "",
            "== ",
        )

        // `==Заголовок` — абзац: пробел после маркера обязателен (`FR-7`).
        // Пустой заголовок `== ` тоже не заголовок: подсвечивать нечего.
        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_10_sevenSignsAreNotAHeading() {
        // Прогон эталона 2026-08-17: `======= Семь знаков` выводится абзацем.
        // Ловит реализацию с открытой верхней границей числа знаков.
        val src = Source("======= Семь знаков", "", "####### Семь решёток")
        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_10_headingIsRecognizedOnlyAtTheTopLevel() {
        val src = Source(
            "====", // 0
            "== Не заголовок внутри example", // 1
            "====", // 2
            "",
            "== Заголовок снаружи", // 4
        )

        // Прогон эталона: внутри `====`, `****`, `____` и `--` строка с `=`
        // остаётся абзацем. Разделы живут только на верхнем уровне документа.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(2), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(4), AdocStyle.Heading1),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region атрибуты документа — FR-8, FR-9

    @Test
    fun TC_11_documentAttributeFormsAreMarked() {
        val src = Source(
            ":status: approved", // 0
            ":status:", // 1
            ":!status:", // 2
            ":status!:", // 3
            ":toc-title: Содержание", // 4
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.AttributeEntry),
                AdocSpan(src.line(1), AdocStyle.AttributeEntry),
                AdocSpan(src.line(2), AdocStyle.AttributeEntry),
                AdocSpan(src.line(3), AdocStyle.AttributeEntry),
                AdocSpan(src.line(4), AdocStyle.AttributeEntry),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_11_colonAtLineStartIsNotAlwaysAnAttribute() {
        val src = Source(
            ": текст", // 0  имя пустое
            "", // 1
            ":: текст", // 2  разделитель списка определений без термина
            "", // 3
            ":не закрыто", // 4
        )

        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_12_attributeValueContinuesOnTheNextLineAfterBackslash() {
        val src = Source(
            ":multi: первая часть \\", // 0
            "вторая часть \\", // 1
            "третья часть", // 2
            "* уже не значение атрибута", // 3
        )

        // Единственная строчная конструкция вне блоков с многострочным состоянием
        // (`FR-9`). Прогон эталона: перенос работает несколько строк подряд, а
        // строка без слэша на конце его завершает.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.AttributeEntry),
                AdocSpan(src.line(1), AdocStyle.AttributeEntry),
                AdocSpan(src.line(2), AdocStyle.AttributeEntry),
                AdocSpan(src.inLine(3, 0, 1), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_12_backslashWithoutASpaceBeforeItDoesNotContinueTheValue() {
        val src = Source(
            ":sdk: C:\\", // 0  слэш без пробела перед ним — часть значения
            "обычный абзац", // 1
            "* не список: абзац уже начат", // 2
        )

        // Прогон эталона 2026-08-17: `:hard: ccc\` значение не продолжает, а
        // следующая строка становится абзацем. Без пробела перед слэшем проверка
        // утащила бы в атрибут любую строку под путём Windows.
        assertEquals(
            listOf(AdocSpan(src.line(0), AdocStyle.AttributeEntry)),
            src.scan().spans,
        )
    }

    // endregion

    // region списки — FR-10

    @Test
    fun TC_13_listMarkersOfEveryKindAreMarked() {
        val src = Source(
            "* пункт", // 0
            "- пункт", // 1
            ". пункт", // 2
            ".. вложенный", // 3
            "... третий уровень", // 4
            "** второй уровень", // 5
        )

        // Размечается только маркер: текст пункта остаётся под инлайн-разбор `SL-3`.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(1, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(2, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(3, 0, 2), AdocStyle.ListMarker),
                AdocSpan(src.inLine(4, 0, 3), AdocStyle.ListMarker),
                AdocSpan(src.inLine(5, 0, 2), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_13_definitionListSeparatorsAreMarkedWithTheTerm() {
        val src = Source(
            "Термин:: описание", // 0
            "Второй::: глубже", // 1
            "Третий:::: ещё глубже", // 2
            "Короткий;; описание", // 3
            "Без описания::", // 4
        )

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 6), AdocStyle.ListTerm),
                AdocSpan(src.inLine(0, 6, 8), AdocStyle.ListMarker),
                AdocSpan(src.inLine(1, 0, 6), AdocStyle.ListTerm),
                AdocSpan(src.inLine(1, 6, 9), AdocStyle.ListMarker),
                AdocSpan(src.inLine(2, 0, 6), AdocStyle.ListTerm),
                AdocSpan(src.inLine(2, 6, 10), AdocStyle.ListMarker),
                AdocSpan(src.inLine(3, 0, 8), AdocStyle.ListTerm),
                AdocSpan(src.inLine(3, 8, 10), AdocStyle.ListMarker),
                AdocSpan(src.inLine(4, 0, 12), AdocStyle.ListTerm),
                AdocSpan(src.inLine(4, 12, 14), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_13_blockMacroIsNotADefinitionList() {
        // `image::pic.png[]` отличается от списка определений ровно отсутствием
        // пробела после разделителя. Сам макрос — слайс `SL-4`; здесь важно, что
        // `SL-2` не разметил его термином.
        val src = Source("image::pic.png[]", "", "http://example.org/a::b")
        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_14_listMarkerRequiresASpaceAfterIt() {
        val src = Source(
            "*Жирная фраза *", // 0  форматирование, а не список
            "", // 1
            "* Жирная фраза *", // 2  список
        )

        // Прогон эталона: первая строка — абзац, третья — пункт списка.
        // Различает ровно пробел после маркера (`FR-10`).
        assertEquals(
            listOf(AdocSpan(src.inLine(2, 0, 1), AdocStyle.ListMarker)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_13_listMarkerMayBeIndented() {
        val src = Source("  * пункт с отступом")

        // Отступ перед маркером произволен и незначим; проверка нужна ещё и
        // потому, что литеральный абзац по отступу (`FR-16`) не должен победить.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 2, 3), AdocStyle.ListMarker)),
            src.scan().spans,
        )
    }

    // endregion

    // region метаданные блока — FR-11

    @Test
    fun TC_16_dotDistinguishesBlockTitleFromListItem() {
        val src = Source(
            ".Заголовок блока", // 0  без пробела после точки
            "", // 1
            ". Пункт", // 2  с пробелом
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockTitle),
                AdocSpan(src.inLine(2, 0, 1), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_16_attributeListAndAnchorsAreMarkedApart() {
        val src = Source(
            "[source,kotlin]", // 0
            "", // 1
            "[[section-id]]", // 2
            "", // 3
            "[#section-id]", // 4
            "", // 5
            "[quote#roads]", // 6
        )

        // `FR-11` перечисляет три разные конструкции, и роли у них разные:
        // якорь — не список атрибутов, хотя обе формы начинаются со скобки.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockAttributes),
                AdocSpan(src.line(2), AdocStyle.Anchor),
                AdocSpan(src.line(4), AdocStyle.Anchor),
                AdocSpan(src.line(6), AdocStyle.BlockAttributes),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_16_attributeListInterruptsAParagraph() {
        val src = Source(
            "абзац", // 0
            "[source]", // 1
            "код", // 2
        )

        // Прогон эталона: `[…]` — единственная строка метаданных, работающая
        // прямо посреди абзаца; заголовок блока `.Title` там уже не работает.
        assertEquals(
            listOf(AdocSpan(src.line(1), AdocStyle.BlockAttributes)),
            src.scan().spans,
        )
    }

    // endregion

    // region admonitions — FR-12

    @Test
    fun TC_15_admonitionLabelMustBeUppercase() {
        val src = Source(
            "WARNING: текст", // 0
            "", // 1
            "Warning: текст", // 2
            "", // 3
            "NOTE:без пробела", // 4
        )

        // Размечается метка вместе с двоеточием; текст остаётся под `SL-3`.
        // Прогон эталона: строчная метка и метка без пробела admonition не дают.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 0, 8), AdocStyle.Admonition)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_15_allFiveAdmonitionLabelsAreKnown() {
        for (label in listOf("NOTE", "TIP", "IMPORTANT", "CAUTION", "WARNING")) {
            val src = Source("$label: текст")
            assertEquals(
                listOf(AdocSpan(src.inLine(0, 0, label.length + 1), AdocStyle.Admonition)),
                src.scan().spans,
                "метка $label не распознана",
            )
        }
    }

    // endregion

    // region строчные комментарии — FR-13

    @Test
    fun FR_13_lineCommentIsMarkedOutsideVerbatimBlocks() {
        val src = Source(
            "// комментарий", // 0
            "//без пробела", // 1
            "/// три косые", // 2
        )

        // Прогон эталона: `///` комментарием НЕ является — выводится абзацем.
        // Ловит реализацию, проверяющую только префикс `//`.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.Comment),
                AdocSpan(src.line(1), AdocStyle.Comment),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_13_lineCommentWorksInsideAParagraphButDoesNotBreakIt() {
        val src = Source(
            "абзац", // 0
            "// комментарий посреди абзаца", // 1
            "* не список: абзац продолжается", // 2
        )

        // Прогон эталона: комментарий вырезается препроцессором, абзац склеивается
        // через него, и строка 2 остаётся текстом. Комментарий прозрачен для
        // состояния «начало блока» — это его единственное неочевидное свойство.
        assertEquals(
            listOf(AdocSpan(src.line(1), AdocStyle.Comment)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_8_lineCommentDoesNotWorkInsideVerbatimBlocks() {
        val src = Source(
            "----", // 0
            "// не комментарий", // 1
            "----", // 2
        )

        // `FR-13` ограничивает строчный комментарий блоками вне дословных, и
        // разведка называет это прямо: внутри listing `//` — обычный текст.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(1), AdocStyle.VerbatimContent),
                AdocSpan(src.line(2), AdocStyle.BlockDelimiter),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region директивы препроцессора — FR-14

    @Test
    fun FR_14_preprocessorDirectivesAreMarkedButNotEvaluated() {
        val src = Source(
            "include::part.adoc[]", // 0
            "ifdef::attr[]", // 1
            "ifndef::attr[]", // 2
            "ifeval::[1 == 1]", // 3
            "endif::[]", // 4
        )

        assertEquals(
            List(5) { AdocSpan(src.line(it), AdocStyle.PreprocessorDirective) },
            src.scan().spans,
        )
    }

    @Test
    fun TC_33_directiveWithoutClosingBracketIsNotMarked() {
        // `FR-28`: сомневаешься — не подсвечивай. Без закрывающей скобки строка
        // директивой не является, и `include:: см. ниже` — обычный список
        // определений, а не препроцессор.
        val src = Source("include:: см. ниже")
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 7), AdocStyle.ListTerm),
                AdocSpan(src.inLine(0, 7, 9), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region разрывы — FR-15

    @Test
    fun FR_15_thematicAndPageBreaksAreMarked() {
        val src = Source(
            "'''", // 0
            "", // 1
            "---", // 2
            "", // 3
            "***", // 4
            "", // 5
            "- - -", // 6
            "", // 7
            "<<<", // 8
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.ThematicBreak),
                AdocSpan(src.line(2), AdocStyle.ThematicBreak),
                AdocSpan(src.line(4), AdocStyle.ThematicBreak),
                AdocSpan(src.line(6), AdocStyle.ThematicBreak),
                AdocSpan(src.line(8), AdocStyle.PageBreak),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_15_fourMarksAreNotAThematicBreak() {
        val src = Source(
            "----", // listing, а не разрыв
            "----",
            "",
            "****", // sidebar
            "****",
            "",
            "* * * *", // прогон эталона: это пункт списка
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(1), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(3), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(4), AdocStyle.BlockDelimiter),
                AdocSpan(src.inLine(6, 0, 1), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region литеральный абзац по отступу — FR-16

    @Test
    fun FR_16_indentedLineIsLiteralOnlyAtTheStartOfABlock() {
        val src = Source(
            "абзац", // 0
            "  отступ сразу под абзацем", // 1  ленивое продолжение абзаца
            "", // 2
            "  отступ после пустой строки", // 3  литеральный абзац
            "  вторая строка литерала", // 4
        )

        // Гипотеза `FR-16` про взаимодействие с продолжением списка снята прогоном
        // эталона: отступ даёт литеральный абзац только в начале блока. Сразу под
        // строкой текста или под пунктом списка та же строка склеивается с ней.
        assertEquals(
            listOf(
                AdocSpan(src.line(3), AdocStyle.VerbatimContent),
                AdocSpan(src.line(4), AdocStyle.VerbatimContent),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_16_indentedLineUnderAListItemIsNotLiteral() {
        val src = Source(
            "* пункт", // 0
            "  продолжение пункта", // 1
            "", // 2
            "  а вот это литерал", // 3
        )

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.line(3), AdocStyle.VerbatimContent),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region начало блока — граница, которой нет в требованиях

    @Test
    fun TC_33_lineConstructsAreRecognizedOnlyAtTheStartOfABlock() {
        val src = Source(
            "обычный абзац", // 0
            "== не заголовок", // 1
            "* не список", // 2
            ":attr: не атрибут", // 3
            ".Не заголовок блока", // 4
            "NOTE: не admonition", // 5
            "'''", // 6  и не разрыв
            "<<< и не разрыв страницы", // 7
        )

        // Прогон эталона 2026-08-17: всё это склеивается в один абзац. Требования
        // такого условия не содержат — расхождение зафиксировано в отчёте слайса.
        // Без этой проверки сканер подсвечивал бы разметкой каждый абзац,
        // начинающийся со звёздочки, точки или прописного слова с двоеточием.
        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_33_delimiterAndMetadataOpenANewBlock() {
        val src = Source(
            "====", // 0  ограничитель — граница блока
            "* список первой строкой", // 1
            "====", // 2
            "[NOTE]", // 3  метаданные — тоже граница
            "* список после метаданных", // 4
            "", // 5
            ".Заголовок", // 6
            "* список после заголовка блока", // 7
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.inLine(1, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.line(2), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(3), AdocStyle.BlockAttributes),
                AdocSpan(src.inLine(4, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.line(6), AdocStyle.BlockTitle),
                AdocSpan(src.inLine(7, 0, 1), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_33_blockTitleRightUnderAListItemIsSwallowed() {
        val src = Source(
            "* пункт", // 0
            ".Не заголовок блока", // 1  склеивается с текстом пункта
            "* следующий пункт", // 2  а маркер после этой строки работает
        )

        // Прогон эталона 2026-08-17: незакрытый пункт списка «удерживает» только
        // маркеры — заголовок блока внутри него уезжает в текст пункта. Пустая
        // строка перед ним вернула бы ему смысл, и соседний кейс это показывает.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(2, 0, 1), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_13_listItemAllowsTheNextMarkerButNotAHeading() {
        val src = Source(
            "* пункт a", // 0
            "* пункт b", // 1
            "== не заголовок под пунктом", // 2
            ". вложенный нумерованный", // 3
        )

        // Прогон эталона: маркер списка после строки-пункта работает, заголовок —
        // нет. Единственное место, где «начало блока» не сводится к пустой строке.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(1, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(3, 0, 1), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_8_noLineConstructIsRecognizedInsideAVerbatimBlock() {
        val src = Source(
            "----", // 0
            "== не заголовок", // 1
            "* не список", // 2
            ":attr: не атрибут", // 3
            "'''", // 4
            "----", // 5
        )

        // `FR-3` целиком: внутри дословного блока строчных конструкций нет вовсе,
        // и роли у всех строк дословные.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(1), AdocStyle.VerbatimContent),
                AdocSpan(src.line(2), AdocStyle.VerbatimContent),
                AdocSpan(src.line(3), AdocStyle.VerbatimContent),
                AdocSpan(src.line(4), AdocStyle.VerbatimContent),
                AdocSpan(src.line(5), AdocStyle.BlockDelimiter),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region маскарад блока — FR-5

    @Test
    fun TC_7_styleLabelAboveDelimiterChangesTheBlockKind() {
        val src = Source(
            "[listing]", // 0
            "....", // 1
            "*не жирный*", // 2
            "....", // 3
        )
        val scan = src.scan()

        // Тип блока наблюдается стеком: содержимое literal и listing одинаково
        // дословно, и по одним диапазонам маскарад не отличить.
        assertEquals(
            listOf(
                emptyList(),
                listOf(AdocBlockFrame(AdocBlockKind.Listing, 4, delimiterKind = AdocBlockKind.Literal)),
                listOf(AdocBlockFrame(AdocBlockKind.Listing, 4, delimiterKind = AdocBlockKind.Literal)),
                emptyList(),
            ),
            scan.blockStates,
        )
        // И блок обязан закрыться теми же знаками, которыми открылся: если бы
        // кадр помнил только эффективный тип, `....` перестала бы совпадать с ним.
        assertEquals(AdocStyle.BlockDelimiter, scan.spans.last().style)
    }

    @Test
    fun TC_7_commentLabelMakesAnOpenBlockOpaque() {
        val src = Source(
            "[comment]", // 0
            "--", // 1
            "----", // 2  внутри комментария вложенного блока НЕ открывается
            "--", // 3
            "", // 4
            "* снова обычный список", // 5
        )
        val scan = src.scan()

        // Самый дорогой маскарад: open — составной блок, comment — непрозрачный.
        // Ошибка здесь утащила бы остаток документа в listing, открытый строкой 2.
        assertEquals(
            listOf(
                emptyList(),
                listOf(AdocBlockFrame(AdocBlockKind.Comment, 2, delimiterKind = AdocBlockKind.Open)),
                listOf(AdocBlockFrame(AdocBlockKind.Comment, 2, delimiterKind = AdocBlockKind.Open)),
                emptyList(),
                emptyList(),
                emptyList(),
            ),
            scan.blockStates,
        )
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockAttributes),
                AdocSpan(src.line(1), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(2), AdocStyle.Comment),
                AdocSpan(src.line(3), AdocStyle.BlockDelimiter),
                AdocSpan(src.inLine(5, 0, 1), AdocStyle.ListMarker),
            ),
            scan.spans,
        )
    }

    @Test
    fun TC_7_masqueradeSurvivesTitleAndAnchorBetweenLabelAndDelimiter() {
        val src = Source(
            "[source,kotlin]", // 0
            ".Заголовок", // 1
            "[[anchor-id]]", // 2
            "....", // 3
            "val x = 1", // 4
            "....", // 5
        )

        // Метаданные блока укладываются построчно в любом порядке, и метка стиля
        // обязана дожить до ограничителя через них. Прогон эталона подтверждает.
        assertEquals(
            listOf(AdocBlockFrame(AdocBlockKind.Listing, 4, delimiterKind = AdocBlockKind.Literal)),
            src.scan().blockStates[3],
        )
    }

    @Test
    fun TC_7_incompatibleStyleLabelDoesNotChangeTheBlock() {
        val src = Source(
            "[quote]", // 0
            "----", // 1
            "*не жирный*", // 2
            "----", // 3
        )

        // Прогон эталона 2026-08-17: `[quote]` над `----` НЕ делает блок цитатой —
        // он остаётся listing. Маскарад несимметричен, и таблица допустимых пар
        // закрыта. Реализация «любая метка меняет тип» провалит этот кейс.
        assertEquals(
            listOf(AdocBlockFrame(AdocBlockKind.Listing, 4)),
            src.scan().blockStates[1],
        )
    }

    @Test
    fun TC_7_emptyStyleDoesNotMasquerade() {
        val src = Source(
            "[,kotlin]", // 0
            "--", // 1
            "текст", // 2
            "--", // 3
        )

        // Прогон эталона: `[,kotlin]` над `--` оставляет open-блок открытым —
        // позиционная метка пуста, маскарада нет.
        assertEquals(
            listOf(AdocBlockFrame(AdocBlockKind.Open, 2)),
            src.scan().blockStates[1],
        )
    }

    @Test
    fun TC_7_styleLabelIsForgottenAfterAnOrdinaryLine() {
        val src = Source(
            "[listing]", // 0
            "просто абзац", // 1  метка израсходована на него
            "", // 2
            "....", // 3  и на этот ограничитель уже не действует
            "содержимое", // 4
            "....", // 5
        )

        assertEquals(
            listOf(AdocBlockFrame(AdocBlockKind.Literal, 4)),
            src.scan().blockStates[3],
        )
    }

    // endregion

    // region сквозные свойства

    @Test
    fun TC_29_lineConstructsNeverCrossALineBreak() {
        val src = Source(
            "= Документ",
            ":status: approved",
            "",
            "* пункт",
            "Термин:: описание",
            "// комментарий",
            "'''",
        )
        val text = src.text

        for (span in src.scan().spans) {
            assertTrue(
                span.range.endExclusive <= text.length,
                "диапазон ${span.range} выходит за пределы текста длиной ${text.length}",
            )
            assertTrue(
                text.substring(span.range.start, span.range.endExclusive).none { it == '\n' },
                "диапазон ${span.range} пересекает перевод строки: сканер построчный",
            )
        }
    }

    @Test
    fun TC_29_spansStayOrderedByStart() {
        val src = Source(
            "= Документ",
            ":status: approved",
            "",
            "Термин:: описание",
            "",
            "WARNING: осторожно",
        )
        val starts = src.scan().spans.map { it.range.start }

        // `AdocScan` обещает диапазоны, отсортированные по началу: `SL-5` обопрётся
        // на этот порядок, а строчные конструкции — первое место, где на одной
        // строке появляется больше одного диапазона.
        assertEquals(starts.sorted(), starts, "диапазоны выданы не по порядку")
    }

    @Test
    fun TC_33_emptyAndWhitespaceLinesGetNoSpans() {
        val scan = AdocBlockScanner.scan("\n   \n\t\n")
        assertEquals(emptyList(), scan.spans, "пустая строка и строка из пробелов размечать нечего")
    }

    // endregion
}
