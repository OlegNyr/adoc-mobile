package io.github.olegnyr.adocmobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

/**
 * Критерии приёмки фичи 002-design-system, слайс SL-1.
 * Источник значений — doc/design/design.adoc, раздел «Палитра».
 *
 * Имена тестов латиницей с разделителем подчёркиванием: имена в обратных
 * кавычках с пробелами поддержаны не на всех целевых платформах Kotlin, а
 * commonTest будет собираться и под iOS.
 */
class AdocThemeTest {

    /**
     * Роли в виде отображения «имя — значение».
     *
     * Живёт в тестах, а не в production: production-коду это отображение не нужно.
     * Ограничение метода известно и односторонне: роль, добавленную в [AdocColors]
     * и забытую здесь, тест не заметит. Полностью это закрывается только
     * рефлексией, недоступной в общем коде.
     */
    private fun AdocColors.asMap(): Map<String, Color> = mapOf(
        "ground" to ground,
        "chrome" to chrome,
        "raised" to raised,
        "sunken" to sunken,
        "toolbar" to toolbar,
        "borderChrome" to borderChrome,
        "borderObject" to borderObject,
        "borderList" to borderList,
        "accent" to accent,
        "accentHover" to accentHover,
        "accentText" to accentText,
        "accentSecondary" to accentSecondary,
        "accentSelection" to accentSelection,
        "accentTrack" to accentTrack,
        "onAccent" to onAccent,
        "textPrimary" to textPrimary,
        "textSecondary" to textSecondary,
        "textParagraph" to textParagraph,
        "textMuted" to textMuted,
        "textFaint" to textFaint,
        "comment" to comment,
        "lineNumber" to lineNumber,
    )

    /**
     * Ожидаемая палитра, выписанная из описания дизайна вручную.
     * Дублирование намеренное: тест ловит расхождение реализации с документом,
     * поэтому не может брать значения из той же реализации.
     */
    private val expected: Map<String, Color> = mapOf(
        "ground" to Color(0xFF131518),
        "chrome" to Color(0xFF17191C),
        "raised" to Color(0xFF16191D),
        "sunken" to Color(0xFF101317),
        "toolbar" to Color(0xFF1A1D21),
        "borderChrome" to Color(0xFF262C33),
        "borderObject" to Color(0xFF2C3A47),
        "borderList" to Color(0xFF1F242A),
        "accent" to Color(0xFF5980A6),
        "accentHover" to Color(0xFF749DC4),
        "accentText" to Color(0xFF94BCE3),
        "accentSecondary" to Color(0xFF7E9CB8),
        "accentSelection" to Color(0xFF1A2530),
        "accentTrack" to Color(0xFF2C455D),
        "onAccent" to Color(0xFF0D1318),
        "textPrimary" to Color(0xFFE6E8EA),
        "textSecondary" to Color(0xFFC6CBD0),
        "textParagraph" to Color(0xFFB9BFC5),
        "textMuted" to Color(0xFF8B9299),
        "textFaint" to Color(0xFF6F767D),
        "comment" to Color(0xFF5D6A75),
        "lineNumber" to Color(0xFF414A53),
    )

    /**
     * Схема-заглушка: 22 произвольных значения, не совпадающих ни с тёмной
     * палитрой, ни с каноническими светлыми (#FFFFFF / #000000) — чтобы её
     * нельзя было принять за образец будущей светлой темы.
     */
    private val stubColors: AdocColors = AdocColors(
        ground = Color(0xFF102030),
        chrome = Color(0xFF112131),
        raised = Color(0xFF122232),
        sunken = Color(0xFF132333),
        toolbar = Color(0xFF142434),
        borderChrome = Color(0xFF152535),
        borderObject = Color(0xFF162636),
        borderList = Color(0xFF172737),
        accent = Color(0xFF182838),
        accentHover = Color(0xFF192939),
        accentText = Color(0xFF1A2A3A),
        accentSecondary = Color(0xFF1B2B3B),
        accentSelection = Color(0xFF1C2C3C),
        accentTrack = Color(0xFF1D2D3D),
        onAccent = Color(0xFF1E2E3E),
        textPrimary = Color(0xFF1F2F3F),
        textSecondary = Color(0xFF203040),
        textParagraph = Color(0xFF213141),
        textMuted = Color(0xFF223242),
        textFaint = Color(0xFF233343),
        comment = Color(0xFF243444),
        lineNumber = Color(0xFF253545),
    )

    @Test
    fun TC_1_darkPaletteMatchesDesignDocument() {
        val actual = darkColors.asMap()

        assertEquals(
            expected.keys.sorted(),
            actual.keys.sorted(),
            "состав ролей разошёлся с описанием дизайна",
        )
        for ((role, value) in expected) {
            assertEquals(value, actual[role], "роль «$role» не совпадает с описанием дизайна")
        }
    }

    @Test
    fun TC_2_darkSchemeIsUsedByDefault() {
        assertSame(
            darkColors,
            AdocTheme.defaultColors,
            "по умолчанию должна применяться тёмная схема",
        )
        // Наблюдаемое следствие, а не факт присваивания: схема, которую тема
        // отдаёт Material по умолчанию, построена из тёмных ролей.
        val scheme = AdocTheme.materialScheme(AdocTheme.defaultColors)
        assertEquals(darkColors.ground, scheme.background)
        assertEquals(darkColors.textPrimary, scheme.onBackground)
    }

    @Test
    fun TC_3_allShapesAreSquare() {
        // Material3 Shapes принимает только CornerBasedShape, поэтому
        // «прямоугольность» выражается нулевым скруглением, а не RectangleShape.
        val square = RoundedCornerShape(0.dp)
        val shapes = AdocTheme.shapes
        val all = listOf(
            "extraSmall" to shapes.extraSmall,
            "small" to shapes.small,
            "medium" to shapes.medium,
            "large" to shapes.large,
            "extraLarge" to shapes.extraLarge,
        )
        for ((name, shape) in all) {
            assertEquals(square, shape, "форма «$name» должна быть без скругления")
        }
    }

    @Test
    fun TC_7_roleSetIsIndependentOfScheme() {
        val stub = stubColors.asMap()
        val dark = darkColors.asMap()

        assertEquals(dark.keys, stub.keys, "смена схемы не должна менять состав ролей")

        // Ни одно значение тёмной палитры не участвует в результате.
        for (role in dark.keys) {
            assertNotEquals(
                dark[role],
                stub[role],
                "роль «$role» в заглушке совпала с тёмной палитрой",
            )
        }

        // Тема собирается с произвольной схемой: отображение на Material строится
        // из переданных ролей, а не из тёмных.
        val scheme = AdocTheme.materialScheme(stubColors)
        assertEquals(stubColors.ground, scheme.background)
        assertEquals(stubColors.accent, scheme.primary)
        assertNotEquals(darkColors.ground, scheme.background)
    }

    @Test
    fun TC_10_materialSchemeIsDerivedFromRoles() {
        val scheme = AdocTheme.materialScheme(darkColors)

        assertEquals(darkColors.ground, scheme.background, "фон Material должен быть ground")
        assertEquals(darkColors.ground, scheme.surface, "поверхность Material должна быть ground")
        assertEquals(darkColors.textPrimary, scheme.onBackground)
        assertEquals(darkColors.textPrimary, scheme.onSurface)
        assertEquals(darkColors.raised, scheme.surfaceVariant)
        assertEquals(darkColors.textSecondary, scheme.onSurfaceVariant)
        assertEquals(darkColors.accent, scheme.primary)
        assertEquals(darkColors.onAccent, scheme.onPrimary)
        assertEquals(darkColors.borderObject, scheme.outline)
        assertEquals(darkColors.borderList, scheme.outlineVariant)

        // Светлая схема Material по умолчанию имеет белый фон — убеждаемся,
        // что подставлена не она.
        assertNotEquals(Color.White, scheme.background)
    }
}
