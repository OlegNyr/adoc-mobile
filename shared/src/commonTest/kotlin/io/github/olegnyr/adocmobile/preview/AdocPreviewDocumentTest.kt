package io.github.olegnyr.adocmobile.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 003-render-preview, слайс `SL-1` — оболочка документа.
 *
 * `TC-17` проверяет ровно `FR-19`: конвертер отдаёт *фрагмент*, а WebView нужен
 * документ. Сборка документа — общий код, платформе остаётся только показ.
 *
 * Слайс намеренно беден по содержимому оболочки: цвета, гарнитуры и перечень
 * классов (`FR-20`…`FR-22`, `FR-24`) закрывает `SL-3`, и проверять их здесь
 * значило бы писать требование задним числом.
 */
class AdocPreviewDocumentTest {

    private val fragment = """<div class="paragraph"><p>текст превью</p></div>"""
    private val document = previewDocument(fragment)

    @Test
    fun TC_17_documentStartsWithDoctype() {
        // Без doctype WebView уходит в quirks mode, и дальше расходится всё:
        // ширина, размеры шрифта, поведение таблиц.
        assertTrue(
            document.startsWith("<!DOCTYPE html>"),
            "документ не начинается с doctype:\n${document.take(80)}",
        )
    }

    @Test
    fun TC_17_documentDeclaresUtf8() {
        // Кодировка объявляется в разметке, а не только в вызове загрузки:
        // документ должен читаться и сам по себе.
        assertTrue(
            document.contains("""<meta charset="utf-8">"""),
            "в оболочке нет объявления кодировки",
        )
    }

    @Test
    fun TC_17_documentDeclaresViewport() {
        // Без viewport WebView считает страницу «десктопной» шириной 980 px и
        // показывает её уменьшенной — текст нечитаем.
        assertTrue(
            document.contains("""<meta name="viewport" content="width=device-width, initial-scale=1">"""),
            "в оболочке нет объявления viewport",
        )
    }

    @Test
    fun TC_17_documentCarriesInlineStyle() {
        // Стиль встроенный: FR-27 запрещает тянуть что-либо по сети, а SL-3
        // будет наполнять именно этот блок.
        val style = document.substringAfter("<style>", "").substringBefore("</style>", "")
        assertTrue(style.isNotBlank(), "в оболочке нет встроенного стиля")
    }

    @Test
    fun TC_17_fragmentIsPlacedInsideBodyUnchanged() {
        // Оболочка ничего не правит в выводе конвертера — «не подменять эталон»
        // из границ работ. Оракул строгий: тело равно фрагменту, а не содержит его.
        assertEquals(
            fragment,
            document.substringAfter("<body>").substringBefore("</body>").trim(),
        )
    }

    @Test
    fun TC_17_emptyFragmentGivesValidEmptyDocument() {
        // Пустой документ — штатный случай (UC-1: «превью показывает пустую
        // страницу без ошибки»), а не повод собрать сломанную разметку.
        val empty = previewDocument("")
        assertTrue(empty.startsWith("<!DOCTYPE html>"), "пустой фрагмент сломал оболочку")
        assertEquals("", empty.substringAfter("<body>").substringBefore("</body>").trim())
    }
}
