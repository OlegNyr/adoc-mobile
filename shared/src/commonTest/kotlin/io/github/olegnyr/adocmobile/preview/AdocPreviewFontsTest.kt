package io.github.olegnyr.adocmobile.preview

import io.github.olegnyr.adocmobile.theme.AdocFontRole
import io.github.olegnyr.adocmobile.theme.AdocFonts
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Встраивание гарнитур в оболочку превью (`SL-3`).
 *
 * WebView не видит ресурсов Compose, а тянуть шрифты по сети запрещает `FR-27`.
 * Единственный путь, не открывающий вью доступа ни к сети, ни к файлам, —
 * `data:`-URI внутри `@font-face`. Источник состава — объявления [AdocFonts],
 * те же, из которых собираются семейства Compose: два списка шрифтов в проекте
 * разъехались бы при первой правке.
 *
 * Сборка CSS отделена от чтения ресурсов и проверяется на подставных байтах:
 * содержимое настоящих файлов здесь не важно, важна форма результата.
 */
class AdocPreviewFontsTest {

    private val declaredFiles = AdocFontRole.entries.flatMap { AdocFonts.filesOf(it) }
    private val css = previewFontFacesFrom { fileName -> fileName.encodeToByteArray() }

    @Test
    fun TC_19_fontFaceIsBuiltForEveryDeclaredFontFile() {
        // Состав @font-face — ровно объявленные файлы: и пропуск, и лишний файл
        // означают расхождение с гарнитурами самого приложения (FR-22).
        val facesCount = Regex("@font-face").findAll(css).count()
        assertEquals(declaredFiles.size, facesCount, "число @font-face не равно числу объявленных файлов")
    }

    @Test
    fun TC_19_fontFacesUseRoleFamilyNamesAndDeclaredWeights() {
        AdocFontRole.entries.forEach { role ->
            AdocFonts.filesOf(role).forEach { file ->
                val face = Regex(
                    """@font-face\s*\{[^}]*font-family:\s*"${previewFontFamily(role)}";[^}]*font-weight:\s*${file.weight.weight};""",
                )
                assertTrue(
                    face.containsMatchIn(css),
                    "нет @font-face для роли $role, файла ${file.fileName} с весом ${file.weight.weight}",
                )
            }
        }
    }

    @Test
    fun TC_20_fontBytesAreEmbeddedAsDataUri() {
        // Байты пересекают границу только внутри строки HTML: ни file://, ни
        // сети (FR-27). Оракул — точный base64 переданных байтов.
        val bytes = ByteArray(64) { (it * 7).toByte() }
        val face = fontFaceCss("stub-family", 400, bytes)
        assertTrue(
            face.contains("src: url(\"data:font/ttf;base64,${Base64.encode(bytes)}\") format(\"truetype\");"),
            "байты шрифта не встроены data:-URI:\n$face",
        )
    }

    @Test
    fun TC_20_fontFacesContainNoExternalReferences() {
        val targets = Regex("""url\(\s*['"]?([^'")]+)""").findAll(css).map { it.groupValues[1] }.toList()
        assertTrue(targets.isNotEmpty(), "в @font-face нет ни одного url() — проверка перестала что-либо проверять")
        val offenders = targets.filterNot { it.startsWith("data:") }
        assertTrue(offenders.isEmpty(), "внешние адреса в @font-face: $offenders (FR-27)")
    }
}
