package io.github.olegnyr.adocmobile.highlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 001-syntax-highlighting, слайс `SL-5` — инкрементальное
 * сканирование (`FR-27`).
 *
 * Оракул везде — *число просканированных строк* и совпадение результата с
 * полным проходом, никогда не время: `TC-31` требует ровно этого, а честные
 * миллисекунды для `TC-34`…`TC-36` требуют `kotlinx-benchmark` — новой
 * зависимости, которую слайс не добавляет (решение владельца). Здесь пороги
 * проверяются структурно: работа пропорциональна правке, а не документу.
 *
 * Совпадение с полным проходом — главный страж: любой дефект склейки головы,
 * пересканированной середины и хвоста немедленно его валит.
 */
class AdocIncrementalHighlighterTest {

    /** Полный проход как эталон: инкрементальный результат обязан совпасть побайтно. */
    private fun assertMatchesFullScan(highlighter: AdocIncrementalHighlighter, text: String) {
        assertEquals(
            AdocBlockScanner.scan(text),
            highlighter.update(text),
            "инкрементальный проход разошёлся с полным",
        )
    }

    private fun document(vararg lines: String): String = lines.joinToString("\n")

    // region TC-31 — пересканируется участок, а не документ

    @Test
    fun TC_31_editRescansOnlyTheEnclosedRegion() {
        val before = document(
            "= Документ", // 0
            "", // 1
            "первый абзац", // 2
            "строка абзаца", // 3
            "", // 4
            "второй абзац", // 5
            "ещё строка", // 6
            "", // 7
            "третий абзац", // 8
            "хвост", // 9
        )
        val after = before.replace("ещё строка", "ещё строкаX")

        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(before)
        assertMatchesFullScan(highlighter, after)

        // Рестарт — строка 5 (над ней пустая 4), сходимость — строка 8 (над ней
        // пустая 7, стек совпал). Просканированы строки 5, 6, 7 — ровно три.
        assertEquals(3, highlighter.lastScannedLines, "участок шире, чем требует правка")
    }

    @Test
    fun TC_31_editInsideLogicalLineRescansTheWholeLogicalLine() {
        val before = document(
            "просто абзац", // 0
            "", // 1
            "перед *жирный", // 2
            "текст* дальше", // 3
            "", // 4
            "после", // 5
        )
        // Правка второй строки пары: инлайн-диапазон начинается строкой выше,
        // и рестарт обязан подняться до начала логической строки.
        val after = before.replace("текст* дальше", "текст* дальшеX")

        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(before)
        assertMatchesFullScan(highlighter, after)
        assertEquals(3, highlighter.lastScannedLines, "логическая строка не пересканирована целиком")
    }

    @Test
    fun TC_31_insertionAndDeletionOfLinesStayIncremental() {
        val base = document(
            "первый", // 0
            "", // 1
            "второй", // 2
            "", // 3
            "третий", // 4
            "", // 5
            "четвёртый", // 6
        )
        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(base)

        // Вставка новой строки в абзац: строки ниже сдвигаются, хвост
        // переиспользуется со сдвигом смещений.
        val inserted = base.replace("второй", "второй\nдобавленная")
        assertMatchesFullScan(highlighter, inserted)
        assertTrue(highlighter.lastScannedLines <= 4, "вставка пересканировала ${highlighter.lastScannedLines} строк")

        // Удаление той же строки — обратный сдвиг.
        assertMatchesFullScan(highlighter, base)
        assertTrue(highlighter.lastScannedLines <= 4, "удаление пересканировало ${highlighter.lastScannedLines} строк")
    }

    @Test
    fun TC_31_attributeContinuationChainRescansFromItsStart() {
        val before = document(
            "", // 0
            ":multi: первая часть \\", // 1
            "вторая часть \\", // 2
            "третья часть", // 3
            "", // 4
            "абзац", // 5
        )
        // Перенос значения атрибута — многострочное состояние без пустых строк
        // внутри: рестарт обязан подняться к началу цепочки.
        val after = before.replace("третья часть", "третья частьX")

        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(before)
        assertMatchesFullScan(highlighter, after)
        assertEquals(4, highlighter.lastScannedLines, "цепочка переноса не пересканирована с начала")
    }

    @Test
    fun TC_31_crlfDocumentStaysIncrementalAndCorrect() {
        val before = "первый\r\n\r\nвторой\r\nстрока\r\n\r\nтретий"
        val after = before.replace("строка", "строкаX")

        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(before)
        assertMatchesFullScan(highlighter, after)
        assertEquals(3, highlighter.lastScannedLines)
    }

    // endregion

    // region TC-32 — незакрытый блок пересканирует остаток, и это ожидаемо

    @Test
    fun TC_32_editInsideUnclosedBlockRescansTheRemainder() {
        val lines = buildList {
            repeat(10) {
                add("абзац $it")
                add("")
            }
            add("----")
            repeat(10) { add("код $it") }
        }
        val before = lines.joinToString("\n")
        val after = before.replace("код 5", "код 5X")

        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(before)
        assertMatchesFullScan(highlighter, after)

        // Правка в незакрытом listing: сходимости нет — старые и новые стеки
        // внутри блока никогда не «пустые», а рестарт — последняя чистая
        // граница над правкой. Пересканирован остаток документа.
        assertTrue(
            highlighter.lastScannedLines >= 7,
            "незакрытый блок обязан пересканировать остаток, а не ${highlighter.lastScannedLines} строк",
        )
    }

    // endregion

    // region TC-34…TC-36 — пороги, проверяемые структурно

    private val thousandLines: List<String> = buildList {
        repeat(333) {
            add("абзац $it со *звёздочкой*")
            add("вторая строка $it")
            add("")
        }
    }

    @Test
    fun TC_34_coldFullScanVisitsEveryLineExactlyOnce() {
        // Прокси холодного прохода: работа линейна — каждая строка посещается
        // ровно один раз, повторный проход того же текста не сканирует ничего.
        // Честный порог «< 100 мс на ~1000 строк» требует kotlinx-benchmark —
        // новая зависимость, решение владельца; здесь проверяется линейность.
        val text = thousandLines.joinToString("\n")
        val highlighter = AdocIncrementalHighlighter()

        highlighter.update(text)
        assertEquals(thousandLines.size, highlighter.lastScannedLines)

        highlighter.update(text)
        assertEquals(0, highlighter.lastScannedLines, "неизменный текст не сканируется вовсе")
    }

    @Test
    fun TC_35_singleEditCostDoesNotGrowWithTheDocument() {
        // Прокси P95 < 16 мс: стоимость правки — константа малых строк, а не
        // доля документа. Три строки — абзац правки и пустая строка до сходимости.
        val text = thousandLines.joinToString("\n")
        val edited = text.replace("вторая строка 200", "вторая строка 200X")

        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(text)
        assertMatchesFullScan(highlighter, edited)
        assertEquals(3, highlighter.lastScannedLines, "правка одного символа стоит больше своего абзаца")
    }

    @Test
    fun TC_36_worstCaseDashSequenceRescansTheTailAndStaysCorrect() {
        // Худший случай UC-4: набор `-`, `--`, `---`, `----` в пустой строке
        // середины документа. Каждый шаг меняет смысл строки, `--` и `----`
        // открывают блок до конца документа — пересканирование остатка на этих
        // шагах ожидаемо (`FR-4`), важна корректность и то, что «дешёвые» шаги
        // остаются дешёвыми.
        val lines = buildList {
            repeat(100) {
                add("текст $it")
                add("")
            }
        }
        val base = lines.joinToString("\n")
        val editedLine = 99 // пустая строка в середине: 49 абзацев выше, 50 ниже
        val totalLines = lines.size

        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(base)

        for (dashes in listOf("-", "--", "---", "----")) {
            val next = lines.toMutableList().also { it[editedLine] = dashes }.joinToString("\n")
            assertEquals(
                AdocBlockScanner.scan(next),
                highlighter.update(next),
                "шаг «$dashes» разошёлся с полным проходом",
            )
            if (dashes == "-") {
                // Одиночный дефис — абзац: сходимость за несколько строк.
                assertTrue(
                    highlighter.lastScannedLines <= 5,
                    "шаг «$dashes» пересканировал ${highlighter.lastScannedLines} строк",
                )
            } else {
                // `--` и `----` открывают блок до конца документа; `---` меняет
                // хвост обратно из open-блока в абзацы — старые и новые стеки
                // ниже правки не совпадают ни на одном шаге, пересканирован
                // остаток от чистой границы над правкой. Это ожидаемая
                // деградация UC-4, и оракул фиксирует её точным числом.
                assertEquals(
                    totalLines - (editedLine - 1),
                    highlighter.lastScannedLines,
                    "шаг «$dashes» обязан пересканировать остаток",
                )
            }
        }
    }

    // endregion
}
