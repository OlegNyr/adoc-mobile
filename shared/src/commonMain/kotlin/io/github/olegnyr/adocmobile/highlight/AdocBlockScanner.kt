package io.github.olegnyr.adocmobile.highlight

/**
 * Блочный автомат подсветки — слайсы `SL-1` и `SL-2` фичи 001-syntax-highlighting.
 *
 * Автомат построчный и держит *стек* кадров «тип, длина ограничителя» (`FR-1`),
 * а не флаг «внутри блока». Флага не хватает по двум независимым причинам:
 * одноимённые составные блоки вкладываются друг в друга сменой длины
 * ограничителя, и ограничитель внутри составного блока открывает вложенный блок,
 * а не закрывает внешний (`FR-6`).
 *
 * `SL-2` добавил к этому три вещи: строчный проход [AdocLineScanner] для строк,
 * не являющихся ограничителями (`FR-7`…`FR-16`), состояние «начало блока», от
 * которого зависит, распознаётся ли строчная конструкция вообще, и маскарад
 * блока (`FR-5`) — метку стиля над ограничителем, меняющую тип блока.
 *
 * `SL-3` добавил инлайн-разбор (`FR-17`…`FR-20`): автомат копит *логическую
 * строку блока* — смежные непустые строки абзаца, пункта списка или описания
 * определения — и на её границе запускает [AdocInlineScanner]. Границей служат
 * пустая строка, ограничитель, метаданные, новый пункт списка и продолжение
 * пункта `+`; строчный комментарий и директива препроцессора прозрачны — абзац
 * склеивается через них, как это делает препроцессор эталона.
 *
 * Чего здесь нет и не должно появиться: макросов и ссылок — это слайс `SL-4`.
 *
 * Спорные места сняты прогоном эталонного Asciidoctor 2.0.26 от 2026-08-17, как
 * требуют границы работ фичи, — документация языка их не описывает:
 *
 * * внутри дословного блока вложенные блоки не открываются ни при какой длине
 *   ограничителя, даже отличной от открывающей;
 * * шкала длин дефисов немонотонна: 2 — open, 3 — тематический разрыв (не блок),
 *   4 и больше — listing;
 * * callout распознаётся только в конце строки;
 * * ограничитель с ведущими пробелами ограничителем не является;
 * * маскарад несимметричен: `[listing]` над `....` работает, `[quote]` над `----`
 *   нет — блок остаётся listing.
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

        // Начало документа — начало блока: первая же строка может быть заголовком.
        var previousRole = AdocLineRole.Boundary
        var insideList = false
        var pendingStyle: String? = null
        var attributeContinues = false
        val inline = InlineAccumulator(source, spans)

        forEachLine(source) { lineStart, lineEnd ->
            val line = source.substring(lineStart, lineEnd)
            val delimiter = parseDelimiter(line)
            val open = stack.lastOrNull()

            when {
                // Внутри непрозрачного блока строку закрывает только точное
                // совпадение с открывающим ограничителем; всё остальное —
                // содержимое, включая ограничители других длин и типов.
                open != null && !open.kind.allowsNestedBlocks ->
                    if (delimiter != null && delimiter.closes(open)) {
                        spans += AdocSpan(AdocRange(lineStart, lineEnd), AdocStyle.BlockDelimiter)
                        stack.removeAt(stack.lastIndex)
                        previousRole = AdocLineRole.Boundary
                    } else {
                        markOpaqueContent(open.kind, line, lineStart, lineEnd, spans)
                    }

                // В составном блоке и вне блоков ограничитель либо закрывает
                // текущий блок, либо открывает новый — вложенный.
                delimiter != null -> {
                    inline.flush()
                    spans += AdocSpan(AdocRange(lineStart, lineEnd), AdocStyle.BlockDelimiter)
                    if (open != null && delimiter.closes(open)) {
                        stack.removeAt(stack.lastIndex)
                    } else {
                        stack += masquerade(delimiter, pendingStyle)
                    }
                    pendingStyle = null
                    attributeContinues = false
                    previousRole = AdocLineRole.Boundary
                    insideList = false
                }

                // Продолжение пункта списка: `+` на отдельной строке присоединяет
                // к пункту следующий блок. Для строчного состояния строка ведёт
                // себя как обычный текст, но логическую строку инлайн-разбора
                // обрывает — прогон эталона показал, что пара форматирования
                // через продолжение не работает (`FR-17`).
                line == LIST_CONTINUATION && insideList && !attributeContinues -> {
                    inline.flush()
                    pendingStyle = null
                    previousRole = AdocLineRole.Text
                }

                else -> {
                    val result = AdocLineScanner.scan(
                        line = line,
                        offset = lineStart,
                        context = AdocLineContext(
                            atBlockStart = previousRole == AdocLineRole.Boundary ||
                                previousRole == AdocLineRole.Metadata,
                            insideList = insideList,
                            afterLiteral = previousRole == AdocLineRole.Literal,
                            atTopLevel = stack.isEmpty(),
                            continuesAttribute = attributeContinues,
                        ),
                        spans = spans,
                    )
                    attributeContinues = result.attributeContinues
                    pendingStyle = when (result.role) {
                        // Метаданные укладываются построчно в любом порядке, и
                        // метка стиля обязана дожить через `.Title` и `[[id]]`.
                        AdocLineRole.Metadata -> result.blockStyle ?: pendingStyle
                        AdocLineRole.Transparent -> pendingStyle
                        else -> null
                    }
                    // Пункт списка не закрывается строкой продолжения: прогон
                    // эталона показал, что после «ленивой» строки внутри пункта
                    // следующий маркер по-прежнему открывает пункт.
                    insideList = when (result.role) {
                        AdocLineRole.ListItem -> true
                        AdocLineRole.Text, AdocLineRole.Transparent -> insideList
                        else -> false
                    }
                    // Границы логической строки блока (`FR-17`): новый пункт
                    // списка начинает свою, текст продолжает текущую, прозрачная
                    // строка не попадает в текст, но и не обрывает его; всё
                    // остальное — граница.
                    when (result.role) {
                        AdocLineRole.ListItem -> {
                            inline.flush()
                            result.inlineContentStart?.let { inline.append(lineStart + it, lineEnd) }
                        }

                        AdocLineRole.Text ->
                            result.inlineContentStart?.let { inline.append(lineStart + it, lineEnd) }

                        AdocLineRole.Transparent -> Unit

                        else -> inline.flush()
                    }
                    // Комментарий и директива препроцессора снимаются до разбора
                    // структуры: абзац склеивается через них, состояние не меняется.
                    if (result.role != AdocLineRole.Transparent) previousRole = result.role
                }
            }

            blockStates += stack.toList()
        }
        inline.flush()

        // Диапазоны разных проходов рождаются не по порядку: инлайн-спан абзаца
        // появляется на его границе, позже спанов следующей строки. Сортировка
        // восстанавливает контракт [AdocScan]: по началу, при совпадении начал
        // первым идёт более широкий диапазон.
        spans.sortWith(compareBy({ it.range.start }, { -it.range.length }))

        return AdocScan(spans = spans, blockStates = blockStates)
    }

    /**
     * Закрывает ли строка-ограничитель открытый кадр.
     *
     * Сравниваются знаки и длина, а не эффективный тип: после маскарада (`FR-5`)
     * блок с типом `listing` может быть открыт четырьмя точками, и закрыть его
     * обязаны те же четыре точки.
     */
    private fun AdocBlockFrame.closes(open: AdocBlockFrame): Boolean =
        delimiterKind == open.delimiterKind && delimiterLength == open.delimiterLength

    /**
     * Маскарад блока `FR-5`: метка стиля над ограничителем меняет тип блока.
     *
     * Таблица закрыта и целиком получена прогоном эталона 2026-08-17, потому что
     * правило несимметрично и «любая метка меняет тип» неверно. Open-блок
     * принимает любую метку — он единственный без собственной модели содержимого.
     * Дословные ограничители меняются только друг на друга: `[listing]` над `....`
     * даёт listing, а `[quote]` над `----` не даёт ничего — блок остаётся listing.
     * Метка вне таблицы тип не меняет, как требует `FR-28`.
     */
    private fun masquerade(delimiter: AdocBlockFrame, style: String?): AdocBlockFrame {
        val masked = MASQUERADE[delimiter.kind to (style ?: return delimiter).lowercase()]
        return if (masked == null || masked == delimiter.kind) delimiter else delimiter.copy(kind = masked)
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

    /**
     * Пары «тип ограничителя, метка стиля» → эффективный тип блока.
     *
     * Ключи в нижнем регистре: метки admonition пишутся прописными, остальные
     * строчными, а различать их здесь незачем.
     */
    private val MASQUERADE: Map<Pair<AdocBlockKind, String>, AdocBlockKind> = buildMap {
        val open = AdocBlockKind.Open
        put(open to "listing", AdocBlockKind.Listing)
        put(open to "source", AdocBlockKind.Listing)
        put(open to "literal", AdocBlockKind.Literal)
        put(open to "comment", AdocBlockKind.Comment)
        put(open to "pass", AdocBlockKind.Passthrough)
        put(open to "quote", AdocBlockKind.Quote)
        put(open to "sidebar", AdocBlockKind.Sidebar)
        put(open to "example", AdocBlockKind.Example)
        // Admonition — стиль поверх example, а не отдельный контейнер: содержимое
        // разбирается так же, поэтому для сканера это тот же составной блок.
        for (label in listOf("note", "tip", "important", "caution", "warning")) {
            put(open to label, AdocBlockKind.Example)
        }
        put(AdocBlockKind.Literal to "listing", AdocBlockKind.Listing)
        put(AdocBlockKind.Literal to "source", AdocBlockKind.Listing)
        put(AdocBlockKind.Listing to "literal", AdocBlockKind.Literal)
    }

    private const val INCLUDE_DIRECTIVE = "include::"
    private const val LIST_CONTINUATION = "+"
    private const val TABLE_PREFIX = '|'
    private const val MIN_DELIMITER_LENGTH = 2
    private const val MIN_TABLE_LENGTH = 4
    private const val MIN_RUN_LENGTH = 4
    private const val OPEN_DELIMITER_LENGTH = 2
}

/**
 * Копилка логической строки блока (`FR-17`) — единицы инлайн-разбора.
 *
 * Смежные непустые строки абзаца, пункта списка или описания определения
 * складываются сюда сегментами исходника, а на границе логической строки
 * [flush] склеивает их через `\n`, прогоняет [AdocInlineScanner] и переводит
 * его диапазоны обратно в смещения исходника. Диапазон пары, пересёкшей перевод
 * строки, получается непрерывным по исходнику — включая строку комментария,
 * если абзац склеился через неё: у той остаётся и собственная роль.
 */
private class InlineAccumulator(
    private val source: String,
    private val spans: MutableList<AdocSpan>,
) {
    private val segmentStarts = mutableListOf<Int>()
    private val segmentEnds = mutableListOf<Int>()

    /** Добавляет текст строки; пустой хвост — маркер без текста — не хранится. */
    fun append(start: Int, endExclusive: Int) {
        if (start < endExclusive) {
            segmentStarts += start
            segmentEnds += endExclusive
        }
    }

    /** Завершает логическую строку: инлайн-проход и очистка копилки. */
    fun flush() {
        if (segmentStarts.isEmpty()) return

        var length = segmentStarts.size - 1
        for (k in segmentStarts.indices) length += segmentEnds[k] - segmentStarts[k]

        // Карта смещений возвращает каждому символу склейки его позицию в
        // исходнике: диапазоны считаются по исходному тексту (`FR-20`, `FR-24`),
        // даже когда сегменты не смежны из-за прозрачной строки между ними.
        val map = IntArray(length)
        val text = StringBuilder(length)
        for (k in segmentStarts.indices) {
            if (k > 0) {
                map[text.length] = segmentEnds[k - 1]
                text.append('\n')
            }
            for (position in segmentStarts[k] until segmentEnds[k]) {
                map[text.length] = position
                text.append(source[position])
            }
        }

        AdocInlineScanner.scan(text.toString()) { from, toExclusive, style ->
            spans += AdocSpan(AdocRange(map[from], map[toExclusive - 1] + 1), style)
        }
        segmentStarts.clear()
        segmentEnds.clear()
    }
}
