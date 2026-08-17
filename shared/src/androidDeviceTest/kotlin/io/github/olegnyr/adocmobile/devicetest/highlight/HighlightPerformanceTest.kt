package io.github.olegnyr.adocmobile.devicetest.highlight

import io.github.olegnyr.adocmobile.highlight.AdocBlockScanner
import io.github.olegnyr.adocmobile.highlight.AdocIncrementalHighlighter

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `T-024`: перф-пороги подсветки на устройстве — честные миллисекунды для
 * `TC-34`…`TC-36` (НФТ «Отзывчивость» фичи 001-syntax-highlighting).
 * Структурные прокси этих кейсов (число просканированных строк) закрыты в
 * `commonTest` слайсом `SL-5`; здесь — время на реальном ARM.
 *
 * Методика (без `kotlinx-benchmark` — решение владельца не добавлять
 * зависимость):
 *
 * * замер `System.nanoTime()` вокруг одного вызова сканера, ничего кроме
 *   сканера в замер не входит;
 * * первые [WARMUP] прогонов каждой серии отбрасываются — прогрев JIT и
 *   выделение буферов; отбрасывание зафиксировано в оракуле, а не подразумевается;
 * * порог холодного прохода сравнивается с *медианой* [FULL_RUNS] замеров —
 *   медиана давит выбросы планировщика; порог инкрементального — с *P95*
 *   [EDIT_RUNS] замеров, как сформулирован сам НФТ;
 * * документ — детерминированный генератор на ~1000 строк со смесью настоящих
 *   конструкций (заголовки, абзацы с инлайн-парами, списки, listing-блоки):
 *   пустой или однородный документ занизил бы стоимость прохода.
 *
 * Пороги из спеки: холодный полный проход < 100 мс на ~1000 строк (`TC-34`),
 * инкрементальный проход при вводе символа P95 < 16 мс (`TC-35`), худший
 * случай UC-4 — ввод `-`…`----` в пустой строке середины — тот же порог 16 мс,
 * но отдельной серией (`TC-36`): каждый шаг пересканирует остаток документа.
 */
class HighlightPerformanceTest {

    // region стенд

    /** ~1000 строк: 111 групп по 9 строк смешанных конструкций. */
    private fun buildDocument(): List<String> = buildList {
        repeat(111) { index ->
            add("== Раздел $index про *важное*")
            add("")
            add("Абзац $index с _курсивом_, `кодом` и ссылкой https://example.org/d$index тут.")
            add("Вторая строка абзаца $index без разметки, просто текст подлиннее.")
            add("")
            add("* пункт списка с **жирным** номер $index")
            add("----")
            add("код листинга $index // с <$index> хвостом")
            add("----")
        }
    }

    private fun median(samples: List<Long>): Long = samples.sorted()[samples.size / 2]

    private fun p95(samples: List<Long>): Long {
        val sorted = samples.sorted()
        return sorted[((sorted.size - 1) * 95) / 100]
    }

    private fun Long.toMillis(): Double = this / 1_000_000.0

    /** Числа замеров нужны отчёту и на зелёном прогоне — пишутся во внешние файлы. */
    private fun report(name: String, lines: List<String>) {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "highlight-perf-report",
        ).apply { mkdirs() }
        File(dir, "$name.txt").writeText(lines.joinToString("\n"))
    }

    // endregion

    @Test
    fun TC_34_coldFullScanUnder100MillisOnAThousandLines() {
        val text = buildDocument().joinToString("\n")

        repeat(WARMUP) { AdocBlockScanner.scan(text) }
        val samples = List(FULL_RUNS) {
            val startedAt = System.nanoTime()
            AdocBlockScanner.scan(text)
            System.nanoTime() - startedAt
        }

        val medianMs = median(samples).toMillis()
        report(
            "tc34-cold-full-scan",
            listOf(
                "строк: ${buildDocument().size}",
                "медиана: %.2f мс".format(medianMs),
                "минимум: %.2f мс".format(samples.min().toMillis()),
                "максимум: %.2f мс".format(samples.max().toMillis()),
                "замеров: ${samples.size} после $WARMUP прогревочных",
            ),
        )
        assertTrue(
            medianMs < 100.0,
            "холодный полный проход: медиана $medianMs мс ≥ 100 мс " +
                "(замеры, мс: ${samples.map { "%.2f".format(it.toMillis()) }})",
        )
    }

    @Test
    fun TC_35_incrementalEditUnder16MillisP95() {
        val lines = buildDocument()
        val base = lines.joinToString("\n")
        val highlighter = AdocIncrementalHighlighter()
        highlighter.update(base)

        // Правки — вставка и откат символа в текстовых строках разных групп:
        // документ возвращается к базе, а позиции гуляют по всему файлу.
        val editableLines = lines.indices.filter { lines[it].startsWith("Вторая строка") }
        val samples = mutableListOf<Long>()
        repeat(WARMUP + EDIT_RUNS) { run ->
            val line = editableLines[run % editableLines.size]
            val edited = lines.toMutableList().also { it[line] = lines[line] + "X" }.joinToString("\n")

            val startedInsert = System.nanoTime()
            highlighter.update(edited)
            val insertTook = System.nanoTime() - startedInsert

            val startedRevert = System.nanoTime()
            highlighter.update(base)
            val revertTook = System.nanoTime() - startedRevert

            if (run >= WARMUP) {
                samples += insertTook
                samples += revertTook
            }
        }

        val p95Ms = p95(samples).toMillis()
        report(
            "tc35-incremental-edit",
            listOf(
                "P95: %.3f мс".format(p95Ms),
                "медиана: %.3f мс".format(median(samples).toMillis()),
                "максимум: %.3f мс".format(samples.max().toMillis()),
                "замеров: ${samples.size} (вставка и откат × $EDIT_RUNS позиций) после $WARMUP прогревочных пар",
            ),
        )
        assertTrue(
            p95Ms < 16.0,
            "инкрементальный проход: P95 $p95Ms мс ≥ 16 мс на ${samples.size} правках " +
                "(медиана ${median(samples).toMillis()} мс)",
        )
    }

    @Test
    fun TC_36_worstCaseDashTypingUnder16MillisP95() {
        // UC-4: ввод `-` в пустой строке середины документа. Шаги `--` и `----`
        // открывают блок до конца документа — каждый такой шаг пересканирует
        // остаток (~500 строк), и именно эта серия обязана влезть в кадр.
        val lines = buildDocument()
        val middleBlank = lines.size / 2 - (lines.size / 2) % 9 + 1 // пустая строка группы
        check(lines[middleBlank].isEmpty()) { "стенд сместился: строка $middleBlank не пуста" }

        val highlighter = AdocIncrementalHighlighter()
        val samples = mutableListOf<Long>()
        repeat(WARMUP_CYCLES + WORST_CYCLES) { cycle ->
            highlighter.update(lines.joinToString("\n"))
            for (dashes in listOf("-", "--", "---", "----")) {
                val edited = lines.toMutableList().also { it[middleBlank] = dashes }.joinToString("\n")
                val startedAt = System.nanoTime()
                highlighter.update(edited)
                val took = System.nanoTime() - startedAt
                if (cycle >= WARMUP_CYCLES) samples += took
            }
        }

        val p95Ms = p95(samples).toMillis()
        report(
            "tc36-worst-case-dashes",
            listOf(
                "P95: %.3f мс".format(p95Ms),
                "медиана: %.3f мс".format(median(samples).toMillis()),
                "максимум: %.3f мс".format(samples.max().toMillis()),
                "замеров: ${samples.size} (шаги -, --, ---, ---- × $WORST_CYCLES циклов) после $WARMUP_CYCLES прогревочных циклов",
            ),
        )
        assertTrue(
            p95Ms < 16.0,
            "худший случай UC-4: P95 $p95Ms мс ≥ 16 мс на ${samples.size} шагах " +
                "(медиана ${median(samples).toMillis()} мс, максимум ${samples.max().toMillis()} мс)",
        )
    }

    private companion object {
        const val WARMUP = 5
        const val FULL_RUNS = 21
        const val EDIT_RUNS = 60 // × две правки (вставка и откат) = 120 замеров
        const val WARMUP_CYCLES = 3
        const val WORST_CYCLES = 25 // × четыре шага = 100 замеров
    }
}
