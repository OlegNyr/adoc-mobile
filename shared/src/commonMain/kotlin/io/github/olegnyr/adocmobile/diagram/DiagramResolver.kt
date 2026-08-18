package io.github.olegnyr.adocmobile.diagram

import io.github.olegnyr.adocmobile.render.DiagramOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Предел одной загрузки, мс (`FR-10`, `NFR-1`).
 *
 * Число из `NFR-1`, а не из головы. Смысл его не в скорости: диаграмма, которая
 * едет дольше десяти секунд, всё равно уже не нужна — пользователь за это время
 * успел прочитать абзац и уйти. Важно другое: бесконечного ожидания не бывает,
 * иначе молчащий сервер держит плейсхолдер до конца сеанса.
 */
const val DIAGRAM_REQUEST_TIMEOUT_MILLIS: Long = 10_000

/**
 * Сколько диаграмм грузится одновременно (`FR-10`, `NFR-1`).
 *
 * Четыре — из `NFR-1`. Документ с двумя десятками диаграмм не должен открывать
 * два десятка соединений: на мобильной сети это медленнее, чем очередь, и
 * заметно расходует батарею.
 */
const val DIAGRAM_PARALLEL_REQUESTS: Int = 4

/**
 * Диаграммы для одного превью: два прохода и загрузка между ними.
 *
 * Собран отдельно от пайплайна намеренно. Пайплайн знает про поколения и
 * публикацию, этот класс — про диаграммы; смешивать их значит получить одно
 * место, где ломается и то и другое. Ни часов, ни собственной области корутин
 * внутри нет: загрузка идёт в корутине вызывающего и отменяется вместе с ней
 * (`FR-10`, `TC-21`).
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
    private val timeoutMillis: Long = DIAGRAM_REQUEST_TIMEOUT_MILLIS,
    private val parallelRequests: Int = DIAGRAM_PARALLEL_REQUESTS,
    private val log: DiagramLoadLog = DiagramLoadLog.None,
    private val clock: () -> Long = { 0 },
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
     * Загружает недостающие изображения.
     *
     * Три ограничения, и каждое закрывает свой способ испортить пользователю
     * сеанс:
     *
     * . *таймаут на каждую загрузку* — молчащий сервер иначе держит плейсхолдер
     *   до конца сеанса (`TC-17`);
     * . *предел одновременных запросов* — документ с двумя десятками диаграмм
     *   иначе открывает два десятка соединений (`TC-18`);
     * . *проверка байтов* — сервер мог ответить `200` и прислать страницу входа
     *   в прокси; отдавать её в `+<img>+` под видом картинки нельзя (`TC-30`).
     *
     * Отказ одной диаграммы не мешает остальным (`FR-21`): исход каждой решается
     * отдельно, общего «не вышло» у прохода нет.
     *
     * Отмена вызывающего отменяет все загрузки разом — они запущены в его
     * области через [coroutineScope], своей у резолвера нет намеренно.
     */
    suspend fun load(missing: List<KrokiAddress>): Unit = coroutineScope {
        val transport = transport() ?: return@coroutineScope
        val permits = Semaphore(parallelRequests)
        val loaded = missing
            .map { address -> async { permits.withPermit { fetchOne(transport, address) } } }
            .awaitAll()
            .filterNotNull()

        // Единственный писатель, и это не аккуратность, а снятие условия.
        // Хранилище — обычный LinkedHashMap, а загрузки идут параллельно;
        // писать из них значило бы держать негласное «вызывающий однопоточный».
        // Сегодня оно верно (пайплайн живёт на главном диспетчере), но нигде не
        // записано, и снимет его первый же вызов с другого диспетчера — молча и
        // не всегда. Запись после ожидания всех убирает условие вовсе, ничего не
        // меняя в поведении: вторая публикация и так ждёт последнюю загрузку.
        for ((address, bytes) in loaded) store.put(address, bytes)
    }

    /**
     * Одна загрузка целиком: срок, проверка байтов, запись об исходе.
     *
     * Исключение транспорта ловится *здесь*, а не в общей области. Это не
     * перестраховка: `async` внутри `coroutineScope` отменяет соседей и
     * всплывает наружу, где пайплайн превращает его в плашку отказа рендера на
     * весь документ. `FR-21` запрещает ровно это — отказ одной диаграммы не
     * должен трогать остальные, и до слайса он стоил одной диаграммы, а с
     * параллельной загрузкой стоил бы всей пачки.
     *
     * Отмена вызывающего исключением не считается и проходит насквозь.
     */
    private suspend fun fetchOne(
        transport: DiagramTransport,
        address: KrokiAddress,
    ): Pair<KrokiAddress, ByteArray>? {
        val startedAt = clock()
        var outcome = DiagramLoadOutcome.Failed
        var result: Pair<KrokiAddress, ByteArray>? = null
        try {
            // Ответ заворачивается в список, чтобы отличить «срок вышел» от
            // «транспорт вернул null»: сам по себе withTimeoutOrNull даёт null
            // в обоих случаях, а для диагностики это разные исходы (TC-29).
            val answered = withTimeoutOrNull(timeoutMillis) { listOf(transport.fetch(address.url)) }
            val bytes = answered?.single()
            when {
                answered == null -> outcome = DiagramLoadOutcome.TimedOut
                bytes == null -> outcome = DiagramLoadOutcome.Failed
                !looksLikeImage(address.format, bytes) -> outcome = DiagramLoadOutcome.NotAnImage
                else -> {
                    outcome = DiagramLoadOutcome.Loaded
                    result = address to bytes
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            outcome = DiagramLoadOutcome.Broken
        }
        log.record(DiagramLoadRecord(outcome, address.url.length, clock() - startedAt))
        return result
    }
}
