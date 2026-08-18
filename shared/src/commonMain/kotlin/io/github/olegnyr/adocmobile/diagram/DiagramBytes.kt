package io.github.olegnyr.adocmobile.diagram

/**
 * Похожи ли байты на изображение заявленного формата (`NFR-8`, `TC-30`).
 *
 * Заголовку `Content-Type` мы не верим осознанно (`SL-3`), но одного недоверия
 * мало: сервер может ответить `200` и прислать страницу входа в корпоративный
 * прокси или сообщение об ошибке. Отдать такие байты в `+<img>+` под видом
 * `image/svg+xml` значит попросить `WebView` разобрать чужой HTML как SVG —
 * ровно та поверхность, которой фича обещала не создавать.
 *
 * Проверка нарочно грубая — по началу файла, а не по разбору содержимого:
 * разбирать чужие байты, чтобы решить, доверять ли им, — это то же самое
 * доверие, только дороже. Задача одна: отсечь «это вообще не картинка».
 *
 * SVG — текст, и у него нет магической сигнатуры; принимаем начало XML или
 * корневой тег, пропуская пробелы и BOM. HTML-страница на этом и отсекается:
 * она начинается с `+<!DOCTYPE html>+` или `+<html+`.
 */
fun looksLikeImage(format: String, bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    return when (format.lowercase()) {
        "png" -> bytes.startsWith(PNG_MAGIC)
        "jpeg", "jpg" -> bytes.startsWith(JPEG_MAGIC)
        "svg" -> looksLikeSvg(bytes)
        // Формат, которого мы не показываем, до сюда не доходит (DiagramImages);
        // если дошёл — не принимаем: неизвестное не бывает безопасным по умолчанию.
        else -> false
    }
}

private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (i in prefix.indices) if (this[i] != prefix[i]) return false
    return true
}

/**
 * Начало SVG: первым *тегом* должен быть `+<svg+`.
 *
 * Ищется не вхождение, а именно первый тег. Разница решающая: страница входа в
 * корпоративный прокси начинается с пролога или `+<!DOCTYPE html>+`, а слово
 * `+<svg+` может встретиться у неё где угодно ниже — в скрипте, в тексте, в
 * значке. Поиск вхождения такую страницу пропускал бы, и `WebView` получил бы
 * чужой HTML под видом векторной графики.
 *
 * Перед корневым тегом законно стоят BOM, пробелы, XML-пролог, комментарии и
 * `DOCTYPE`; всё это пропускается, а дальше проверяется ровно одно имя.
 */
private fun looksLikeSvg(bytes: ByteArray): Boolean {
    val window = bytes.copyOfRange(0, minOf(bytes.size, SVG_WINDOW_BYTES))
    var text = window.decodeToString().trimStart('\uFEFF', ' ', '\n', '\r', '\t')

    // Пропускаем всё, что законно предшествует корневому тегу. Ограничение по
    // числу шагов — от бесконечного цикла на обрезанном окне.
    repeat(MAX_PROLOG_PARTS) {
        text = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        val skipped = when {
            text.startsWith("<?") -> text.substringAfter("?>", missingDelimiterValue = "")
            text.startsWith("<!--") -> text.substringAfter("-->", missingDelimiterValue = "")
            text.startsWith("<!DOCTYPE", ignoreCase = true) -> text.substringAfter('>', missingDelimiterValue = "")
            else -> return text.startsWith("<svg", ignoreCase = true)
        }
        if (skipped.isEmpty()) return false
        text = skipped
    }
    return false
}

private const val MAX_PROLOG_PARTS = 8
private const val SVG_WINDOW_BYTES = 512
