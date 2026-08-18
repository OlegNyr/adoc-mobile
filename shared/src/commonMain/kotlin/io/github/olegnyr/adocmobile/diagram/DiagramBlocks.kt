package io.github.olegnyr.adocmobile.diagram

/**
 * Пометка на месте недоступной диаграммы — `FR-20`.
 *
 * Текст, а не цвет и не значок: пометка обязана читаться и в оттенках серого, и
 * при выключенных изображениях (NFR доступности). Один текст на все причины
 * отказа — решение `ADR-011`: пользователю всё равно, не было сети или отказал
 * сервер, а три разных поведения на одну беду только сбивают.
 */
const val DIAGRAM_UNAVAILABLE_NOTE: String = "ДИАГРАММА НЕ ЗАГРУЖЕНА"

/**
 * Подпись плейсхолдера, пока диаграмма грузится, — `FR-8` и макет 02a.
 *
 * Форма из дизайна: источник, тип диаграммы и состояние моноширинной строкой
 * прописными. Слово «СБОРКА» — про сборку диаграммы на сервере, а не про сборку
 * приложения; в макете стоит именно оно.
 */
const val DIAGRAM_PENDING_SOURCE: String = "KROKI"

/** Что показать на месте блока диаграммы. */
sealed interface DiagramOutcome {

    /**
     * Изображение получено; [src] — локальный адрес, который отдаст перехватчик
     * превью. Внешний адрес до страницы не доходит никогда (`NFR-8`).
     */
    data class Image(val src: String) : DiagramOutcome

    /**
     * Изображения нет: нет сети, отказал сервер, режим `ВЫКЛ` или расширение
     * само не справилось.
     *
     * @property source исходный текст диаграммы, восстановленный из адреса, или
     * `null`, если восстановить не удалось. Пустая строка равнозначна `null`:
     * пустой блок кода — это враньё об исходнике, а не его показ (`FR-22`).
     */
    data class Unavailable(val source: String?) : DiagramOutcome

    /**
     * Изображения ещё нет, но оно едет: на месте диаграммы стоит плейсхолдер.
     *
     * Отдельный исход, а не «недоступна с пустым текстом»: пользователь должен
     * различать «сейчас появится» и «не появится». Решение владельца по `OQ-4`
     * — публиковать страницу сразу с плейсхолдерами и дорисовывать картинки по
     * мере загрузки; без этого исхода публиковать было бы нечего.
     */
    data object Loading : DiagramOutcome
}

/**
 * Подставляет исход на место каждой диаграммы в готовом фрагменте конвертера.
 *
 * Меняется *ровно один* тег `+<img>+`, чей `src` разобрался как адрес диаграммы
 * настроенного сервера ([parseKrokiAddress]); всё прочее — обёртки блока, роли,
 * подпись рисунка, соседние блоки, локальные картинки документа — доходит до
 * страницы посимвольно неизменным (`FR-13`, `TC-11`).
 *
 * Замена именно тега, а не блока-обёртки, выбрана сознательно. Чтобы заменить
 * блок, пришлось бы искать парный `+</div>+`, то есть считать вложенность `div`
 * регулярным выражением, и первая же диаграмма с подписью ломала бы разбор
 * молча. Тег `+<img …>+` — единственный самозакрывающийся токен без
 * вложенности, и его границы видны точно. Побочный эффект честный: при
 * деградации блок остаётся `imageblock`, внутри которого стоит код, — это
 * оформляется стилем, а не притворством разметки.
 *
 * @param serverUrl адрес сервера из тех же параметров, с которыми документ
 * конвертировался. Разъехаться они не имеют права: чужой сервер здесь означает,
 * что диаграммы не будут узнаны вовсе.
 * @param resolve решение по каждому найденному адресу. Функция чистая и
 * синхронная: загрузка, кэш и режимы — забота вызывающего (`ADR-009`), здесь
 * только переписывание разметки.
 */
fun resolveDiagramBlocks(
    fragment: String,
    serverUrl: String,
    resolve: (KrokiAddress) -> DiagramOutcome,
): String {
    if (IMG_TAG !in fragment) return fragment

    val result = StringBuilder(fragment.length)
    var cursor = 0
    while (true) {
        val tagStart = fragment.indexOf(IMG_TAG, cursor)
        if (tagStart < 0) break
        val tagEnd = fragment.indexOf('>', tagStart)
        if (tagEnd < 0) break

        val tag = fragment.substring(tagStart, tagEnd + 1)
        val address = tag.srcAttribute()?.let { parseKrokiAddress(it, serverUrl) }
        if (address == null) {
            result.append(fragment, cursor, tagEnd + 1)
        } else {
            result.append(fragment, cursor, tagStart)
            result.append(replacementFor(tag, address, resolve(address)))
        }
        cursor = tagEnd + 1
    }
    result.append(fragment, cursor, fragment.length)
    return result.toString()
}

private const val IMG_TAG = "<img"

/** Значение `src` в теге; `null` — атрибута нет или он записан не в кавычках. */
private fun String.srcAttribute(): String? {
    val marker = indexOf("src=\"")
    if (marker < 0) return null
    val valueStart = marker + 5
    val valueEnd = indexOf('"', valueStart)
    if (valueEnd < 0) return null
    return substring(valueStart, valueEnd)
}

private fun replacementFor(tag: String, address: KrokiAddress, outcome: DiagramOutcome): String =
    when (outcome) {
        // Меняется только адрес: alt, ширина, роль — всё, что расширение и
        // пользователь задали блоку, остаётся на месте. Новый адрес идёт через
        // экранирование атрибута: он собран нами, но собран из значений, чей
        // источник — документ, и кавычка в нём означала бы произвольную
        // разметку в странице превью.
        is DiagramOutcome.Image ->
            tag.replace("src=\"${address.url}\"", "src=\"${escapeHtml(outcome.src)}\"")

        // Плейсхолдер — «чертёжный» блок макета 02a: рамка в клетку с меткой.
        // Разметка минимальная, вся картинка задаётся стилем: скриптов на
        // странице нет, и ничего динамического в плейсхолдере быть не может.
        DiagramOutcome.Loading -> buildString {
            append("<div class=\"kroki-pending\">")
            append("<div class=\"kroki-note\">")
            append(DIAGRAM_PENDING_SOURCE)
            append(" · ")
            append(address.type.uppercase())
            append(" · СБОРКА…")
            append("</div>")
            append("</div>")
        }

        is DiagramOutcome.Unavailable -> buildString {
            append("<div class=\"kroki-unavailable\">")
            append("<div class=\"kroki-note\">")
            append(DIAGRAM_UNAVAILABLE_NOTE)
            append(" · ")
            // Тип экранировать незачем: parseKrokiAddress пропускает только
            // латиницу, цифры и дефис. Экранирование здесь создавало бы
            // впечатление, что значение опасно, и прятало бы, где опасность на
            // самом деле — в тексте диаграммы ниже.
            append(address.type.uppercase())
            append("</div>")
            val source = outcome.source
            if (!source.isNullOrBlank()) {
                append("<pre><code>")
                append(escapeHtml(source))
                append("</code></pre>")
            }
            append("</div>")
        }
    }

/**
 * Экранирование текста, попадающего в разметку.
 *
 * Исходник диаграммы — текст документа пользователя, то есть недоверенный ввод:
 * `+<+` внутри диаграммы обязан остаться символом, а не стать тегом. Кавычки
 * экранируются тоже: тот же перечень применяется к значению атрибута, а разные
 * перечни для текста и атрибута — верный способ однажды перепутать их местами.
 */
internal fun escapeHtml(text: String): String = buildString(text.length) {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
