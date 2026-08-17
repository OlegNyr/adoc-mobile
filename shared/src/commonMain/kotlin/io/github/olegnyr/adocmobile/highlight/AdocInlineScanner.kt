package io.github.olegnyr.adocmobile.highlight

/**
 * Инлайн-проход — слайс `SL-3` фичи 001-syntax-highlighting: passthrough,
 * constrained/unconstrained-форматирование и экранирование (`FR-17`…`FR-20`).
 *
 * Единица разбора — *логическая строка блока* (`FR-17`): текст приходит сюда уже
 * склеенным из смежных непустых строк, и `\n` внутри него — обычный пробельный
 * символ, через который пара имеет право пересекаться.
 *
 * Порядок проходов — часть требований, а не удобство реализации:
 *
 * . passthrough идёт первым (`FR-18`) — иначе проход форматирования ошибётся на
 *   `+*не жирный*+`; внутри passthrough более длинный ограничитель идёт раньше
 *   более короткого, чтобы `+++` не был съеден как `++` с хвостом;
 * . пары форматирования идут в порядке группы `quotes` эталона: полужирный,
 *   моноширинный, курсив, выделение, затем над- и подстрочный. Типографские
 *   кавычки, стоящие в эталоне между полужирным и моноширинным, вынесены в
 *   не-цели решением `FR-23`, поэтому моноширинный оказывается первым из
 *   «кавычечных» и `"`код`"` размечается как моноширинный в обычных кавычках;
 * . экранирование (`FR-20`) проверяется в каждом проходе по числу слэшей перед
 *   знаком: одиночному знаку хватает одного, сдвоенному нужны два. Эталон
 *   смотрит только на открывающий знак — экранировать закрывающий нельзя.
 *
 * Условия constrained-пары взяты из поведения эталона, а не из прозы
 * документации, — прогон Asciidoctor 2.0.26 от 2026-08-17 показал, что проза
 * строже реализации (`(*скобки*)` даёт полужирный, хотя документация требует
 * слева пробел). Все спорные места этого файла сняты тем же прогоном.
 */
internal object AdocInlineScanner {

    /**
     * Размечает логическую строку [text], отдавая каждую конструкцию в [emit]
     * в координатах [text]; отображение в исходник — забота вызывающего.
     */
    fun scan(text: String, emit: (start: Int, endExclusive: Int, style: AdocStyle) -> Unit) {
        if (text.length < MIN_CONSTRUCT_LENGTH) return
        val consumed = BooleanArray(text.length)

        scanTriplePassthrough(text, consumed, emit)
        scanPassMacro(text, consumed, emit)
        scanUnconstrained(text, PASS_MARK, AdocStyle.InlinePassthrough, opaque = true, consumed, emit)
        scanConstrained(text, PASS_MARK, AdocStyle.InlinePassthrough, opaque = true, consumed, emit)

        for ((mark, style) in QUOTE_ORDER) {
            scanUnconstrained(text, mark, style, opaque = false, consumed, emit)
            scanConstrained(text, mark, style, opaque = false, consumed, emit)
        }

        scanContiguous(text, '^', AdocStyle.Superscript, consumed, emit)
        scanContiguous(text, '~', AdocStyle.Subscript, consumed, emit)
    }

    // region passthrough — FR-18

    /**
     * `+++текст+++` — passthrough без единой подстановки.
     *
     * Содержимое — любые символы до ближайшей тройки плюсов; пустое допустимо,
     * как допускает его эталон.
     */
    private fun scanTriplePassthrough(text: String, consumed: BooleanArray, emit: Emit) {
        var i = text.indexOf(TRIPLE_PLUS)
        while (i >= 0) {
            if (backslashesBefore(text, i) > 0) {
                i = text.indexOf(TRIPLE_PLUS, i + 1)
                continue
            }
            val close = text.indexOf(TRIPLE_PLUS, i + TRIPLE_PLUS.length)
            if (close < 0) return
            val end = close + TRIPLE_PLUS.length
            emit(i, end, AdocStyle.InlinePassthrough)
            consume(consumed, i, end)
            i = text.indexOf(TRIPLE_PLUS, end)
        }
    }

    /**
     * `pass:[текст]` и форма со списком подстановок `pass:c,q[текст]`.
     *
     * Граница слова перед именем НЕ требуется: прогон эталона 2026-08-17
     * показал, что `bypass:[*x*]` содержит работающий passthrough — «осторожная»
     * проверка границы недоподсветила бы относительно эталона (`FR-28`).
     * Закрывает конструкцию первая неэкранированная `]`.
     */
    private fun scanPassMacro(text: String, consumed: BooleanArray, emit: Emit) {
        var i = text.indexOf(PASS_MACRO)
        while (i >= 0) {
            if (consumed[i] || backslashesBefore(text, i) > 0) {
                i = text.indexOf(PASS_MACRO, i + 1)
                continue
            }
            var cursor = i + PASS_MACRO.length
            while (cursor < text.length && text[cursor].isSubsListChar()) cursor++
            if (cursor >= text.length || text[cursor] != '[') {
                i = text.indexOf(PASS_MACRO, i + 1)
                continue
            }
            var close = cursor + 1
            while (close < text.length && !(text[close] == ']' && text[close - 1] != '\\')) close++
            if (close >= text.length) {
                i = text.indexOf(PASS_MACRO, i + 1)
                continue
            }
            emit(i, close + 1, AdocStyle.InlinePassthrough)
            consume(consumed, i, close + 1)
            i = text.indexOf(PASS_MACRO, close + 1)
        }
    }

    // endregion

    // region пары форматирования — FR-19

    /**
     * Unconstrained-пара: сдвоенный знак, работающий в любой позиции.
     *
     * Содержимое — любые символы, нежадно, *без ограничений на края*: прогон
     * эталона (`TC-20`) дал `<em>kernel qualifier is </em>` с пробелом в конце
     * содержимого. Требование непробельных краёв здесь недоподсветило бы
     * относительно эталона (`FR-28`). Экранирование — два слэша (`FR-20`):
     * одного мало, и `\__не курсив__` курсив даёт.
     */
    private fun scanUnconstrained(
        text: String,
        mark: Char,
        style: AdocStyle,
        opaque: Boolean,
        consumed: BooleanArray,
        emit: Emit,
    ) {
        val last = text.length - 1
        var i = 0
        while (i < last) {
            if (text[i] != mark || text[i + 1] != mark || consumed[i] || consumed[i + 1]) {
                i++
                continue
            }
            if (backslashesBefore(text, i) >= DOUBLED_ESCAPE) {
                i += 2
                continue
            }

            // Закрывающая пара — ближайшая, содержимое минимум один символ.
            var close = -1
            var j = i + 4
            while (j < last + 1) {
                if (text[j - 1] == mark && text[j] == mark && !consumed[j - 1] && !consumed[j]) {
                    close = j - 1
                    break
                }
                j++
            }
            if (close < 0) {
                i += 2
                continue
            }

            val prefix = attributePrefixStart(text, i, consumed)
            val start = if (prefix >= 0) prefix else i
            emit(start, close + 2, style)
            if (opaque) {
                consume(consumed, start, close + 2)
            } else {
                consume(consumed, start, i + 2)
                consume(consumed, close, close + 2)
            }
            i = close + 2
        }
    }

    /**
     * Constrained-пара: одиночный знак с условием на соседние символы.
     *
     * Условия — поведение эталона, снятое прогоном, а не проза документации:
     *
     * * слева от открывающего знака — начало логической строки либо символ не из
     *   класса «буква, цифра, `_`» и не из `;`, `:`, `}` — поэтому
     *   `foo*bar*baz` пары не даёт (`TC-19`), а `(*скобки*)` даёт;
     * * содержимое не начинается и не кончается пробельным символом;
     * * справа от закрывающего знака — не буква, не цифра и не `_`; закрывающий
     *   знак, нарушивший это, пропускается, и поиск идёт дальше — так
     *   `*a*b*` даёт полужирный `*a*b*` целиком, как у эталона.
     *
     * Необязательный префикс атрибутов `[.role]` входит в диапазон конструкции,
     * как входит он в конструкцию эталона; если символ перед `[` не пускает
     * пару, эталон отступает к форме без префикса (`x[1]*b*` даёт полужирный
     * без него), и сканер делает то же.
     */
    private fun scanConstrained(
        text: String,
        mark: Char,
        style: AdocStyle,
        opaque: Boolean,
        consumed: BooleanArray,
        emit: Emit,
    ) {
        val length = text.length
        var i = 0
        while (i < length - 2) {
            if (text[i] != mark || consumed[i]) {
                i++
                continue
            }
            if (backslashesBefore(text, i) > 0) {
                i++
                continue
            }

            var start = i
            val prefix = attributePrefixStart(text, i, consumed)
            if (prefix >= 0 && allowsConstrainedOpen(text, prefix - 1)) {
                start = prefix
            } else if (!allowsConstrainedOpen(text, i - 1)) {
                i++
                continue
            }
            if (text[i + 1].isWhitespace()) {
                i++
                continue
            }

            var close = -1
            var j = i + 2
            while (j < length) {
                if (text[j] == mark && !consumed[j] && !text[j - 1].isWhitespace() &&
                    (j + 1 >= length || !text[j + 1].isWordChar())
                ) {
                    close = j
                    break
                }
                j++
            }
            if (close < 0) {
                i++
                continue
            }

            emit(start, close + 1, style)
            if (opaque) {
                consume(consumed, start, close + 1)
            } else {
                consume(consumed, start, i + 1)
                consumed[close] = true
            }
            i = close + 1
        }
    }

    /**
     * Надстрочный и подстрочный — «ни constrained, ни unconstrained»: границы
     * без условий на соседей, но текст обязан быть непрерывным, без пробельных
     * символов (`FR-19`, `TC-28`). Прогон эталона: `~/projects/~` подстрочный
     * даёт — это бытовое срабатывание эталон разделяет, значит разделяет и
     * сканер (`FR-28`).
     */
    private fun scanContiguous(
        text: String,
        mark: Char,
        style: AdocStyle,
        consumed: BooleanArray,
        emit: Emit,
    ) {
        val length = text.length
        var i = 0
        while (i < length - 2) {
            if (text[i] != mark || consumed[i] || backslashesBefore(text, i) > 0) {
                i++
                continue
            }
            var j = i + 1
            while (j < length && text[j] != mark && !text[j].isWhitespace()) j++
            if (j < length && j > i + 1 && text[j] == mark && !consumed[j]) {
                emit(i, j + 1, style)
                consumed[i] = true
                consumed[j] = true
                i = j + 1
            } else {
                i++
            }
        }
    }

    // endregion

    // region вспомогательное

    /**
     * Индекс `[` префикса атрибутов, стоящего вплотную перед знаком, или -1.
     * Содержимое скобок непустое, без вложенных скобок и перевода строки, и не
     * занято более ранним проходом.
     */
    private fun attributePrefixStart(text: String, markIndex: Int, consumed: BooleanArray): Int {
        if (markIndex < MIN_PREFIX_LENGTH || text[markIndex - 1] != ']') return -1
        var i = markIndex - 2
        while (i >= 0) {
            val symbol = text[i]
            if (symbol == '[') break
            if (symbol == ']' || symbol == '\n') return -1
            i--
        }
        if (i < 0 || text[i] != '[' || i == markIndex - 2) return -1
        for (position in i until markIndex) if (consumed[position]) return -1
        return i
    }

    /** Число слэшей, стоящих вплотную перед позицией [index] (`FR-20`). */
    private fun backslashesBefore(text: String, index: Int): Int {
        var count = 0
        var i = index - 1
        while (i >= 0 && text[i] == '\\') {
            count++
            i--
        }
        return count
    }

    /** Пускает ли символ перед знаком constrained-пару. Начало строки пускает. */
    private fun allowsConstrainedOpen(text: String, index: Int): Boolean {
        if (index < 0) return true
        val symbol = text[index]
        return !symbol.isWordChar() && symbol !in CONSTRAINED_FORBIDDEN_BEFORE
    }

    private fun consume(consumed: BooleanArray, from: Int, toExclusive: Int) {
        for (i in from until toExclusive) consumed[i] = true
    }

    /** Класс «слово» эталона: буква любого алфавита, цифра, подчёркивание. */
    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

    /** Символ списка подстановок `pass:c,q[…]`: латиница, запятая, дефис. */
    private fun Char.isSubsListChar(): Boolean = this in 'a'..'z' || this == ',' || this == '-'

    // endregion

    /** Порядок пар — порядок группы `quotes` эталона без типографских кавычек. */
    private val QUOTE_ORDER = listOf(
        '*' to AdocStyle.Bold,
        '`' to AdocStyle.Monospace,
        '_' to AdocStyle.Italic,
        '#' to AdocStyle.Highlight,
    )

    private const val PASS_MARK = '+'
    private const val TRIPLE_PLUS = "+++"
    private const val PASS_MACRO = "pass:"
    private const val CONSTRAINED_FORBIDDEN_BEFORE = ";:}"
    private const val DOUBLED_ESCAPE = 2
    private const val MIN_CONSTRUCT_LENGTH = 3
    private const val MIN_PREFIX_LENGTH = 3
}

/** Приёмник конструкции: полуинтервал в координатах логической строки и роль. */
private typealias Emit = (start: Int, endExclusive: Int, style: AdocStyle) -> Unit
