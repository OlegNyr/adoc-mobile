package io.github.olegnyr.adocmobile.highlight

/**
 * Документ теста, помнящий, где начинается каждая строка.
 *
 * Диапазоны сканера считаются в смещениях по всему исходнику, а тест-кейсы
 * сформулированы в строках. Пересчёт вынесен сюда, чтобы ожидаемые значения в
 * тестах читались как «строка 2 целиком», а не как «символы 17..28» — иначе
 * правка одного символа в документе теста ломает все ожидания разом.
 */
internal class HighlightSource(vararg lines: String) {
    private val lines: List<String> = lines.toList()
    val text: String = this.lines.joinToString("\n")

    private val starts: List<Int> = buildList {
        var offset = 0
        for (line in this@HighlightSource.lines) {
            add(offset)
            offset += line.length + 1
        }
    }

    /** Диапазон всей строки, без перевода строки. */
    fun line(index: Int): AdocRange = AdocRange(starts[index], starts[index] + lines[index].length)

    /** Диапазон внутри строки — для конструкций уже строки: callout, маркер списка, метка admonition. */
    fun inLine(index: Int, from: Int, to: Int): AdocRange = AdocRange(starts[index] + from, starts[index] + to)

    fun scan(): AdocScan = AdocBlockScanner.scan(text)

    /** Диапазоны, попавшие в строку. Удобно, когда кейс говорит об одной строке из многих. */
    fun spansOf(index: Int): List<AdocSpan> {
        val range = line(index)
        return scan().spans.filter { it.range.start >= range.start && it.range.endExclusive <= range.endExclusive }
    }
}
