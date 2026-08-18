package io.github.olegnyr.adocmobile.diagram

/**
 * Адрес диаграммы, порождённый расширением Kroki, разобранный на части.
 *
 * Формат снят прогоном расширения (разведка фичи, Q2), а не вспомнен:
 * `{сервер}/{тип}/{формат}/{payload}`, где `payload` — исходник диаграммы,
 * сжатый deflate и записанный base64url.
 *
 * Адрес обратим, то есть *является* содержимым документа (`NFR-8`): из него
 * восстанавливается исходный текст диаграммы. Отсюда два следствия, о которых
 * легко забыть. Первое: в готовую страницу превью адрес попадать не должен —
 * его место занимает либо локальная ссылка на загруженное изображение, либо
 * блок с исходником. Второе: в журнал он не пишется никогда.
 *
 * @property url адрес целиком, как он стоял в `src`; он же ключ кэша (`ADR-010`).
 */
data class KrokiAddress(
    val url: String,
    val serverUrl: String,
    val type: String,
    val format: String,
    val payload: String,
)

/**
 * Разбирает адрес изображения; `null` — это не диаграмма настроенного сервера.
 *
 * *Происхождение адреса проверяется, а не угадывается* — решение владельца
 * 2026-08-18, отменившее прежнее умолчание. Сначала признаком «это Kroki»
 * служила одна форма пути: три сегмента, последний из алфавита base64url. Под
 * неё попадает слишком многое — вложение вики, аватар из API, значок сборки,
 * любая внешняя картинка без расширения в имени. Такая картинка молча
 * превратилась бы в пометку «диаграмма не загружена» с мусорным типом,
 * вытащенным из середины чужого пути. Это не деградация, а порча документа:
 * диаграммы там не было.
 *
 * Обратная цена принята сознательно: если пользователь сменит адрес сервера,
 * пока страница уже собрана, диаграмма со старым адресом останется показанной
 * как есть до следующего прогона. Один устаревший кадр дешевле съеденных
 * картинок.
 *
 * Сверка идёт по префиксу без учёта регистра: схема и хост регистронезависимы
 * (RFC 3986), и `+HTTPS://Kroki.IO+` в документе — тот же сервер. Дальше
 * регистр важен и сравнивается точно: путь к нему чувствителен.
 *
 * @param serverUrl адрес сервера из тех же параметров, с которыми документ
 * конвертировался; хвостовой `/` не важен.
 */
fun parseKrokiAddress(url: String, serverUrl: String): KrokiAddress? {
    val server = serverUrl.trimEnd('/')
    if (server.isEmpty()) return null
    if (url.length <= server.length) return null
    if (!url.regionMatches(0, server, 0, server.length, ignoreCase = true)) return null
    if (url[server.length] != '/') return null

    val segments = url.substring(server.length + 1).split('/')
    if (segments.size != 3) return null

    val (type, format, payload) = segments
    if (!isPathToken(type) || !isPathToken(format)) return null
    if (payload.isEmpty() || !payload.all(::isBase64UrlChar)) return null

    return KrokiAddress(url = url, serverUrl = server, type = type, format = format, payload = payload)
}

/** Тип и формат — латиница, цифры, дефис: ровно то, что порождает расширение. */
private fun isPathToken(token: String): Boolean =
    token.isNotEmpty() && token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }

/** Алфавит base64url: `+` и `/` заменены на `-` и `_`; `=` — выравнивание. */
private fun isBase64UrlChar(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_' || c == '='
