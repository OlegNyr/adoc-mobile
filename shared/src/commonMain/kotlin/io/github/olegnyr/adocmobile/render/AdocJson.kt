package io.github.olegnyr.adocmobile.render

/**
 * Граница с JS: исходник документа пересекает её только строкой-литералом JSON.
 *
 * `FR-5` называет это требованием архитектуры, а не удобством. Документ уходит в
 * движок подстановкой в текст скрипта, и любой неэкранированный символ там — не
 * «кривой текст», а *инъекция*: закрывающая кавычка обрывает литерал, и остаток
 * документа исполняется как код. Замеры `T-013` показали цену — 1–3 мс, то есть
 * требование архитектуры не стоит ничего.
 *
 * Готового средства взять неоткуда. `org.json.JSONObject.quote`, которым
 * пользовался стенд `T-013`, платформенный и не экранирует `U+2028`/`U+2029` —
 * ровно тот случай, ради которого пункт про них попал в `FR-5`. Поэтому
 * сериализация написана здесь и живёт в общем коде.
 */

private const val LINE_SEPARATOR = '\u2028'
private const val PARAGRAPH_SEPARATOR = '\u2029'
private const val FORM_FEED = '\u000C'

/**
 * Собирает литерал строки JSON: результат включает обрамляющие кавычки и годится
 * для прямой подстановки в текст скрипта.
 *
 * Экранируются четыре класса символов:
 *
 * * кавычка и обратный слэш — иначе литерал обрывается или съедает соседа;
 * * управляющие символы ниже `U+0020` — грамматика JSON запрещает их внутри
 *   литерала; у пяти есть короткая форма, у остальных только `\uXXXX`;
 * * `U+2028` и `U+2029` — в JSON это обычные символы, а в JavaScript они
 *   исторически завершают строку, и разбор скрипта ломается именно на них;
 * * непарные суррогаты — в UTF-8 они непредставимы, и передача их символом
 *   означает либо порчу текста, либо падение в конвертере кодировок.
 *
 * Всё остальное, включая кириллицу и корректные суррогатные пары, проходит как
 * есть: экранировать текст до ASCII незачем, а литерал это раздуло бы вчетверо.
 */
internal fun jsonStringLiteral(value: String): String = buildString(value.length + 2) {
    append('"')
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c == '\b' -> append("\\b")
            c == FORM_FEED -> append("\\f")
            c < ' ' -> appendUnicodeEscape(c)
            c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR -> appendUnicodeEscape(c)
            c.isHighSurrogate() -> {
                val low = if (i + 1 < value.length) value[i + 1] else null
                if (low != null && low.isLowSurrogate()) {
                    append(c).append(low)
                    i++
                } else {
                    appendUnicodeEscape(c)
                }
            }
            c.isLowSurrogate() -> appendUnicodeEscape(c)
            else -> append(c)
        }
        i++
    }
    append('"')
}

/** `\uXXXX` в нижнем регистре: форма одна на все случаи, чтобы её не пришлось угадывать. */
private fun StringBuilder.appendUnicodeEscape(c: Char) {
    append("\\u").append(c.code.toString(16).padStart(4, '0'))
}
