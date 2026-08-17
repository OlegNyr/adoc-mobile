package io.github.olegnyr.adocmobile.render

import androidx.test.platform.app.InstrumentationRegistry
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Тестовый экземпляр движка — для проверок, которым нужен движок *не* в
 * продуктовой конфигурации: корпус сверки рендерится опциями эталона (без
 * `showtitle`, см. `expected/_manifest.json`), а `TC-3`/`TC-4` нарочно зовут
 * движок способами, которых в продукте нет и не должно быть.
 *
 * Продуктовый синглтон эти тесты не трогают намеренно: его счётчик подъёмов —
 * оракул `TC-2`, и чужие обращения превратили бы тот тест в лотерею.
 *
 * Поток со стеком 8 МБ и запас до лимита движка — те же, что в
 * `AdocRenderer.android.kt`, по той же причине (`FR-4`): разбор AsciiDoc
 * рекурсивен, а лимит движка обязан сработать раньше настоящего стека.
 */
internal object TestEngine {

    private const val STACK_KB = 8192
    private const val STACK_HEADROOM_KB = 1024

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "adoc-test-engine", STACK_KB.toLong() * 1024)
    }.asCoroutineDispatcher()

    /** Поднимает свежий движок с бандлом, отдаёт его блоку и закрывает. */
    fun <T> withEngine(block: suspend (QuickJs) -> T): T = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bundle = assets.open("asciidoctor/asciidoctor.js").use {
            it.readBytes().decodeToString()
        }
        withContext(dispatcher) {
            val engine = QuickJs.create(dispatcher)
            try {
                engine.maxStackSize = (STACK_KB - STACK_HEADROOM_KB).toLong() * 1024
                engine.evaluate<Any?>(bundle, filename = "asciidoctor.js", asModule = false)
                block(engine)
            } finally {
                engine.close()
            }
        }
    }

    /**
     * Конвертация для сверки с эталоном корпуса.
     *
     * `corpus.py` требует один набор опций с эталоном — «иначе сравниваются не
     * реализации, а конфигурации». Эталон снят `html5` + `article` + embedded;
     * здесь то же самое. Два сознательных отступления от продуктового
     * `CONVERT_SCRIPT` и от эталона:
     *
     * * *режим безопасности не задаётся* — как в продукте: решение `OQ-6`
     *   оставило умолчание движка (`secure`), тогда как эталон снят в `safe`.
     *   Единственное следствие — `include::` превращается в ссылку; `TC-27`
     *   требует, чтобы это расхождение *присутствовало в перечне*, а не
     *   пряталось подгонкой опций теста;
     * * *без `showtitle`* — он продуктовая подача (`FR-7`), а не свойство
     *   движка: с ним каждый титулованный документ разошёлся бы с эталоном
     *   на `<h1>`, и конфигурационный шум утопил бы настоящие расхождения.
     */
    suspend fun convertForCorpus(engine: QuickJs, source: String): String {
        val literal = jsonStringLiteral(source)
        engine.evaluate<Any?>("globalThis.__corpusSource = $literal;", filename = "corpus-source.js")
        engine.evaluate<Any?>(
            """
            globalThis.__corpusHtml = await globalThis.Asciidoctor.convert(
              globalThis.__corpusSource,
              { standalone: false, backend: 'html5', doctype: 'article' }
            );
            """.trimIndent(),
            filename = "corpus-convert.mjs",
            asModule = true,
        )
        return engine.evaluate<String>("globalThis.__corpusHtml", filename = "corpus-fetch.js")
    }
}
