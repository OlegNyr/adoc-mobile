package io.github.olegnyr.adocmobile.document

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Критерии приёмки фичи 004-document-and-files, слайс SL-1: `TC-6`…`TC-9`.
 *
 * Требование `FR-4`: тип переводов строк определяется при чтении и хранится в
 * модели, а не выводится из текста при записи. `FR-5`: при записи исходный тип
 * восстанавливается — файл с CRLF не переписывается в LF.
 */
class LineEndingTest {

    @Test
    fun TC_6_crlfFileSurvivesReadEditWrite() {
        val raw = "= Заголовок\r\n\r\nАбзац.\r\n"

        val ending = LineEnding.detect(raw)
        val text = raw.toEditorText()
        val written = text.toFileText(ending)

        assertEquals(LineEnding.Crlf, ending)
        assertEquals("= Заголовок\n\nАбзац.\n", text, "в редакторе текст всегда с LF")
        assertEquals(raw, written, "запись обязана вернуть исходный вид")
    }

    @Test
    fun TC_7_lfFileStaysLf() {
        val raw = "= Заголовок\n\nАбзац.\n"

        assertEquals(LineEnding.Lf, LineEnding.detect(raw))
        assertEquals(raw, raw.toEditorText().toFileText(LineEnding.Lf))
    }

    @Test
    fun TC_9a_crOnlyFileStaysCr() {
        // Старый macOS-стиль. Встречается редко, но переписать такой файл в LF
        // значит поменять пользователю каждую строку — то есть весь диф.
        val raw = "= Заголовок\r\rАбзац.\r"

        assertEquals(LineEnding.Cr, LineEnding.detect(raw))
        assertEquals(raw, raw.toEditorText().toFileText(LineEnding.Cr))
    }

    @Test
    fun TC_8_typeComesFromModelNotFromText() {
        // Пользователь удалил все переводы строк. Вывести тип из текста уже
        // невозможно — именно поэтому он хранится в модели (FR-4).
        val ending = LineEnding.detect("строка\r\nещё\r\n")
        val editedToSingleLine = "теперь одна строка без переводов"

        assertEquals(
            editedToSingleLine,
            editedToSingleLine.toFileText(ending),
            "нечего переводить — текст остаётся как есть",
        )
        assertEquals(
            "теперь одна\r\nстрока",
            "теперь одна\nстрока".toFileText(ending),
            "как только перевод строки появился, применяется сохранённый тип",
        )
    }

    @Test
    fun TC_9b_emptyFileFallsBackToLf() {
        // У пустого файла типа переводов строк нет. Отказывать нельзя, гадать
        // тоже: берём LF как самый нейтральный и не мешающий Git.
        assertEquals(LineEnding.Lf, LineEnding.detect(""))
        assertEquals(LineEnding.Lf, LineEnding.detect("одна строка без перевода"))
    }

    @Test
    fun TC_9c_mixedEndingsResolveToFirstEncountered() {
        // Смешанный файл — не редкость после правки разными инструментами.
        // Берём первый встреченный тип: это то, чем файл начинали, и любое
        // другое правило было бы столь же произвольным, но менее предсказуемым.
        assertEquals(LineEnding.Crlf, LineEnding.detect("первая\r\nвторая\nтретья\n"))
        assertEquals(LineEnding.Lf, LineEnding.detect("первая\nвторая\r\n"))
    }

    @Test
    fun TC_9c_ambiguousNeighbourhoodsResolveCorrectly() {
        // Три входа, на которых арифметика по индексам ломается первой, если
        // detect однажды перепишут. Сейчас все три верны — это незакрытая
        // граница, а не дефект: ветка «первым встретился перевод строки» не
        // исполнялась ни одним тестом.
        assertEquals(LineEnding.Cr, LineEnding.detect("\r\r\n"), "первый возврат каретки одиночный")
        assertEquals(LineEnding.Lf, LineEnding.detect("\n\r"), "первым встретился перевод строки")
        assertEquals(LineEnding.Cr, LineEnding.detect("a\rb\n"), "возврат каретки раньше перевода строки")
        assertEquals(LineEnding.Crlf, LineEnding.detect("\r\n\r"))
    }

    @Test
    fun TC_9c_mixedFileIsNormalisedToOneKindOnWrite() {
        // FR-5a: смешанный файл приводится к одному типу, и строки, которых
        // пользователь не касался, тоже меняют окончание. Решение записано в
        // спеке; тест закрепляет его, чтобы оно не превратилось в сюрприз.
        val raw = "= Заголовок\r\n\r\nАбзац.\nЕщё.\r\n"
        val ending = LineEnding.detect(raw)

        assertEquals(LineEnding.Crlf, ending)
        assertEquals(
            "= Заголовок\r\n\r\nАбзац.\r\nЕщё.\r\n",
            raw.toEditorText().toFileText(ending),
            "весь файл приводится к первому встреченному типу",
        )
    }

    @Test
    fun TC_9_unicodeSurvivesRoundTrip() {
        // Кириллица, эмодзи и составной символ (е + комбинирующая диакритика).
        // Проверка сторожит не сам код нормализации, а будущие его правки:
        // как только замена переводов строк станет умнее посимвольной, риск
        // разрубить суррогатную пару или разорвать составной символ появится.
        val raw = "Привет, мир! 🚀\r\nСоставной: é\r\nЭмодзи-семья: 👨‍👩‍👧‍👦\r\n"

        val ending = LineEnding.detect(raw)
        val text = raw.toEditorText()

        assertEquals(raw, text.toFileText(ending))
        assertEquals(raw.replace("\r\n", "\n"), text)
    }

    @Test
    fun TC_9d_loneCarriageReturnInsideCrlfFileIsNotDoubled() {
        // Ловушка наивной реализации «заменить \n на \r\n»: одиночный \r,
        // оставшийся в тексте, превратился бы в \r\r\n.
        val text = "строка\nещё"

        assertEquals("строка\r\nещё", text.toFileText(LineEnding.Crlf))
        assertEquals(
            "строка\r\nещё",
            "строка\r\nещё".toEditorText().toFileText(LineEnding.Crlf),
            "повторное применение не должно удваивать возврат каретки",
        )
    }
}
