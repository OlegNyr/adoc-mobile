package io.github.olegnyr.adocmobile.android.bench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Точка входа стенда замеров рендера (`T-013`). Только в debug-сборке: в релиз
 * не попадает ни активность, ни движок, ни бандл.
 *
 * Запуск с параметрами:
 * ```
 * adb shell am start -n io.github.olegnyr.adocmobile/io.github.olegnyr.adocmobile.android.bench.RenderBenchActivity \
 *   --ei lines 3000 --ei runs 5
 * ```
 *
 * * `lines` — размер документа в строках;
 * * `runs` — сколько раз подряд конвертировать; первый прогон всегда дороже,
 *   отдельные числа по прогонам показывают прогрев;
 * * `stack` — размер стека потока движка в КБ, на случай если разбор упрётся в
 *   рекурсию;
 * * `bundle` — путь к бандлу внутри `assets`, если понадобится сравнить версии.
 */
class RenderBenchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val lines = intent.getIntExtra("lines", DEFAULT_RENDER_LINES)
        val runs = intent.getIntExtra("runs", DEFAULT_RENDER_RUNS)
        val stackKb = intent.getIntExtra("stack", DEFAULT_RENDER_STACK_KB)
        val bundlePath = intent.getStringExtra("bundle") ?: ASCIIDOCTOR_ASSET

        setContent {
            RenderBenchStand(
                assets = assets,
                lines = lines,
                runs = runs,
                stackKb = stackKb,
                bundlePath = bundlePath,
            )
        }
    }
}
