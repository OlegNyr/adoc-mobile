package io.github.olegnyr.adocmobile.preview

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.olegnyr.adocmobile.theme.AdocTheme

/**
 * Android-реализация шва превью: `AndroidView { WebView }` (`FR-18`).
 *
 * Всё, что вью умеет, — показать переданную строку. JavaScript выключен: вывод
 * конвертера статичен, а включённый движок скриптов в компоненте, показывающем
 * чужой документ, — лишняя поверхность атаки.
 *
 * Базовый URL пустой (`null`): относительные ссылки на файлы документа не должны
 * разрешаться до ответа на `OQ-9`, а поведение ссылок задаёт `SL-5` (`FR-28`).
 *
 * Обновление сохраняет позицию прокрутки (`FR-23`, `SL-4`): страница заменяется
 * целиком, но позиция — состояние *превью*, а не страницы. Прокрутка снимается
 * перед загрузкой и восстанавливается по `postVisualStateCallback` — сигналу
 * «новая страница уже разложена и готова к отрисовке»; восстанавливать раньше
 * бессмысленно (высоты ещё нет), позже — видно прыжок. Выход за высоту новой
 * страницы безопасен: Chromium ограничивает `scrollTo` фактическим диапазоном.
 */
@Composable
actual fun AdocPreview(html: String, modifier: Modifier) {
    // Фон роли ground у самой вью, а не только в CSS страницы (FR-20, TC-22):
    // WebView до конца загрузки первой страницы и между загрузками рисует
    // собственный фон, по умолчанию белый, — это и есть та самая вспышка.
    val ground = AdocTheme.colors.ground.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                // Рендер офлайн (FR-26): показывать превью незачем ходить в сеть.
                settings.blockNetworkLoads = true
                setBackgroundColor(ground)
            }
        },
        update = { view ->
            // Загрузка только при смене содержимого: `update` вызывается на каждую
            // рекомпозицию, а перезагрузка страницы — заметная работа и мигание.
            if (view.tag != html) {
                val firstLoad = view.tag == null
                view.tag = html
                val scrollY = view.scrollY
                view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                if (!firstLoad && scrollY > 0) {
                    // Восстановление именно на перерисовке (TC-21): при первом
                    // показе восстанавливать нечего, а нулевую позицию не нужно.
                    view.postVisualStateCallback(
                        0L,
                        object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                view.scrollTo(0, scrollY)
                            }
                        },
                    )
                }
            }
        },
    )
}
