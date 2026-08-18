package io.github.olegnyr.adocmobile.diagram

import io.github.olegnyr.adocmobile.render.DiagramOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-4`: ограничения загрузки, которым не нужны часы.
 *
 * Дом — `commonTest`, как требует правило проекта: предел параллельности и
 * проверка байтов от времени не зависят, а приём с воротами уже применён в
 * соседнем `PreviewPipelineDiagramsTest`. В host-наборе остался единственный
 * кейс, которому нужны настоящие часы (`TC-17`).
 */
class DiagramResolverTest {

    private val server = "https://kroki.example"
    private val options = DiagramOptions(krokiEnabled = true, serverUrl = server)
    private val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".encodeToByteArray()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun address(payload: String) =
        checkNotNull(parseKrokiAddress("$server/plantuml/svg/$payload", server))

    private fun resolver(store: DiagramImageStore, transport: DiagramTransport) = DiagramResolver(
        options = { options },
        store = store,
        transport = { transport },
        inflate = { null },
    )

    /**
     * `TC-18` — покрывает `FR-10`: одновременных загрузок ровно предел, не
     * больше и не одна.
     *
     * Оракул — число из требования (`NFR-1`: не более четырёх), записанное
     * литералом. Сравнивать с самой константой бессмысленно: подмена её на
     * сорок оставила бы тест зелёным.
     *
     * Второе утверждение не менее важно первого: последовательная реализация,
     * какой она была до слайса, «предел» тоже не превышает. Поэтому проверяется,
     * что стартовавших *ровно* четыре, пока ни одна не ответила.
     */
    @Test
    fun TC_18_noMoreThanFourRequestsAtOnce() {
        val store = DiagramImageStore()
        val gated = GatedTransport()

        scope.launch { resolver(store, gated).load((1..8).map { address("PAYLOAD$it") }) }

        assertEquals(4, gated.started, "одновременно стартовало ${gated.started} загрузок вместо четырёх")

        gated.answerAll(svg)
        // Ровно то, ради чего тест написан: следующие поехали только после
        // ответа предыдущим, то есть предел держится не на первом такте, а всё
        // время. Второй ответ нужен именно поэтому — вторая четвёрка ещё висит.
        assertEquals(8, gated.started, "после ответа первых четырёх остальные не поехали")

        gated.answerAll(svg)
        assertTrue(store.contains(address("PAYLOAD8")), "последняя диаграмма не загрузилась")
    }

    /** `TC-30` — покрывает `NFR-8`: не похожее на картинку в хранилище не попадает. */
    @Test
    fun TC_30_foreignBytesNeverReachTheStore() {
        val store = DiagramImageStore()
        val proxyPage = DiagramTransport { "<!DOCTYPE html><html>Вход в сеть</html>".encodeToByteArray() }

        scope.launch { resolver(store, proxyPage).load(listOf(address("AAAA"))) }

        assertFalse(store.contains(address("AAAA")), "страница входа в прокси попала в хранилище")
    }

    /** `FR-21`: отказ одной диаграммы не мешает соседней. */
    @Test
    fun TC_9_oneFailureDoesNotStopTheRest() {
        val store = DiagramImageStore()
        val picky = DiagramTransport { url -> if ("BAD" in url) null else svg }

        scope.launch { resolver(store, picky).load(listOf(address("BAD"), address("GOOD"))) }

        assertFalse(store.contains(address("BAD")))
        assertTrue(store.contains(address("GOOD")), "соседняя диаграмма не загрузилась из-за чужого отказа")
    }

    /**
     * `FR-21`: *бросок* из транспорта тоже остаётся локальным.
     *
     * Отдельно от предыдущего кейса, потому что механизм другой и опаснее.
     * Возврат `null` — предусмотренный контрактом исход; исключение проходит по
     * другому пути: `async` внутри общей области отменяет соседей и всплывает в
     * пайплайн, где становится плашкой отказа на весь документ. До параллельной
     * загрузки это стоило одной диаграммы, теперь стоило бы всей пачки.
     */
    @Test
    fun TC_9_throwingTransportDoesNotKillTheBatch() {
        val store = DiagramImageStore()
        val throwing = DiagramTransport { url ->
            if ("BAD" in url) error("транспорт сломался") else svg
        }

        scope.launch { resolver(store, throwing).load(listOf(address("BAD"), address("GOOD"))) }

        assertFalse(store.contains(address("BAD")))
        assertTrue(store.contains(address("GOOD")), "бросок из одной загрузки уронил соседнюю")
    }

    /**
     * `TC-29` — покрывает `NFR-8`: в диагностику уходит исход, длина и время —
     * и ничего из документа.
     */
    @Test
    fun TC_29_diagnosticRecordCarriesNoDocumentContent() {
        val store = DiagramImageStore()
        val records = mutableListOf<DiagramLoadRecord>()
        val resolver = DiagramResolver(
            options = { options },
            store = store,
            transport = { DiagramTransport { "<!DOCTYPE html><html>прокси</html>".encodeToByteArray() } },
            inflate = { null },
            log = { records += it },
        )
        val secret = address("0JHQvtC70YzRiNC-0Lkg0YHQtdC60YDQtdGC")

        scope.launch { resolver.load(listOf(secret)) }

        val record = records.single()
        assertEquals(DiagramLoadOutcome.NotAnImage, record.outcome, "исход записан неверно")
        assertEquals(secret.url.length, record.addressLength)
        val line = record.toString()
        assertFalse(secret.url in line, "адрес диаграммы попал в диагностику: $line")
        assertFalse(secret.payload in line, "закодированный исходник попал в диагностику: $line")
    }

    /** Транспорт с воротами: считает стартовавшие загрузки, отвечает по команде. */
    private class GatedTransport : DiagramTransport {
        private val gates = mutableListOf<CompletableDeferred<ByteArray?>>()
        val started: Int get() = gates.size

        override suspend fun fetch(url: String): ByteArray? {
            val gate = CompletableDeferred<ByteArray?>()
            gates += gate
            return gate.await()
        }

        fun answerAll(bytes: ByteArray) {
            // Снимок: ответ первым четырём запускает следующие, и те добавятся
            // в список прямо во время обхода.
            gates.toList().forEach { it.complete(bytes) }
        }
    }
}
