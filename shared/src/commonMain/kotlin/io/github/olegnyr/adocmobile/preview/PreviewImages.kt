package io.github.olegnyr.adocmobile.preview

import io.github.olegnyr.adocmobile.diagram.DIAGRAM_PATH_PREFIX
import io.github.olegnyr.adocmobile.diagram.DiagramSupport

/**
 * Локальные изображения документа в превью — решение `OQ-9` (`SL-6`).
 *
 * Конвертер отдаёт `+<img src="pic.png">+` с путём относительно документа.
 * WebView разрешает его против базового адреса страницы и спрашивает ресурс;
 * платформенный перехватчик обязан отдать байты картинки — и *только* картинки,
 * и *только* из каталога документа. Это новая поверхность безопасности, поэтому
 * решение «что отдавать» принимает не платформа, а эта чистая функция: правило
 * проверяется в `commonTest` перечнем атак, а платформе остаётся чтение байтов.
 */

/**
 * Базовый адрес страницы превью.
 *
 * Хост в зоне `.invalid` (RFC 2606): такое имя не разрешается DNS по стандарту,
 * то есть даже при ошибке в перехвате и снятой блокировке сети запрос не может
 * дойти до настоящего сервера. Третий рубеж после перехватчика и
 * `blockNetworkLoads`, а не замена им.
 */
const val PREVIEW_BASE_URL: String = "https://adoc-preview.invalid/"

/** Вердикт по запросу ресурса из превью. */
sealed interface PreviewImageRequest {

    /**
     * Запрос локального изображения документа.
     *
     * @property relativePath нормализованный путь относительно каталога
     * документа: декодированные сегменты через `/`, без `.`, `..` и пустых.
     * @property mimeType тип по расширению — для заголовка ответа.
     */
    data class Image(val relativePath: String, val mimeType: String) : PreviewImageRequest

    /**
     * Всё остальное: чужой адрес, не-изображение, выход из каталога, мусор в
     * кодировке. Единый исход намеренно — перехватчику незачем знать, *почему*
     * запрос отвергнут, а различия дали бы атакующему сигнал, что именно
     * сработало.
     */
    data object Denied : PreviewImageRequest
}

/**
 * Байты изображения по нормализованному относительному пути; `null` — файла нет
 * или доступ не выдан.
 *
 * Контракт для файловой стороны (поток 4): реализация обязана трактовать путь
 * строго внутри каталога документа. Выйти из него путём нельзя — резолвер не
 * пропускает ни `..`, ни абсолютные пути, ни закодированные разделители, — но
 * реализация не должна полагаться только на это (защита в глубину).
 *
 * Функция блокирующая, не `suspend`, намеренно: перехват ресурсов WebView —
 * синхронный вызов на его рабочем потоке, и это единственное место вызова.
 */
fun interface PreviewImageSource {
    fun read(relativePath: String): ByteArray?
}

/**
 * Разбор запроса ресурса из превью: либо безопасный относительный путь
 * изображения, либо отказ.
 *
 * Правила, каждое закрыто негативным тестом:
 *
 * * адрес обязан начинаться с [PREVIEW_BASE_URL] — чужие схемы, хосты и порты
 *   отвергаются, включая подмену префикса хостом `adoc-preview.invalid.evil.com`;
 * * запрос (`?`) и фрагмент (`#`) у локального файла не значат ничего — отказ;
 * * путь разбирается посегментно, и декодирование выполняется *после* разбиения:
 *   `%2F` внутри сегмента не имеет права стать разделителем;
 * * `..` не бывает легальным — даже «безвредный» `img/../pic.png` отвергается:
 *   нормализация с разрешёнными `..` — это код, ошибка в котором означает выход
 *   из каталога;
 * * `\`, управляющие символы и нулевой байт — отказ;
 * * расширение обязано быть из перечня изображений. `svg` в перечне осознанно:
 *   в `<img>` он рисуется без выполнения скриптов, а JavaScript у WebView
 *   превью выключен.
 */
fun resolvePreviewImageRequest(requestUrl: String): PreviewImageRequest {
    if (!requestUrl.startsWith(PREVIEW_BASE_URL)) return PreviewImageRequest.Denied
    val path = requestUrl.removePrefix(PREVIEW_BASE_URL)
    if (path.isEmpty() || path.contains('?') || path.contains('#')) return PreviewImageRequest.Denied

    val segments = path.split('/').map { segment ->
        decodePercent(segment) ?: return PreviewImageRequest.Denied
    }
    if (segments.any { !isSafeSegment(it) }) return PreviewImageRequest.Denied

    val extension = segments.last().substringAfterLast('.', "").lowercase()
    val mimeType = IMAGE_MIME_BY_EXTENSION[extension] ?: return PreviewImageRequest.Denied

    return PreviewImageRequest.Image(segments.joinToString("/"), mimeType)
}

/** Сегмент пути, которому можно доверять: не пустой, не `.`/`..`, без разделителей и управляющих. */
private fun isSafeSegment(segment: String): Boolean =
    segment.isNotEmpty() &&
        segment != "." &&
        segment != ".." &&
        segment.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F }

/**
 * Процентное декодирование одного сегмента; `null` — мусор в кодировке.
 *
 * `+` пробелом не становится: это правило форм, а не путей. Байты собираются и
 * читаются как UTF-8 строго — последовательность, не складывающаяся в текст,
 * это отказ, а не «как получится» (тот же принцип, что в [openDocument]).
 */
internal fun decodePercent(segment: String): String? {
    if (!segment.contains('%')) return segment
    val bytes = ArrayList<Byte>(segment.length)
    var i = 0
    while (i < segment.length) {
        val ch = segment[i]
        if (ch == '%') {
            if (i + 3 > segment.length) return null
            val high = segment[i + 1].digitToIntOrNull(16) ?: return null
            val low = segment[i + 2].digitToIntOrNull(16) ?: return null
            bytes.add((high shl 4 or low).toByte())
            i += 3
        } else {
            ch.toString().encodeToByteArray().forEach(bytes::add)
            i += 1
        }
    }
    return runCatching {
        bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()
}

/** Расширения изображений и их типы. Перечень закрытый: не картинка — не ресурс превью. */
private val IMAGE_MIME_BY_EXTENSION: Map<String, String> = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "bmp" to "image/bmp",
    "avif" to "image/avif",
)

/**
 * Источник картинок превью, знающий про диаграммы (`FR-7`, `ADR-009`).
 *
 * Изображения диаграмм и картинки рядом с документом приходят одним и тем же
 * запросом от `WebView`, но лежат в разных местах: первые — в хранилище фичи
 * диаграмм, вторые — в каталоге документа. Разводит их префикс пути, а не
 * догадка по расширению.
 *
 * Композиция живёт здесь, а не у экрана, сознательно: экран не должен знать о
 * существовании диаграмм, чтобы подключить превью, — иначе каждая новая
 * разновидность картинки означала бы правку навигации.
 *
 * Порядок проверки важен: сначала диаграммы. Каталог документа — территория
 * пользователя, и файл `diagram/….svg`, случайно лежащий рядом с документом,
 * не должен подменять собой отрисованную диаграмму.
 */
fun diagramAwareImageSource(
    documentImages: PreviewImageSource?,
    diagramImages: (String) -> ByteArray? = { path -> DiagramSupport.images.read(path)?.bytes },
): PreviewImageSource = PreviewImageSource { relativePath ->
    if (relativePath.startsWith(DIAGRAM_PATH_PREFIX)) {
        diagramImages(relativePath)
    } else {
        documentImages?.read(relativePath)
    }
}
