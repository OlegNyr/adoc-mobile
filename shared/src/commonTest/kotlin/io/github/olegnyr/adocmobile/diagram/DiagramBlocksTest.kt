package io.github.olegnyr.adocmobile.diagram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-2`: подстановка исхода на место диаграммы.
 *
 * Разметка в тестах — настоящая, снятая прогоном расширения, а не выдуманная:
 * `+<div class="imageblock kroki">+` с вложенным `+<div class="content">+` и
 * `+<img>+` внутри. Дальше от неё зависят и деградация (`SL-2`), и подстановка
 * загруженной картинки (`SL-3`).
 */
class DiagramBlocksTest {

    private val server = "https://kroki.example"

    private val diagramHtml = """
        <div class="imageblock kroki">
        <div class="content">
        <img src="https://kroki.example/plantuml/svg/eNpLVNC1U0jiAgAGdQF5" alt="alice-bob">
        </div>
        </div>
    """.trimIndent()

    /** `TC-8` — покрывает `FR-19`, `FR-20`: диаграмму взять неоткуда — показан её исходник с пометкой. */
    @Test
    fun TC_8_unavailableDiagramBecomesItsOwnSourceWithAMarker() {
        val html = resolveDiagramBlocks(diagramHtml, server) {
            DiagramOutcome.Unavailable(source = "@startuml\na -> b\n@enduml")
        }

        assertFalse("<img" in html, "изображение осталось на месте: $html")
        assertTrue("@startuml" in html, "исходник диаграммы не показан: $html")
        assertTrue("a -&gt; b" in html, "исходник не экранирован — разметка документа утекла в страницу: $html")
        assertTrue(DIAGRAM_UNAVAILABLE_NOTE in html, "нет текстовой пометки: $html")
        assertFalse("kroki.example" in html, "адрес внешнего сервера остался в странице: $html")
    }

    /** `TC-10` — покрывает `FR-22`: исходник восстановить не удалось — пометка есть, текста нет. */
    @Test
    fun TC_10_unavailableDiagramWithoutSourceStillGetsAMarker() {
        val html = resolveDiagramBlocks(diagramHtml, server) { DiagramOutcome.Unavailable(source = null) }

        assertTrue(DIAGRAM_UNAVAILABLE_NOTE in html, "нет текстовой пометки: $html")
        assertFalse("<img" in html, "изображение осталось на месте: $html")
        assertFalse("<pre" in html, "пустой блок кода вместо честного отсутствия текста: $html")
    }

    /**
     * `TC-10` — та же честность для пустого и пробельного исходника.
     *
     * Пустая строка — не «текст, который нечего показывать», а то же самое
     * «восстановить не удалось»: пустой блок кода врал бы, что диаграмма пуста.
     */
    @Test
    fun TC_10_blankSourceIsTreatedAsNoSource() {
        for (blank in listOf("", "   ", "\n\t ")) {
            val html = resolveDiagramBlocks(diagramHtml, server) { DiagramOutcome.Unavailable(source = blank) }

            assertFalse("<pre" in html, "пустой исходник «$blank» дал пустой блок кода: $html")
            assertTrue(DIAGRAM_UNAVAILABLE_NOTE in html, "пометка пропала: $html")
        }
    }

    /** `TC-6` (часть `SL-2`) — покрывает `FR-7`: пришедшая картинка подставляется вместо адреса Kroki. */
    @Test
    fun TC_6_resolvedDiagramKeepsTheBlockAndSwapsTheAddress() {
        val html = resolveDiagramBlocks(diagramHtml, server) {
            DiagramOutcome.Image(src = "https://adoc-preview.invalid/diagram/ab12.svg")
        }

        assertTrue("""<img src="https://adoc-preview.invalid/diagram/ab12.svg" alt="alice-bob">""" in html, html)
        assertFalse("kroki.example" in html, "внешний адрес остался в странице: $html")
        assertTrue("imageblock kroki" in html, "обёртка блока потеряна: $html")
    }

    /** Подставляемый адрес экранируется: кавычка в нём означала бы произвольную разметку. */
    @Test
    fun TC_29_substitutedAddressIsEscaped() {
        val html = resolveDiagramBlocks(diagramHtml, server) {
            DiagramOutcome.Image(src = """x" onerror="alert(1)""")
        }

        assertFalse("""onerror="alert(1)"""" in html, "адрес вырвался из атрибута: $html")
        assertTrue("&quot;" in html, "кавычка в адресе не экранирована: $html")
    }

    /** `TC-11` — покрывает `FR-13`: всё, кроме блоков диаграмм, доходит до страницы неизменным. */
    @Test
    fun TC_11_everythingExceptTheDiagramBlockIsUntouched() {
        val fragment = """
            <div class="paragraph">
            <p>Абзац со ссылкой <a href="https://kroki.example/docs">на документацию</a>.</p>
            </div>
            <div class="imageblock">
            <div class="content">
            <img src="pic.png" alt="Локальная картинка">
            </div>
            </div>
            <div class="imageblock">
            <div class="content">
            <img src="https://wiki.acme.io/download/attachments/12345/QUJDRA" alt="Вложение вики">
            </div>
            </div>
            <table class="tableblock frame-all grid-all stretch">
            <tbody><tr><td class="tableblock halign-left valign-top">Ячейка</td></tr></tbody>
            </table>
        """.trimIndent()

        val html = resolveDiagramBlocks(fragment, server) { DiagramOutcome.Unavailable(source = "не должно случиться") }

        assertEquals(fragment, html, "тронуто что-то, кроме блока диаграммы")
    }

    /** `TC-9` — покрывает `FR-21`: отказ одной диаграммы не влияет на соседнюю. */
    @Test
    fun TC_9_oneDiagramFailsAndTheOtherSurvives() {
        val fragment = """
            <div class="imageblock kroki">
            <div class="content">
            <img src="https://kroki.example/plantuml/svg/AAAA" alt="первая">
            </div>
            </div>
            <div class="imageblock kroki">
            <div class="content">
            <img src="https://kroki.example/mermaid/svg/BBBB" alt="вторая">
            </div>
            </div>
        """.trimIndent()

        val html = resolveDiagramBlocks(fragment, server) { address ->
            if (address.type == "plantuml") {
                DiagramOutcome.Unavailable(source = "@startuml")
            } else {
                DiagramOutcome.Image(src = "https://adoc-preview.invalid/diagram/bb.svg")
            }
        }

        assertTrue(DIAGRAM_UNAVAILABLE_NOTE in html, "отказавшая диаграмма без пометки: $html")
        assertTrue("@startuml" in html, "исходник отказавшей диаграммы не показан: $html")
        assertTrue("https://adoc-preview.invalid/diagram/bb.svg" in html, "удачная диаграмма не подставлена: $html")
        assertEquals(1, Regex("<img").findAll(html).count(), "изображений должно остаться ровно одно: $html")
    }

    @Test
    fun TC_11_titleAndRoleOfTheDiagramBlockSurviveDegradation() {
        val fragment = """
            <div class="myrole kroki-format-svg kroki imageblock">
            <div class="content">
            <img src="https://kroki.example/plantuml/svg/AAAA" alt="Схема обмена">
            </div>
            <div class="title">Figure 1. Схема обмена</div>
            </div>
        """.trimIndent()

        val html = resolveDiagramBlocks(fragment, server) { DiagramOutcome.Unavailable(source = "@startuml") }

        assertTrue("""<div class="title">Figure 1. Схема обмена</div>""" in html, "подпись рисунка потеряна: $html")
        assertTrue("myrole" in html, "роль блока потеряна: $html")
    }

    /**
     * Заголовок диаграммы с угловой скобкой: конвертер экранирует её в `alt`, и
     * тег не обрывается раньше времени.
     *
     * Разбор ищет конец тега по первому `+>+`, и незаэкранированная скобка в
     * значении атрибута обрезала бы тег посередине. Проверяется именно та
     * форма, которую порождает конвертер, — иначе тест доказывал бы свойство
     * выдуманной разметки.
     */
    @Test
    fun TC_11_escapedAngleBracketInAltDoesNotCutTheTag() {
        val fragment = """
            <div class="imageblock kroki">
            <div class="content">
            <img src="https://kroki.example/plantuml/svg/AAAA" alt="a &gt; b">
            </div>
            </div>
        """.trimIndent()

        val html = resolveDiagramBlocks(fragment, server) {
            DiagramOutcome.Image(src = "https://adoc-preview.invalid/diagram/aa.svg")
        }

        assertTrue("""<img src="https://adoc-preview.invalid/diagram/aa.svg" alt="a &gt; b">""" in html, html)
    }

    /** Адрес диаграммы нигде не остаётся: он обратим и приравнен к содержимому документа (`NFR-8`). */
    @Test
    fun TC_29_diagramAddressNeverSurvivesInThePage() {
        val html = resolveDiagramBlocks(diagramHtml, server) { DiagramOutcome.Unavailable(source = null) }

        assertFalse("eNpLVNC1U0jiAgAGdQF5" in html, "закодированный исходник остался в странице: $html")
    }
}
