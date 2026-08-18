package io.github.olegnyr.adocmobile.diagram

import kotlin.concurrent.Volatile

/**
 * Загруженное изображение диаграммы.
 *
 * @property mimeType тип берётся из *формата диаграммы*, а не из заголовка
 * ответа: сервер диаграмм — такой же недоверенный источник, как документ, и
 * верить его словам о том, что он прислал, незачем.
 */
class DiagramImage(val bytes: ByteArray, val mimeType: String)

/**
 * Форматы Kroki, которые мы показываем, и их типы.
 *
 * Перечень закрытый и совпадает по духу с перечнем `PreviewImages`: не картинка
 * — не ресурс превью. `pdf`, `txt` и прочее расширение порождать умеет, но
 * показывать их в `+<img>+` нечем, и такая диаграмма деградирует в исходник.
 */
private val MIME_BY_FORMAT: Map<String, String> = mapOf(
    "svg" to "image/svg+xml",
    "png" to "image/png",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
)

/** Расширение локального адреса по формату: то же имя, что понимает резолвер превью. */
private val EXTENSION_BY_FORMAT: Map<String, String> = mapOf(
    "svg" to "svg",
    "png" to "png",
    "jpeg" to "jpg",
    "jpg" to "jpg",
)

/** Каталог локальных адресов диаграмм внутри зоны превью. */
const val DIAGRAM_PATH_PREFIX: String = "diagram/"

/** Показуемый ли это формат: `null` — диаграмму в изображение не превратить. */
fun mimeTypeForDiagramFormat(format: String): String? = MIME_BY_FORMAT[format.lowercase()]

/**
 * Локальный путь, под которым изображение диаграммы отдаётся превью.
 *
 * Имя — устойчивый хеш адреса, а не сам адрес: адрес обратим и содержит текст
 * документа (`NFR-8`), и попадать в разметку страницы он не имеет права даже в
 * виде имени файла. Хеш вычисляется тут же (FNV-1a): он должен быть одинаков на
 * обеих платформах и между запусками — на нём же будет стоять файловый кэш
 * (`ADR-010`), а `hashCode` таких обещаний не даёт.
 */
fun diagramImagePath(address: KrokiAddress): String? {
    val extension = EXTENSION_BY_FORMAT[address.format.lowercase()] ?: return null
    return DIAGRAM_PATH_PREFIX + fnv1a64Hex(address.url) + "." + extension
}

/**
 * Хранилище загруженных изображений на время жизни процесса.
 *
 * Между сборкой страницы и запросом от `WebView` проходит время, и байтам надо
 * где-то полежать: страницу собирает общий код, а спрашивает их платформенный
 * перехват (`ADR-009`). Это не кэш офлайна — тот появится в `SL-5` файлами и
 * переживёт перезапуск; здесь ровно то, что нужно показать *сейчас*.
 *
 * Предел на число записей держит память в узде на длинном документе: вытесняется
 * самая давняя по обращению.
 */
class DiagramImageStore(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    /**
     * Снимок, а не изменяемая карта, и это про потоки, а не про стиль.
     *
     * Пишет сюда общий код (загрузка диаграмм), а *читает* платформенный
     * перехват — с рабочего потока `WebView`, синхронно, пока уже
     * опубликованная первая страница тянет картинки. То есть чтение и запись
     * идут с разных потоков по определению, а не по недосмотру.
     *
     * Обычная карта такого не переживает: чтение во время вставки может застать
     * её в разобранном виде. Поэтому запись собирает новый снимок и подменяет
     * ссылку целиком, а чтение берёт ссылку и работает со своим неизменяемым
     * снимком. Копия стоит десятки записей — против гонки, которая
     * воспроизводится раз в сотню открытий.
     */
    @Volatile
    private var entries: Map<String, DiagramImage> = emptyMap()

    /** Кладёт изображение и отдаёт локальный путь; `null` — формат не показуемый. */
    fun put(address: KrokiAddress, bytes: ByteArray): String? {
        val path = diagramImagePath(address) ?: return null
        val mimeType = mimeTypeForDiagramFormat(address.format) ?: return null
        val updated = LinkedHashMap<String, DiagramImage>(entries)
        updated.remove(path)
        updated[path] = DiagramImage(bytes, mimeType)
        while (updated.size > maxEntries) {
            updated.remove(updated.keys.first())
        }
        entries = updated
        return path
    }

    /**
     * Изображение по локальному пути.
     *
     * Чтение *ничего не меняет* — намеренно. Раньше оно переставляло запись,
     * чтобы вытеснять по давности обращения, и это делало чтение с чужого
     * потока изменением структуры: ловушка, которую следующий читатель кода
     * заметил бы уже по симптомам. Вытеснение теперь по давности *появления*, и
     * этого достаточно: хранилище живёт в пределах запуска и держит картинки
     * открытого документа. Вытеснение по давности обращения — свойство
     * файлового кэша (`ADR-010`), и там оно будет опираться на файловую систему,
     * а не на порядок в карте.
     */
    fun read(path: String): DiagramImage? = entries[path]

    /** Лежит ли изображение для этого адреса. */
    fun contains(address: KrokiAddress): Boolean {
        val path = diagramImagePath(address) ?: return false
        return entries.containsKey(path)
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}

/**
 * FNV-1a, 64 бита, в шестнадцатеричной записи.
 *
 * Не криптография и ею не притворяется: задача — короткое устойчивое имя для
 * адреса. Выбран за то, что укладывается в десяток строк и даёт одинаковый
 * результат на всех платформах, в отличие от `hashCode`.
 */
internal fun fnv1a64Hex(value: String): String {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (byte in value.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= 0x100000001b3L
    }
    val unsigned = hash.toULong()
    return unsigned.toString(16).padStart(16, '0')
}
