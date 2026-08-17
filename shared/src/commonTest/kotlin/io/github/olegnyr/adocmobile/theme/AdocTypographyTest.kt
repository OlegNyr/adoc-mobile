package io.github.olegnyr.adocmobile.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 002-design-system, слайс SL-2.
 * Источник значений — doc/design/design.adoc, раздел «Типографика».
 *
 * Здесь проверяются метрики и то, как они разложены по слотам Material.
 * Загрузка самих файлов шрифтов требует композиции (ресурсы Compose
 * Multiplatform читаются composable-функцией), поэтому проверки, которым нужна
 * гарнитура, принимают её параметром — как `materialScheme` принимает палитру.
 */
class AdocTypographyTest {

    private data class Expected(
        val family: AdocFontRole,
        val weight: FontWeight,
        val size: TextUnit,
        val letterSpacing: TextUnit,
        val lineHeight: TextUnit,
    )

    /** Выписано вручную из описания дизайна — тест не может брать значения из проверяемой реализации. */
    private val expected: Map<String, Expected> = mapOf(
        "screenTitle" to Expected(AdocFontRole.Heading, FontWeight.SemiBold, 19.sp, 0.04.em, TextUnit.Unspecified),
        "buttonLabel" to Expected(AdocFontRole.Heading, FontWeight.SemiBold, 16.sp, 0.06.em, TextUnit.Unspecified),
        "previewTitle" to Expected(AdocFontRole.Heading, FontWeight.SemiBold, 30.sp, 0.01.em, TextUnit.Unspecified),
        // Описание дизайна задаёт межстрочный интервал только двум стилям:
        // абзацу («line-height 1.5–1.55» от кегля 16) и коду редактора («13 px / 21 px»).
        // Остальным он не задан, и выдумывать его нельзя — там Unspecified,
        // то есть высота строки считается по метрикам гарнитуры.
        "body" to Expected(AdocFontRole.Body, FontWeight.Normal, 16.sp, 0.sp, 24.sp),
        "listItem" to Expected(AdocFontRole.Body, FontWeight.Normal, 16.sp, 0.sp, TextUnit.Unspecified),
        "editorCode" to Expected(AdocFontRole.Mono, FontWeight.Normal, 13.sp, 0.sp, 21.sp),
        "sectionLabel" to Expected(AdocFontRole.Mono, FontWeight.Normal, 11.sp, 0.1.em, TextUnit.Unspecified),
        "metadata" to Expected(AdocFontRole.Mono, FontWeight.Normal, 11.sp, 0.05.em, TextUnit.Unspecified),
    )

    /**
     * Заглушки вместо загруженных ресурсов: три заведомо разных системных
     * семейства. Тесту нужна не сама гарнитура, а то, что в слот попала
     * гарнитура *нужной роли* — три различимых значения это и показывают.
     */
    private val stubFamilies = AdocFontFamilies(
        heading = FontFamily.Cursive,
        body = FontFamily.Serif,
        mono = FontFamily.Monospace,
    )

    @Test
    fun TC_5_typographyMatchesDesignDocument() {
        val actual = AdocTypography.metrics

        assertEquals(
            expected.keys.sorted(),
            actual.keys.sorted(),
            "состав стилей разошёлся с описанием дизайна",
        )
        for ((name, want) in expected) {
            val got = actual.getValue(name)
            assertEquals(want.family, got.family, "стиль «$name»: гарнитура")
            assertEquals(want.weight, got.weight, "стиль «$name»: начертание")
            assertEquals(want.size, got.size, "стиль «$name»: кегль")
            assertEquals(want.letterSpacing, got.letterSpacing, "стиль «$name»: трекинг")
            assertEquals(want.lineHeight, got.lineHeight, "стиль «$name»: межстрочный интервал")
        }
    }

    @Test
    fun TC_11_materialTypographyUsesProjectFamilies() {
        // Без этого MaterialTheme раздаёт слоты со шрифтом системы: цвет и формы
        // темы применяются, а текст компонентов Material остаётся Roboto.
        val typography = AdocTheme.materialTypography(stubFamilies)

        val slots = mapOf(
            "displayLarge" to typography.displayLarge,
            "displayMedium" to typography.displayMedium,
            "displaySmall" to typography.displaySmall,
            "headlineLarge" to typography.headlineLarge,
            "headlineMedium" to typography.headlineMedium,
            "headlineSmall" to typography.headlineSmall,
            "titleLarge" to typography.titleLarge,
            "titleMedium" to typography.titleMedium,
            "titleSmall" to typography.titleSmall,
            "bodyLarge" to typography.bodyLarge,
            "bodyMedium" to typography.bodyMedium,
            "bodySmall" to typography.bodySmall,
            "labelLarge" to typography.labelLarge,
            "labelMedium" to typography.labelMedium,
            "labelSmall" to typography.labelSmall,
        )
        val known = setOf(stubFamilies.heading, stubFamilies.body, stubFamilies.mono)
        for ((name, style) in slots) {
            assertTrue(
                style.fontFamily in known,
                "слот Material «$name» остался с гарнитурой ${style.fontFamily}, а не с проектной",
            )
        }
    }

    @Test
    fun TC_11_materialSlotsWithProjectStyleCarryItsMetrics() {
        val typography = AdocTheme.materialTypography(stubFamilies)

        // Слоты, у которых есть прямое соответствие в описании дизайна,
        // обязаны нести его метрики целиком, а не только гарнитуру.
        val bound = mapOf(
            "titleLarge" to (typography.titleLarge to AdocTypography.screenTitle),
            "headlineMedium" to (typography.headlineMedium to AdocTypography.previewTitle),
            "bodyLarge" to (typography.bodyLarge to AdocTypography.body),
            "labelLarge" to (typography.labelLarge to AdocTypography.buttonLabel),
            "labelSmall" to (typography.labelSmall to AdocTypography.sectionLabel),
        )
        for ((name, pair) in bound) {
            val (style, metrics) = pair
            assertEquals(stubFamilies[metrics.family], style.fontFamily, "слот «$name»: гарнитура")
            assertEquals(metrics.weight, style.fontWeight, "слот «$name»: начертание")
            assertEquals(metrics.size, style.fontSize, "слот «$name»: кегль")
            assertEquals(metrics.letterSpacing, style.letterSpacing, "слот «$name»: трекинг")
        }
    }

    @Test
    fun TC_4_everyFontRoleHasDeclaredResources() {
        // Каждая роль гарнитуры должна объявлять хотя бы один файл, иначе при
        // сборке типографики она молча уедет на системный шрифт.
        for (role in AdocFontRole.entries) {
            val files = AdocFonts.filesOf(role)
            assertTrue(files.isNotEmpty(), "роль «$role» не объявляет ни одного файла шрифта")
            for (file in files) {
                assertTrue(
                    file.fileName.endsWith(".ttf"),
                    "роль «$role»: файл «${file.fileName}» не похож на шрифт",
                )
            }
        }
    }

    @Test
    fun TC_4_declaredWeightsCoverTypography() {
        // Все начертания, которые требует типографика, должны быть объявлены —
        // иначе Compose подставит ближайшее и текст поедет.
        for ((name, want) in expected) {
            val declared = AdocFonts.filesOf(want.family).map { it.weight }
            assertTrue(
                want.weight in declared,
                "стиль «$name» требует ${want.weight} у роли «${want.family}», объявлены: $declared",
            )
        }
    }

    @Test
    fun TC_4_eachDeclaredFileBindsItsOwnResource() {
        // Объявление обязано вести к отдельному ресурсу. Проверка ловит вставку
        // копированием, когда у двух начертаний оказывается один и тот же файл:
        // тогда «объявлено три веса», а на экране один.
        val all = AdocFontRole.entries.flatMap { AdocFonts.filesOf(it) }
        assertEquals(
            all.size,
            all.map { it.resource }.toSet().size,
            "разные объявления шрифтов ссылаются на один ресурс: $all",
        )
        assertEquals(
            all.size,
            all.map { it.fileName }.toSet().size,
            "имена файлов шрифтов повторяются: ${all.map { it.fileName }}",
        )
    }

    @Test
    fun TC_5_textStyleCarriesEveryMetric() {
        // Стиль строится из метрик целиком: пропущенный межстрочный интервал
        // не виден ни в одном тесте на сами метрики.
        val style = AdocTypography.editorCode.toTextStyle(stubFamilies.mono)

        assertEquals(stubFamilies.mono, style.fontFamily)
        assertEquals(AdocTypography.editorCode.weight, style.fontWeight)
        assertEquals(AdocTypography.editorCode.size, style.fontSize)
        assertEquals(AdocTypography.editorCode.letterSpacing, style.letterSpacing)
        assertEquals(21.sp, style.lineHeight, "межстрочный интервал кода редактора")
        assertNotEquals(TextUnit.Unspecified, style.lineHeight)
    }
}
