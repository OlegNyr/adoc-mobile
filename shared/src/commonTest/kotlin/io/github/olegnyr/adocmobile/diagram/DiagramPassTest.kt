package io.github.olegnyr.adocmobile.diagram

import io.github.olegnyr.adocmobile.preview.PREVIEW_BASE_URL
import io.github.olegnyr.adocmobile.render.DiagramOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-3`: проход по фрагменту.
 *
 * Проверяется решение `OQ-4` в действии: первый проход не ждёт сеть, ставит
 * плейсхолдеры и называет недостающее; второй — подставляет пришедшее.
 */
class DiagramPassTest {

    private val server = "https://kroki.example"
    private val options = DiagramOptions(krokiEnabled = true, serverUrl = server)

    private val twoDiagrams = """
        <div class="imageblock kroki">
        <div class="content">
        <img src="https://kroki.example/plantuml/svg/AAAA" alt="первая">
        </div>
        </div>
        <div class="imageblock kroki">
        <div class="content">
        <img src="https://kroki.example/mermaid/svg/BBBB" alt="вторая">
        </div>
        </div>
    """.trimIndent()

    /** `TC-7` — покрывает `FR-8`: пока изображения нет, на его месте плейсхолдер. */
    @Test
    fun TC_7_firstPassShowsPlaceholdersAndNamesWhatIsMissing() {
        val pass = diagramPass(twoDiagrams, options, DiagramImageStore(), inflate = null)

        assertTrue("kroki-pending" in pass.html, "нет плейсхолдера: ${pass.html}")
        assertTrue("СБОРКА…" in pass.html, "плейсхолдер без подписи состояния: ${pass.html}")
        assertTrue("PLANTUML" in pass.html && "MERMAID" in pass.html, "тип диаграммы не назван: ${pass.html}")
        assertFalse("<img" in pass.html, "картинка взялась ниоткуда: ${pass.html}")
        assertEquals(listOf("plantuml", "mermaid"), pass.missing.map { it.type })
    }

    /** `TC-12` — покрывает `FR-11`: одна и та же диаграмма грузится один раз. */
    @Test
    fun TC_12_repeatedDiagramIsRequestedOnce() {
        val twice = twoDiagrams + "\n" + twoDiagrams

        val pass = diagramPass(twice, options, DiagramImageStore(), inflate = null)

        assertEquals(2, pass.missing.size, "повторы не схлопнулись: ${pass.missing.map { it.url }.size}")
    }

    /** `TC-6` — покрывает `FR-7`: пришедшее изображение подставляется локальным адресом. */
    @Test
    fun TC_6_secondPassSubstitutesLoadedImages() {
        val store = DiagramImageStore()
        val first = diagramPass(twoDiagrams, options, store, inflate = null)
        for (address in first.missing) {
            store.put(address, byteArrayOf(1, 2, 3))
        }

        val second = diagramPass(twoDiagrams, options, store, inflate = null)

        assertTrue(second.missing.isEmpty(), "после загрузки всё ещё чего-то не хватает")
        assertFalse("kroki-pending" in second.html, "плейсхолдер остался: ${second.html}")
        assertEquals(2, Regex("<img").findAll(second.html).count(), "изображений не два: ${second.html}")
        assertFalse("kroki.example" in second.html, "внешний адрес дошёл до страницы: ${second.html}")
        assertTrue(PREVIEW_BASE_URL in second.html, "адрес не из зоны превью: ${second.html}")
    }

    /**
     * `TC-42`: локальный адрес собирается из того же基 адреса, что понимает
     * перехватчик превью.
     *
     * Значение продублировано в двух пакетах намеренно (взаимная зависимость
     * пакетов хуже), и расхождение стоило бы битой картинки на каждой
     * диаграмме — молча.
     */
    @Test
    fun TC_42_localAddressMatchesThePreviewZone() {
        assertEquals(PREVIEW_BASE_URL, PREVIEW_BASE_URL_FOR_DIAGRAMS)
    }

    @Test
    fun TC_7_unshowableFormatDegradesWithoutWaiting() {
        val pdf = """<img src="https://kroki.example/plantuml/pdf/AAAA" alt="схема">"""

        val pass = diagramPass(pdf, options, DiagramImageStore(), inflate = null)

        assertTrue(pass.missing.isEmpty(), "формат, который нечем показать, поставлен в очередь загрузки")
        assertTrue(DIAGRAM_UNAVAILABLE_NOTE in pass.html, "нет пометки: ${pass.html}")
    }

    @Test
    fun TC_27_disabledModeTouchesNothing() {
        val pass = diagramPass(twoDiagrams, DiagramOptions.Disabled, DiagramImageStore(), inflate = null)

        assertEquals(twoDiagrams, pass.html, "при выключенных диаграммах разметка изменена")
        assertTrue(pass.missing.isEmpty(), "при выключенных диаграммах что-то поставлено в очередь")
    }

    @Test
    fun TC_8_secondPassWithoutLoadingDegradesPlaceholders() {
        val pass = diagramPass(
            twoDiagrams,
            options,
            DiagramImageStore(),
            inflate = Inflate { _, _ -> "a -> b".encodeToByteArray() },
            pending = DiagramPending.Unavailable,
        )

        assertFalse("kroki-pending" in pass.html, "остался плейсхолдер вместо деградации: ${pass.html}")
        assertTrue(DIAGRAM_UNAVAILABLE_NOTE in pass.html, "нет пометки: ${pass.html}")
        assertTrue("a -&gt; b" in pass.html, "исходник не показан: ${pass.html}")
    }

    @Test
    fun TC_42_storeKeyIsStableAcrossInstances() {
        val address = assertNotNull(parseKrokiAddress("$server/plantuml/svg/AAAA", server))

        assertEquals(diagramImagePath(address), diagramImagePath(address.copy()))
        assertTrue(diagramImagePath(address)!!.startsWith(DIAGRAM_PATH_PREFIX))
        assertTrue(diagramImagePath(address)!!.endsWith(".svg"))
    }

    /**
     * `TC-32` — покрывает `NFR-1`: проход по предельному документу не съедает
     * бюджет отзывчивости.
     *
     * Порог с запасом и меряется на JVM, а не на устройстве: работа здесь —
     * чистые строки, без ввода-вывода и без платформы, и держать ради неё
     * device-прогон значит платить устройством за уже покрытое. Число служит
     * сторожем от алгоритмической ошибки (случайный квадрат по длине
     * документа), а не паспортом производительности телефона.
     */
    @Test
    fun TC_32_passOverALimitDocumentStaysWithinBudget() {
        val body = buildString {
            repeat(1_000) { line ->
                append("<div class=\"paragraph\"><p>Строка документа номер ").append(line).append("</p></div>\n")
                if (line % 100 == 0) {
                    append("<div class=\"imageblock kroki\"><div class=\"content\">")
                    append("<img src=\"").append(server).append("/plantuml/svg/AAAA").append(line)
                    append("\" alt=\"схема\"></div></div>\n")
                }
            }
        }
        val store = DiagramImageStore()
        // Полный кэш: меряется разбор и подстановка, а не загрузка.
        diagramPass(body, options, store, inflate = null).missing.forEach { store.put(it, byteArrayOf(1)) }

        // Прогрев: первый проход платит за прогрев JIT, и мерить его нечестно.
        repeat(3) { diagramPass(body, options, store, inflate = null) }

        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        val pass = diagramPass(body, options, store, inflate = null)
        val elapsedMillis = startedAt.elapsedNow().inWholeMilliseconds

        assertTrue(pass.missing.isEmpty(), "кэш не полон — меряется не то")
        assertTrue(elapsedMillis < 50, "проход по предельному документу занял $elapsedMillis мс")
    }
}
