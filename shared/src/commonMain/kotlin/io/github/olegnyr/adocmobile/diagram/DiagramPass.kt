package io.github.olegnyr.adocmobile.diagram

import io.github.olegnyr.adocmobile.render.DiagramOptions

/**
 * Результат одного прохода по фрагменту: готовая разметка и список того, чего
 * не хватило.
 *
 * @property html фрагмент, в котором каждая диаграмма уже заменена своим
 * исходом — картинкой, плейсхолдером или исходником с пометкой.
 * @property missing адреса, которых нет в хранилище. Список без повторов и в
 * порядке появления: одна и та же диаграмма, встреченная дважды, грузится один
 * раз (`FR-11`).
 */
class DiagramPass(val html: String, val missing: List<KrokiAddress>)

/**
 * Проход по фрагменту: подставить всё, что уже есть, и назвать недостающее.
 *
 * Функция *синхронная и чистая* — в этом весь смысл решения `OQ-4`. Страница
 * публикуется сразу, с плейсхолдерами на местах ещё не пришедших диаграмм, а
 * загрузка идёт следом и приводит ко второму проходу. Если бы проход ждал сеть,
 * пользователь ждал бы вместе с ним — при отсутствии сети до самого таймаута.
 *
 * Режим `ВЫКЛ` до этой функции не доходит по построению: расширение не
 * регистрировалось, адресов Kroki в разметке нет. Проверка [DiagramOptions]
 * здесь всё равно стоит — на случай, если разметка досталась от прошлого
 * прогона с другими параметрами.
 *
 * @param pending что показывать на месте недостающей диаграммы. Второй проход
 * зовёт эту же функцию с [DiagramPending.Unavailable], и плейсхолдеры
 * превращаются в исходники с пометкой — без ожидания, потому что грузить больше
 * нечего.
 */
fun diagramPass(
    fragment: String,
    options: DiagramOptions,
    store: DiagramImageStore,
    inflate: Inflate?,
    pending: DiagramPending = DiagramPending.Loading,
): DiagramPass {
    if (!options.krokiEnabled) return DiagramPass(fragment, emptyList())

    val missing = LinkedHashMap<String, KrokiAddress>()
    val html = resolveDiagramBlocks(fragment, options.serverUrl) { address ->
        val path = diagramImagePath(address)
        val stored = path?.let { store.read(it) }
        when {
            // Уже загружено — показываем; адрес наружу не уходит.
            stored != null && path != null -> DiagramOutcome.Image(previewImageUrl(path))

            // Формат не превращается в картинку (pdf, txt): ждать нечего,
            // деградируем сразу.
            path == null -> DiagramOutcome.Unavailable(decodeKrokiSource(address.payload, inflate))

            else -> {
                missing[address.url] = address
                when (pending) {
                    DiagramPending.Loading -> DiagramOutcome.Loading
                    DiagramPending.Unavailable ->
                        DiagramOutcome.Unavailable(decodeKrokiSource(address.payload, inflate))
                }
            }
        }
    }
    return DiagramPass(html, missing.values.toList())
}

/** Что ставить на место диаграммы, которой ещё нет. */
enum class DiagramPending {

    /** Плейсхолдер: изображение сейчас поедет. */
    Loading,

    /** Исходник с пометкой: грузить больше нечего или незачем. */
    Unavailable,
}

/**
 * Локальный адрес изображения в зоне превью.
 *
 * Собирается здесь, а не в пакете `preview`, чтобы правило «внешний адрес до
 * страницы не доходит» держалось в одном месте: единственный способ получить
 * адрес картинки — пройти через эту функцию.
 */
internal fun previewImageUrl(path: String): String = PREVIEW_BASE_URL_FOR_DIAGRAMS + path

/**
 * Тот же базовый адрес, что у превью (`PreviewImages.PREVIEW_BASE_URL`).
 *
 * Значение продублировано намеренно и с этой пометкой: пакет диаграмм не должен
 * зависеть от пакета превью — превью и так зависит от диаграмм, а взаимная
 * зависимость пакетов кончается тем, что их нельзя разделить. Расхождение ловит
 * `TC-42`.
 */
internal const val PREVIEW_BASE_URL_FOR_DIAGRAMS: String = "https://adoc-preview.invalid/"
