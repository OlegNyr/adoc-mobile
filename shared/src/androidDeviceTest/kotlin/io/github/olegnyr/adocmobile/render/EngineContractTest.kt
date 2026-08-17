package io.github.olegnyr.adocmobile.render

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 003-render-preview: `engine`-кейсы контракта рендера — `TC-1`…`TC-4`,
 * `TC-6`. Написаны в `SL-1`, но не имели исполняемого дома до ADR-006: им нужен
 * живой QuickJS с бандлом, то есть устройство.
 *
 * `TC-1`, `TC-2`, `TC-6` и половина `TC-4` идут через *продуктовый* рендерер —
 * проверяется контракт `render`, а не движок сам по себе. `TC-3` и вторая
 * половина `TC-4` зовут движок мимо продукта ([TestEngine]): их предмет —
 * способ вызова, которого в продукте нет ([TC_3]) или нет с такими опциями.
 */
class EngineContractTest {

    private val renderer = adocRenderer()

    init {
        installAdocRenderer(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun TC_1_documentConvertsToHtmlWithExpectedClasses() {
        val source = """
            = Документ контракта

            == Секция

            Абзац обычного текста.

            NOTE: Предупреждение в admonition.

            [source,kotlin]
            ----
            val x = 1
            ----

            |===
            | Ячейка таблицы
            |===
        """.trimIndent()

        val html = runBlocking { renderer.render(source) }

        // Оракул — классы из FR-21, а не длина строки.
        for (expectedClass in listOf("sect1", "paragraph", "admonitionblock", "listingblock", "tableblock")) {
            assertTrue(
                "\"$expectedClass\"" in html || " $expectedClass" in html || "=\"$expectedClass" in html,
                "в выводе нет класса «$expectedClass»; начало вывода: ${html.take(200)}",
            )
        }
    }

    @Test
    fun TC_2_twoRendersDoNotRaiseEngineTwice() {
        val before = QuickJsAdocRenderer.initCount

        runBlocking { renderer.render("= Первый\n\nтекст\n") }
        val afterFirst = QuickJsAdocRenderer.initCount

        runBlocking { renderer.render("= Второй\n\nтекст\n") }
        val afterSecond = QuickJsAdocRenderer.initCount

        // FR-3: движок поднимается не более одного раза, и только если ещё не
        // был поднят. Дельта, а не абсолют: порядок тестов в классе решает
        // раннер, и другой тест мог поднять синглтон раньше.
        assertTrue(afterFirst - before <= 1, "первый render поднял движок ${afterFirst - before} раз(а)")
        assertEquals(afterFirst, afterSecond, "второй render не имеет права поднимать движок заново")
        assertTrue(afterSecond >= 1, "движок не поднялся вовсе")
    }

    @Test
    fun TC_3_naivePromiseReadIsGarbageNotHtml() {
        val source = "= Документ\n\nАбзац.\n"

        // Наивный путь: результат convert берётся как есть, без разворачивания
        // промиса. В 4.x convert асинхронный, и строковое представление промиса
        // неотличимо на глаз от пустого документа — тест существует, чтобы
        // регресс FR-6 был виден, а не выглядел как пустой вывод.
        val naive = TestEngine.withEngine { engine ->
            val literal = jsonStringLiteral(source)
            engine.evaluate<String>(
                "'' + globalThis.Asciidoctor.convert($literal, { standalone: false });",
                filename = "naive.js",
            )
        }

        val honest = runBlocking { renderer.render(source) }

        assertFalse("<p>" in naive, "наивное чтение внезапно вернуло HTML: $naive")
        assertTrue(naive.length < 100, "наивное чтение вернуло не подпись промиса, а ${naive.length} симв.")
        assertTrue("<p>" in honest, "продуктовый путь обязан возвращать настоящий HTML")
        assertTrue("Абзац." in honest)
    }

    @Test
    fun TC_4_showtitleControlsDocumentTitleInFragmentMode() {
        val source = "= Заголовок документа\n\nАбзац.\n"

        // Продуктовый путь задаёт showtitle (FR-7) — заголовок в выводе есть.
        val withTitle = runBlocking { renderer.render(source) }
        assertTrue("Заголовок документа</h1>" in withTitle, "с showtitle заголовок обязан попасть в фрагмент")

        // Без showtitle заголовок в режиме фрагмента не попадает в вывод.
        val withoutTitle = TestEngine.withEngine { engine ->
            TestEngine.convertForCorpus(engine, source)
        }
        assertFalse("<h1>" in withoutTitle, "без showtitle заголовку в фрагменте взяться неоткуда")
        assertTrue("Абзац." in withoutTitle, "документ без showtitle всё равно конвертируется")
    }

    @Test
    fun TC_6_thousandLineDocumentConvertsWithoutStackOverflow() {
        // Документ предельного размера по ADR-004: ~1000 строк со всеми
        // основными конструкциями, чтобы рекурсивный разбор был настоящим.
        val source = buildString {
            appendLine("= Предельный документ")
            appendLine()
            repeat(84) { section ->
                appendLine("== Секция $section")
                appendLine()
                appendLine("Абзац текста в секции $section с *жирным* и `кодом`.")
                appendLine()
                appendLine("* пункт списка один")
                appendLine("* пункт списка два")
                appendLine()
                appendLine("[source,kotlin]")
                appendLine("----")
                appendLine("val value$section = $section")
                appendLine("----")
                appendLine()
            }
        }
        assertTrue(source.lines().size >= 1000, "стенд обязан подавать не меньше 1000 строк")

        val html = runBlocking { renderer.render(source) }

        assertTrue("Секция 83" in html, "хвост документа потерян — конвертация не дошла до конца")
        assertTrue("sect1" in html)
    }
}
