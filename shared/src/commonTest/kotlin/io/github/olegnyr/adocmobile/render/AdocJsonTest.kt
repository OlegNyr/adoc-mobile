package io.github.olegnyr.adocmobile.render

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Критерии приёмки фичи 003-render-preview, слайс `SL-1` — граница с JS.
 *
 * `TC-5` в спеке сформулирован с оракулом «исходник, вернувшийся из движка
 * обратно, побайтово равен переданному», но помечен `automatable`: движок для
 * этой проверки не нужен, потому что проверяется не движок, а *литерал*, который
 * мы кладём в скрипт. Поэтому оракул здесь двойной:
 *
 * * для каждого класса символов из `FR-5` выписано ожидаемое экранирование —
 *   руками, по грамматике JSON, а не снятое с реализации;
 * * плюс круговой прогон через независимый разборщик, написанный в этом же файле
 *   (`decodeJsonString`). Он реализует обратное преобразование по той же
 *   грамматике и ничего не знает об устройстве `jsonStringLiteral`.
 *
 * Разборщик в тесте — не дублирование продукта: продукту обратное преобразование
 * не нужно вовсе, разбором литерала занимается сам JS-движок.
 */
class AdocJsonTest {

    private fun q(value: String): String = jsonStringLiteral(value)

    // region классы символов из FR-5

    @Test
    fun TC_5_quoteAndBackslashAreEscaped() {
        // Два символа, ломающие литерал напрямую: закрывающая кавычка и обратный
        // слэш, который иначе съел бы следующий за ним символ.
        assertEquals(""""он сказал \"да\" и \\ушёл"""", q("""он сказал "да" и \ушёл"""))
    }

    @Test
    fun TC_5_bothLineEndingConventionsSurvive() {
        // CRLF и LF — разные переводы строк, и оба обязаны дойти до движка
        // неизменными: в AsciiDoc пустая строка значима.
        assertEquals(""""a\r\nb\nc"""", q("a\r\nb\nc"))
    }

    @Test
    fun TC_5_controlCharactersBecomeEscapes() {
        // Управляющие символы грамматика JSON внутри литерала запрещает. У части
        // из них есть короткая форма, у остальных остаётся только \u.
        val source = "a\tb\u0001c\bd\u000Ce"
        assertEquals(""""a\tb\u0001c\bd\fe"""", q(source))
    }

    @Test
    fun TC_5_cyrillicPassesThroughUnescaped() {
        // Кириллицу экранировать нечем и незачем: строка уходит в движок текстом,
        // а не ASCII-потоком. Экранирование только раздуло бы литерал вчетверо.
        assertEquals(""""Привет, мир"""", q("Привет, мир"))
    }

    @Test
    fun TC_5_astralCharacterKeepsItsSurrogatePair() {
        // Символ вне BMP в UTF-16 — суррогатная пара; она должна пройти целиком.
        assertEquals("\"\uD83D\uDE80\"", q("\uD83D\uDE80"))
    }

    @Test
    fun TC_5_lineSeparatorsAreEscaped() {
        // U+2028 и U+2029 — самый неочевидный пункт FR-5: в JSON это обычные
        // символы, а в JavaScript они исторически завершают строку. Литерал,
        // собранный строго «по JSON», ломает разбор скрипта именно на них.
        assertEquals(""""a\u2028b\u2029c"""", q("a\u2028b\u2029c"))
    }

    @Test
    fun TC_5_unpairedSurrogateIsEscaped() {
        // Одинокий суррогат в UTF-8 непредставим: передать его через границу
        // символом значит либо испортить текст, либо уронить конвертер кодировок.
        // Экранированная форма хотя бы оставляет скрипт разбираемым.
        assertEquals(""""a\ud800b"""", q("a\uD800b"))
    }

    // endregion

    // region круговой прогон

    @Test
    fun TC_5_roundTripReturnsTheSourceUnchanged() {
        val source = buildString {
            append("= Заголовок \"в кавычках\"\n")
            append(":attr: c:\\путь\\файл\n")
            append("\r\nАбзац с эмодзи \uD83D\uDE80 и разделителем\u2028строк.\n")
            append("\tотступ табуляцией\u0001\n")
        }

        assertEquals(source, decodeJsonString(q(source)))
    }

    // endregion

    /**
     * Независимый разбор литерала JSON обратно в строку.
     *
     * Написан по грамматике, а не по реализации: узнаёт только те escape-формы,
     * которые грамматика разрешает, и падает на всех остальных. Это и делает его
     * оракулом — изобрети `jsonStringLiteral` свою форму экранирования, тест
     * упадёт здесь, а не пройдёт молча.
     */
    private fun decodeJsonString(literal: String): String {
        require(literal.length >= 2 && literal.first() == '"' && literal.last() == '"') {
            "литерал не обёрнут в кавычки: $literal"
        }
        val body = literal.substring(1, literal.length - 1)
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                require(c != '"') { "неэкранированная кавычка внутри литерала" }
                out.append(c)
                i++
                continue
            }
            i++
            when (val escaped = body[i]) {
                '"', '\\', '/' -> out.append(escaped)
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    out.append(body.substring(i + 1, i + 5).toInt(16).toChar())
                    i += 4
                }
                else -> throw IllegalArgumentException("недопустимая escape-форма \\$escaped")
            }
            i++
        }
        return out.toString()
    }
}
