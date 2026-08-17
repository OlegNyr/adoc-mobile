package io.github.olegnyr.adocmobile.theme

import adoc_mobile.shared.generated.resources.Res
import adoc_mobile.shared.generated.resources.barlow_condensed_semibold
import adoc_mobile.shared.generated.resources.barlow_medium
import adoc_mobile.shared.generated.resources.barlow_regular
import adoc_mobile.shared.generated.resources.jetbrains_mono_bold
import adoc_mobile.shared.generated.resources.jetbrains_mono_medium
import adoc_mobile.shared.generated.resources.jetbrains_mono_regular
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.FontResource

/**
 * Роли гарнитур. Экран просит «заголовочную» или «моноширинную», а не Barlow
 * Condensed — так же, как цвет берётся ролью, а не значением.
 */
enum class AdocFontRole {
    /** Barlow Condensed: заголовки экранов, подписи кнопок, заголовки в превью. */
    Heading,

    /** Barlow: человеческий текст — имена файлов, названия настроек, абзацы. */
    Body,

    /** JetBrains Mono: код, пути, ветки, счётчики, служебные метки. */
    Mono,
}

/**
 * Файл шрифта: ресурс, из которого он читается, начертание, которое он даёт, и
 * имя файла.
 *
 * Имя нужно не коду, а проверкам: задача сборки `verifyFontDeclarations`
 * сверяет объявленные здесь имена с содержимым каталога ресурсов, а
 * `verifyFontsPackaged` — с содержимым APK. Так объявление, файл на диске и
 * файл в сборке связаны в одну цепочку, а не живут тремя независимыми списками.
 */
data class AdocFontFile(
    val fileName: String,
    val weight: FontWeight,
    val resource: FontResource,
)

/**
 * Объявленные файлы шрифтов — единственный источник, из которого собираются
 * семейства (см. [rememberAdocFontFamilies]).
 *
 * Состав начертаний выведен из таблицы «Типографика» описания дизайна и
 * намеренно минимален: каждый файл — это вес в APK. Происхождение файлов и
 * лицензии — в `shared/licenses/fonts/SOURCES.adoc`.
 */
object AdocFonts {

    private val files: Map<AdocFontRole, List<AdocFontFile>> = mapOf(
        AdocFontRole.Heading to listOf(
            AdocFontFile("barlow_condensed_semibold.ttf", FontWeight.SemiBold, Res.font.barlow_condensed_semibold),
        ),
        AdocFontRole.Body to listOf(
            AdocFontFile("barlow_regular.ttf", FontWeight.Normal, Res.font.barlow_regular),
            AdocFontFile("barlow_medium.ttf", FontWeight.Medium, Res.font.barlow_medium),
        ),
        AdocFontRole.Mono to listOf(
            AdocFontFile("jetbrains_mono_regular.ttf", FontWeight.Normal, Res.font.jetbrains_mono_regular),
            AdocFontFile("jetbrains_mono_medium.ttf", FontWeight.Medium, Res.font.jetbrains_mono_medium),
            AdocFontFile("jetbrains_mono_bold.ttf", FontWeight.Bold, Res.font.jetbrains_mono_bold),
        ),
    )

    /** Файлы, объявленные для роли. Пустой список означает, что роль уедет на системный шрифт. */
    fun filesOf(role: AdocFontRole): List<AdocFontFile> = files.getValue(role)
}
