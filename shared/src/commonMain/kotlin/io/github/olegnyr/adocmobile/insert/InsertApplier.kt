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
