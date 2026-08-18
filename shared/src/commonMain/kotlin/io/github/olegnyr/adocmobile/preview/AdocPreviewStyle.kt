package io.github.olegnyr.adocmobile.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.olegnyr.adocmobile.theme.AdocColors
import io.github.olegnyr.adocmobile.theme.AdocFontRole

/**
 * Стиль превью, порождённый из ролей палитры (`FR-22`).
 *
 * В этом файле нет ни одного цветового литерала — и не потому, что страж
 * `verifyNoColorLiterals` не пустит, а потому, что цвет живёт в одном месте:
 * [AdocColors]. CSS собирается из ролей функцией, и правка палитры меняет
 * превью сама, без второго списка значений.
 *
 * Перечень селекторов — из `FR-21`: классы, которые конвертер порождает
 * фактически, снятые прогоном, а не вспомненные.
 */

/** Цвет роли в записи CSS: `#rrggbb`. Альфа-канал отбрасывается — палитра непрозрачна. */
internal fun cssColor(color: Color): String {
    val rgb = color.toArgb() and 0xFFFFFF
    return "#" + rgb.toString(16).padStart(6, '0')
}

/**
 * Имя семейства в CSS для роли гарнитуры.
 *
 * Имя внутреннее (`adoc-heading`), а не «Barlow Condensed»: настоящие названия
 * гарнитур остались бы литералами мимо токенов — ровно то, что запрещает
 * `FR-22`. Файлы к именам привязывает [previewFontFaces].
 */
fun previewFontFamily(role: AdocFontRole): String = when (role) {
    AdocFontRole.Heading -> "adoc-heading"
    AdocFontRole.Body -> "adoc-body"
    AdocFontRole.Mono -> "adoc-mono"
}

/**
 * Полный встроенный стиль превью.
 *
 * @param colors роли палитры; умолчание — тёмная схема, единственная реализованная.
 * @param fontFaces блок `@font-face` от [previewFontFaces]. Пустая строка —
 *   честный откат на системные шрифты через generic-семейства; молча это не
 *   происходит, выбор за вызывающим.
 *
 * Числа раскладки (кегли, отступы) — из xref-таблиц «Типографика» и «Метрики»
 * описания дизайна: заголовок документа 30px, абзац 16px/1.5, код 13px/21px,
 * служебная метка 11px с трекингом .1em, горизонтальные поля 14.
 *
 * Ширина (`FR-24`): страница по горизонтали не прокручивается вовсе, широкие
 * блоки — код и таблицы — прокручиваются внутри себя. У таблицы для этого
 * прячется `colgroup`: проценты ширин колонок, посчитанные конвертером от
 * ширины страницы, внутри прокручиваемого контейнера только ломают раскладку.
 */
fun previewStylesheet(
    colors: AdocColors,
    fontFaces: String = "",
): String {
    val heading = previewFontFamily(AdocFontRole.Heading)
    val body = previewFontFamily(AdocFontRole.Body)
    val mono = previewFontFamily(AdocFontRole.Mono)

    val ground = cssColor(colors.ground)
    val raised = cssColor(colors.raised)
    val borderChrome = cssColor(colors.borderChrome)
    val borderObject = cssColor(colors.borderObject)
    val borderList = cssColor(colors.borderList)
    val accent = cssColor(colors.accent)
    val accentText = cssColor(colors.accentText)
    val textPrimary = cssColor(colors.textPrimary)
    val textSecondary = cssColor(colors.textSecondary)
    val textParagraph = cssColor(colors.textParagraph)
    val textMuted = cssColor(colors.textMuted)
    val textFaint = cssColor(colors.textFaint)

    return fontFaces + """
        html {
          -webkit-text-size-adjust: 100%;
          background-color: $ground;
        }
        body {
          margin: 0;
          padding: 16px 14px;
          background-color: $ground;
          color: $textParagraph;
          font-family: $body, sans-serif;
          font-size: 16px;
          line-height: 1.5;
          overflow-x: hidden;
          overflow-wrap: break-word;
        }
        h1, h2, h3, h4, h5, h6 {
          font-family: $heading, sans-serif;
          font-weight: 600;
          color: $textPrimary;
          line-height: 1.2;
          margin: 1.25em 0 0.5em;
        }
        h1 {
          font-size: 30px;
          letter-spacing: 0.01em;
          margin-top: 0;
        }
        a { color: $accentText; }
        .sect1, .sect2, .sect3, .sect4 { margin-top: 1em; }
        .paragraph p { margin: 0 0 1em; }
        .title, .attribution {
          font-family: $mono, monospace;
          font-variant-ligatures: none;
          font-size: 11px;
          letter-spacing: 0.05em;
          text-transform: uppercase;
          color: $textFaint;
        }
        #toc {
          border: 1px solid $borderObject;
          background-color: $raised;
          padding: 12px 16px;
          margin: 0 0 1.5em;
        }
        #toctitle {
          font-family: $mono, monospace;
          font-variant-ligatures: none;
          font-size: 11px;
          font-weight: normal;
          letter-spacing: 0.1em;
          text-transform: uppercase;
          color: $textFaint;
          margin: 0 0 0.5em;
        }
        #toc ul {
          margin: 0;
          padding-left: 1.25em;
          list-style-type: none;
        }
        #toc a { text-decoration: none; }
        pre, code {
          font-family: $mono, monospace;
          font-variant-ligatures: none;
          font-size: 13px;
          color: $textSecondary;
        }
        pre {
          margin: 0;
          line-height: 21px;
        }
        p code, li code, td code, dd code {
          background-color: $raised;
          padding: 1px 4px;
        }
        .listingblock, .literalblock { margin: 0 0 1em; }
        .listingblock pre, .literalblock pre {
          background-color: $raised;
          border: 1px solid $borderList;
          padding: 12px;
          overflow-x: auto;
        }
        pre.highlight code, code[data-lang] {
          background-color: transparent;
          padding: 0;
        }
        .admonitionblock {
          border: 1px solid $borderObject;
          background-color: $raised;
          border-collapse: collapse;
          width: 100%;
          margin: 0 0 1em;
        }
        .admonitionblock td.icon {
          vertical-align: top;
          padding: 10px 12px;
          border-right: 1px solid $borderList;
        }
        .admonitionblock td.icon .title {
          font-family: $mono, monospace;
          font-variant-ligatures: none;
          font-size: 11px;
          letter-spacing: 0.1em;
          text-transform: uppercase;
          color: $accent;
        }
        .admonitionblock td.content { padding: 10px 12px; }
        table.tableblock {
          display: block;
          overflow-x: auto;
          max-width: 100%;
          border-collapse: collapse;
          margin: 0 0 1em;
        }
        table.tableblock colgroup { display: none; }
        .tableblock th, .tableblock td {
          border: 1px solid $borderList;
          padding: 6px 10px;
          text-align: left;
          vertical-align: top;
        }
        .tableblock th {
          color: $textPrimary;
          font-weight: 500;
        }
        .tableblock p { margin: 0; }
        .ulist ul, .olist ol {
          margin: 0 0 1em;
          padding-left: 1.5em;
        }
        .ulist li, .olist li { margin-bottom: 0.25em; }
        .dlist dt {
          color: $textPrimary;
          font-weight: 500;
        }
        .dlist dd { margin: 0 0 0.75em 1.25em; }
        .imageblock { margin: 0 0 1em; }
        .imageblock img, span.image img {
          max-width: 100%;
          height: auto;
        }
        .kroki-unavailable {
          border: 1px solid $borderObject;
          background-color: $raised;
        }
        .kroki-note {
          font-family: $mono, monospace;
          font-size: 11px;
          letter-spacing: .1em;
          color: $textMuted;
          padding: 8px 12px;
        }
        .kroki-unavailable pre {
          margin: 0;
          border-top: 1px solid $borderList;
          padding: 12px;
          overflow-x: auto;
        }
        .quoteblock {
          border-left: 2px solid $accent;
          margin: 0 0 1em;
          padding: 4px 0 4px 14px;
          color: $textMuted;
        }
        .quoteblock blockquote { margin: 0; }
        hr {
          border: 0;
          border-top: 1px solid $borderChrome;
          margin: 1.5em 0;
        }
        #footnotes {
          color: $textMuted;
          font-size: 13px;
        }
        #footnotes hr {
          width: 96px;
          margin: 1.5em 0 0.75em;
        }
    """.trimIndent()
}
