package io.github.olegnyr.adocmobile.render.corpus

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.olegnyr.adocmobile.render.TestEngine
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TC-25` (`FR-29`): корпус сверки на устройстве — вывод связки
 * «Asciidoctor.js + QuickJS + ARM» против эталона Ruby Asciidoctor.
 *
 * ADR-005 требует различать три исхода, и тест их различает:
 *
 * новое расхождение:: файл разошёлся, а в реестре известных его нет, или его
 *   отпечаток не совпал с записанным — *тест падает*;
 * известное расхождение:: файл разошёлся ровно так, как записано в
 *   [knownDeviations], — тест проходит;
 * известное исчезло:: файл из реестра совпал с эталоном — *тест падает* с
 *   требованием вычеркнуть запись: это сигнал, что апстрим починил и можно
 *   обновляться; без падения о починке никто не узнает.
 *
 * Отпечаток известного расхождения — SHA-256 канонической формы вывода движка:
 * любой сдвиг в известном файле (починка, новый дефект рядом) меняет отпечаток
 * и всплывает как исход, требующий человека.
 *
 * Полный вывод, канонические формы и диффы пишутся в каталог внешних файлов
 * приложения — путь печатается в сообщении об ошибке, забирается `adb pull`.
 */
class CorpusComparisonTest {

    /**
     * Реестр известных расхождений — «перечень принятых расхождений» из `OQ-7`.
     *
     * Источники записей — ADR-005 (два дефекта апстрима) и `OQ-6`+`TC-27`
     * (include в режиме `secure`). Отпечатки сняты прогоном на устройстве
     * (Samsung SM-S948B, Android 16) и защищают записи от разрастания: под
     * запись с чужим отпечатком новое расхождение не спрячется.
     */
    private val knownDeviations = mapOf(
        // Записи «асциидоктор#1870: pass:[…] экранирует содержимое» (10-macros-inline,
        // 18-substitutions) умерли переходом на 4.0.10 — дефект починен апстримом,
        // подтверждено этим тестом и комментарием в issue (2026-08-17).
        "13-footnotes" to Known(
            reason = "асциидоктор#1871 (комментарий от 2026-08-17): 4.0.10 починил дубли id, " +
                "но сноски списков и ячеек нумеруются раньше предшествующих абзацных — " +
                "порядок расходится с Ruby",
            actualSha256 = "1e438b330deda2ff0e1a152be80b224b5831c2ca8fbc79e87bb4e0742c76ae45",
        ),
        "14-include-directives" to Known(
            reason = "OQ-6/TC-27: режим secure — include:: превращается в ссылку; эталон снят в safe с файловой системой",
            actualSha256 = "427a62054ff7d6406d8331ebb674b217dcecb2710aa53a6aebd61d88be494619",
        ),
    )

    private data class Known(val reason: String, val actualSha256: String?)

    private val assets: AssetManager =
        InstrumentationRegistry.getInstrumentation().context.assets

    private fun corpusStems(): List<String> =
        assets.list("src").orEmpty()
            .filter { it.endsWith(".adoc") }
            .map { it.removeSuffix(".adoc") }
            .sorted()

    private fun readAsset(path: String): String =
        assets.open(path).use { it.readBytes().decodeToString() }

    private fun sha256(lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { digest.update(it.toByteArray()); digest.update('\n'.code.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun TC_25_corpusMatchesReferenceUpToKnownDeviations() {
        val stems = corpusStems()
        assertEquals(18, stems.size, "состав корпуса изменился: $stems")
        knownDeviations.keys.forEach { stem ->
            assertTrue(stem in stems, "в реестре известных расхождений мёртвая запись: $stem")
        }

        val reportDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir(null),
            "render-corpus-report",
        ).apply { deleteRecursively(); mkdirs() }

        val newDeviations = mutableListOf<String>()
        val knownGone = mutableListOf<String>()
        val knownSeen = mutableListOf<String>()
        val matched = mutableListOf<String>()

        TestEngine.withEngine { engine ->
            for (stem in stems) {
                val source = readAsset("src/$stem.adoc")
                val actualHtml = TestEngine.convertForCorpus(engine, source)
                val expectedHtml = readAsset("expected/$stem.html")

                val actual = canonicalize(actualHtml)
                val expected = canonicalize(expectedHtml)
                File(reportDir, "$stem.actual.html").writeText(actualHtml)
                File(reportDir, "$stem.actual.canonical.txt").writeText(actual.joinToString("\n"))
                File(reportDir, "$stem.expected.canonical.txt").writeText(expected.joinToString("\n"))

                val known = knownDeviations[stem]
                if (actual == expected) {
                    if (known != null) {
                        knownGone += "$stem — записано как «${known.reason}», но вывод совпал с эталоном"
                    } else {
                        matched += stem
                    }
                    continue
                }

                val fingerprint = sha256(actual)
                val diff = unifiedDiff(expected, actual, context = 2)
                File(reportDir, "$stem.diff").writeText(diff.joinToString("\n"))

                when {
                    known == null -> newDeviations +=
                        "$stem — расхождение без записи в реестре; отпечаток $fingerprint\n" +
                            diff.take(30).joinToString("\n")

                    known.actualSha256 == null -> newDeviations +=
                        "$stem — известное расхождение без отпечатка; зафиксируйте actualSha256 = \"$fingerprint\"\n" +
                            "причина: ${known.reason}\n" + diff.take(15).joinToString("\n")

                    known.actualSha256 != fingerprint -> newDeviations +=
                        "$stem — вывод не совпал с отпечатком известного расхождения (было ${known.actualSha256}, стало $fingerprint):\n" +
                            "либо апстрим сдвинулся, либо рядом появился новый дефект\n" +
                            diff.take(30).joinToString("\n")

                    else -> knownSeen += "$stem — ${known.reason}"
                }
            }
        }

        val verdict = buildString {
            appendLine("корпус: ${stems.size} файлов; совпало: ${matched.size}; известных расхождений: ${knownSeen.size}")
            knownSeen.forEach { appendLine("  известное: $it") }
            knownGone.forEach { appendLine("  ИСЧЕЗЛО: $it") }
            newDeviations.forEach { appendLine("  НОВОЕ: $it") }
            appendLine("отчёт: ${reportDir.absolutePath}")
        }
        File(reportDir, "summary.txt").writeText(verdict)

        assertTrue(
            newDeviations.isEmpty() && knownGone.isEmpty(),
            "корпус разошёлся с ожиданиями:\n$verdict",
        )
    }

    /**
     * Паритет порта канонизации с python-оригиналом: `_manifest.json` эталона
     * хранит `canonical_lines`, посчитанные `corpus.py` на тех же HTML-файлах.
     * Расхождение счёта — сломанный порт, который дальше сравнивал бы шум.
     * (Свёртка `fold_presentational` счёт строк не меняет: она переписывает
     * атрибуты внутри строки тега, не добавляя и не убирая строк.)
     */
    @Test
    fun canonicalizerCountsMatchPythonManifest() {
        val manifest = JSONObject(readAsset("expected/_manifest.json")).getJSONObject("files")
        val mismatches = mutableListOf<String>()
        for (stem in corpusStems()) {
            val expectedCount = manifest.getJSONObject("$stem.adoc").getInt("canonical_lines")
            val actualCount = canonicalize(readAsset("expected/$stem.html")).size
            if (expectedCount != actualCount) {
                mismatches += "$stem: python насчитал $expectedCount, порт — $actualCount"
            }
        }
        assertTrue(mismatches.isEmpty(), "порт канонизации разошёлся с оригиналом:\n${mismatches.joinToString("\n")}")
    }
}

/** Юнифицированный дифф на LCS — только для человекочитаемого отчёта. */
internal fun unifiedDiff(expected: List<String>, actual: List<String>, context: Int): List<String> {
    val n = expected.size
    val m = actual.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] = if (expected[i] == actual[j]) lcs[i + 1][j + 1] + 1
            else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
    }
    data class Op(val kind: Char, val line: String)
    val ops = mutableListOf<Op>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            expected[i] == actual[j] -> { ops += Op(' ', expected[i]); i++; j++ }
            lcs[i + 1][j] >= lcs[i][j + 1] -> { ops += Op('-', expected[i]); i++ }
            else -> { ops += Op('+', actual[j]); j++ }
        }
    }
    while (i < n) { ops += Op('-', expected[i]); i++ }
    while (j < m) { ops += Op('+', actual[j]); j++ }

    val keep = BooleanArray(ops.size)
    ops.forEachIndexed { index, op ->
        if (op.kind != ' ') {
            for (k in maxOf(0, index - context)..minOf(ops.size - 1, index + context)) keep[k] = true
        }
    }
    val out = mutableListOf<String>()
    var lastKept = -2
    ops.forEachIndexed { index, op ->
        if (!keep[index]) return@forEachIndexed
        if (index != lastKept + 1) out += "@@"
        out += "${op.kind}${op.line}"
        lastKept = index
    }
    return out
}
