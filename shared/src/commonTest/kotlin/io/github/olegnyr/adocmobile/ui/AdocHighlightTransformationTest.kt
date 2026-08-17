package io.github.olegnyr.adocmobile.ui

import androidx.compose.ui.text.SpanStyle
import io.github.olegnyr.adocmobile.highlight.AdocBlockScanner
import io.github.olegnyr.adocmobile.highlight.AdocRange
import io.github.olegnyr.adocmobile.highlight.AdocSpan
import io.github.olegnyr.adocmobile.highlight.AdocStyle
import io.github.olegnyr.adocmobile.theme.darkColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Трансформация отображения — слайс `SL-1a` фичи 001-syntax-highlighting.
 *
 * Главное обязательство — `FR-24`/`TC-29`: трансформация украшает отображение
 * и никогда не меняет буфер. Прямо прогнать `transformOutput` здесь нельзя:
 * `addStyle` принимается только на буфере, созданном самим полем для
 * трансформации отображения, и снаружи композиции такой буфер не собрать.
 * Поэтому проверяется [AdocHighlightTransformation.decorate] — вся логика
 * трансформации, которой от буфера видна одна лишь длина; часть «копирование
 * даёт исходный текст» уходит в ручную проверку на устройстве, а на уровне
 * сканера `TC-29` уже закрыт слайсом `SL-1`.
 *
 * Второе обязательство — устойчивость к рассинхрону: сканирование идёт по
 * изменению текста и хранится в состоянии, а отрисовка случается чаще, поэтому
 * между правкой и пересканированием диапазоны могут указывать за конец буфера.
 * Такой диапазон обрезается или выбрасывается, но не роняет поле.
 */
class AdocHighlightTransformationTest {

    private val styles = AdocHighlightStyles(darkColors)

    private val document = """
        = Заголовок
        :status: черновик

        // комментарий
        ----
        fun main() = Unit
        ----
    """.trimIndent()

    /** Всё, что трансформация сделала с буфером: только записи «стиль, диапазон». */
    private fun placements(
        transformation: AdocHighlightTransformation,
        length: Int,
    ): List<Triple<SpanStyle, Int, Int>> = buildList {
        transformation.decorate(length) { style, start, end -> add(Triple(style, start, end)) }
    }

    @Test
    fun TC_29_decorationOnlyPlacesStylesAndStaysInsideBuffer() {
        val spans = AdocBlockScanner.scan(document).spans
        val transformation = AdocHighlightTransformation(styles, spans)

        val placed = placements(transformation, document.length)

        // Диапазонов сканера немало на этом документе — трансформация обязана
        // донести каждый, ничего не придумав от себя.
        assertEquals(spans.size, placed.size, "трансформация потеряла или добавила диапазоны")
        for ((span, placement) in spans.zip(placed)) {
            val (style, start, end) = placement
            assertEquals(styles[span.style], style)
            assertEquals(span.range.start, start)
            assertEquals(span.range.endExclusive, end)
            assertTrue(end <= document.length, "стиль поставлен за концом буфера")
        }
    }

    @Test
    fun TC_29_staleSpansBeyondBufferEndAreNotPlaced() {
        // Худший рассинхрон: диапазоны сняты с длинного текста, буфер уже короче.
        val shortenedLength = "= За".length
        val transformation = AdocHighlightTransformation(styles, AdocBlockScanner.scan(document).spans)

        val placed = placements(transformation, shortenedLength)

        assertTrue(placed.isNotEmpty(), "начало заголовка ещё в буфере и должно быть подсвечено")
        for ((_, start, end) in placed) {
            assertTrue(start in 0 until shortenedLength, "начало $start вне буфера")
            assertTrue(end in (start + 1)..shortenedLength, "конец $end вне буфера")
        }
    }

    @Test
    fun spanInsideBufferIsKeptAsIs() {
        val spans = listOf(AdocSpan(AdocRange(0, 4), AdocStyle.Heading0))

        assertEquals(spans, clampSpans(spans, length = 10))
    }

    @Test
    fun spanCrossingBufferEndIsClamped() {
        val spans = listOf(AdocSpan(AdocRange(2, 8), AdocStyle.AttributeEntry))

        assertEquals(
            listOf(AdocSpan(AdocRange(2, 5), AdocStyle.AttributeEntry)),
            clampSpans(spans, length = 5),
        )
    }

    @Test
    fun spanBeyondBufferEndIsDropped() {
        val spans = listOf(
            AdocSpan(AdocRange(0, 3), AdocStyle.Heading0),
            AdocSpan(AdocRange(5, 9), AdocStyle.Comment),
        )

        assertEquals(spans.take(1), clampSpans(spans, length = 4))
    }

    @Test
    fun clampNeverProducesEmptyRanges() {
        val spans = listOf(
            AdocSpan(AdocRange(3, 3), AdocStyle.Comment), // пустой уже на входе
            AdocSpan(AdocRange(4, 9), AdocStyle.Comment), // станет пустым на границе
        )

        assertTrue(clampSpans(spans, length = 4).isEmpty())
    }

    @Test
    fun clampPreservesOrder() {
        val spans = AdocBlockScanner.scan(document).spans
        val clamped = clampSpans(spans, length = document.length)

        assertEquals(spans, clamped, "внутри буфера порядок и состав не меняются")
    }
}
