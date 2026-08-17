package io.github.olegnyr.adocmobile.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.olegnyr.adocmobile.highlight.AdocStyle
import io.github.olegnyr.adocmobile.theme.darkColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Отображение ролей сканера в стили текста — слайс `SL-1a` фичи
 * 001-syntax-highlighting.
 *
 * Проверяются два обязательства. Первое — полнота: каждая роль [AdocStyle]
 * получает стиль, и появление новой роли без отображения валит прогон, а не
 * молча оставляет её неокрашенной. Второе — соответствие описанию дизайна:
 * четыре отображения заданы макетом (design.adoc, «Подсветка в макете») и
 * закреплены здесь по значениям палитры, чтобы расхождение с макетом было
 * видно тестом, а не глазами.
 */
class AdocHighlightStylesTest {

    private val colors = darkColors
    private val styles = AdocHighlightStyles(colors)

    @Test
    fun everyRoleHasAStyle() {
        for (role in AdocStyle.entries) {
            styles[role] // getValue кидает исключение, если роли нет в таблице.
        }
    }

    @Test
    fun designMapping_headingsAreAccentTextAndBold() {
        // design.adoc: «заголовки #94bce3 полужирным» — роль accentText.
        val headings = listOf(
            AdocStyle.Heading0, AdocStyle.Heading1, AdocStyle.Heading2,
            AdocStyle.Heading3, AdocStyle.Heading4, AdocStyle.Heading5,
        )
        for (heading in headings) {
            assertEquals(colors.accentText, styles[heading].color, "цвет $heading")
            assertEquals(FontWeight.Bold, styles[heading].fontWeight, "начертание $heading")
        }
    }

    @Test
    fun designMapping_attributeEntryIsAccentSecondary() {
        // design.adoc: «атрибуты #7e9cb8» — роль accentSecondary,
        // заведённая в палитре именно под это.
        assertEquals(colors.accentSecondary, styles[AdocStyle.AttributeEntry].color)
    }

    @Test
    fun designMapping_blockDelimitersAndBlockAttributesAreCommentColored() {
        // design.adoc: «разделители блоков и [NOTE] — #5d6a75» — роль comment.
        assertEquals(colors.comment, styles[AdocStyle.BlockDelimiter].color)
        assertEquals(colors.comment, styles[AdocStyle.BlockAttributes].color)
        assertEquals(colors.comment, styles[AdocStyle.Admonition].color)
    }

    @Test
    fun commentsAreDistinguishedByFaceNotOnlyByColor() {
        // NFR доступности: цвет не единственный носитель смысла. Комментарий
        // делит цвет с разделителем блока, поэтому его отличает начертание.
        assertEquals(colors.comment, styles[AdocStyle.Comment].color)
        assertEquals(FontStyle.Italic, styles[AdocStyle.Comment].fontStyle)
    }

    @Test
    fun verbatimContentIsCarriedByBackgroundNotColor() {
        // Дословное содержимое отмечается фоном (роль raised — «блоки кода»),
        // а не цветом текста: внутри listing текст остаётся текстом.
        assertEquals(colors.raised, styles[AdocStyle.VerbatimContent].background)
        assertEquals(Color.Unspecified, styles[AdocStyle.VerbatimContent].color)
    }

    @Test
    fun everyStyleColorComesFromThePalette() {
        // Страховка той же природы, что verifyNoColorLiterals: стиль подсветки
        // не имеет права принести цвет мимо ролей палитры.
        val palette = setOf(
            colors.ground, colors.chrome, colors.raised, colors.sunken, colors.toolbar,
            colors.borderChrome, colors.borderObject, colors.borderList,
            colors.accent, colors.accentHover, colors.accentText, colors.accentSecondary,
            colors.accentSelection, colors.accentTrack, colors.onAccent,
            colors.textPrimary, colors.textSecondary, colors.textParagraph,
            colors.textMuted, colors.textFaint, colors.comment, colors.lineNumber,
            Color.Unspecified,
        )
        for (role in AdocStyle.entries) {
            val style = styles[role]
            if (style.color !in palette) fail("цвет роли $role взят мимо палитры: ${style.color}")
            if (style.background !in palette) fail("фон роли $role взят мимо палитры: ${style.background}")
        }
    }

    @Test
    fun stylesAreVisibleAgainstEditorBase() {
        // Стиль, совпадающий с базовым начертанием редактора целиком, ничего не
        // подсвечивает. У каждой роли должно быть хоть одно отличие: цвет, фон,
        // насыщенность или наклон.
        for (role in AdocStyle.entries) {
            val style = styles[role]
            val differs = style.color != Color.Unspecified ||
                style.background != Color.Unspecified ||
                style.fontWeight != null ||
                style.fontStyle != null
            assertTrue(differs, "роль $role неотличима от обычного текста")
        }
    }
}
