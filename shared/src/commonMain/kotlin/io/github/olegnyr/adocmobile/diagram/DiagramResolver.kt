package io.github.olegnyr.adocmobile.diagram

import io.github.olegnyr.adocmobile.render.DiagramOptions

/**
 * Диаграммы для одного превью: два прохода и загрузка между ними.
 *
 * Собран отдельно от пайплайна намеренно. Пайплайн знает про поколения и
 * публикацию, этот класс — про диаграммы; смешивать их значит получить одно
 * место, где ломается и то и другое. Ни часов, ни корутин внутри нет: загрузка
 * идёт в корутине вызывающего и отменяется вместе с ней (`FR-10`).
 *
 * @param options параметры, с которыми документ конвертировался. Функция, а не
 * значение: пользователь меняет их когда угодно, а разъехаться с рендером они
 * не имеют права — иначе адреса перестанут узнаваться.
 */
class DiagramResolver(
    private val options: () -> DiagramOptions = { DiagramOptions.Disabled },
    private val store: DiagramImageStore = DiagramSupport.images,
    private val transport: () -> DiagramTransport? = { DiagramSupport.transport },
    private val inflate: () -> Inflate? = { DiagramSupport.inflate },
) {

    /**
     * Параметры, с которыми обязан идти *и* рендер.
     *
     * Разъехаться им нельзя: расширение подставляет адреса того сервера,
     * который назвали движку, а узнаёт их проход по тому, который назвали ему.
     * Разные значения означают, что диаграммы просто перестанут узнаваться —
     * молча, без единой ошибки. Поэтому источник один и спрашивается здесь.
     */
    fun options(): DiagramOptions = options.invoke()

    /**
     * Первый проход: подставить, что уже есть, остальное — плейсхолдерами.
     *
     * Ничего не ждёт: страница публикуется сразу (`OQ-4`).
     */
    fun pending(fragment: String): DiagramPass =
        diagramPass(fragment, options(), store, inflate(), DiagramPending.Loading)

    /**
     * Второй проход: подставить пришедшее, остальное — исходниками с пометкой.
     *
     * Зовётся после [load], когда грузить больше нечего, поэтому недостающее
     * превращается не в плейсхолдер, а в деградацию (`FR-19`).
     */
    fun resolved(fragment: String): DiagramPass =
        diagramPass(fragment, options(), store, inflate(), DiagramPending.Unavailable)

    /**
     * Загружает недостающие изображения; `true` — хоть одно приехало.
     *
     * Последовательно и по одному: предел параллельности и таймауты — предмет
     * `SL-4`, и придумывать их здесь до требований значит закреплять числа,
     * взятые из головы.
     *
     * Отказ одной диаграммы не мешает остальным (`FR-21`): исход каждой
     * решается отдельно, а общего «не вышло» у прохода нет вовсе.
     */
    suspend fun load(missing: List<KrokiAddress>): Boolean {
        val transport = transport() ?: return false
        var loaded = false
        for (address in missing) {
            val bytes = transport.fetch(address.url) ?: continue
            if (store.put(address, bytes) != null) loaded = true
        }
        return loaded
    }
}
