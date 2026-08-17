package io.github.olegnyr.adocmobile.android.bench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Точка входа стенда замеров. Только в debug-сборке: в релиз не попадает.
 *
 * Запуск с параметрами:
 * ```
 * adb shell am start -n io.github.olegnyr.adocmobile/.android.bench.BenchActivity --ei lines 3000 --ei styles 3000
 * ```
 * `lines` — размер документа, `styles` — сколько диапазонов ставит заглушка
 * подсветки (0 даёт базовую линию «поле без подсветки»), `from` — с какой
 * строки их ставить: большое значение уводит все диапазоны за пределы экрана,
 * не меняя их числа.
 */
class BenchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        val lines = intent.getIntExtra("lines", DEFAULT_LINES)
        val styles = intent.getIntExtra("styles", lines)
        val fromLine = intent.getIntExtra("from", 0)

        setContent {
            BenchStand(lines = lines, styles = styles, fromLine = fromLine)
        }
    }

    private companion object {
        const val DEFAULT_LINES = 3000
    }
}
