package io.github.olegnyr.adocmobile.insert

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/**
 * Готовая правка вставки: чем заменить диапазон и куда встанет выделение.
 *
 * Координаты выделения — в *новом* тексте, уже с заменой. Каретка — вырожденное
 * выделение (`selectionStart == selectionEnd`); диапазон понадобится `SL-2`,
 * где замещающий текст заготовок выделяется при вставке (`OQ-5`).
 *
 * Значение неинтерпретируемо без текста, к которому посчитано, поэтому оно не
 * хранится, а сразу применяется — см. [applyInsert].
 */
data class InsertEdit(
    /** Начало заменяемого диапазона в исходном тексте. */
    val rangeStart: Int,
    /** Конец заменяемого диапазона, не включая символ под ним. */
    val rangeEnd: Int,
    /** Текст, который встаёт на место диапазона. */
    val replacement: String,
    /** Начало выделения после правки, в координатах нового текста. */
    val selectionStart: Int,
    /** Конец выделения после правки. */
    val selectionEnd: Int,
)

/**
 * Чистая функция вставки: по паре «текст, выделение» и конструкции считает
 * правку (`NFR-10`). Ничего не знает ни о Compose-состоянии, ни об истории
 * отмены — только арифметика над строкой.
 *
 * Выделение принимается в любом порядке концов: обратное (пользователь вёл
 * палец справа налево) нормализуется здесь, чтобы каждая конструкция не
 * повторяла `minOf`/`maxOf` у себя.
 */
fun insertEditFor(
    construct: InsertConstruct,
    text: CharSequence,
    selectionStart: Int,
    selectionEnd: Int,
): InsertEdit {
    val start = minOf(selectionStart, selectionEnd)
    val end = maxOf(selectionStart, selectionEnd)
    require(start >= 0 && end <= text.length) {
        "Выделение [$selectionStart, $selectionEnd] вне текста длиной ${text.length}"
    }
    return when (construct) {
        is InlineWrap -> inlineWrapEdit(construct, text, start, end)
        is LinePrefix -> linePrefixEdit(construct, text, start, end)
        is MacroTemplate -> macroTemplateEdit(construct, text, start, end)
        is BlockTemplate -> blockTemplateEdit(construct, text, start, end)
    }
}

/**
 * Обёртка инлайн-маркерами.
 *
 * Непустое выделение: маркеры вокруг выделенного текста, сам текст не меняется
 * — и не интерпретируется: многострочное выделение оборачивается буквально,
 * чинить разметку — не дело панели (`FR-7`, `TC-8`). Каретка — сразу за
 * закрывающим маркером (`OQ-6`): продолжение набора — самый частый случай.
 *
 * Пустое выделение: пара маркеров в позицию каретки, каретка между ними —
 * содержимое набирается сразу после касания (`FR-8`).
 */
private fun inlineWrapEdit(construct: InlineWrap, text: CharSequence, start: Int, end: Int): InsertEdit {
    val replacement = buildString {
        append(construct.marker)
        append(text, start, end)
        append(construct.marker)
    }
    val caret = if (start == end) {
        start + construct.marker.length
    } else {
        start + replacement.length
    }
    return InsertEdit(
        rangeStart = start,
        rangeEnd = end,
        replacement = replacement,
        selectionStart = caret,
        selectionEnd = caret,
    )
}

/**
 * Строчная конструкция (`FR-10`, решения `OQ-2`).
 *
 * Одна строка (каретка, выделение внутри строки или заголовок при любом
 * выделении): префикс в начало логической строки; повтор — переключатель,
 * префикс на строке, уже начинающейся с него, снимается. Позиции выделения
 * сдвигаются на длину префикса; при снятии — прижимаются к началу строки,
 * если стояли внутри снятого префикса.
 *
 * Многострочное выделение у конструкции с [LinePrefix.eachSelectedLine]
 * (список): маркер на каждую строку выделения; строка, которой выделение
 * касается только позицией конца в её начале, не в счёт — выделено в ней ноль
 * символов. Переключателя здесь нет: решение `OQ-2` для этого случая называет
 * только «маркер на каждую строку». Всё — одна замена диапазона, то есть один
 * шаг отмены.
 */
private fun linePrefixEdit(construct: LinePrefix, text: CharSequence, start: Int, end: Int): InsertEdit {
    val prefix = construct.prefix
    val firstLineStart = lineStartOf(text, start)
    // Строка конца выделения: конец в начале чужой строки её не захватывает.
    val lastLineStart = if (end > start && end == lineStartOf(text, end)) {
        lineStartOf(text, end - 1)
    } else {
        lineStartOf(text, end)
    }
    val multiline = construct.eachSelectedLine && lastLineStart > firstLineStart

    if (!multiline) {
        return if (text.startsWith(prefix, firstLineStart)) {
            // Повтор — переключатель (OQ-2): префикс снимается.
            val clamp = { position: Int -> maxOf(position - prefix.length, firstLineStart) }
            InsertEdit(
                rangeStart = firstLineStart,
                rangeEnd = firstLineStart + prefix.length,
                replacement = "",
                selectionStart = clamp(start),
                selectionEnd = clamp(end),
            )
        } else {
            InsertEdit(
                rangeStart = firstLineStart,
                rangeEnd = firstLineStart,
                replacement = prefix,
                selectionStart = start + prefix.length,
                selectionEnd = end + prefix.length,
            )
        }
    }

    // Маркер на каждую строку выделения — одной заменой диапазона
    // [начало первой строки, конец выделения).
    var lineCount = 0
    val replacement = buildString {
        var lineStart = true
        for (index in firstLineStart until end) {
            if (lineStart) {
                append(prefix)
                lineCount++
            }
            val char = text[index]
            append(char)
            lineStart = char == '\n'
        }
    }
    return InsertEdit(
        rangeStart = firstLineStart,
        rangeEnd = end,
        replacement = replacement,
        // Выделение остаётся на тех же символах: начало сдвигает один маркер
        // (первой строки), конец — маркеры всех строк выделения.
        selectionStart = start + prefix.length,
        selectionEnd = end + prefix.length * lineCount,
    )
}

/**
 * Заготовка макро-формы `цель[атрибут]` (`FR-8`, решения `OQ-5`).
 *
 * Пустое выделение: вставляется форма целиком с русскими заместителями,
 * заместитель цели выделен — печать заменяет его сразу. Непустое: выделенный
 * текст встаёт на место цели (`⌶` таблицы заготовок), выделенным остаётся
 * заместитель атрибута; при пустом заместителе каретка встаёт между скобками.
 *
 * Блочная форма — с начала строки: в середине непустой строки перед заготовкой
 * добавляется перевод строки (правило блочных заготовок аналитики); хвост
 * строки за вставкой уезжает на свою строку тем же переводом — блочная форма
 * не терпит текста вплотную за `[]`.
 */
private fun macroTemplateEdit(construct: MacroTemplate, text: CharSequence, start: Int, end: Int): InsertEdit {
    val target = if (start == end) construct.targetPlaceholder else text.substring(start, end)
    val leading = if (construct.block && start > lineStartOf(text, start)) "\n" else ""
    val trailing = if (construct.block && end < text.length && text[end] != '\n') "\n" else ""

    val head = leading + construct.prefix
    val replacement = head + target + "[" + construct.attributePlaceholder + "]" + trailing

    val (selectionStart, selectionEnd) = if (start == end) {
        val from = start + head.length
        from to from + construct.targetPlaceholder.length
    } else {
        val from = start + head.length + target.length + 1
        from to from + construct.attributePlaceholder.length
    }
    return InsertEdit(
        rangeStart = start,
        rangeEnd = end,
        replacement = replacement,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
    )
}

/**
 * Блочная заготовка раскрытия `›` (`FR-11`, `TC-7`).
 *
 * Слот между [BlockTemplate.before] и [BlockTemplate.after]: пустое выделение
 * получает выделенный заместитель (или каретку при пустом), непустое встаёт в
 * слот с кареткой за собой. Переводы строк вокруг — те же правила, что у
 * блочной `image::` в [macroTemplateEdit].
 */
private fun blockTemplateEdit(construct: BlockTemplate, text: CharSequence, start: Int, end: Int): InsertEdit {
    val slot = if (start == end) construct.placeholder else text.substring(start, end)
    val leading = if (start > lineStartOf(text, start)) "\n" else ""
    val trailing = if (end < text.length && text[end] != '\n') "\n" else ""

    val head = leading + construct.before
    val replacement = head + slot + construct.after + trailing

    val (selectionStart, selectionEnd) = if (start == end) {
        start + head.length to start + head.length + construct.placeholder.length
    } else {
        val caret = start + head.length + slot.length
        caret to caret
    }
    return InsertEdit(
        rangeStart = start,
        rangeEnd = end,
        replacement = replacement,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
    )
}

/** Начало логической строки, на которой стоит [index]: позиция за ближайшим `\n` слева. */
private fun lineStartOf(text: CharSequence, index: Int): Int {
    var position = index
    while (position > 0 && text[position - 1] != '\n') {
        position--
    }
    return position
}

/**
 * Применить конструкцию к полю ввода — обработчик касания кнопки панели.
 *
 * Ровно один вызов `edit {}` на касание — это граница 🚫 плана, а не деталь
 * реализации: `edit {}` фиксирует правку в истории отмены одним нераздельным
 * шагом (`FR-9`, сторож — `DocumentEditorTest.TC_12_*`), и второй вызов
 * раздробил бы шаг. Замена диапазона и постановка каретки происходят внутри
 * одного блока на одном буфере.
 *
 * Принимающий [TextFieldState] — единственный state экрана (`FR-6`): подсветка,
 * модель документа и превью узнают о правке существующими подписками экрана,
 * панели не нужен ни один новый канал.
 */
fun TextFieldState.applyInsert(construct: InsertConstruct) {
    edit {
        val insertEdit = insertEditFor(
            construct = construct,
            text = asCharSequence(),
            selectionStart = selection.start,
            selectionEnd = selection.end,
        )
        replace(insertEdit.rangeStart, insertEdit.rangeEnd, insertEdit.replacement)
        selection = TextRange(insertEdit.selectionStart, insertEdit.selectionEnd)
    }
}
