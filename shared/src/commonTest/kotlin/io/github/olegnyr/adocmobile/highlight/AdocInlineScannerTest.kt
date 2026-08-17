package io.github.olegnyr.adocmobile.highlight

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Критерии приёмки фичи 001-syntax-highlighting, слайс `SL-3` — инлайн-разбор:
 * passthrough, constrained/unconstrained-форматирование, экранирование
 * (`FR-17`…`FR-20`).
 *
 * Оракул везде выписан руками из требования либо из прогона эталонного
 * Asciidoctor 2.0.26 от 2026-08-17, но никогда из реализации. Ключевые факты,
 * снятые прогоном и уточняющие требования:
 *
 * * содержимое unconstrained-пары — любые символы, включая пробелы по краям:
 *   `The __kernel qualifier is __important` даёт `<em>kernel qualifier is </em>`
 *   с пробелом в конце содержимого (`TC-20`);
 * * закрывающий constrained-знак, за которым идёт буква, пропускается, и поиск
 *   продолжается дальше: `*a*b*` даёт `<strong>a*b</strong>`;
 * * слева от constrained-знака допустим не только пробел, вопреки прозе
 *   документации: `(*скобки*)` даёт полужирный;
 * * `pass:[…]` не требует границы слова: `bypass:[*x*]` содержит работающий
 *   passthrough;
 * * `+` на отдельной строке внутри списка — продолжение пункта, и оно обрывает
 *   логическую строку: пара через него не работает.
 */
class AdocInlineScannerTest {

    private fun Source(vararg lines: String) = HighlightSource(*lines)

    // region единица разбора — логическая строка блока (FR-17)

    @Test
    fun TC_17_constrainedPairInAParagraphIsMarked() {
        val src = Source("абзац с *жирным* словом")

        // Диапазон покрывает конструкцию целиком, вместе со знаками.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 8, 16), AdocStyle.Bold)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_17_pairBrokenByALineBreakInsideAParagraphIsMarked() {
        val src = Source(
            "перед *жирный", // 0
            "текст* дальше", // 1
        )

        // Прогон эталона: `quotes` применяется после склейки строк абзаца, и пара
        // пересекает перевод строки. Диапазон один и непрерывен по исходнику.
        assertEquals(
            listOf(
                AdocSpan(
                    AdocRange(src.line(0).start + 6, src.line(1).start + 6),
                    AdocStyle.Bold,
                ),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_18_pairBrokenByABlankLineIsNotMarked() {
        val src = Source(
            "перед *жирный", // 0
            "", // 1  пустая строка обрывает логическую строку блока
            "текст* дальше", // 2
        )

        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun FR_17_formattingWorksInListItemText() {
        val src = Source("* *жирный* пункт")

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(0, 2, 10), AdocStyle.Bold),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_17_formattingWorksInAdmonitionText() {
        val src = Source("NOTE: *жирный* текст")

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 5), AdocStyle.Admonition),
                AdocSpan(src.inLine(0, 6, 14), AdocStyle.Bold),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_17_formattingWorksInDefinitionListDescription() {
        val src = Source("Термин:: *жирный* дальше")

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 6), AdocStyle.ListTerm),
                AdocSpan(src.inLine(0, 6, 8), AdocStyle.ListMarker),
                AdocSpan(src.inLine(0, 9, 17), AdocStyle.Bold),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_17_newListItemBreaksTheLogicalLine() {
        val src = Source(
            "* один *жирный", // 0
            "* хвост* два", // 1
        )

        // Прогон эталона: каждый пункт — своя логическая строка, пара между
        // пунктами не работает.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 1), AdocStyle.ListMarker),
                AdocSpan(src.inLine(1, 0, 1), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_17_listContinuationBreaksTheLogicalLine() {
        val src = Source(
            "* пункт *жирный", // 0
            "+", // 1  продолжение пункта — граница логической строки
            "текст* дальше", // 2
        )

        // Прогон эталона 2026-08-17: `+` на отдельной строке внутри списка
        // присоединяет к пункту новый блок, и пара через него не работает.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 0, 1), AdocStyle.ListMarker)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_17_pairCrossesALineCommentInsideAParagraph() {
        val src = Source(
            "абзац *жирный", // 0
            "// комментарий", // 1  прозрачна: препроцессор снимает её до разбора
            "текст* дальше", // 2
        )

        // Прогон эталона: комментарий вырезается, абзац склеивается через него,
        // и пара работает. Диапазон считается по исходнику и потому накрывает
        // строку комментария — у неё остаётся и собственная роль.
        assertEquals(
            listOf(
                AdocSpan(AdocRange(src.line(0).start + 6, src.line(2).start + 6), AdocStyle.Bold),
                AdocSpan(src.line(1), AdocStyle.Comment),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_17_metadataLineBreaksTheLogicalLine() {
        val src = Source(
            "абзац *жирный", // 0
            "[source]", // 1  метаданные обрывают абзац (см. SL-2)
            "хвост* дальше", // 2
        )

        assertEquals(
            listOf(AdocSpan(src.line(1), AdocStyle.BlockAttributes)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_17_inlineWorksInsideCompoundBlocks() {
        val src = Source(
            "====", // 0
            "внутри *жирный* работает", // 1
            "====", // 2
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.inLine(1, 7, 15), AdocStyle.Bold),
                AdocSpan(src.line(2), AdocStyle.BlockDelimiter),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_3_noInlineInsideVerbatimBlocks() {
        val src = Source(
            "----", // 0
            "*не жирный* и +pass+", // 1
            "----", // 2
        )

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

    // region passthrough первым (FR-18)

    @Test
    fun TC_21_passthroughRunsBeforeFormatting() {
        val src = Source("+*не жирный*+")

        // Ломается на любой реализации, где проход форматирования идёт раньше:
        // звёздочки внутри passthrough не имеют права стать полужирным.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 0, 13), AdocStyle.InlinePassthrough)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_18_allPassthroughFormsSuppressFormatting() {
        val src = Source(
            "++__не курсив__++", // 0
            "", // 1
            "+++*сырой*+++", // 2
            "", // 3
            "pass:[*тоже*]", // 4
        )

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 17), AdocStyle.InlinePassthrough),
                AdocSpan(src.inLine(2, 0, 13), AdocStyle.InlinePassthrough),
                AdocSpan(src.inLine(4, 0, 13), AdocStyle.InlinePassthrough),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_18_passMacroNeedsNoWordBoundary() {
        val src = Source("bypass:[*x*] тут")

        // Прогон эталона 2026-08-17: `bypass:[*x*]` выводится как `by*x*` — то
        // есть `pass:[…]` срабатывает и посреди слова. «Осторожная» граница
        // слова здесь недоподсветила бы относительно эталона (`FR-28`).
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 2, 12), AdocStyle.InlinePassthrough)),
            src.scan().spans,
        )
    }

    // endregion

    // region constrained против unconstrained (FR-19)

    @Test
    fun TC_19_constrainedPairNeedsNonWordNeighbours() {
        val src = Source(
            "foo*bar*baz", // 0
            "", // 1
            "сло*во*вот и **жир**ный", // 2
        )

        // Подтверждено прогоном эталона: constrained-пара между буквами не
        // работает — ни латинскими, ни кириллическими, — а unconstrained
        // работает где угодно.
        assertEquals(
            listOf(AdocSpan(src.inLine(2, 13, 20), AdocStyle.Bold)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_19_openingContextAloneRejectsThePair() {
        val src = Source(
            "сло*во* вот", // 0  слева от открывающего знака буква
            "", // 1
            "}*не жирный*{", // 2  фигурная скобка тоже запрещена
            "", // 3
            "двоеточие:*не жирный*", // 4  и двоеточие
        )

        // Прогон эталона 2026-08-17: ни одна строка форматирования не получает.
        // Закрывающий знак везде валиден — за ним пробел, `{` или конец строки, —
        // поэтому кейс проверяет именно контекст слева от открывающего (`FR-19`):
        // мутация, снявшая одну эту проверку, обязана уронить этот тест.
        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_20_unconstrainedPairMatchesLiterally() {
        val src = Source("The __kernel qualifier is __important")

        // Прогон эталона: `<em>kernel qualifier is </em>` — содержимое пары
        // кончается пробелом, и «аккуратная» реализация с непробельными краями
        // недоподсветила бы относительно эталона (`FR-28`).
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 4, 28), AdocStyle.Italic)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_19_unconstrainedPairWorksMidWord() {
        val src = Source("foo**bar**baz")

        assertEquals(
            listOf(AdocSpan(src.inLine(0, 3, 10), AdocStyle.Bold)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_19_allConstrainedMarksAreRecognized() {
        val src = Source(
            "это _курсив_ тут", // 0
            "", // 1
            "это `моно` тут", // 2
            "", // 3
            "это #марк# тут", // 4
        )

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 4, 12), AdocStyle.Italic),
                AdocSpan(src.inLine(2, 4, 10), AdocStyle.Monospace),
                AdocSpan(src.inLine(4, 4, 10), AdocStyle.Highlight),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_19_nestedFormattingKeepsBothRoles() {
        val src = Source("*жирный _и курсив_ внутри*")

        // Вложение по эталону: полужирный снаружи, курсив внутри. Диапазоны
        // пересекаются, и при совпадении начал более широкий идёт первым.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 26), AdocStyle.Bold),
                AdocSpan(src.inLine(0, 8, 18), AdocStyle.Italic),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_19_monospaceOverInlinePassthrough() {
        val src = Source("`+литерал+`")

        // «Моноширинный без подстановок» из таблицы разведки: passthrough
        // внутри, моноширинный снаружи — обе роли на месте.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 11), AdocStyle.Monospace),
                AdocSpan(src.inLine(0, 1, 10), AdocStyle.InlinePassthrough),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_19_rolePrefixJoinsTheFormattedRange() {
        val src = Source("[.underline]#текст# тут")

        // Прогон эталона: `[.underline]#текст#` — одна конструкция, и префикс
        // роли входит в её диапазон.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 0, 19), AdocStyle.Highlight)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_28_superscriptAndSubscriptNeedContiguousText() {
        val src = Source(
            "H~2~O", // 0
            "", // 1
            "a ~ b ~ c", // 2  пробелы внутри — пары нет
            "", // 3
            "x^2^", // 4
        )

        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 1, 4), AdocStyle.Subscript),
                AdocSpan(src.inLine(4, 1, 4), AdocStyle.Superscript),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region экранирование (FR-20)

    @Test
    fun TC_22_escapingDistinguishesSingleAndDoubledMarks() {
        val src = Source(
            "\\*не жирный*", // 0  один слэш экранирует одиночный знак
            "", // 1
            "\\__не курсив__", // 2  для сдвоенного знака одного слэша МАЛО
            "", // 3
            "\\\\__текст__", // 4  сдвоенный знак экранируют два слэша
        )

        // Средний случай подтверждён прогоном эталона: `\__не курсив__` даёт
        // `<em>_не курсив_</em>` — курсив есть, и диапазон накрывает всю
        // конструкцию после слэша.
        assertEquals(
            listOf(AdocSpan(src.inLine(2, 1, 14), AdocStyle.Italic)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_22_escapedUnconstrainedPairConsumesItsClosingMarks() {
        val src = Source("\\\\__экранировано__, а \\__не экранировано__ тут")

        // Дефект, найденный сверкой SL-6, закреплён прогоном эталона 2026-08-17
        // (SL-7): `\\__экранировано__` литерально ЦЕЛИКОМ — его закрывающая
        // пара не имеет права сцепиться накрест с открывающей следующей
        // конструкции. Вторая пара при этом курсив даёт.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 23, 42), AdocStyle.Italic)),
            src.scan().spans,
        )
    }

    @Test
    fun TC_22_escapedPairsStayLiteralInIsolationAndInARow() {
        val src = Source(
            "хвост \\\\__в конце строки__", // 0  прогон K02
            "", // 1
            "\\\\__первая__ и \\\\__вторая__ подряд", // 2  прогон K03
        )

        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_22_realPairsAroundAnEscapedOneStillWork() {
        val src = Source(
            "\\\\__экранировано__ и __настоящая пара__ тут", // 0  прогон K04
            "", // 1
            "\\\\__экранировано__ а потом _одиночный курсив_ тут", // 2  прогон K07
            "", // 3
            "\\\\**жирная пара** после **настоящая** тут", // 4  прогон K06
        )

        // Экранированная конструкция съедена, соседние пары — и unconstrained,
        // и constrained, и другой знак — работают как без неё.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 21, 39), AdocStyle.Italic),
                AdocSpan(src.inLine(2, 27, 45), AdocStyle.Italic),
                AdocSpan(src.inLine(4, 24, 37), AdocStyle.Bold),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_23_rangesAreCountedOverTheSourceText() {
        val src = Source("\\*нет* и *да*")

        // Экранирующий слэш занимает позицию в исходнике, хотя в выводе
        // Asciidoctor его нет: диапазон второй пары начинается с 9, а не с 8.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 9, 13), AdocStyle.Bold)),
            src.scan().spans,
        )
    }

    // endregion
}
