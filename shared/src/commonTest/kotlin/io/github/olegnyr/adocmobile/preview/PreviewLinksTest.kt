package io.github.olegnyr.adocmobile.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Поведение ссылок в превью (`FR-28`, решение `OQ-8`, слайс `SL-5`).
 *
 * Классификация — чистая функция в `commonMain`: какое касание чем кончается,
 * решает общий код, платформе остаётся исполнение (браузер, уведомление).
 *
 * У `FR-28` не было тест-кейса до решения `OQ-8` (пробел назван в матрице
 * трассируемости) — идентификатор `TC-35` взят следующим свободным и должен
 * быть внесён в спеку владельцем документа.
 */
class PreviewLinksTest {

    // ---- Внутренний якорь: прокрутка внутри превью ----

    @Test
    fun TC_35_internalAnchorScrollsInsidePreview() {
        assertIs<PreviewLinkAction.ScrollInPreview>(
            classifyPreviewLink("$PREVIEW_BASE_URL#_секция_1"),
        )
        assertIs<PreviewLinkAction.ScrollInPreview>(
            classifyPreviewLink("$PREVIEW_BASE_URL#fn-1"),
        )
    }

    @Test
    fun TC_35_bareBaseUrlIsIgnored() {
        // Пустой href разрешается в адрес самой страницы; перезагрузка снесла бы
        // прокрутку и ничего не дала.
        assertIs<PreviewLinkAction.Ignore>(classifyPreviewLink(PREVIEW_BASE_URL))
    }

    // ---- Внешний URL: системный браузер ----

    @Test
    fun TC_35_externalUrlsOpenExternally() {
        val https = classifyPreviewLink("https://example.com/page")
        assertIs<PreviewLinkAction.OpenExternally>(https)
        assertEquals("https://example.com/page", https.url)

        assertIs<PreviewLinkAction.OpenExternally>(classifyPreviewLink("http://example.com"))
        assertIs<PreviewLinkAction.OpenExternally>(classifyPreviewLink("mailto:dev@example.com"))
    }

    @Test
    fun TC_35_hostPrefixConfusionIsExternalNotLocal() {
        // Хост, начинающийся с нашего, — чужой внешний адрес, не документ.
        assertIs<PreviewLinkAction.OpenExternally>(
            classifyPreviewLink("https://adoc-preview.invalid.evil.com/other.html"),
        )
    }

    // ---- Межфайловый xref: уведомление «документ не открыт» ----

    @Test
    fun TC_35_crossFileXrefBecomesDocumentLink() {
        val link = classifyPreviewLink("${PREVIEW_BASE_URL}other.html#_раздел")
        assertIs<PreviewLinkAction.DocumentLink>(link)
        assertEquals("other.html", link.path)
    }

    @Test
    fun TC_35_documentLinkPathIsDecodedForDisplay() {
        val link = classifyPreviewLink("$PREVIEW_BASE_URL%D0%B4%D1%80%D1%83%D0%B3%D0%BE%D0%B9.html")
        assertIs<PreviewLinkAction.DocumentLink>(link)
        assertEquals("другой.html", link.path)
    }

    @Test
    fun TC_35_documentLinkKeepsSubdirectories() {
        val link = classifyPreviewLink("${PREVIEW_BASE_URL}docs/guide.html")
        assertIs<PreviewLinkAction.DocumentLink>(link)
        assertEquals("docs/guide.html", link.path)
    }

    // ---- Опасные схемы глотаются, а не исполняются ----

    @Test
    fun TC_35_dangerousSchemesAreSwallowed() {
        // intent:// — классический путь запуска чужих компонентов из WebView;
        // file и content — чтение за пределами документа; javascript — код.
        // Ни один из них не уходит ни в браузер, ни в уведомление.
        listOf(
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://media/external/images/1",
            "data:text/html,<b>x</b>",
            "about:blank",
            "ftp://example.com/x",
        ).forEach { url ->
            assertIs<PreviewLinkAction.Ignore>(classifyPreviewLink(url), "обязан глотаться: $url")
        }
    }
}
