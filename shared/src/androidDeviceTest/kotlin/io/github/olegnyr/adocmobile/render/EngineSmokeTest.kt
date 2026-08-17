package io.github.olegnyr.adocmobile.render

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Дымовой тест инструментального прогона (ADR-006): раннер стартует, контекст
 * есть, ассеты корпуса и бандл движка доехали до APK. Падает первым и внятно,
 * если сборка потеряла ассеты, — остальным тестам тогда нечего диагностировать.
 */
class EngineSmokeTest {

    @Test
    fun corpusAssetsArePackaged() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val sources = assets.list("src").orEmpty()
        assertTrue(
            sources.count { it.endsWith(".adoc") } == 18,
            "в ассетах ожидались 18 документов корпуса, найдено: ${sources.toList()}",
        )
    }

    @Test
    fun asciidoctorBundleIsPackaged() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open("asciidoctor/asciidoctor.js").use { it.readBytes() }
        assertTrue(bytes.size > 500_000, "бандл подозрительно мал: ${bytes.size} байт")
    }
}
