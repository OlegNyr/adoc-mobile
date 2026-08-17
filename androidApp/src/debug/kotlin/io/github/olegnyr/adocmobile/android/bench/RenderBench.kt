package io.github.olegnyr.adocmobile.android.bench

import android.content.res.AssetManager
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Измерительная часть стенда `T-013`: сколько стоит рендер AsciiDoc → HTML на
 * устройстве.
 *
 * Отделена от экрана намеренно — экран только показывает то, что здесь
 * посчитано, и в измерение ничем не вмешивается.
 *
 * Замеряется то, что различается по природе:
 *
 * * *инициализация* — чтение бандла, создание рантайма, выполнение ~865 КБ
 *   скрипта. Разовая и дорогая: движок задуман синглтоном
 *   (doc/asciidoc-editor-vision.adoc, раздел «Рендерер»);
 * * *каждый вызов render* — тремя числами: передача исходника через границу,
 *   сама конвертация, возврат HTML обратно. Несколько прогонов подряд, чтобы
 *   был виден прогрев.
 *
 * Исходник пересекает границу в JS только строгой JSON-сериализацией — это
 * требование архитектуры, а не удобство стенда, поэтому его цена меряется
 * отдельной строкой, а не прячется внутри конвертации.
 */

/** Один прогон конвертации. */
data class RenderPass(
    val index: Int,
    val marshalMs: Double,
    val convertMs: Double,
    val fetchMs: Double,
    val htmlChars: Int,
) {
    val totalMs: Double get() = marshalMs + convertMs + fetchMs
}

/** Накопленный результат замера. Стенд показывает снимки по мере заполнения. */
data class RenderBenchReport(
    val stage: String,
    val lines: Int = 0,
    val runs: Int = 0,
    val sourceChars: Int = 0,
    val bundleChars: Int = 0,
    val assetReadMs: Double = 0.0,
    val engineCreateMs: Double = 0.0,
    val bundleEvalMs: Double = 0.0,
    val quickJsVersion: String = "",
    val coreVersion: String = "",
    val passes: List<RenderPass> = emptyList(),
    val mallocSize: Long = 0,
    val memoryUsedSize: Long = 0,
    val error: String? = null,
) {
    val initTotalMs: Double get() = assetReadMs + engineCreateMs + bundleEvalMs
}

/** Путь к пропатченному бандлу внутри `assets`. */
const val ASCIIDOCTOR_ASSET = "asciidoctor/asciidoctor.js"

/** Размер документа по умолчанию — верх диапазона из критериев `T-013`. */
const val DEFAULT_RENDER_LINES = 3000

/** Прогонов конвертации по умолчанию: одного мало, чтобы отличить прогрев от нормы. */
const val DEFAULT_RENDER_RUNS = 5

/**
 * Размер стека потока движка, КБ. Разбор AsciiDoc рекурсивен, а `maxStackSize`
 * в QuickJS — только проверка против настоящего стека потока: выставить лимит
 * больше него значит поменять понятную ошибку на SIGSEGV. Поэтому поток
 * создаётся свой, с известным стеком, и лимит движка ставится ниже него.
 */
const val DEFAULT_RENDER_STACK_KB = 8192

/**
 * Прогоняет замер и отдаёт снимки по мере готовности, чтобы экран заполнялся
 * постепенно, а не мигал пустотой полминуты.
 */
fun renderBench(
    assets: AssetManager,
    lines: Int = DEFAULT_RENDER_LINES,
    runs: Int = DEFAULT_RENDER_RUNS,
    stackKb: Int = DEFAULT_RENDER_STACK_KB,
    bundlePath: String = ASCIIDOCTOR_ASSET,
): Flow<RenderBenchReport> = flow {
    var report = RenderBenchReport(stage = "готовим документ", lines = lines, runs = runs)
    emit(report)

    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "adoc-render-bench", stackKb.toLong() * 1024)
    }
    val engineDispatcher = executor.asCoroutineDispatcher()

    try {
        val source = generateDocument(lines)
        report = report.copy(sourceChars = source.length, stage = "читаем бандл")
        emit(report)

        val (bundle, assetReadMs) = measured {
            assets.open(bundlePath).use { it.readBytes().decodeToString() }
        }
        report = report.copy(
            bundleChars = bundle.length,
            assetReadMs = assetReadMs,
            stage = "поднимаем движок",
        )
        emit(report)

        val (quickJs, engineCreateMs) = measured { QuickJs.create(engineDispatcher) }
        try {
            // Запас в 1 МБ до края настоящего стека: движок должен упереться в
            // свой лимит и бросить исключение раньше, чем поток снесёт стек.
            quickJs.maxStackSize = maxOf(stackKb - 1024, 256).toLong() * 1024
            report = report.copy(
                engineCreateMs = engineCreateMs,
                quickJsVersion = quickJs.version,
                stage = "выполняем бандл",
            )
            emit(report)

            // Скрипт, а не модуль: финальный `export` в бандле заменён на
            // присваивание globalThis.Asciidoctor (см. tools/patch-asciidoctor.py).
            val (_, bundleEvalMs) = measured {
                withContext(engineDispatcher) {
                    quickJs.evaluate<Any?>(bundle, filename = "asciidoctor.js", asModule = false)
                }
            }
            val coreVersion = withContext(engineDispatcher) {
                quickJs.evaluate<String>("String(globalThis.Asciidoctor.getCoreVersion())")
            }
            report = report.copy(
                bundleEvalMs = bundleEvalMs,
                coreVersion = coreVersion,
                stage = "рендерим",
            )
            emit(report)

            val json = JSONObject.quote(source)
            repeat(runs) { index ->
                val (_, marshalMs) = measured {
                    withContext(engineDispatcher) {
                        quickJs.evaluate<String>(
                            "globalThis.__source = $json; String(globalThis.__source.length)",
                            filename = "source.js",
                        )
                    }
                }
                // `convert` в Asciidoctor.js 4 асинхронный, и промис приходится
                // разворачивать вручную: обычный скрипт вернул бы сам объект
                // Promise, а его строковое представление ничем не выдаёт ошибку
                // — стенд бы бодро отрапортовал «html: 32 символа». Модуль с
                // top-level await ждёт результат, но значения не возвращает
                // (модуль — не выражение), поэтому результат кладётся в
                // globalThis и забирается вторым, дешёвым вызовом.
                val (_, convertMs) = measured {
                    withContext(engineDispatcher) {
                        quickJs.evaluate<Any?>(
                            "globalThis.__html = await globalThis.Asciidoctor" +
                                ".convert(globalThis.__source, { standalone: false });",
                            filename = "convert.mjs",
                            asModule = true,
                        )
                    }
                }
                val (html, fetchMs) = measured {
                    withContext(engineDispatcher) {
                        quickJs.evaluate<String>("globalThis.__html", filename = "fetch.js")
                    }
                }
                report = report.copy(
                    passes = report.passes +
                        RenderPass(index + 1, marshalMs, convertMs, fetchMs, html.length),
                )
                emit(report)
            }

            val usage = quickJs.memoryUsage
            report = report.copy(
                stage = "готово",
                mallocSize = usage.mallocSize,
                memoryUsedSize = usage.memoryUsedSize,
            )
            emit(report)
        } finally {
            quickJs.close()
        }
    } catch (e: Throwable) {
        // Стенд обязан показать причину: без неё «ничего не произошло» на экране
        // неотличимо от «упало на разборе бандла».
        emit(report.copy(stage = "ошибка", error = describe(e)))
    } finally {
        executor.shutdown()
    }
}

/** Значение и время его получения в миллисекундах. */
private inline fun <T> measured(block: () -> T): Pair<T, Double> {
    val start = System.nanoTime()
    val value = block()
    return value to (System.nanoTime() - start) / 1_000_000.0
}

private fun describe(e: Throwable): String = buildString {
    append(e::class.java.name).append(": ").append(e.message)
    e.stackTrace.take(6).forEach { append('\n').append("  at ").append(it) }
    e.cause?.let { append("\nПричина: ").append(it::class.java.name).append(": ").append(it.message) }
}
