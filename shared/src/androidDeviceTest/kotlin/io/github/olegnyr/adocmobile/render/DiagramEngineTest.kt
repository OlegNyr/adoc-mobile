package io.github.olegnyr.adocmobile.render

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-1`: расширение Kroki внутри бандла.
 *
 * Дом у этих кейсов только на устройстве (ADR-006): предмет проверки — живой
 * QuickJS с двумя бандлами, а не общий код. Разведка ставила ровно этот вопрос
 * и отвечала на него wasm-сборкой движка; здесь тот же вопрос задан настоящему
 * `quickjs-kt` (риск №1 плана работ).
 *
 * Все кейсы идут через *продуктовый* рендерер: предмет — контракт
 * [AdocRenderer.render] с [DiagramOptions], а не движок сам по себе.
 */
class DiagramEngineTest {

    private val renderer = adocRenderer()

    init {
        installAdocRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    private val diagramSource = """
        = Документ с диаграммой

        [plantuml, alice-bob, svg]
        ....
        @startuml
        alice -> bob: привет
        @enduml
        ....
    """.trimIndent()

    /** `TC-1` — покрывает `FR-1`, `FR-4`: блок диаграммы становится изображением с адресом Kroki. */
    @Test
    fun TC_1_plantumlBlockBecomesKrokiImage() {
        val options = DiagramOptions(krokiEnabled = true, serverUrl = "https://kroki.example")

        val html = runBlocking { renderer.render(diagramSource, options) }

        // Оракул один, а не два независимых: адрес обязан стоять именно в src
        // изображения. Раздельные проверки «есть подстрока» и «есть <img>»
        // прошли бы и на выводе, где адрес попал, например, в ссылку.
        val image = Regex("""<img[^>]+src="(https://kroki\.example/plantuml/svg/[^"]+)"""").find(html)
        assertTrue(image != null, "нет <img> с адресом Kroki; начало вывода: ${html.take(300)}")
        assertFalse(
            "@startuml" in html || "alice -> bob" in html,
            "исходник диаграммы остался в выводе — блок не был превращён в изображение",
        )
    }

    /**
     * `TC-2` — покрывает `FR-3`, `FR-23`: при выключенных диаграммах адресов
     * Kroki нет вовсе.
     *
     * Три вызова подряд, и каждый закрывает свою дыру.
     *
     * . *Сначала включённый рендер* — иначе движок к моменту проверки может
     *   вообще не знать о диаграммах, и «адреса нет» ничего не доказывает.
     * . *Затем выключенный на том же движке* — глобальные переменные JS живут
     *   между конвертациями, и значение прошлого рендера не имеет права
     *   протечь в следующий.
     * . *И вызов без опций* — это единственная форма, которой пользуется сам
     *   продукт (`PreviewPipeline`). Проверять только явный `Disabled` значит
     *   не заметить, как умолчание в реализации поменяли на «включено», и
     *   документы поедут на чужой сервер (решение владельца `OQ-2`).
     */
    @Test
    fun TC_2_disabledModeLeavesNoKrokiAddress() {
        val options = DiagramOptions(krokiEnabled = true, serverUrl = "https://kroki.example")

        val enabled = runBlocking { renderer.render(diagramSource, options) }
        val disabled = runBlocking { renderer.render(diagramSource, DiagramOptions.Disabled) }
        val withoutOptions = runBlocking { renderer.render(diagramSource) }

        assertTrue("https://kroki.example/" in enabled, "включённый рендер не дал адреса — проверять нечего")

        for ((name, html) in listOf("явный Disabled" to disabled, "вызов без опций" to withoutOptions)) {
            assertFalse("kroki" in html.lowercase(), "$name: в выводе есть след Kroki: ${html.take(300)}")
            assertTrue("literalblock" in html, "$name: блок не выведен литеральным: ${html.take(300)}")
            assertTrue("@startuml" in html, "$name: исходник диаграммы пропал: ${html.take(300)}")
        }
    }

    /**
     * `TC-3` — покрывает `FR-6`: реестр расширений поднимается вместе с движком
     * и переживает несколько конвертаций.
     *
     * Второе утверждение здесь важнее первого: реестр Asciidoctor.js —
     * состояние, и повторное использование одного экземпляра на двух документах
     * могло бы молча перестать работать со второго раза.
     */
    @Test
    fun TC_3_engineAndRegistryAreRaisedOnce() {
        val options = DiagramOptions(krokiEnabled = true, serverUrl = "https://kroki.example")

        val before = QuickJsAdocRenderer.initCount
        val first = runBlocking { renderer.render(diagramSource, options) }
        val afterFirst = QuickJsAdocRenderer.initCount
        val second = runBlocking { renderer.render(diagramSource, options) }
        val afterSecond = QuickJsAdocRenderer.initCount

        // Спека говорит «счётчик равен 1». Буквально это недостижимо: движок —
        // синглтон процесса, и до этого теста его мог поднять любой другой.
        // Проверяемый инвариант тот же: первый рендер поднимает движок не более
        // одного раза, второй — не поднимает вовсе, и движок к этому моменту
        // поднят хотя бы раз. Формулировка кейса приведена к этому же виду.
        assertEquals(afterFirst, afterSecond, "движок поднялся повторно")
        assertTrue(afterFirst - before <= 1, "первый рендер поднял движок больше одного раза")
        assertTrue(afterFirst >= 1, "движок не поднимался вовсе — счётчик подъёмов не работает")
        assertTrue("https://kroki.example/plantuml/svg/" in first, "первый вывод без адреса диаграммы")
        assertTrue("https://kroki.example/plantuml/svg/" in second, "второй вывод без адреса диаграммы")
    }

    /**
     * `TC-37` — покрывает `FR-19`: сломанное или отсутствующее расширение не
     * отнимает у пользователя превью.
     *
     * Ассет расширения — файл в APK, и «его не может не быть» звучит убедительно
     * ровно до первой сборки, где он не собрался. Цена ошибки несимметрична:
     * без расширения теряются диаграммы, без движка — весь документ, причём и у
     * тех, у кого диаграммы выключены (умолчание `ВЫКЛ`, `OQ-2`).
     *
     * Движок здесь берётся тестовый ([TestEngine]): продуктовый синглтон уже
     * поднят с настоящим расширением, и сломать его нечем.
     */
    @Test
    fun TC_37_brokenExtensionLeavesEngineUsable() {
        TestEngine.withEngine { engine ->
            val installed = installKrokiExtension(engine, "это не( валидный javascript {{{")

            assertFalse(installed, "сломанный ассет расширения выдал себя за поднятый")

            // Ядро живо: конвертация после отказа расширения работает как обычно.
            // Заголовок здесь не оракул намеренно: TestEngine конвертирует как
            // эталон корпуса, то есть без showtitle, и заголовок документа в
            // вывод не попадает (см. его KDoc). Проверяется тело документа.
            val html = TestEngine.convertForCorpus(engine, "= Заголовок\n\nАбзац после отказа.")
            assertTrue("Абзац после отказа." in html, "ядро не пережило отказ расширения: ${html.take(200)}")
            assertTrue("paragraph" in html, "ядро отдало не тот вывод: ${html.take(200)}")
        }
    }

    /**
     * `TC-35` — покрывает `NFR-8`, `FR-4`: адрес сервера задаёт приложение, а не
     * документ.
     *
     * Документ пользователя — недоверенный ввод. Если бы `:kroki-server-url:`
     * внутри него перебивал значение из настроек, любой присланный файл
     * отправлял бы содержимое своих диаграмм куда угодно, а пользователь при
     * этом видел бы в настройках свой сервер. Атрибуты, переданные через API,
     * по умолчанию сильнее объявленных в документе — этот тест фиксирует, что
     * умолчание именно такое, и не даёт ему поменяться при обновлении движка.
     */
    @Test
    fun TC_35_documentCannotOverrideServerAddress() {
        val source = """
            = Документ с чужим сервером
            :kroki-server-url: https://evil.example

            [plantuml]
            ....
            @startuml
            a -> b
            @enduml
            ....
        """.trimIndent()
        val options = DiagramOptions(krokiEnabled = true, serverUrl = "https://kroki.example")

        val html = runBlocking { renderer.render(source, options) }

        assertFalse("evil.example" in html, "документ переопределил адрес сервера: ${html.take(300)}")
        assertTrue("https://kroki.example/plantuml/svg/" in html, "адрес из настроек не применён: ${html.take(300)}")
    }

    /**
     * `TC-5` — покрывает `FR-19`: путь, которому нужен HTTP из движка,
     * деградирует в блок с исходным текстом, а не роняет конвертацию.
     *
     * `format=txt` заставляет расширение самому сходить на сервер за текстовым
     * представлением; `fetch` в QuickJS нет, и расширение пересобирает блок с
     * ролью `kroki-error`. Это и есть деградация, которой требует `US-E6-02`.
     */
    @Test
    fun TC_5_pathThatNeedsHttpFromEngineDegradesToSource() {
        val source = """
            = Документ с диаграммой

            [plantuml, format=txt]
            ....
            @startuml
            a -> b
            @enduml
            ....
        """.trimIndent()
        val options = DiagramOptions(krokiEnabled = true, serverUrl = "https://kroki.example")

        // Предусловие, без которого тест меняет смысл: деградация здесь наступает
        // потому, что в движке нет fetch. Появится полифил — этот кейс начнёт
        // ходить в сеть с устройства, и лучше он упадёт здесь с внятным
        // сообщением, чем зависнет на таймауте конвертации и утащит за собой
        // синглтон движка.
        val hasFetch = TestEngine.withEngine { engine ->
            engine.evaluate<String>("typeof globalThis.fetch", filename = "probe.js")
        }
        assertEquals("undefined", hasFetch, "в движке появился fetch — TC-5 нужно переписать, он уйдёт в сеть")

        val html = runBlocking { renderer.render(source, options) }

        assertTrue("kroki-error" in html, "нет пометки отказа расширения: ${html.take(300)}")
        assertTrue("@startuml" in html, "исходник диаграммы не показан: ${html.take(300)}")
    }
}
