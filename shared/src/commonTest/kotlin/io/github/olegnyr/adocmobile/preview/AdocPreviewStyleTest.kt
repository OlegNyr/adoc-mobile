package io.github.olegnyr.adocmobile.preview

import androidx.compose.ui.graphics.Color
import io.github.olegnyr.adocmobile.theme.AdocFontRole
import io.github.olegnyr.adocmobile.theme.darkColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 003-render-preview, слайс `SL-3` — тема превью.
 *
 * `TC-18` — стиль покрывает перечень классов, которые конвертер порождает
 * фактически (`FR-21`); `TC-19` — цвета и гарнитуры приходят из токенов темы, а
 * не литералами (`FR-22`); `TC-20` — оболочка не тянет ничего по сети (`FR-27`).
 *
 * У `TC-22` (белая вспышка), `TC-23` (ширина) и `TC-30` (масштабирование) здесь
 * проверяемая без устройства половина: правила в CSS и объявление `viewport`.
 * Вторая половина — глазами и пикселями на устройстве, см. журнал слайса.
 */
class AdocPreviewStyleTest {

    private val stylesheet = previewStylesheet(darkColors)

    /**
     * `TC-31` фичи 008 (`FR-20`, NFR доступности): блок недоступной диаграммы
     * оформлен, и смысл несёт не цвет.
     *
     * Проверяется наличие правил и то, что цвета берутся из ролей палитры —
     * литералов в этом файле нет и быть не может. Читаемость самой пометки
     * глазами остаётся ручной проверкой: её оракул — человек.
     */
    @Test
    fun TC_31_unavailableDiagramBlockIsStyled() {
        for (selector in listOf(".kroki-unavailable", ".kroki-note", ".kroki-unavailable pre")) {
            assertTrue(selector in stylesheet, "в стиле нет правила для «$selector»")
        }
        // Пометка набрана моноширинной гарнитурой служебных меток дизайна, как
        // и прочие метки превью, а не выделена одним лишь цветом.
        val note = stylesheet.substringAfter(".kroki-note {").substringBefore("}")
        assertTrue("font-family" in note, "пометка не задаёт гарнитуру: $note")
        assertTrue("letter-spacing" in note, "пометка не задаёт трекинг служебной метки: $note")
    }

    // ---- TC-18: перечень классов из FR-21, снятый прогоном конвертера ----

    @Test
    fun TC_18_stylesheetCoversEveryClassTheConverterEmits() {
        // Перечень — из FR-21 дословно. Проверка списком, а не «на глаз»:
        // выпавший из стиля класс валит тест с именем класса.
        val selectors = listOf(
            "#toc",
            "#toctitle",
            ".sect1",
            ".sect2",
            ".sect3",
            ".sect4",
            ".paragraph",
            ".admonitionblock",
            "td.icon",
            ".listingblock",
            ".literalblock",
            "pre.highlight",
            "code[data-lang]",
            ".tableblock",
            "colgroup",
            ".ulist",
            ".olist",
            ".dlist",
            ".imageblock",
            "span.image",
            ".quoteblock",
            "#footnotes",
            "hr",
        )
        val missing = selectors.filterNot { stylesheet.contains(it) }
        assertTrue(missing.isEmpty(), "в стиле превью нет правил для: $missing (FR-21)")
    }

    // ---- TC-19: цвета и гарнитуры только из токенов ----

    @Test
    fun TC_19_cssColorRendersRolesAsLowercaseHex() {
        // Оракул задан литералами из описания дизайна, а не производством от
        // проверяемой функции: тесту положено содержать ожидаемые значения.
        assertEquals("#131518", cssColor(Color(0xFF131518)))
        assertEquals("#94bce3", cssColor(Color(0xFF94BCE3)))
        assertEquals("#000000", cssColor(Color(0xFF000000)))
    }

    @Test
    fun TC_19_everyColorInStylesheetComesFromPaletteRoles() {
        val allowed = with(darkColors) {
            setOf(
                ground, chrome, raised, sunken, toolbar,
                borderChrome, borderObject, borderList,
                accent, accentHover, accentText, accentSecondary,
                accentSelection, accentTrack, onAccent,
                textPrimary, textSecondary, textParagraph, textMuted, textFaint,
                comment, lineNumber,
            ).map(::cssColor).toSet()
        }
        val used = Regex("#[0-9a-fA-F]{6}").findAll(stylesheet).map { it.value }.toList()
        assertTrue(used.isNotEmpty(), "стиль не содержит ни одного цвета — проверка перестала что-либо проверять")
        val offenders = used.filterNot { it in allowed }
        assertTrue(offenders.isEmpty(), "цвета мимо ролей палитры: $offenders (FR-22)")
    }

    @Test
    fun TC_19_everyFontFamilyInStylesheetComesFromRoleNames() {
        // Разрешены только имена семейств, производные от ролей гарнитур, и
        // generic-ключевые слова CSS. «Barlow» литералом — обход токена.
        val allowed = AdocFontRole.entries.map(::previewFontFamily).toSet() + setOf("sans-serif", "monospace")
        val declarations = Regex("""font-family:\s*([^;}]+)""").findAll(stylesheet).toList()
        assertTrue(declarations.isNotEmpty(), "стиль не задаёт ни одной гарнитуры")
        val names = declarations
            .flatMap { it.groupValues[1].split(",") }
            .map { it.trim().trim('"', '\'') }
        val offenders = names.filterNot { it in allowed }
        assertTrue(offenders.isEmpty(), "гарнитуры мимо ролей темы: $offenders (FR-22)")
    }

    // ---- TC-20: ноль внешних ресурсов ----

    @Test
    fun TC_20_shellReferencesNoExternalResources() {
        // Оболочка с встроенными шрифтами — худший случай: в base64 легально
        // встречается `//`, поэтому проверяются адреса ссылок, а не подстроки.
        val fontFaces = fontFaceCss("stub", 400, ByteArray(256) { it.toByte() })
        val document = previewDocument("", previewStylesheet(darkColors, fontFaces))

        val targets =
            Regex("""(?:src|href)\s*=\s*"([^"]*)"""").findAll(document).map { it.groupValues[1] } +
                Regex("""url\(\s*['"]?([^'")]+)""").findAll(document).map { it.groupValues[1] }
        val offenders = targets
            .filterNot { it.startsWith("data:") || it.startsWith("#") }
            .toList()
        assertTrue(offenders.isEmpty(), "внешние адреса в оболочке: $offenders (FR-27)")

        assertTrue(!document.contains("<link"), "оболочка подключает внешний ресурс тегом <link> (FR-27)")
        assertTrue(!document.contains("<script"), "оболочке не положен <script>: превью статично (FR-27)")
    }

    // ---- TC-22: тёмный фон задан до первой отрисовки (проверяемая половина) ----

    @Test
    fun TC_22_stylesheetPaintsGroundColorOnHtmlAndBody() {
        // Фон на html, а не только на body: WebView красит «занавес» за пределами
        // body цветом корневого элемента, и без этого правила края страницы
        // остаются белыми до загрузки. Значение — роль «фон экрана».
        val htmlRule = Regex("""html\s*\{[^}]*background-color:\s*#131518""")
        val bodyRule = Regex("""body\s*\{[^}]*background-color:\s*#131518""")
        assertTrue(htmlRule.containsMatchIn(stylesheet), "html без фона роли ground (FR-20)")
        assertTrue(bodyRule.containsMatchIn(stylesheet), "body без фона роли ground (FR-20)")
    }

    @Test
    fun TC_22_styleIsDeclaredBeforeBody() {
        // Стиль обязан быть в <head>: объявленный после содержимого фон дал бы
        // ровно ту вспышку, которую FR-20 запрещает.
        val document = previewDocument("<p>текст</p>")
        val styleAt = document.indexOf("<style>")
        val bodyAt = document.indexOf("<body>")
        assertTrue(styleAt in 0 until bodyAt, "стиль объявлен не раньше содержимого (FR-20)")
    }

    // ---- TC-23: ширина по экрану (проверяемая половина) ----

    @Test
    fun TC_23_pageForbidsHorizontalScrollAndWideBlocksScrollInside() {
        // Страница не прокручивается по горизонтали; широкое — таблица, длинная
        // строка кода — прокручивается в своём контейнере (FR-24).
        assertTrue(
            Regex("""body\s*\{[^}]*overflow-x:\s*hidden""").containsMatchIn(stylesheet),
            "страница не запрещает горизонтальную прокрутку",
        )
        assertTrue(
            Regex("""pre[^{]*\{[^}]*overflow-x:\s*auto""").containsMatchIn(stylesheet),
            "блок кода не прокручивается внутри себя",
        )
        assertTrue(
            Regex("""tableblock[^{]*\{[^}]*overflow-x:\s*auto""").containsMatchIn(stylesheet),
            "таблица не прокручивается внутри себя",
        )
    }

    @Test
    fun TC_23_imagesNeverExceedScreenWidth() {
        assertTrue(
            Regex("""img[^{]*\{[^}]*max-width:\s*100%""").containsMatchIn(stylesheet),
            "изображение шире экрана растянет страницу (FR-24)",
        )
    }

    // ---- TC-30: масштабирование не запрещено (проверяемая половина) ----

    @Test
    fun TC_30_viewportAllowsUserScaling() {
        // NFR доступности: `user-scalable=no` и `maximum-scale` запрещены —
        // масштабирование жестом должно оставаться доступным.
        val document = previewDocument("")
        assertTrue(!document.contains("user-scalable"), "viewport запрещает масштабирование")
        assertTrue(!document.contains("maximum-scale"), "viewport ограничивает масштаб")
    }
}
