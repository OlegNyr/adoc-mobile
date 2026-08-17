package io.github.olegnyr.adocmobile.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.compose.resources.Font

/**
 * Три гарнитуры проекта, разобранные по ролям.
 *
 * Собираются один раз на тему и раздаются через [LocalAdocFontFamilies]: без
 * этого каждый вызов [adocTextStyle] строил бы `FontFamily` заново, то есть на
 * каждую рекомпозицию каждой надписи.
 */
@Immutable
data class AdocFontFamilies(
    val heading: FontFamily,
    val body: FontFamily,
    val mono: FontFamily,
) {
    operator fun get(role: AdocFontRole): FontFamily = when (role) {
        AdocFontRole.Heading -> heading
        AdocFontRole.Body -> body
        AdocFontRole.Mono -> mono
    }
}

/**
 * Действующие гарнитуры внутри композиции.
 *
 * Значение по умолчанию `null`, а не системные шрифты: пустой значение здесь
 * означало бы молчаливый откат на шрифт системы — ровно тот отказ, который уже
 * один раз проехал незамеченным. Вместо этого [adocFontFamily] вне темы
 * загружает ресурсы сам, просто без кэша.
 */
val LocalAdocFontFamilies = staticCompositionLocalOf<AdocFontFamilies?> { null }

/**
 * Семейства из объявлений [AdocFonts].
 *
 * Composable вынужденно: ресурсы Compose Multiplatform читаются только внутри
 * композиции. Список файлов при этом не дублируется — он берётся из
 * объявлений, которые проверяются тестами `TC-4` и задачами сборки.
 */
@Composable
fun rememberAdocFontFamilies(): AdocFontFamilies = AdocFontFamilies(
    heading = fontFamilyOf(AdocFontRole.Heading),
    body = fontFamilyOf(AdocFontRole.Body),
    mono = fontFamilyOf(AdocFontRole.Mono),
)

@Composable
private fun fontFamilyOf(role: AdocFontRole): FontFamily =
    FontFamily(AdocFonts.filesOf(role).map { Font(it.resource, it.weight) })

/**
 * Гарнитура роли: из темы, если она есть, иначе загружается на месте.
 */
@Composable
fun adocFontFamily(role: AdocFontRole): FontFamily =
    LocalAdocFontFamilies.current?.get(role) ?: fontFamilyOf(role)

/**
 * Готовый стиль текста: метрики из [AdocTypography] плюс загруженная гарнитура.
 *
 * Экран пишет `adocTextStyle(AdocTypography.screenTitle)` — то есть просит
 * названный стиль, а не набор чисел.
 */
@Composable
fun adocTextStyle(metrics: AdocTextMetrics): TextStyle =
    metrics.toTextStyle(adocFontFamily(metrics.family))
