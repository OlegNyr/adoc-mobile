package io.github.olegnyr.adocmobile.diagram

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-4`, `TC-30`: байты обязаны быть картинкой.
 *
 * Прямые тесты, а не через загрузку: правило простое, а вход у него —
 * недоверенные байты из сети, и перебирать их формы удобнее здесь. Самая частая
 * боевая форма — не выдуманный «text/html», а *страница входа в корпоративный
 * прокси*, отданная с кодом `200` вместо картинки.
 */
class DiagramBytesTest {

    private fun svg(text: String) = looksLikeImage("svg", text.encodeToByteArray())

    @Test
    fun TC_30_plainSvgIsAccepted() {
        assertTrue(svg("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"))
        assertTrue(svg("  \n<svg/>"), "пробелы перед корневым тегом сделали SVG чужим")
        assertTrue(svg("﻿<svg/>"), "BOM сделал SVG чужим")
        assertTrue(svg("<SVG/>"), "регистр тега сделал SVG чужим")
    }

    @Test
    fun TC_30_svgWithPrologAndCommentsIsAccepted() {
        // Самая частая форма ответа Kroki: пролог, DOCTYPE, комментарий — и уже
        // потом корневой тег. Ветка была непроверенной.
        assertTrue(svg("<?xml version=\"1.0\" encoding=\"UTF-8\"?><svg/>"))
        assertTrue(
            svg(
                "<?xml version=\"1.0\"?>\n" +
                    "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n" +
                    "<svg/>",
            ),
        )
        assertTrue(svg("<?xml version=\"1.0\"?><!-- сгенерировано Kroki --><svg/>"))
    }

    @Test
    fun TC_30_proxyLoginPageIsRefusedEvenWithProlog() {
        // Ровно та дыра, ради которой разбор ищет первый тег, а не вхождение:
        // страница может упоминать <svg где угодно ниже.
        assertFalse(svg("<!DOCTYPE html><html><body><svg/></body></html>"))
        assertFalse(svg("<?xml version=\"1.0\"?><html><body>Вход в сеть <svg/></body></html>"))
        assertFalse(svg("<html><head><title>Требуется вход</title></head></html>"))
        assertFalse(svg("{\"error\":\"unauthorized\"}"))
    }

    @Test
    fun TC_30_truncatedAndEmptyBytesAreRefused() {
        assertFalse(looksLikeImage("svg", ByteArray(0)), "пустой ответ принят за картинку")
        assertFalse(svg("<?xml version=\"1.0\""), "оборванный пролог принят за картинку")
        assertFalse(svg("<?xml"), "один открытый пролог принят за картинку")
    }

    @Test
    fun TC_30_prologLongerThanTheWindowIsRefused() {
        // Окно ограничено: если корневого тега в нём нет, это не тот SVG,
        // который нарисует браузер.
        val padding = "<!-- " + "к".repeat(1024) + " -->"

        assertFalse(svg(padding + "<svg/>"), "корневой тег за пределами окна принят")
    }

    @Test
    fun TC_30_rasterFormatsAreCheckedByMagic() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        assertTrue(looksLikeImage("png", png))
        assertTrue(looksLikeImage("jpeg", jpeg))
        assertFalse(looksLikeImage("png", jpeg), "JPEG принят за PNG")
        assertFalse(looksLikeImage("png", "<svg/>".encodeToByteArray()), "SVG принят за PNG")
        assertFalse(looksLikeImage("png", byteArrayOf(0x89.toByte(), 0x50)), "обрезанная сигнатура принята")
    }

    @Test
    fun TC_30_unknownFormatIsNeverAccepted() {
        // Неизвестное не бывает безопасным по умолчанию.
        assertFalse(looksLikeImage("pdf", "%PDF-1.7".encodeToByteArray()))
        assertFalse(looksLikeImage("", "<svg/>".encodeToByteArray()))
    }
}
