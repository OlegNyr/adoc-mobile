package io.github.olegnyr.adocmobile.ui

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.SpanStyle
import io.github.olegnyr.adocmobile.highlight.AdocRange
import io.github.olegnyr.adocmobile.highlight.AdocSpan

/**
 * Трансформация отображения: расставляет готовые диапазоны сканера по буферу —
 * слайс `SL-1a` фичи 001-syntax-highlighting, последнее звено walking skeleton.
 *
 * Два обязательства, оба из требований:
 *
 * * `FR-24`/`TC-29` — трансформация украшает *отображение* и никогда не меняет
 *   буфер: единственный вызов внутри — [TextFieldBuffer.addStyle]. Копирование
 *   отдаёт исходный текст с разметкой.
 * * Границы работ фичи — прохода сканера здесь нет. `transformOutput` вызывается
 *   на каждую отрисовку, и сканирование в нём превратило бы каждый кадр в полный
 *   проход по документу. Сканирование идёт по изменению текста, диапазоны
 *   хранятся в состоянии, экземпляр создаётся на готовый список.
 *
 * Диапазоны при этом могут отставать от буфера: между правкой и
 * пересканированием отрисовка случается раньше, чем приезжает новый список.
 * Устаревший диапазон за концом буфера обрезается или выбрасывается ([clampSpans])
 * — на один кадр подсветка чуть отстаёт, но поле не падает и текст не страдает.
 */
class AdocHighlightTransformation(
    private val styles: AdocHighlightStyles,
    private val spans: List<AdocSpan>,
) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        decorate(length) { style, start, end -> addStyle(style, start, end) }
    }

    /**
     * Вся логика трансформации, отделённая от буфера.
     *
     * От буфера сюда попадает единственное число — его длина, поэтому логика
     * *по сигнатуре* не способна ни прочитать, ни изменить текст: обязательство
     * `FR-24` здесь держится на типах, а не на дисциплине. В [transformOutput]
     * остаётся однострочный переходник на [TextFieldBuffer.addStyle].
     *
     * Вынос сделан и ради проверяемости: `addStyle` принимается только на
     * буфере, созданном самим полем для трансформации отображения, — снаружи
     * композиции такой буфер не собрать, и потому `TC-29` на этом слое
     * проверяется через [decorate], а «копирование даёт исходный текст» — на
     * устройстве.
     */
    internal fun decorate(length: Int, place: (style: SpanStyle, start: Int, end: Int) -> Unit) {
        for (span in clampSpans(spans, length)) {
            place(styles[span.style], span.range.start, span.range.endExclusive)
        }
    }
}

/**
 * Диапазоны, целиком помещающиеся в буфер длины [length]: выходящий за конец
 * обрезается по нему, оказавшийся за концом или пустой — выбрасывается.
 *
 * Вынесено из трансформации чистой функцией, чтобы устойчивость к рассинхрону
 * проверялась обычным тестом, без буфера и композиции.
 */
internal fun clampSpans(spans: List<AdocSpan>, length: Int): List<AdocSpan> =
    spans.mapNotNull { span ->
        when {
            span.range.endExclusive <= length ->
                if (span.range.length > 0) span else null

            span.range.start < length ->
                span.copy(range = AdocRange(span.range.start, length))

            else -> null
        }
    }
