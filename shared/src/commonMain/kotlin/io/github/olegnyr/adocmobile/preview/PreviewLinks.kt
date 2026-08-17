package io.github.olegnyr.adocmobile.preview

/**
 * Поведение ссылок в превью — `FR-28`, решение `OQ-8` (`SL-5`).
 *
 * Формы ссылок сняты прогоном конвертера: внутренний якорь `#anchor`, внешний
 * URL, `mailto:`, межфайловый `xref:` → `other.html#id`. Что делает каждая,
 * решает эта чистая функция; платформе остаётся исполнение — прокрутка,
 * системный браузер, уведомление.
 */

/** Что делать с касанием ссылки. */
sealed interface PreviewLinkAction {

    /**
     * Внутренний якорь: прокрутка внутри превью. Платформа отдаёт навигацию
     * WebView — переход по фрагменту той же страницы он делает сам.
     */
    data object ScrollInPreview : PreviewLinkAction

    /**
     * Внешний адрес — системному приложению (`OQ-8`). Схемы перечислены
     * закрыто: `http`, `https`, `mailto` — и ничего больше; `intent://` и
     * прочие способы дёрнуть чужой компонент сюда не попадают никогда.
     */
    data class OpenExternally(val url: String) : PreviewLinkAction

    /**
     * Межфайловый `xref` на документ, которого в превью нет. MVP-ответ —
     * уведомление «документ не открыт»; [path] — человекочитаемый путь для
     * текста уведомления, декодированный, без якоря.
     */
    data class DocumentLink(val path: String) : PreviewLinkAction

    /** Всё остальное — молча проглотить: ни навигации, ни внешнего запуска. */
    data object Ignore : PreviewLinkAction
}

/**
 * Классификация касания ссылки.
 *
 * Правила из `OQ-8`, каждое закрыто тестом `TC-35`:
 *
 * * `#якорь` на нашей странице → [PreviewLinkAction.ScrollInPreview];
 * * `http`/`https`/`mailto` наружу → [PreviewLinkAction.OpenExternally] —
 *   подмена префикса хоста (`adoc-preview.invalid.evil.com`) остаётся внешним
 *   адресом, а не документом;
 * * путь на нашей странице (`other.html`) → [PreviewLinkAction.DocumentLink];
 * * опасные и бессмысленные схемы (`intent`, `javascript`, `file`, `content`,
 *   `data` и любые прочие) → [PreviewLinkAction.Ignore].
 */
fun classifyPreviewLink(url: String): PreviewLinkAction {
    if (url == PREVIEW_BASE_URL) return PreviewLinkAction.Ignore

    if (url.startsWith(PREVIEW_BASE_URL)) {
        val rest = url.removePrefix(PREVIEW_BASE_URL)
        if (rest.startsWith("#")) return PreviewLinkAction.ScrollInPreview
        val path = rest.substringBefore('#').substringBefore('?')
        if (path.isEmpty()) return PreviewLinkAction.Ignore
        return PreviewLinkAction.DocumentLink(displayPath(path))
    }

    val external = url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")
    return if (external) PreviewLinkAction.OpenExternally(url) else PreviewLinkAction.Ignore
}

/**
 * Путь для текста уведомления: сегменты декодируются, мусорная кодировка
 * остаётся как есть — путь здесь только показывается, а не открывается.
 */
private fun displayPath(path: String): String =
    path.split('/').joinToString("/") { segment -> decodePercent(segment) ?: segment }
