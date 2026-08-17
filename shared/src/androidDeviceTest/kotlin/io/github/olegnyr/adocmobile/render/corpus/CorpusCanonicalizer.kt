package io.github.olegnyr.adocmobile.render.corpus

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Каноническая форма HTML — порт `testdata/render-corpus/tools/corpus.py`
 * (класс `Canonicalizer`) на Kotlin для инструментального прогона (ADR-006).
 *
 * Зачем порт, а не python: прогон корпуса — *тест*, а не разовая процедура
 * (ADR-005 требует трёх исходов), и тест обязан вынести вердикт сам, на
 * устройстве, без цепочки adb → файлы → python, которую нечем провалить.
 *
 * Риск двух реализаций погашен двумя способами:
 *
 * . Обе стороны сравнения — эталон и вывод движка — канонизируются *этим же*
 *   кодом, поэтому вердикт «совпало/разошлось» не зависит от побайтового
 *   равенства с python-формой.
 * . Паритет с python всё же проверяется: `_manifest.json` эталона хранит
 *   `canonical_lines`, посчитанные python-реализацией, и отдельный тест
 *   сверяет с ними счёт этой реализации на тех же файлах.
 *
 * Отличия от оригинала, все сознательные:
 *
 * * `fold_presentational` включён всегда: два косметических класса расхождений
 *   приняты ADR-005 как известные, и гасит их именно эта свёртка.
 * * `script`/`style` у python-парсера — CDATA без раскрытия сущностей; здесь
 *   они разбираются как обычные теги. В корпусе этих элементов нет, а свою
 *   форму обе стороны получают одинаковую.
 */

private val VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
)

private val VERBATIM_TAGS = setOf("pre", "script", "style", "textarea")

private val BLOCK_TAGS = setOf(
    "html", "head", "body", "div", "p", "section", "article", "aside", "nav",
    "header", "footer", "main", "figure", "figcaption", "details", "summary",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "br",
    "ul", "ol", "li", "dl", "dt", "dd",
    "table", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup",
    "caption", "pre", "blockquote", "form", "fieldset", "legend",
    "video", "audio", "source", "iframe", "meta", "link", "title",
    "script", "style", "hgroup",
)

private val VOLATILE_IDS = setOf("footer", "footer-text")

/**
 * Юникодный пробел — тот же класс, что у python-`\s` в str-режиме: иначе
 * `&#160;` перестаёт быть пробелом. Класс выписан явно: `(?U)` на Android не
 * компилируется (java.util.regex тут поверх ICU, и флаг
 * `UNICODE_CHARACTER_CLASS` в нём не поддержан — падает `PatternNative`).
 */
private val WHITESPACE = Regex(
    "[ \\t\\n\\r\\u000B\\u000C\\u001C-\\u001F\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+",
)

private val LONG_NUMBER = Regex("\\d+\\.\\d{5,}")

/** Именованные сущности, которые порождает html5-бэкенд Asciidoctor. */
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "copy" to "©", "reg" to "®", "trade" to "™",
    "hellip" to "…", "mdash" to "—", "ndash" to "–",
    "laquo" to "«", "raquo" to "»", "larr" to "←", "rarr" to "→",
    "deg" to "°", "plusmn" to "±", "times" to "×", "divide" to "÷",
    "sect" to "§", "para" to "¶", "middot" to "·", "bull" to "•",
    "dagger" to "†", "Dagger" to "‡", "prime" to "′", "Prime" to "″",
    "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "sbquo" to "‚", "bdquo" to "„", "shy" to "­", "euro" to "€",
)

/** 33.333333% -> 33.3333%: разница округления шириной колонки не значима. */
internal fun roundNumbers(value: String): String = LONG_NUMBER.replace(value) { match ->
    // round() в python — банковское округление; BigDecimal.HALF_EVEN — оно же.
    // Хвостовые нули срезаются, как это делает питоновский формат :g.
    BigDecimal(match.value).setScale(4, RoundingMode.HALF_EVEN)
        .stripTrailingZeros().toPlainString()
}

/** Раскрытие сущностей — то, что у python делает `convert_charrefs=True`. */
internal fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch != '&') {
            out.append(ch); i++; continue
        }
        val semi = text.indexOf(';', i + 1)
        if (semi == -1 || semi - i > 32) {
            out.append(ch); i++; continue
        }
        val body = text.substring(i + 1, semi)
        val decoded: String? = when {
            body.startsWith("#x") || body.startsWith("#X") ->
                body.drop(2).toIntOrNull(16)?.let { cp -> String(Character.toChars(cp)) }
            body.startsWith("#") ->
                body.drop(1).toIntOrNull()?.let { cp -> String(Character.toChars(cp)) }
            else -> NAMED_ENTITIES[body]
        }
        if (decoded != null) {
            out.append(decoded); i = semi + 1
        } else {
            out.append(ch); i++
        }
    }
    return out.toString()
}

/** Экранирование строки в стиле `json.dumps(..., ensure_ascii=False)`. */
internal fun jsonQuote(value: String): String {
    val out = StringBuilder(value.length + 2).append('"')
    for (ch in value) {
        when (ch) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '' -> out.append("\\f")
            else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
        }
    }
    return out.append('"').toString()
}

private sealed interface Event {
    data class Tag(val rendered: String, val name: String) : Event
    data class Text(val payload: String) : Event
    data class Verbatim(val payload: String) : Event
}

private class Tokenizer(private val html: String) {

    val events = mutableListOf<Event>()
    private var verbatimDepth = 0
    private var skipDepth = 0
    private var skipTag: String? = null
    private var pos = 0

    fun run(): List<Event> {
        val text = StringBuilder()
        while (pos < html.length) {
            val ch = html[pos]
            if (ch != '<') {
                text.append(ch); pos++; continue
            }
            // Разбираемое как тег начинается с буквы, '/', '!' — иначе это данные.
            val next = html.getOrNull(pos + 1)
            if (next == null || !(next.isLetter() || next == '/' || next == '!')) {
                text.append(ch); pos++; continue
            }
            flushText(text)
            when {
                html.startsWith("<!--", pos) -> skipComment()
                next == '!' -> declaration()
                next == '/' -> endTag()
                else -> startTag()
            }
        }
        flushText(text)
        return events
    }

    private fun flushText(text: StringBuilder) {
        if (text.isEmpty()) return
        val payload = text.toString()
        text.setLength(0)
        if (skipDepth > 0) return
        if (verbatimDepth > 0) {
            events += Event.Verbatim(decodeEntities(payload).replace("\r\n", "\n"))
        } else {
            events += Event.Text(decodeEntities(payload))
        }
    }

    private fun skipComment() {
        val end = html.indexOf("-->", pos + 4)
        pos = if (end == -1) html.length else end + 3
    }

    private fun declaration() {
        val end = html.indexOf('>', pos)
        val body = html.substring(pos + 2, if (end == -1) html.length else end)
        pos = if (end == -1) html.length else end + 1
        if (skipDepth > 0) return
        val name = body.trim().split(WHITESPACE).first().lowercase()
        events += Event.Tag("<!$name>", "!$name")
    }

    private fun endTag() {
        val end = html.indexOf('>', pos)
        val name = html.substring(pos + 2, if (end == -1) html.length else end)
            .trim().lowercase()
        pos = if (end == -1) html.length else end + 1
        if (name in VOID_TAGS) return // </br> и подобное — мусор разметки
        if (skipDepth > 0) {
            if (name == skipTag) {
                skipDepth--
                if (skipDepth == 0) skipTag = null
            }
            return
        }
        if (name in VERBATIM_TAGS && verbatimDepth > 0) verbatimDepth--
        events += Event.Tag("</$name>", name)
    }

    private fun startTag() {
        var i = pos + 1
        while (i < html.length && (html[i].isLetterOrDigit() || html[i] == '-')) i++
        val name = html.substring(pos + 1, i).lowercase()
        val attrs = mutableListOf<Pair<String, String?>>()
        var selfClosing = false

        while (i < html.length && html[i] != '>') {
            if (html[i].isWhitespace()) { i++; continue }
            if (html[i] == '/') { selfClosing = true; i++; continue }
            val nameStart = i
            while (i < html.length && html[i] !in "=/> \t\n\r") i++
            val attrName = html.substring(nameStart, i).lowercase()
            while (i < html.length && html[i].isWhitespace()) i++
            if (i < html.length && html[i] == '=') {
                i++
                while (i < html.length && html[i].isWhitespace()) i++
                val value: String
                if (i < html.length && (html[i] == '"' || html[i] == '\'')) {
                    val quote = html[i]; i++
                    val valueStart = i
                    while (i < html.length && html[i] != quote) i++
                    value = html.substring(valueStart, i)
                    if (i < html.length) i++
                } else {
                    val valueStart = i
                    while (i < html.length && html[i] !in "> \t\n\r") i++
                    value = html.substring(valueStart, i)
                }
                attrs += attrName to decodeEntities(value)
            } else {
                attrs += attrName to null
            }
        }
        pos = if (i < html.length) i + 1 else html.length

        if (skipDepth > 0) {
            if (!selfClosing && name !in VOID_TAGS && name == skipTag) skipDepth++
            return
        }
        if (isVolatile(name, attrs)) {
            if (!selfClosing && name !in VOID_TAGS) {
                skipDepth = 1
                skipTag = name
            }
            return
        }
        val rendered = renderAttrs(name, attrs)
        events += Event.Tag("<$name${if (rendered.isEmpty()) "" else " $rendered"}>", name)
        if (!selfClosing && name in VERBATIM_TAGS) verbatimDepth++
    }

    private fun isVolatile(tag: String, attrs: List<Pair<String, String?>>): Boolean {
        val map = attrs.associate { (k, v) -> k to (v ?: "") }
        if (tag == "meta" && map["name"]?.lowercase() == "generator") return true
        return map["id"] in VOLATILE_IDS
    }
}

/**
 * Свёртка двух разобранных вручную системных расхождений (`_fold` оригинала):
 * устаревший `width` на `col`/`table` вместо `style` и разрыв страницы классом
 * вместо инлайнового стиля.
 */
private fun fold(tag: String, attrs: List<Pair<String, String?>>): List<Pair<String, String?>> {
    val map = LinkedHashMap<String, String?>()
    attrs.forEach { (k, v) -> map[k] = v }
    if (tag in setOf("col", "table") && !map["width"].isNullOrEmpty()) {
        val width = map.remove("width")
        val style = map["style"] ?: ""
        if ("width" !in style) {
            map["style"] = (if (style.isEmpty()) "" else "$style ") + "width: $width;"
        }
    }
    if (tag == "div") {
        val style = (map["style"] ?: "").replace(" ", "")
        val classes = (map["class"] ?: "").split(" ").filter { it.isNotEmpty() }
        if ("page-break-after:always;" in style || "page-break" in classes) {
            return listOf("class" to "page-break")
        }
    }
    return map.toList()
}

private fun renderAttrs(tag: String, rawAttrs: List<Pair<String, String?>>): String {
    val parts = mutableListOf<String>()
    for ((name, rawValue) in fold(tag, rawAttrs)) {
        if (rawValue == null) {
            parts += name
            continue
        }
        var value = WHITESPACE.replace(rawValue, " ").trim()
        if (name == "class") {
            value = value.split(" ").filter { it.isNotEmpty() }.sorted().joinToString(" ")
        }
        if (name in setOf("style", "width", "height")) {
            value = roundNumbers(value)
        }
        parts += "$name=${jsonQuote(value)}"
    }
    parts.sort()
    return parts.joinToString(" ")
}

/** Каноническая форма: список строк, устойчивый к незначащим расхождениям. */
internal fun canonicalize(html: String): List<String> {
    val events = Tokenizer(html).run()

    fun isBlockBoundary(index: Int): Boolean {
        if (index < 0 || index >= events.size) return true // край документа
        val event = events[index] as? Event.Tag ?: return false
        return event.name in BLOCK_TAGS
    }

    val lines = mutableListOf<String>()
    events.forEachIndexed { i, event ->
        when (event) {
            is Event.Tag -> lines += event.rendered
            is Event.Verbatim -> {
                val text = event.payload.removePrefix("\n")
                if (text.isNotEmpty()) lines += "verbatim ${jsonQuote(text)}"
            }
            is Event.Text -> {
                var text = WHITESPACE.replace(event.payload, " ")
                if (isBlockBoundary(i - 1)) text = text.trimStart()
                if (isBlockBoundary(i + 1)) text = text.trimEnd()
                if (text.isNotEmpty()) lines += "text ${jsonQuote(text)}"
            }
        }
    }
    return lines
}
