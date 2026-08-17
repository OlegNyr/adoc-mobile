package io.github.olegnyr.adocmobile.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Точка доступа к теме проекта: роли палитры и формы.
 *
 * Скругления выключены здесь, а не подавляются у каждого компонента по месту —
 * так требует doc/design/design.adoc, раздел «Визуальный язык».
 */
object AdocTheme {

    /** Схема по умолчанию. Светлая появится отдельной задачей и заменит значение при выборе темы. */
    val defaultColors: AdocColors = darkColors

    /**
     * Все формы без скругления. `RectangleShape` здесь неприменим: Material3
     * принимает только `CornerBasedShape`, поэтому прямоугольность выражается
     * нулевым радиусом.
     *
     * Ограничение: публичный конструктор `Shapes` задаёт пять слотов из восьми.
     * Три оставшихся (`largeIncreased`, `extraLargeIncreased`, `extraExtraLarge`)
     * закрыты `internal` и остаются скруглёнными — их берут FAB среднего размера,
     * которых в макетах проекта нет.
     */
    val shapes: Shapes = Shapes(
        extraSmall = RoundedCornerShape(0.dp),
        small = RoundedCornerShape(0.dp),
        medium = RoundedCornerShape(0.dp),
        large = RoundedCornerShape(0.dp),
        extraLarge = RoundedCornerShape(0.dp),
    )

    /**
     * Действующая схема внутри композиции.
     *
     * `@ReadOnlyComposable` — как у аналога в Material: чтение не создаёт группу
     * в слоте композиции и остаётся доступным из других `@ReadOnlyComposable`.
     */
    val colors: AdocColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAdocColors.current

    /**
     * Отображение ролей проекта на цветовую схему Material.
     *
     * Без него `MaterialTheme` подставляет *светлую* схему по умолчанию, и любой
     * компонент Material, берущий цвет из схемы, оказывается светлым на тёмном
     * экране. Роли проекта шире схемы Material, поэтому отображение неполное:
     * то, чему соответствия нет, остаётся на умолчаниях тёмной схемы.
     *
     * `error` и `onError` намеренно не переопределяются: семантические цвета
     * проекта не утверждены, а выдумывать их запрещено границами работ.
     */
    fun materialScheme(colors: AdocColors): ColorScheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.accentText,
        onSecondary = colors.onAccent,
        background = colors.ground,
        onBackground = colors.textPrimary,
        surface = colors.ground,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.raised,
        onSurfaceVariant = colors.textSecondary,
        outline = colors.borderObject,
        outlineVariant = colors.borderList,
    )
}

/** Роли палитры внутри композиции. Значение по умолчанию — тёмная схема. */
val LocalAdocColors = staticCompositionLocalOf { darkColors }

/**
 * Корневая тема приложения. Оборачивает Material3, но роли цвета берутся не из
 * его схемы: набор ролей проекта шире и называет то, чего в Material нет —
 * границу объекта, утопленную поверхность, служебный текст.
 */
@Composable
fun AdocTheme(
    colors: AdocColors = AdocTheme.defaultColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAdocColors provides colors) {
        MaterialTheme(
            colorScheme = AdocTheme.materialScheme(colors),
            shapes = AdocTheme.shapes,
            content = content,
        )
    }
}
