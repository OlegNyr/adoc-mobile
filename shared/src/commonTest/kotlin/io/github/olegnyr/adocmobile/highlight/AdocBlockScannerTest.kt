package io.github.olegnyr.adocmobile.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 001-syntax-highlighting, слайс `SL-1` — блочный автомат.
 *
 * Оракул везде выписан руками из требования либо из прогона эталонного
 * Asciidoctor 2.0.26, но никогда из реализации. Спорные случаи, которых
 * документация языка не описывает (незакрытый блок, вложенность внутри
 * дословного блока, шкала длин дефисов, позиция callout), сняты прогоном
 * эталона 2026-08-17 — так предписывают границы работ фичи.
 */
class AdocBlockScannerTest {

    // region вспомогательное

    /**
     * Документ, помнящий, где начинается каждая строка.
     *
     * Диапазоны сканера считаются в смещениях по всему исходнику, а тест-кейсы
     * сформулированы в строках. Пересчёт вынесен сюда, чтобы ожидаемые значения
     * в тестах читались как «строка 2 целиком», а не как «символы 17..28» —
     * иначе правка одного символа в документе теста ломает все ожидания разом.
     */
    private class Source(vararg lines: String) {
        private val lines: List<String> = lines.toList()
        val text: String = this.lines.joinToString("\n")

        private val starts: List<Int> = buildList {
            var offset = 0
            for (line in this@Source.lines) {
                add(offset)
                offset += line.length + 1
            }
        }

        /** Диапазон всей строки, без перевода строки. */
        fun line(index: Int): AdocRange = AdocRange(starts[index], starts[index] + lines[index].length)

        /** Диапазон внутри строки — для конструкций уже строки, вроде callout. */
        fun inLine(index: Int, from: Int, to: Int): AdocRange = AdocRange(starts[index] + from, starts[index] + to)

        fun scan(): AdocScan = AdocBlockScanner.scan(text)
    }

    private fun listing(length: Int) = AdocBlockFrame(AdocBlockKind.Listing, length)
    private fun example(length: Int) = AdocBlockFrame(AdocBlockKind.Example, length)

    // endregion

    // region состояние блоков

    @Test
    fun TC_1_listingDelimiterOpensAndTheSameDelimiterCloses() {
        val src = Source(
            "before",
            "----",
            "code line",
            "----",
            "after",
        )

        // Оракул выписан целиком: важно не только то, что содержимое размечено,
        // но и то, что строка «after» не получила НИ ОДНОГО диапазона. Проверка
        // «содержит нужное» этого не поймала бы — блок, который не закрылся,
        // прошёл бы её.
        assertEquals(
            listOf(
                AdocSpan(src.line(1), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(2), AdocStyle.VerbatimContent),
                AdocSpan(src.line(3), AdocStyle.BlockDelimiter),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_2_longerDelimiterDoesNotCloseTheBlock() {
        val src = Source(
            "before",
            "----",
            "content",
            "-----",
            "still content",
            "----",
            "after",
        )
        val scan = src.scan()

        // Ловит реализацию, сравнивающую только символ ограничителя. Прогон
        // эталона подтверждает: пятидефисная строка остаётся содержимым.
        assertEquals(
            listOf(
                AdocSpan(src.line(1), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(2), AdocStyle.VerbatimContent),
                AdocSpan(src.line(3), AdocStyle.VerbatimContent),
                AdocSpan(src.line(4), AdocStyle.VerbatimContent),
                AdocSpan(src.line(5), AdocStyle.BlockDelimiter),
            ),
            scan.spans,
        )
        assertEquals(emptyList(), scan.blockStates[6], "блок должен был закрыться четырёхдефисной строкой")
    }

    @Test
    fun TC_3_nestedCompoundBlockOfTheSameKindKeepsTheStack() {
        val src = Source(
            "before", // 0
            "====", // 1  открывает example длины 4
            "outer", // 2
            "======", // 3  открывает ВЛОЖЕННЫЙ example длины 6
            "inner", // 4
            "======", // 5  закрывает вложенный: длина совпала
            "outer again", // 6
            "====", // 7  закрывает внешний
            "after", // 8
        )
        val scan = src.scan()

        // Смысл кейса — состояние должно быть стеком, а не флагом «внутри блока».
        // Флаг закрыл бы блок на строке 5 и оставил бы 6..8 снаружи.
        assertEquals(
            listOf(
                emptyList(),
                listOf(example(4)),
                listOf(example(4)),
                listOf(example(4), example(6)),
                listOf(example(4), example(6)),
                listOf(example(4)),
                listOf(example(4)),
                emptyList(),
                emptyList(),
            ),
            scan.blockStates,
        )
    }

    @Test
    fun TC_3_verbatimBlockDoesNotNestEvenOnADifferentDelimiterLength() {
        val src = Source(
            "----", // 0
            "outer", // 1
            "-------", // 2
            "inner", // 3
            "-------", // 4
            "outer again", // 5
            "----", // 6
            "after", // 7
        )
        val scan = src.scan()

        // Зеркало предыдущего кейса и единственное место, где буква `TC-3`
        // расходится с `FR-3`. Прогон эталона 2026-08-17: внутри дословного
        // listing семидефисные строки остаются содержимым, вложенный блок не
        // открывается, и весь блок закрывает первая же строка `----`.
        assertEquals(
            listOf(
                listOf(listing(4)),
                listOf(listing(4)),
                listOf(listing(4)),
                listOf(listing(4)),
                listOf(listing(4)),
                listOf(listing(4)),
                emptyList(),
                emptyList(),
            ),
            scan.blockStates,
        )
    }

    @Test
    fun TC_4_delimiterInsideCompoundBlockOpensNestedBlock() {
        val src = Source(
            "====", // 0
            "example content", // 1
            "----", // 2
            "listing inside example", // 3
            "----", // 4
            "more example", // 5
            "====", // 6
        )
        val scan = src.scan()

        assertEquals(
            listOf(
                listOf(example(4)),
                listOf(example(4)),
                listOf(example(4), listing(4)),
                listOf(example(4), listing(4)),
                listOf(example(4)),
                listOf(example(4)),
                emptyList(),
            ),
            scan.blockStates,
        )
        // И содержимое вложенного listing обязано быть дословным: составной
        // блок снаружи не отменяет `FR-3` внутри.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(2), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(3), AdocStyle.VerbatimContent),
                AdocSpan(src.line(4), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(6), AdocStyle.BlockDelimiter),
            ),
            scan.spans,
        )
    }

    @Test
    fun TC_5_unclosedBlockRunsToTheEndOfDocument() {
        val src = Source(
            "before",
            "----",
            "inside",
            "",
            "still inside even after a blank line",
            "== not a heading",
        )
        val scan = src.scan()

        // Решение владельца 2026-08-17 и поведение эталона: пустая строка блок
        // не закрывает, конец документа тоже. Всё ниже ограничителя — содержимое.
        // Пустая строка 3 диапазона не получает: он был бы нулевой длины и не
        // нёс бы ничего, кроме нагрузки на буфер, которую `FR-26` как раз считает.
        assertEquals(
            listOf(
                AdocSpan(src.line(1), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(2), AdocStyle.VerbatimContent),
                AdocSpan(src.line(4), AdocStyle.VerbatimContent),
                AdocSpan(src.line(5), AdocStyle.VerbatimContent),
            ),
            scan.spans,
        )
        assertEquals(listOf(listing(4)), scan.blockStates.last())
        assertTrue(scan.spans.none { it.range.length == 0 }, "диапазонов нулевой длины быть не должно")
    }

    @Test
    fun TC_6_dashRunLengthChangesMeaningNonMonotonically() {
        val src = Source(
            "-", // 0  абзац: одиночный дефис блока не открывает
            "", // 1
            "--", // 2  open-блок
            "open content", // 3
            "--", // 4
            "", // 5
            "---", // 6  тематический разрыв: блоком НЕ является
            "", // 7
            "----", // 8  listing
            "listing content", // 9
            "----", // 10
        )
        val scan = src.scan()

        // Шкала немонотонна, и это главная ловушка автомата: 1 — не блок,
        // 2 — блок, 3 — снова не блок, 4 и больше — снова блок. Реализация
        // «дефисов не меньше двух» проваливает строку 6.
        assertEquals(
            listOf(
                emptyList(),
                emptyList(),
                listOf(AdocBlockFrame(AdocBlockKind.Open, 2)),
                listOf(AdocBlockFrame(AdocBlockKind.Open, 2)),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                listOf(listing(4)),
                listOf(listing(4)),
                emptyList(),
            ),
            scan.blockStates,
        )

        // Разметка тематического разрыва — это `FR-15`, слайс `SL-2`.
        // Здесь проверяется ровно то, что автомат `SL-1` за него не цепляется.
        assertTrue(
            scan.spans.none { it.range == src.line(0) || it.range == src.line(6) },
            "строки `-` и `---` блочный автомат размечать не должен",
        )
    }

    @Test
    fun TC_8_verbatimContentIsNotParsedExceptCalloutAndInclude() {
        val src = Source(
            "----", // 0
            "*bold* {attr} // comment", // 1
            "code <1>", // 2
            "include::real.adoc[]", // 3
            "----", // 4
        )

        // Дословный блок — не «всё серым», а «серое с двумя исключениями»
        // (`FR-3`). Позиция callout взята из прогона эталона: он распознаётся
        // только в конце строки, `<4>` в середине остаётся текстом.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(1), AdocStyle.VerbatimContent),
                AdocSpan(src.line(2), AdocStyle.VerbatimContent),
                AdocSpan(src.inLine(2, 5, 8), AdocStyle.Callout),
                AdocSpan(src.line(3), AdocStyle.PreprocessorDirective),
                AdocSpan(src.line(4), AdocStyle.BlockDelimiter),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_8_calloutIsRecognizedOnlyAtTheEndOfLine() {
        val src = Source(
            "----",
            "text with <4> inside",
            "----",
        )

        // Прогон эталона: `<4>` в середине строки экранируется как текст, а не
        // становится callout. Без этой проверки наивное выражение подсветит его.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(1), AdocStyle.VerbatimContent),
                AdocSpan(src.line(2), AdocStyle.BlockDelimiter),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_8_commentBlockContentIsMarkedAsComment() {
        val src = Source(
            "before",
            "////",
            "comment *bold*",
            "////",
            "after",
        )

        assertEquals(
            listOf(
                AdocSpan(src.line(1), AdocStyle.BlockDelimiter),
                AdocSpan(src.line(2), AdocStyle.Comment),
                AdocSpan(src.line(3), AdocStyle.BlockDelimiter),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_2_everyDelimiterOfTheRequirementIsRecognized() {
        // Перечень `FR-2` целиком: пропуск одного ограничителя иначе всплывёт
        // только на реальном документе.
        val cases = mapOf(
            "////" to AdocBlockFrame(AdocBlockKind.Comment, 4),
            "====" to AdocBlockFrame(AdocBlockKind.Example, 4),
            "----" to AdocBlockFrame(AdocBlockKind.Listing, 4),
            "...." to AdocBlockFrame(AdocBlockKind.Literal, 4),
            "--" to AdocBlockFrame(AdocBlockKind.Open, 2),
            "****" to AdocBlockFrame(AdocBlockKind.Sidebar, 4),
            "____" to AdocBlockFrame(AdocBlockKind.Quote, 4),
            "++++" to AdocBlockFrame(AdocBlockKind.Passthrough, 4),
            // Длина таблицы считается по всей строке, вместе с ведущим `|`:
            // прогон эталона показал, что `|====` тоже открывает таблицу, то
            // есть длина у неё переменная, как у остальных типов.
            "|===" to AdocBlockFrame(AdocBlockKind.Table, 4),
        )
        for ((delimiter, frame) in cases) {
            val scan = AdocBlockScanner.scan("$delimiter\ncontent")
            assertEquals(listOf(frame), scan.blockStates[0], "ограничитель «$delimiter» не открыл блок")
        }
    }

    @Test
    fun TC_2_shortDelimiterRunDoesNotOpenABlock() {
        // Минимальная длина — четыре для всех, кроме open. Три знака блоком не
        // являются ни для одного типа.
        for (delimiter in listOf("///", "===", "...", "***", "___", "+++")) {
            val scan = AdocBlockScanner.scan("$delimiter\ncontent")
            assertEquals(emptyList(), scan.blockStates[0], "ограничитель «$delimiter» короче минимума, а блок открыт")
        }
    }

    // endregion

    // region поведение сканера

    @Test
    fun TC_29_scanLeavesTheBufferByteIdentical() {
        val text = Source(
            "= Заголовок",
            "----",
            "код <1>",
            "----",
            "*жирный* текст",
        ).text
        val before = text

        val scan = AdocBlockScanner.scan(text)

        // Первая половина проверки тривиальна на неизменяемой строке; вторая —
        // нет. Она ловит реализацию, которая понесла бы в диапазонах *свой*
        // текст (например, с вырезанными ограничителями): тогда подстрока
        // исходника под диапазоном разошлась бы с тем, что диапазон описывает,
        // и копирование из редактора вернуло бы не то, что набрано (`FR-24`).
        assertEquals(before, text, "сканер изменил исходный текст")
        for (span in scan.spans) {
            assertTrue(
                span.range.endExclusive <= text.length,
                "диапазон ${span.range} выходит за пределы текста длиной ${text.length}",
            )
            assertTrue(
                text.substring(span.range.start, span.range.endExclusive).none { it == '\n' },
                "диапазон ${span.range} пересекает перевод строки: блочный автомат работает построчно",
            )
        }
    }

    @Test
    fun TC_30_outputIsBuiltFromPlainKotlinValues() {
        val src = Source("----", "код", "----")

        // Значение стороны проверки `TC-30`: ожидаемый результат целиком собран
        // из `Int` и перечислений, без единого типа Compose. Если в модель
        // приедет `SpanStyle` или `TextRange`, этот тест перестанет
        // компилироваться — то есть проверка держится на типах, а не на утверждении.
        // Сторону «в исходниках нет импорта androidx.compose» проверяет задача
        // сборки verifyHighlightIsPlatformNeutral: состав файлов из commonTest не виден.
        val expected = AdocScan(
            spans = listOf(
                AdocSpan(AdocRange(0, 4), AdocStyle.BlockDelimiter),
                AdocSpan(AdocRange(5, 8), AdocStyle.VerbatimContent),
                AdocSpan(AdocRange(9, 13), AdocStyle.BlockDelimiter),
            ),
            blockStates = listOf(
                listOf(AdocBlockFrame(AdocBlockKind.Listing, 4)),
                listOf(AdocBlockFrame(AdocBlockKind.Listing, 4)),
                emptyList(),
            ),
        )
        assertEquals(expected, src.scan())
    }

    @Test
    fun TC_32_editInsideUnclosedBlockChangesStateOfTheWholeRemainder() {
        val tail = List(20) { "строка $it" }
        val before = Source("начало", "---", *tail.toTypedArray())
        val after = Source("начало", "----", *tail.toTypedArray())

        val statesBefore = before.scan().blockStates
        val stateAfter = after.scan().blockStates

        // Худший случай инкрементальности из `UC-4`: добавлен один символ, а
        // состояние изменилось у каждой строки до конца документа. Оракул —
        // ЧИСЛО строк, чьё состояние разошлось, а не время: он показывает
        // границы участка, который придётся пересканировать в `SL-5`.
        val changed = statesBefore.indices.count { statesBefore[it] != stateAfter[it] }
        assertEquals(
            statesBefore.size - 1,
            changed,
            "правка должна была изменить состояние всех строк ниже, кроме первой",
        )
        assertEquals(emptyList(), statesBefore.last(), "три дефиса блока не открывают")
        assertEquals(listOf(listing(4)), stateAfter.last(), "четыре дефиса открывают блок до конца документа")
    }

    @Test
    fun TC_29_emptyDocumentScansWithoutError() {
        // Расширение `UC-1`: пустой документ даёт пустой список диапазонов и не
        // падает. Дешёвая проверка, но именно её обычно и нет.
        val scan = AdocBlockScanner.scan("")
        assertEquals(emptyList(), scan.spans)
        assertEquals(listOf(emptyList()), scan.blockStates, "у пустого документа одна пустая строка")
    }

    @Test
    fun TC_29_carriageReturnsDoNotShiftRanges() {
        // Документ пользователя может прийти с CRLF, и смещения обязаны
        // считаться по исходнику как есть, иначе подсветка уедет на символ в
        // строке — тем сильнее, чем ниже строка.
        val text = "before\r\n----\r\ncode\r\n----\r\nafter"
        val scan = AdocBlockScanner.scan(text)

        assertEquals(
            listOf(
                AdocSpan(AdocRange(8, 12), AdocStyle.BlockDelimiter),
                AdocSpan(AdocRange(14, 18), AdocStyle.VerbatimContent),
                AdocSpan(AdocRange(20, 24), AdocStyle.BlockDelimiter),
            ),
            scan.spans,
        )
    }

    // endregion
}
