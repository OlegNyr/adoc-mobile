package io.github.olegnyr.adocmobile.android.bench

import android.content.res.AssetManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/** Тег для инструментальных проверок и для поиска экрана в дампе иерархии. */
const val RENDER_BENCH_TAG = "render-bench"

/**
 * Экран стенда `T-013`: показывает то, что посчитал [renderBench].
 *
 * Экран сознательно бедный — это измерительный прибор, а не часть продукта.
 * Единственное требование к нему: числа должны читаться с фотографии экрана,
 * поэтому всё моноширинным и в одну колонку.
 */
@Composable
fun RenderBenchStand(
    assets: AssetManager,
    lines: Int,
    runs: Int,
    stackKb: Int,
    bundlePath: String,
) {
    AdocTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AdocTheme.colors.ground,
            contentColor = AdocTheme.colors.textSecondary,
        ) {
            val report: RenderBenchReport by rememberRenderBench(assets, lines, runs, stackKb, bundlePath)

            Text(
                text = formatReport(report),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
                    .testTag(RENDER_BENCH_TAG),
                style = adocTextStyle(AdocTypography.editorCode)
                    .copy(color = AdocTheme.colors.textSecondary),
            )
        }
    }
}

@Composable
private fun rememberRenderBench(
    assets: AssetManager,
    lines: Int,
    runs: Int,
    stackKb: Int,
    bundlePath: String,
): State<RenderBenchReport> = produceState(
    initialValue = RenderBenchReport(stage = "старт", lines = lines, runs = runs),
    lines, runs, stackKb, bundlePath,
) {
    // Подготовка уходит с главного потока целиком: чтение 865 КБ ассета и
    // сборка документа на нём дали бы и подтормаживание, и грязные числа.
    // Сам движок всё равно живёт на своём потоке, заведённом внутри renderBench.
    renderBench(assets, lines, runs, stackKb, bundlePath)
        .flowOn(Dispatchers.Default)
        .collect { value = it }
}

/**
 * Отчёт одним текстом. Формат выбран так, чтобы его можно было целиком
 * перенести в документ результатов, не переписывая.
 */
internal fun formatReport(report: RenderBenchReport): String = buildString {
    appendLine("СТЕНД РЕНДЕРА · T-013")
    appendLine("состояние: ${report.stage}")
    appendLine()
    appendLine("документ: ${report.lines} строк, ${report.sourceChars} символов")
    appendLine("бандл: ${report.bundleChars} символов")
    appendLine("QuickJS ${report.quickJsVersion}   Asciidoctor core ${report.coreVersion}")
    appendLine()
    appendLine("ИНИЦИАЛИЗАЦИЯ (разовая)")
    appendLine("  чтение бандла   ${ms(report.assetReadMs)}")
    appendLine("  создание движка ${ms(report.engineCreateMs)}")
    appendLine("  выполнение JS   ${ms(report.bundleEvalMs)}")
    appendLine("  итого           ${ms(report.initTotalMs)}")
    appendLine()
    appendLine("RENDER (json → convert → возврат HTML)")
    if (report.passes.isEmpty()) {
        appendLine("  ещё не считали")
    } else {
        report.passes.forEach { pass ->
            appendLine(
                "  #${pass.index}  json ${ms(pass.marshalMs)}" +
                    "  convert ${ms(pass.convertMs)}" +
                    "  возврат ${ms(pass.fetchMs)}",
            )
            appendLine("      итого ${ms(pass.totalMs)}  html ${pass.htmlChars} символов")
        }
        val totals = report.passes.map { it.totalMs }
        appendLine()
        appendLine("  первый прогон   ${ms(totals.first())}")
        val warm = totals.drop(1).ifEmpty { totals }
        if (totals.size > 1) {
            appendLine("  прогретые       мин ${ms(warm.min())}  медиана ${ms(median(warm))}  макс ${ms(warm.max())}")
        }
        appendLine("  порог дебаунса 300 мс: ${verdict(median(warm))}")
    }
    if (report.mallocSize > 0) {
        appendLine()
        appendLine("ПАМЯТЬ QuickJS после прогонов")
        appendLine("  malloc ${kb(report.mallocSize)}   используется ${kb(report.memoryUsedSize)}")
    }
    report.error?.let {
        appendLine()
        appendLine("ОШИБКА")
        appendLine(it)
    }
}

private fun verdict(medianMs: Double): String =
    if (medianMs <= 300.0) "укладываемся" else "НЕ укладываемся"

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

private fun ms(value: Double): String = String.format(Locale.ROOT, "%,.1f мс", value)

private fun kb(value: Long): String = String.format(Locale.ROOT, "%,d КБ", value / 1024)
