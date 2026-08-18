package io.github.olegnyr.adocmobile.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertIs

/**
 * Критерии приёмки фичи 003-render-preview, слайс `SL-6` — локальные изображения.
 *
 * Резолв пути — чистая функция в `commonMain`; платформе остаётся только чтение
 * байтов. Это требование безопасности, а не вкус: правило «отдаём только то,
 * что достижимо через выданный доступ» обязано проверяться без устройства.
 *
 * У слайса нет тест-кейсов в `analysis.adoc` (реестр заканчивается на `TC-30`) —
 * идентификаторы `TC-31`…`TC-34` взяты следующими свободными и должны быть
 * внесены в спеку владельцем документа; см. отчёт слайса.
 */
class PreviewImagesTest {

    private fun resolved(url: String) = resolvePreviewImageRequest(url)

    private fun assertDenied(url: String) {
        assertIs<PreviewImageRequest.Denied>(resolved(url), "обязан быть отвергнут: $url")
    }

    // ---- TC-31 (happy): относительные изображения из каталога документа ----

    @Test
    fun TC_31_plainRelativeImageResolves() {
        val image = resolved("${PREVIEW_BASE_URL}pic.png")
        assertIs<PreviewImageRequest.Image>(image)
        assertEquals("pic.png", image.relativePath)
        assertEquals("image/png", image.mimeType)
    }

    @Test
    fun TC_31_subdirectoryImageResolves() {
        // Подкаталог — не выход из каталога документа, а спуск внутрь него.
        val image = resolved("${PREVIEW_BASE_URL}img/diagrams/pic.jpg")
        assertIs<PreviewImageRequest.Image>(image)
        assertEquals("img/diagrams/pic.jpg", image.relativePath)
        assertEquals("image/jpeg", image.mimeType)
    }

    @Test
    fun TC_31_cyrillicNameIsPercentDecoded() {
        // WebView кодирует не-ASCII в URL; в файловый шов обязан прийти
        // человеческий путь, иначе файл «схема.png» не найдётся никогда.
        val image = resolved("${PREVIEW_BASE_URL}%D1%81%D1%85%D0%B5%D0%BC%D0%B0.png")
        assertIs<PreviewImageRequest.Image>(image)
        assertEquals("схема.png", image.relativePath)
    }

    @Test
    fun TC_31_knownImageTypesGetTheirMime() {
        val expected = mapOf(
            "a.png" to "image/png",
            "a.jpg" to "image/jpeg",
            "a.jpeg" to "image/jpeg",
            "a.gif" to "image/gif",
            "a.webp" to "image/webp",
            "a.svg" to "image/svg+xml",
            "a.bmp" to "image/bmp",
            "a.PNG" to "image/png",
        )
        expected.forEach { (name, mime) ->
            val image = resolved(PREVIEW_BASE_URL + name)
            assertIs<PreviewImageRequest.Image>(image, "не распознан тип: $name")
            assertEquals(mime, image.mimeType, name)
        }
    }

    // ---- TC-32 (negative): выход из каталога документа отвергается ----

    @Test
    fun TC_32_parentTraversalIsDenied() {
        assertDenied("${PREVIEW_BASE_URL}../secret.png")
        assertDenied("${PREVIEW_BASE_URL}../../etc/passwd.png")
        assertDenied("${PREVIEW_BASE_URL}img/../../secret.png")
    }

    @Test
    fun TC_32_anyDotDotSegmentIsDeniedEvenIfItStaysInside() {
        // «img/../pic.png» математически остаётся в каталоге, но нормализация с
        // разрешёнными «..» — это код, в котором ошибка означает выход из
        // каталога. Правило простое и доказуемое: «..» не бывает легальным.
        assertDenied("${PREVIEW_BASE_URL}img/../pic.png")
    }

    @Test
    fun TC_32_encodedTraversalIsDenied() {
        assertDenied("${PREVIEW_BASE_URL}%2e%2e/secret.png")
        assertDenied("${PREVIEW_BASE_URL}%2e%2e%2fsecret.png")
        assertDenied("${PREVIEW_BASE_URL}..%2Fsecret.png")
        // Закодированный разделитель внутри сегмента — попытка сменить структуру
        // пути после проверки.
        assertDenied("${PREVIEW_BASE_URL}img%2F..%2Fsecret.png")
        assertDenied("${PREVIEW_BASE_URL}a%5C..%5Cb.png")
    }

    @Test
    fun TC_32_backslashesControlCharsAndNulBytesAreDenied() {
        assertDenied("${PREVIEW_BASE_URL}..\\secret.png")
        assertDenied("${PREVIEW_BASE_URL}a\\b.png")
        assertDenied("${PREVIEW_BASE_URL}pic%00.png")
        assertDenied("${PREVIEW_BASE_URL}pic%0a.png")
    }

    @Test
    fun TC_32_emptyAndDotSegmentsAreDenied() {
        assertDenied("${PREVIEW_BASE_URL}img//pic.png")
        assertDenied("${PREVIEW_BASE_URL}./pic.png")
        assertDenied("${PREVIEW_BASE_URL}img/./pic.png")
        assertDenied(PREVIEW_BASE_URL)
    }

    @Test
    fun TC_32_brokenPercentEncodingIsDenied() {
        assertDenied("${PREVIEW_BASE_URL}pic%.png")
        assertDenied("${PREVIEW_BASE_URL}pic%GG.png")
        // Обрыв на последнем символе.
        assertDenied("${PREVIEW_BASE_URL}pic.png%")
        // Байты, не складывающиеся в UTF-8.
        assertDenied("${PREVIEW_BASE_URL}%FF%FE.png")
    }

    // ---- TC-33 (negative): только изображения, не произвольные файлы ----

    @Test
    fun TC_33_nonImageFilesAreDenied() {
        assertDenied("${PREVIEW_BASE_URL}document.adoc")
        assertDenied("${PREVIEW_BASE_URL}секрет.txt")
        assertDenied("${PREVIEW_BASE_URL}archive.zip")
        assertDenied("${PREVIEW_BASE_URL}noextension")
        assertDenied("${PREVIEW_BASE_URL}page.html")
        assertDenied("${PREVIEW_BASE_URL}script.js")
    }

    // ---- TC-34 (negative): чужие адреса не резолвятся вовсе ----

    @Test
    fun TC_34_foreignOriginsAndSchemesAreDenied() {
        assertDenied("https://example.com/pic.png")
        // Тот же хост, но не тот протокол или порт — уже не наш адрес.
        assertDenied("http://adoc-preview.invalid/pic.png")
        assertDenied("https://adoc-preview.invalid:8080/pic.png")
        // Классическая подмена префикса: хост, начинающийся с нашего.
        assertDenied("https://adoc-preview.invalid.evil.com/pic.png")
        assertDenied("file:///etc/passwd")
        assertDenied("content://media/external/images/1")
        assertDenied("data:image/png;base64,AAAA")
        assertDenied("about:blank")
    }

    @Test
    fun TC_34_queryAndFragmentAreDenied() {
        // Запрос и фрагмент у локального файла не значат ничего — а значит,
        // это не запрос локального файла.
        assertDenied("${PREVIEW_BASE_URL}pic.png?width=100")
        assertDenied("${PREVIEW_BASE_URL}pic.png#top")
    }

    /**
     * `TC-6` фичи 008: диаграммы и картинки документа разводятся по префиксу.
     *
     * Порядок проверки — часть требования, а не деталь: файл `diagram/….svg`,
     * лежащий рядом с документом, не должен подменять собой отрисованную
     * диаграмму.
     */
    @Test
    fun TC_6_diagramImagesComeBeforeDocumentFiles() {
        val fromDocument = PreviewImageSource { path -> "документ:$path".encodeToByteArray() }
        val source = diagramAwareImageSource(fromDocument) { path -> "диаграмма:$path".encodeToByteArray() }

        assertEquals(
            "диаграмма:diagram/abc.svg",
            source.read("diagram/abc.svg")?.decodeToString(),
            "запрос диаграммы ушёл в каталог документа",
        )
        assertEquals(
            "документ:pic.png",
            source.read("pic.png")?.decodeToString(),
            "обычная картинка документа перестала читаться",
        )
    }

    @Test
    fun TC_6_missingDocumentSourceStillServesDiagrams() {
        val source = diagramAwareImageSource(documentImages = null) { _ -> byteArrayOf(7) }

        assertEquals(7, source.read("diagram/abc.svg")?.single())
        assertNull(source.read("pic.png"), "без источника документа картинка не берётся ниоткуда")
    }
}
