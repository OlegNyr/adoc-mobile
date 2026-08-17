package io.github.olegnyr.adocmobile.preview

import adoc_mobile.shared.generated.resources.Res
import io.github.olegnyr.adocmobile.theme.AdocFontRole
import io.github.olegnyr.adocmobile.theme.AdocFonts
import kotlin.io.encoding.Base64

/**
 * Гарнитуры для WebView превью.
 *
 * WebView не видит ресурсов Compose, а `FR-27` запрещает оболочке тянуть
 * что-либо по сети. Байты шрифтов встраиваются в CSS `data:`-URI — путь,
 * который не открывает вью ни сети, ни файловой системы: доступ к файлам —
 * отдельная поверхность безопасности, и её заводит `SL-6`, а не тема.
 *
 * Состав — объявления [AdocFonts]: те же файлы, из которых собираются семейства
 * Compose. Второго списка шрифтов в проекте нет.
 *
 * Цена решения названа, а не спрятана: шесть файлов дают ~1.5 МБ base64 в
 * строке страницы. Блок поэтому собирается один раз на процесс и кэшируется —
 * как движок рендера (`FR-3`): дорогое поднимается однажды.
 */

private var cachedFontFaces: String? = null

/**
 * Блок `@font-face` со встроенными байтами всех объявленных шрифтов.
 *
 * Первый вызов читает ресурсы (suspend), дальнейшие отдают кэш. Гонка двух
 * первых вызовов безобидна: результат детерминирован, победитель любой.
 */
suspend fun previewFontFaces(): String = cachedFontFaces ?: run {
    val bytesByFile = AdocFontRole.entries
        .flatMap { role -> AdocFonts.filesOf(role) }
        .associate { file -> file.fileName to Res.readBytes("font/${file.fileName}") }
    previewFontFacesFrom { fileName -> bytesByFile.getValue(fileName) }
        .also { cachedFontFaces = it }
}

/**
 * Сборка блока `@font-face` из объявлений и источника байтов.
 *
 * Чтение байтов передано параметром, чтобы форма результата проверялась тестом
 * без ресурсов и корутин: содержимое файла на форму не влияет.
 */
internal fun previewFontFacesFrom(fontBytes: (fileName: String) -> ByteArray): String =
    buildString {
        AdocFontRole.entries.forEach { role ->
            AdocFonts.filesOf(role).forEach { file ->
                append(fontFaceCss(previewFontFamily(role), file.weight.weight, fontBytes(file.fileName)))
            }
        }
    }

/** Одно правило `@font-face`: семейство, вес, байты `data:`-URI. */
internal fun fontFaceCss(familyName: String, weight: Int, bytes: ByteArray): String = buildString {
    append("@font-face {\n")
    append("  font-family: \"").append(familyName).append("\";\n")
    append("  font-weight: ").append(weight).append(";\n")
    append("  src: url(\"data:font/ttf;base64,").append(Base64.encode(bytes)).append("\") format(\"truetype\");\n")
    append("}\n")
}
