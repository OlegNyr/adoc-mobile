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
 *
 * `SL-4` добавил хвост порядка подстановок эталона: после группы `quotes` идут
 * ссылки на атрибуты (`FR-22`), затем группа `macros` — макросы закрытого
 * перечня, перекрёстные ссылки и автоссылки (`FR-21`). Группа `replacements`
 * (типографские замены `...`, `(C)` и подобные) не реализуется — её нет в
 * требованиях; единственное видимое следствие — автоссылка с многоточием на
 * конце размечается без него, а эталон успевает превратить точки в символ
 * многоточия и утащить его в ссылку.
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

        // Хвост порядка подстановок (`SL-4`): атрибуты после `quotes`, затем
        // макросы и ссылки. Автоссылки идут последними, чтобы `link:URL[…]`
        // достался проходу макросов целиком, а не разобрался на две конструкции.
        scanAttributeReferences(text, consumed, emit)
        scanMacros(text, consumed, emit)
        scanCrossReferences(text, consumed, emit)
        scanAutolinks(text, consumed, emit)
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

    // region макросы, ссылки, атрибуты — FR-21, FR-22

    /**
     * Ссылка на атрибут `{name}` (`FR-22`).
     *
     * Имя — буква или цифра, дальше буквы, цифры, `-` и `_`; кириллица допустима
     * (прогон эталона: `{кир}` подставляется). Пробел в имени делает скобки
     * обычным текстом. Определён ли атрибут — сканер не проверяет: таблицу
     * атрибутов он не ведёт, и ссылка размечается по форме; эталон оставляет
     * неопределённую ссылку текстом — расхождение записано в отчёте слайса.
     */
    private fun scanAttributeReferences(text: String, consumed: BooleanArray, emit: Emit) {
        var i = text.indexOf('{')
        while (i >= 0 && i < text.length - 2) {
            if (consumed[i] || backslashesBefore(text, i) > 0 || !text[i + 1].isLetterOrDigit()) {
                i = text.indexOf('{', i + 1)
                continue
            }
            var j = i + 2
            while (j < text.length && text[j].isAttributeNameChar()) j++
            if (j >= text.length || text[j] != '}' || anyConsumed(consumed, i, j + 1)) {
                i = text.indexOf('{', i + 1)
                continue
            }
            emit(i, j + 1, AdocStyle.AttributeReference)
            consume(consumed, i, j + 1)
            i = text.indexOf('{', j + 1)
        }
    }

    /**
     * Инлайновый макрос `name:target[attrs]` по закрытому перечню `FR-21`.
     *
     * Границы слова слева нет: прогон эталона 2026-08-17 показал, что
     * `хвостimage:pic.png[]` срабатывает. Цель не начинается с `:` — двойное
     * двоеточие означает блочную форму, которая посреди абзаца остаётся
     * текстом. Правила цели зависят от имени — [AdocMacroSyntax.targetAllowed].
     */
    private fun scanMacros(text: String, consumed: BooleanArray, emit: Emit) {
        for (name in AdocMacroSyntax.INLINE_NAMES) {
            val token = "$name:"
            var i = text.indexOf(token)
            while (i >= 0) {
                val targetStart = i + token.length
                if (consumed[i] || backslashesBefore(text, i) > 0 || targetStart >= text.length) {
                    i = text.indexOf(token, i + 1)
                    continue
                }
                var bracket = targetStart
                while (bracket < text.length && text[bracket] != '[' && text[bracket] != '\n') bracket++
                if (bracket >= text.length || text[bracket] != '[' || consumed[bracket]) {
                    i = text.indexOf(token, i + 1)
                    continue
                }
                val target = text.substring(targetStart, bracket)
                if (!AdocMacroSyntax.targetAllowed(name, target)) {
                    i = text.indexOf(token, i + 1)
                    continue
                }
                val close = indexOfUnescaped(text, ']', bracket + 1)
                if (close < 0 || consumed[close]) {
                    i = text.indexOf(token, i + 1)
                    continue
                }
                emit(i, close + 1, AdocMacroSyntax.styleFor(name))
                consume(consumed, i, close + 1)
                i = text.indexOf(token, close + 1)
            }
        }
    }

    /**
     * Перекрёстная ссылка `<<target>>` и `<<target,текст>>` (`FR-21`).
     *
     * Содержимое непустое, без `<`, `>` и перевода строки; экранирование —
     * один слэш перед `<<`, как показал прогон эталона.
     */
    private fun scanCrossReferences(text: String, consumed: BooleanArray, emit: Emit) {
        var i = text.indexOf(XREF_OPEN)
        while (i >= 0) {
            if (consumed[i] || consumed[i + 1] || backslashesBefore(text, i) > 0) {
                i = text.indexOf(XREF_OPEN, i + 2)
                continue
            }
            val close = text.indexOf(XREF_CLOSE, i + 2)
            if (close < 0) return
            val body = text.substring(i + 2, close)
            if (body.isEmpty() || body.any { it == '<' || it == '>' || it == '\n' } ||
                consumed[close] || consumed[close + 1]
            ) {
                i = text.indexOf(XREF_OPEN, i + 2)
                continue
            }
            emit(i, close + 2, AdocStyle.CrossReference)
            consume(consumed, i, close + 2)
            i = text.indexOf(XREF_OPEN, close + 2)
        }
    }

    /**
     * Автоссылки `FR-21`: голые URL схем `http`, `https`, `ftp`, `irc` и форма
     * с текстом `URL[текст]`; `mailto:` — только с `[…]`.
     *
     * Все границы сняты прогоном эталона 2026-08-17:
     *
     * * слева нужна граница — начало, пробельный символ или один из `<>()[];"'`;
     *   `слитноhttp://…` ссылкой не становится;
     * * URL тянется до пробельного символа, `[`, `]`, `<` — или до знака уже
     *   размеченного форматирования: `http://…/_подчерк_/x` даёт ссылку только
     *   до подчёркиваний, потому что группа `quotes` уже съела курсив;
     * * финальные знаки препинания и закрывающая скобка в ссылку не входят;
     * * голый `mailto:адрес` у эталона остаётся текстом — вопреки `FR-21`,
     *   перечислившему mailto среди автоссылок; расхождение в отчёте слайса.
     */
    private fun scanAutolinks(text: String, consumed: BooleanArray, emit: Emit) {
        for (scheme in BARE_SCHEMES) scanScheme(text, scheme, requireBrackets = false, consumed, emit)
        scanScheme(text, MAILTO_SCHEME, requireBrackets = true, consumed, emit)
    }

    private fun scanScheme(
        text: String,
        scheme: String,
        requireBrackets: Boolean,
        consumed: BooleanArray,
        emit: Emit,
    ) {
        var i = text.indexOf(scheme)
        while (i >= 0) {
            if (consumed[i] || backslashesBefore(text, i) > 0 ||
                (!requireBrackets && !allowsAutolinkStart(text, i))
            ) {
                i = text.indexOf(scheme, i + 1)
                continue
            }
            var j = i + scheme.length
            while (j < text.length && !text[j].isWhitespace() && text[j] !in URL_STOP && !consumed[j]) j++
            if (j == i + scheme.length) {
                i = text.indexOf(scheme, i + 1)
                continue
            }

            // URL, упирающийся в `[`, — форма с текстом: скобки входят в ссылку.
            if (j < text.length && text[j] == '[' && !consumed[j]) {
                val close = indexOfUnescaped(text, ']', j + 1)
                if (close >= 0 && !consumed[close]) {
                    emit(i, close + 1, AdocStyle.Link)
                    consume(consumed, i, close + 1)
                    i = text.indexOf(scheme, close + 1)
                    continue
                }
            }
            if (requireBrackets) {
                i = text.indexOf(scheme, j)
                continue
            }

            var end = j
            while (end > i + scheme.length && text[end - 1] in TRAILING_PUNCTUATION) end--
            if (end > i + scheme.length) {
                emit(i, end, AdocStyle.Link)
                consume(consumed, i, end)
            }
            i = text.indexOf(scheme, j)
        }
    }

    /** Граница слева от автоссылки — по классу символов ссылочного прохода эталона. */
    private fun allowsAutolinkStart(text: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = text[index - 1]
        return previous.isWhitespace() || previous in AUTOLINK_BOUNDARY
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

    private fun anyConsumed(consumed: BooleanArray, from: Int, toExclusive: Int): Boolean {
        for (i in from until toExclusive) if (consumed[i]) return true
        return false
    }

    /** Первая позиция [symbol] без слэша перед ней, начиная с [from], или -1. */
    private fun indexOfUnescaped(text: String, symbol: Char, from: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == symbol && text[i - 1] != '\\') return i
            i++
        }
        return -1
    }

    /** Класс «слово» эталона: буква любого алфавита, цифра, подчёркивание. */
    private fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

    /** Символ списка подстановок `pass:c,q[…]`: латиница, запятая, дефис. */
    private fun Char.isSubsListChar(): Boolean = this in 'a'..'z' || this == ',' || this == '-'

    /** Символ имени атрибута после первого: буква, цифра, `-`, `_` (`FR-22`). */
    private fun Char.isAttributeNameChar(): Boolean = isLetterOrDigit() || this == '-' || this == '_'

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
    private const val XREF_OPEN = "<<"
    private const val XREF_CLOSE = ">>"
    private const val MAILTO_SCHEME = "mailto:"
    private const val URL_STOP = "[]<"
    private const val TRAILING_PUNCTUATION = ".,;:!?)"
    private const val AUTOLINK_BOUNDARY = "<>()[];\"'"
    private val BARE_SCHEMES = listOf("https://", "http://", "ftp://", "irc://")
}

/**
 * Синтаксис макросов `FR-21`, общий для инлайнового прохода и строчного
 * сканера: закрытый перечень имён, роли и правила цели.
 *
 * Перечень закрыт решением владельца; правила цели у имён разные, и все они
 * сняты прогоном эталона 2026-08-17: `image` и `xref` пускают пробелы внутри
 * цели (`image:тут пробел.png[]` работает — разведка ошибочно запрещала пробелы
 * всем), `link` и `footnote` — нет, цель `kbd` всегда пуста.
 */
internal object AdocMacroSyntax {

    /** Имена, у которых есть инлайновая и блочная форма. `include` — не здесь: он директива (`FR-14`). */
    val INLINE_NAMES: List<String> = listOf("image", "xref", "link", "footnote", "kbd")

    /** Роль конструкции: обе формы перекрёстной ссылки выглядят одинаково. */
    fun styleFor(name: String): AdocStyle =
        if (name == "xref") AdocStyle.CrossReference else AdocStyle.Macro

    /** Допустима ли цель [target] у макроса [name]. */
    fun targetAllowed(name: String, target: String): Boolean {
        if (target.startsWith(':')) return false // двойное двоеточие — блочная форма
        return when (name) {
            "kbd" -> target.isEmpty()
            "footnote" -> target.none { it.isWhitespace() }
            "link" -> target.isNotEmpty() && target.none { it.isWhitespace() }
            else -> target.isNotEmpty() && !target.first().isWhitespace() && !target.last().isWhitespace()
        }
    }
}

/** Приёмник конструкции: полуинтервал в координатах логической строки и роль. */
private typealias Emit = (start: Int, endExclusive: Int, style: AdocStyle) -> Unit
