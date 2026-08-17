package io.github.olegnyr.adocmobile.highlight

/**
 * Блочный автомат подсветки — слайс `SL-1` фичи 001-syntax-highlighting.
 *
 * Автомат построчный и держит *стек* кадров «тип, длина ограничителя» (`FR-1`),
 * а не флаг «внутри блока». Флага не хватает по двум независимым причинам:
 * одноимённые составные блоки вкладываются друг в друга сменой длины
 * ограничителя, и ограничитель внутри составного блока открывает вложенный блок,
 * а не закрывает внешний (`FR-6`).
 *
 * Чего здесь нет и не должно появиться: строчных конструкций, инлайн-разбора и
 * макросов — это слайсы `SL-2`…`SL-4`. Обычная строка вне блока не получает ни
 * одного диапазона намеренно, а не потому, что до неё не дошли руки.
 *
 * Спорные места сняты прогоном эталонного Asciidoctor 2.0.26 от 2026-08-17, как
 * требуют границы работ фичи, — документация языка их не описывает:
 *
 * * внутри дословного блока вложенные блоки не открываются ни при какой длине
 *   ограничителя, даже отличной от открывающей;
 * * шкала длин дефисов немонотонна: 2 — open, 3 — тематический разрыв (не блок),
 *   4 и больше — listing;
 * * callout распознаётся только в конце строки;
 * * ограничитель с ведущими пробелами ограничителем не является.
 */
object AdocBlockScanner {

    /**
     * Единственный вход. Принимает исходник и возвращает новый результат —
     * буфер не трогается ни при каком исходе (`FR-24`).
     */
    fun scan(source: String): AdocScan {
        val spans = mutableListOf<AdocSpan>()
        val blockStates = mutableListOf<List<AdocBlockFrame>>()
        val stack = mutableListOf<AdocBlockFrame>()

        forEachLine(source) { lineStart, lineEnd ->
            val line = source.substring(lineStart, lineEnd)
            val delimiter = parseDelimiter(line)
            val open = stack.lastOrNull()

            when {
                // Внутри непрозрачного блока строку закрывает только точное
                // совпадение с открывающим ограничителем; всё остальное —
                // содержимое, включая ограничители других длин и типов.
                open != null && !open.kind.allowsNestedBlocks ->
                    if (delimiter == open) {
                        spans += AdocSpan(AdocRange(lineStart, lineEnd), AdocStyle.BlockDelimiter)
                        stack.removeAt(stack.lastIndex)
                    } else {
                        markOpaqueContent(open.kind, line, lineStart, lineEnd, spans)
                    }

                // В составном блоке и вне блоков ограничитель либо закрывает
                // текущий блок, либо открывает новый — вложенный.
                delimiter != null -> {
                    spans += AdocSpan(AdocRange(lineStart, lineEnd), AdocStyle.BlockDelimiter)
                    if (delimiter == open) stack.removeAt(stack.lastIndex) else stack += delimiter
                }
            }

            blockStates += stack.toList()
        }

        return AdocScan(spans = spans, blockStates = blockStates)
    }

    /**
     * Разметка строки внутри блока, содержимое которого не разбирается.
     *
     * `FR-3` формулирует это как «не разбирать», но у правила ровно два
     * исключения, и оба видны глазом: callout и `include::`. Директива
     * препроцессора обрабатывается при чтении строк, до разбора структуры, и
     * потому пробивает дословное состояние — она получает свою роль вместо
     * дословной, а не поверх неё.
     */
    private fun markOpaqueContent(
        kind: AdocBlockKind,
        line: String,
        lineStart: Int,
        lineEnd: Int,
        spans: MutableList<AdocSpan>,
    ) {
        if (line.isEmpty()) return // Диапазон нулевой длины ничего не показал бы.

        when (kind) {
            AdocBlockKind.Comment ->
                spans += AdocSpan(AdocRange(lineStart, lineEnd), AdocStyle.Comment)

            AdocBlockKind.Listing, AdocBlockKind.Literal, AdocBlockKind.Passthrough ->
                if (line.startsWith(INCLUDE_DIRECTIVE) && line.endsWith(']')) {
                    spans += AdocSpan(AdocRange(lineStart, lineEnd), AdocStyle.PreprocessorDirective)
                } else {
                    spans += AdocSpan(AdocRange(lineStart, lineEnd), AdocStyle.VerbatimContent)
                    calloutRange(line)?.let { (from, to) ->
                        spans += AdocSpan(AdocRange(lineStart + from, lineStart + to), AdocStyle.Callout)
                    }
                }

            // Содержимое таблицы — ячейки с обычным текстом, а не дословный
            // текст. Разбирать его нечем: внутренности таблиц вынесены в
            // не-цели, и красить их дословной ролью было бы прямой ошибкой.
            //
            // ОТКРЫТЫЙ ВОПРОС для владельца. Прогон эталона 2026-08-17 показал,
            // что ячейка со стилем `a|` всё-таки содержит вложенные блоки:
            // `----` внутри неё открывает listing. Распознать это — значит
            // разбирать спецификаторы ячеек, а они в не-целях. Здесь выбран
            // недоперелёт: таблица непрозрачна, listing внутри ячейки не
            // подсвечен вовсе. Обратный выбор хуже — `----` съел бы закрывающий
            // `|===`, и остаток документа уехал бы в дословный блок. Расхождение
            // в разведке не перечислено, поэтому требует решения, а не теста.
            AdocBlockKind.Table -> Unit

            // Составные блоки сюда не попадают: у них allowsNestedBlocks = true,
            // и вызывающий их в эту ветку не отправляет. Ветка оставлена явной,
            // чтобы добавленный тип блока не растворился в else.
            AdocBlockKind.Example, AdocBlockKind.Open, AdocBlockKind.Sidebar, AdocBlockKind.Quote -> Unit
        }
    }

    /**
     * Положение callout `<N>` в конце строки, или `null`.
     *
     * Позиция важна: прогон эталона показал, что `<4>` в середине строки
     * остаётся текстом, а callout распознаётся только последним на строке.
     * Наивный поиск `<цифры>` где угодно даст ложную подсветку в любом коде,
     * где встречается сравнение или дженерик.
     */
    private fun calloutRange(line: String): Pair<Int, Int>? {
        var end = line.length
        while (end > 0 && line[end - 1].isWhitespace()) end--
        if (end == 0 || line[end - 1] != '>') return null

        var digitsEnd = end - 1
        var cursor = digitsEnd
        while (cursor > 0 && line[cursor - 1].isDigit()) cursor--
        if (cursor == digitsEnd) return null // `<>` без номера — не callout.
        if (cursor == 0 || line[cursor - 1] != '<') return null

        digitsEnd = end
        return (cursor - 1) to digitsEnd
    }

    /**
     * Ограничитель, если строка им является.
     *
     * Строка обязана состоять из одного повторяющегося знака (или начинаться с
     * `|` у таблицы) и ничего больше не содержать: ведущий пробел, как показал
     * прогон эталона, делает строку обычным текстом.
     */
    private fun parseDelimiter(line: String): AdocBlockFrame? {
        val length = line.length
        if (length < MIN_DELIMITER_LENGTH) return null

        if (line[0] == TABLE_PREFIX) {
            if (length < MIN_TABLE_LENGTH) return null
            for (i in 1 until length) if (line[i] != '=') return null
            return AdocBlockFrame(AdocBlockKind.Table, length)
        }

        val mark = line[0]
        for (i in 1 until length) if (line[i] != mark) return null

        val kind = when (mark) {
            '/' -> AdocBlockKind.Comment
            '=' -> AdocBlockKind.Example
            '.' -> AdocBlockKind.Literal
            '*' -> AdocBlockKind.Sidebar
            '_' -> AdocBlockKind.Quote
            '+' -> AdocBlockKind.Passthrough
            // Дефис — единственный знак с немонотонной шкалой: ровно два знака
            // открывают open-блок, ровно три дают тематический разрыв (`FR-15`,
            // слайс `SL-2`), четыре и больше — listing. Проверка `length >= 2`
            // здесь была бы ошибкой, невидимой на трёх остальных длинах.
            '-' -> return when {
                length == OPEN_DELIMITER_LENGTH -> AdocBlockFrame(AdocBlockKind.Open, length)
                length >= MIN_RUN_LENGTH -> AdocBlockFrame(AdocBlockKind.Listing, length)
                else -> null
            }

            else -> return null
        }
        return if (length >= MIN_RUN_LENGTH) AdocBlockFrame(kind, length) else null
    }

    /**
     * Обход строк с сохранением смещений в исходнике.
     *
     * `split` здесь не годится: диапазоны считаются по исходному тексту, а
     * документ пользователя может прийти с CRLF. Завершающий `\r` в строку не
     * входит — иначе он попадал бы в ограничитель и ломал сравнение длин, — но
     * смещения при этом остаются исходными.
     */
    private inline fun forEachLine(source: String, action: (start: Int, endExclusive: Int) -> Unit) {
        var start = 0
        while (true) {
            val breakAt = source.indexOf('\n', start)
            val rawEnd = if (breakAt < 0) source.length else breakAt
            val end = if (rawEnd > start && source[rawEnd - 1] == '\r') rawEnd - 1 else rawEnd
            action(start, end)
            if (breakAt < 0) return
            start = breakAt + 1
        }
    }

    /**
     * Пускает ли блок внутрь себя вложенные блоки.
     *
     * Разделение проходит не по «дословный или нет», а именно по этому вопросу:
     * комментарий и таблица дословными не являются, но ограничитель внутри них
     * вложенного блока не открывает.
     */
    private val AdocBlockKind.allowsNestedBlocks: Boolean
        get() = when (this) {
            AdocBlockKind.Example, AdocBlockKind.Open, AdocBlockKind.Sidebar, AdocBlockKind.Quote -> true
            AdocBlockKind.Comment, AdocBlockKind.Listing, AdocBlockKind.Literal,
            AdocBlockKind.Passthrough, AdocBlockKind.Table,
            -> false
        }

    private const val INCLUDE_DIRECTIVE = "include::"
    private const val TABLE_PREFIX = '|'
    private const val MIN_DELIMITER_LENGTH = 2
    private const val MIN_TABLE_LENGTH = 4
    private const val MIN_RUN_LENGTH = 4
    private const val OPEN_DELIMITER_LENGTH = 2
}
