package io.github.olegnyr.adocmobile.highlight

/**
 * Роль строки — то, что о ней должна знать *следующая* строка.
 *
 * Роль существует не ради подсветки, а ради одного факта, снятого прогоном
 * эталонного Asciidoctor 2.0.26 и не описанного ни в требованиях, ни в разведке:
 * *строчная конструкция распознаётся только в начале блока*. Строка `== Раздел`
 * под строкой текста заголовком не становится — эталон склеивает её с абзацем.
 * Без этого условия сканер подсвечивал бы разметкой каждый абзац, начинающийся
 * со звёздочки, точки или прописного слова с двоеточием, и расходился бы с
 * превью на обычном русском тексте.
 */
internal enum class AdocLineRole {
    /** Следующая строка начинает новый блок: пустая строка, заголовок, разрыв, атрибут. */
    Boundary,

    /** Метаданные блока: тоже граница, но метка стиля доживает до ограничителя (`FR-5`). */
    Metadata,

    /** Пункт списка: заголовком следующая строка стать не может, а пунктом — да. */
    ListItem,

    /** Литеральный абзац по отступу (`FR-16`): следующая строка с отступом продолжает его. */
    Literal,

    /**
     * Строка, которую препроцессор снимает до разбора: строчный комментарий и
     * условные директивы. Состояние не меняется вовсе — абзац склеивается через неё.
     */
    Transparent,

    /** Обычный текст. */
    Text,
}

/** Что строчный проход сообщает автомату помимо диапазонов. */
internal data class AdocLineResult(
    val role: AdocLineRole,
    /** Позиционная метка стиля из `[style,attrs]` — сырьё маскарада `FR-5`. */
    val blockStyle: String? = null,
    /** Значение атрибута оборвано слэшем и продолжается на следующей строке (`FR-9`). */
    val attributeContinues: Boolean = false,
    /**
     * Смещение в строке, с которого начинается текст логической строки блока для
     * инлайн-разбора `SL-3`, или `null`, если инлайн-текста в строке нет.
     * У абзаца это 0, у пункта списка — позиция за пробелом после маркера, у
     * admonition — за меткой; заголовки, атрибуты и метаданные текста не отдают.
     */
    val inlineContentStart: Int? = null,
)

/**
 * Что строчному проходу нужно знать о том, что было выше.
 *
 * @property atBlockStart предыдущая строка закончила блок: пустая строка,
 * ограничитель, метаданные. Почти все строчные конструкции живут только здесь.
 * @property insideList выше идёт список, пункт которого ещё не закрыт. Прогон
 * эталона показал, что маркер списка распознаётся и после «ленивой» строки
 * продолжения пункта, тогда как заголовок и заголовок блока — уже нет.
 * @property afterLiteral предыдущая строка была литеральным абзацем по отступу.
 * @property atTopLevel стек блоков пуст — единственное место, где живут разделы.
 * @property continuesAttribute предыдущая строка оборвала значение атрибута слэшем.
 */
internal data class AdocLineContext(
    val atBlockStart: Boolean,
    val insideList: Boolean,
    val afterLiteral: Boolean,
    val atTopLevel: Boolean,
    val continuesAttribute: Boolean,
)

/**
 * Строчные конструкции — слайс `SL-2` фичи 001-syntax-highlighting.
 *
 * Проход работает по одной строке и ничего не знает о стеке блоков: всё, что ему
 * нужно от соседей, приходит в [AdocLineContext]. Блочный автомат остаётся тем
 * же, что и в `SL-1`, — сюда вынесены только `FR-7`…`FR-16`.
 *
 * Порядок проверок — часть требований, а не удобство реализации:
 *
 * * разрывы идут раньше списков, потому что `* * *` — тематический разрыв, а
 *   `* * * *` уже пункт списка;
 * * литеральный абзац по отступу проверяется последним, иначе он съел бы
 *   `  * пункт` с незначимым отступом перед маркером;
 * * запись атрибута идёт раньше списка определений: `:name: value` иначе разошлось
 *   бы по обеим веткам.
 *
 * Инлайн-разбора здесь нет и не должно появиться — это `SL-3` и `SL-4`.
 */
internal object AdocLineScanner {

    /**
     * Размечает строку, дописывая диапазоны в [spans] со смещением [offset], и
     * возвращает то, что о ней нужно помнить дальше.
     */
    fun scan(
        line: String,
        offset: Int,
        context: AdocLineContext,
        spans: MutableList<AdocSpan>,
    ): AdocLineResult {
        if (line.isBlank()) return AdocLineResult(AdocLineRole.Boundary)

        if (context.continuesAttribute) {
            spans += wholeLine(offset, line, AdocStyle.AttributeEntry)
            return AdocLineResult(
                role = AdocLineRole.Boundary,
                attributeContinues = line.endsWith(VALUE_CONTINUATION),
            )
        }

        // Комментарий и директивы препроцессора снимаются до разбора структуры и
        // потому работают в любой позиции, включая середину абзаца.
        if (isLineComment(line)) {
            spans += wholeLine(offset, line, AdocStyle.Comment)
            return AdocLineResult(AdocLineRole.Transparent)
        }
        if (isPreprocessorDirective(line)) {
            spans += wholeLine(offset, line, AdocStyle.PreprocessorDirective)
            return AdocLineResult(AdocLineRole.Transparent)
        }

        // Список атрибутов — вторая конструкция без привязки к началу блока:
        // прогон эталона показал, что `[source]` обрывает абзац, а `.Title` нет.
        blockAttributeLine(line)?.let { metadata ->
            spans += wholeLine(offset, line, metadata.role)
            return AdocLineResult(AdocLineRole.Metadata, blockStyle = metadata.style)
        }

        if (context.atBlockStart) {
            if (isBlockTitle(line)) {
                spans += wholeLine(offset, line, AdocStyle.BlockTitle)
                return AdocLineResult(AdocLineRole.Metadata)
            }

            if (context.atTopLevel) {
                // Раздел существует только на верхнем уровне: прогон эталона
                // показал, что внутри `====`, `****`, `____` и `--` строка с `=`
                // остаётся абзацем.
                headingStyle(line)?.let { style ->
                    spans += wholeLine(offset, line, style)
                    return AdocLineResult(AdocLineRole.Boundary)
                }
            }

            if (isThematicBreak(line)) {
                spans += wholeLine(offset, line, AdocStyle.ThematicBreak)
                return AdocLineResult(AdocLineRole.Boundary)
            }
            if (line == PAGE_BREAK) {
                spans += wholeLine(offset, line, AdocStyle.PageBreak)
                return AdocLineResult(AdocLineRole.Boundary)
            }

            if (isAttributeEntry(line)) {
                spans += wholeLine(offset, line, AdocStyle.AttributeEntry)
                return AdocLineResult(
                    role = AdocLineRole.Boundary,
                    attributeContinues = line.endsWith(VALUE_CONTINUATION),
                )
            }
        }

        // Маркер списка — единственная конструкция, которой мало «начала блока»:
        // прогон эталона показал, что внутри незакрытого пункта он работает и
        // после ленивой строки продолжения, а заголовок блока там уже нет.
        if (context.atBlockStart || context.insideList) {
            listMarker(line)?.let { (from, to) ->
                spans += AdocSpan(AdocRange(offset + from, offset + to), AdocStyle.ListMarker)
                // За маркером обязан идти пробел; текст пункта — после него.
                return AdocLineResult(AdocLineRole.ListItem, inlineContentStart = to + 1)
            }
            definitionListSeparator(line)?.let { (from, to) ->
                spans += AdocSpan(AdocRange(offset, offset + from), AdocStyle.ListTerm)
                spans += AdocSpan(AdocRange(offset + from, offset + to), AdocStyle.ListMarker)
                return AdocLineResult(
                    role = AdocLineRole.ListItem,
                    // Описание на той же строке начинается за пробелом после
                    // разделителя; `Термин::` без описания текста не отдаёт.
                    inlineContentStart = if (to < line.length) to + 1 else null,
                )
            }
        }

        if (context.atBlockStart) {
            admonitionLabelLength(line)?.let { length ->
                spans += AdocSpan(AdocRange(offset, offset + length), AdocStyle.Admonition)
                return AdocLineResult(AdocLineRole.Text, inlineContentStart = length + 1)
            }
        }

        // `FR-16`: отступ даёт литеральный абзац только в начале блока. Гипотеза
        // разведки про взаимодействие с продолжением списка снята прогоном
        // эталона — сразу под строкой текста или под пунктом списка та же строка
        // склеивается с предыдущей, а литеральным блоком не становится.
        if ((context.atBlockStart || context.afterLiteral) && line[0].isIndent()) {
            spans += wholeLine(offset, line, AdocStyle.VerbatimContent)
            return AdocLineResult(AdocLineRole.Literal)
        }

        return AdocLineResult(AdocLineRole.Text, inlineContentStart = 0)
    }

    // region конструкции

    /**
     * Строчный комментарий `FR-13`.
     *
     * Третья косая черта отменяет комментарий: прогон эталона показал, что `///`
     * выводится абзацем, а ограничителем блока становятся только четыре знака.
     */
    private fun isLineComment(line: String): Boolean =
        line.startsWith(LINE_COMMENT) && (line.length == LINE_COMMENT.length || line[LINE_COMMENT.length] != '/')

    /**
     * Директива препроцессора `FR-14`.
     *
     * Закрывающая скобка обязательна: без неё `include:: см. ниже` — обычный
     * список определений, и подсветить его директивой значило бы нарушить `FR-28`.
     */
    private fun isPreprocessorDirective(line: String): Boolean =
        line.endsWith(']') && DIRECTIVES.any { line.startsWith(it) }

    /** Разбор строки в квадратных скобках: какая это роль и какая в ней метка стиля. */
    private class BlockAttributeLine(val role: AdocStyle, val style: String?)

    /**
     * Строка метаданных в квадратных скобках: список атрибутов или якорь (`FR-11`).
     *
     * Формы `[[id]]` и `[#id]` — якоря; `[quote#roads]` уже список атрибутов со
     * стилем, потому что метка стоит перед решёткой.
     */
    private fun blockAttributeLine(line: String): BlockAttributeLine? {
        if (line.length < 2 || line[0] != '[' || !line.endsWith(']')) return null
        val body = line.substring(1, line.length - 1)
        if (body.startsWith('[') && body.endsWith(']') && body.length >= 2) {
            return BlockAttributeLine(AdocStyle.Anchor, null)
        }
        if (body.startsWith('#')) return BlockAttributeLine(AdocStyle.Anchor, null)
        return BlockAttributeLine(AdocStyle.BlockAttributes, positionalStyle(body))
    }

    /**
     * Позиционная метка стиля из списка атрибутов, или `null`, если её нет.
     *
     * Метка — всё до первой запятой, обрезанное по идентификатору (`#`), роли
     * (`.`) и опции (`%`). Пустая метка `[,kotlin]` маскарада не даёт: прогон
     * эталона показал, что такой open-блок остаётся открытым.
     */
    private fun positionalStyle(body: String): String? {
        val head = body.substringBefore(',')
        val name = head.takeWhile { it != '#' && it != '.' && it != '%' }.trim()
        return name.ifEmpty { null }
    }

    /** Заголовок блока `.Title` — без пробела после точки и не начало нумерованного списка. */
    private fun isBlockTitle(line: String): Boolean =
        line.length > 1 && line[0] == '.' && line[1] != '.' && !line[1].isWhitespace()

    /**
     * Роль заголовка по числу знаков (`FR-7`).
     *
     * Знаков от одного до шести, а не «от двух до шести», как записано в `FR-7`:
     * прогон эталона 2026-08-17 показал, что `= ` даёт заголовок уровня 0, а семь
     * знаков заголовком уже не являются. Форма Markdown с `#` работает так же.
     */
    private fun headingStyle(line: String): AdocStyle? {
        val mark = line[0]
        if (mark != '=' && mark != '#') return null

        var signs = 0
        while (signs < line.length && line[signs] == mark) signs++
        if (signs > HEADING_STYLES.size) return null
        if (signs >= line.length || line[signs] != ' ') return null
        if (line.substring(signs).isBlank()) return null

        return HEADING_STYLES[signs - 1]
    }

    /**
     * Тематический разрыв `FR-15`.
     *
     * Две независимые формы. Классическая — три и больше апострофов подряд.
     * Markdown-подобная — *ровно три* знака `-`, `*` или `_`, разделённые
     * одинаковыми промежутками из пробелов, с отступом не больше трёх пробелов.
     * Всё проверено прогоном эталона; подчёркивание в `FR-15` не упомянуто, но
     * разрывом является — расхождение зафиксировано в журнале слайса.
     */
    private fun isThematicBreak(line: String): Boolean {
        if (line.length >= THEMATIC_MARKS && line.all { it == '\'' }) return true

        var cursor = 0
        while (cursor < line.length && line[cursor] == ' ') cursor++
        if (cursor > MAX_BREAK_INDENT || cursor >= line.length) return false

        val mark = line[cursor]
        if (mark !in MARKDOWN_BREAK_MARKS) return false

        var marks = 1
        var gap = UNKNOWN_GAP
        cursor++
        while (cursor < line.length) {
            var spaces = 0
            while (cursor < line.length && line[cursor] == ' ') {
                spaces++
                cursor++
            }
            if (cursor >= line.length || line[cursor] != mark) return false
            if (gap == UNKNOWN_GAP) gap = spaces else if (spaces != gap) return false
            if (++marks > THEMATIC_MARKS) return false
            cursor++
        }
        return marks == THEMATIC_MARKS
    }

    /**
     * Запись атрибута документа `FR-8`: `:name:`, `:name: value`, `:!name:`, `:name!:`.
     *
     * Строка `: текст` атрибутом не является — имя пустое, и разведка называет
     * этот случай прямо.
     */
    private fun isAttributeEntry(line: String): Boolean {
        if (line[0] != ':') return false
        var cursor = 1
        if (cursor < line.length && line[cursor] == '!') cursor++

        val nameStart = cursor
        while (cursor < line.length && line[cursor].isAttributeNameChar()) cursor++
        if (cursor == nameStart) return false

        if (cursor < line.length && line[cursor] == '!') cursor++
        if (cursor >= line.length || line[cursor] != ':') return false
        cursor++

        // После закрывающего двоеточия либо конец строки, либо пробел перед значением.
        return cursor == line.length || line[cursor] == ' '
    }

    /**
     * Маркер маркированного или нумерованного списка (`FR-10`), или `null`.
     *
     * Пробел после маркера обязателен — иначе `*Жирная фраза *` была бы принята за
     * список, а это форматирование. Отступ перед маркером незначим.
     */
    private fun listMarker(line: String): Pair<Int, Int>? {
        var start = 0
        while (start < line.length && line[start].isIndent()) start++
        if (start >= line.length) return null

        val mark = line[start]
        if (mark == '-') {
            return if (start + 1 < line.length && line[start + 1] == ' ') start to start + 1 else null
        }
        if (mark != '*' && mark != '.') return null

        var end = start
        while (end < line.length && line[end] == mark) end++
        if (end - start > MAX_LIST_MARKER_RUN) return null
        if (end >= line.length || line[end] != ' ') return null
        return start to end
    }

    /**
     * Разделитель списка определений и длина термина перед ним (`FR-10`), или `null`.
     *
     * Отличает список определений от блочного макроса ровно тем, чем отличает его
     * эталон: после разделителя обязан идти пробел или конец строки. Поэтому
     * `image::pic.png[]` разделителем не считается, а `Термин::` — считается.
     */
    private fun definitionListSeparator(line: String): Pair<Int, Int>? {
        val colon = line.indexOf(DEFINITION_COLON)
        val semicolon = line.indexOf(DEFINITION_SEMICOLON)
        val start = when {
            colon < 0 -> semicolon
            semicolon < 0 -> colon
            else -> minOf(colon, semicolon)
        }
        if (start <= 0 || line.take(start).isBlank()) return null

        val mark = line[start]
        var end = start
        while (end < line.length && line[end] == mark) end++

        val allowed = if (mark == ':') COLON_SEPARATOR_LENGTHS else SEMICOLON_SEPARATOR_LENGTHS
        if (end - start !in allowed) return null
        if (end < line.length && line[end] != ' ') return null
        return start to end
    }

    /** Длина метки абзацного admonition вместе с двоеточием (`FR-12`), или `null`. */
    private fun admonitionLabelLength(line: String): Int? {
        for (label in ADMONITIONS) {
            val labelled = label.length + 1
            if (line.length > labelled &&
                line.startsWith(label) &&
                line[label.length] == ':' &&
                line[labelled] == ' '
            ) {
                return labelled
            }
        }
        return null
    }

    // endregion

    private fun wholeLine(offset: Int, line: String, style: AdocStyle) =
        AdocSpan(AdocRange(offset, offset + line.length), style)

    private fun Char.isIndent(): Boolean = this == ' ' || this == '\t'

    private fun Char.isAttributeNameChar(): Boolean = isLetterOrDigit() || this == '-' || this == '_'

    private val HEADING_STYLES = listOf(
        AdocStyle.Heading0,
        AdocStyle.Heading1,
        AdocStyle.Heading2,
        AdocStyle.Heading3,
        AdocStyle.Heading4,
        AdocStyle.Heading5,
    )

    private val DIRECTIVES = listOf("include::", "ifdef::", "ifndef::", "ifeval::", "endif::")
    private val ADMONITIONS = listOf("NOTE", "TIP", "IMPORTANT", "CAUTION", "WARNING")

    private const val LINE_COMMENT = "//"
    private const val PAGE_BREAK = "<<<"
    /**
     * Перенос значения атрибута — пробел перед слэшем обязателен (`FR-9`).
     *
     * Прогон эталона 2026-08-17: `:hard: ccc\` без пробела значение не продолжает,
     * а следующая строка становится обычным абзацем. Проверка `endsWith("\\")`
     * утащила бы в атрибут любую строку под `:sdk: C:\`.
     * Жёсткая форма переноса (` + \`) под это правило подходит тоже.
     */
    private const val VALUE_CONTINUATION = " \\"
    private const val DEFINITION_COLON = "::"
    private const val DEFINITION_SEMICOLON = ";;"
    private const val MARKDOWN_BREAK_MARKS = "-*_"
    private const val THEMATIC_MARKS = 3
    private const val MAX_BREAK_INDENT = 3
    private const val MAX_LIST_MARKER_RUN = 5
    private const val UNKNOWN_GAP = -1
    private val COLON_SEPARATOR_LENGTHS = 2..4
    private val SEMICOLON_SEPARATOR_LENGTHS = 2..2
}
