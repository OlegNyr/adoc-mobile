package io.github.olegnyr.adocmobile.preview

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import io.github.olegnyr.adocmobile.theme.AdocTheme
import java.io.ByteArrayInputStream

/**
 * Android-реализация шва превью: `AndroidView { WebView }` (`FR-18`).
 *
 * Всё, что вью умеет, — показать переданную строку и отдать локальные картинки
 * документа через перехват (`OQ-9`). JavaScript выключен: вывод конвертера
 * статичен, а включённый движок скриптов в компоненте, показывающем чужой
 * документ, — лишняя поверхность атаки.
 *
 * Доступ вью к файлам и провайдерам выключен явно (`allowFileAccess`,
 * `allowContentAccess`): документ пользователя — недоверенная разметка, и
 * `+<img src="content://…">+` в ней не должен читать ничего. Единственный путь
 * к байтам — перехват, и он отдаёт только то, что разрешил
 * [resolvePreviewImageRequest] и вернул [PreviewImageSource].
 *
 * Обновление сохраняет позицию прокрутки (`FR-23`, `SL-4`): страница заменяется
 * целиком, но позиция — состояние *превью*, а не страницы. Прокрутка снимается
 * перед загрузкой и восстанавливается по `postVisualStateCallback` — сигналу
 * «новая страница уже разложена и готова к отрисовке»; восстанавливать раньше
 * бессмысленно (высоты ещё нет), позже — видно прыжок. Выход за высоту новой
 * страницы безопасен: Chromium ограничивает `scrollTo` фактическим диапазоном.
 */
@Composable
actual fun AdocPreview(
    html: String,
    modifier: Modifier,
    imageSource: PreviewImageSource?,
    onDocumentLink: (String) -> Unit,
) {
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
                // Недоверенной разметке не положены ни file://, ни content://:
                // на API < 30 доступ к файлам у WebView включён по умолчанию.
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(ground)
                webViewClient = PreviewWebViewClient(diagramAwareImageSource(imageSource), onDocumentLink)
            }
        },
        update = { view ->
            // Источник и слушатель обновляются и на рекомпозиции без смены HTML:
            // документ тот же, а лямбды экрана могли пересоздаться.
            (view.webViewClient as? PreviewWebViewClient)?.let { client ->
                client.imageSource = imageSource
                client.onDocumentLink = onDocumentLink
            }

            // Загрузка только при смене содержимого: `update` вызывается на каждую
            // рекомпозицию, а перезагрузка страницы — заметная работа и мигание.
            if (view.tag != html) {
                val firstLoad = view.tag == null
                view.tag = html
                val scrollY = view.scrollY
                // Базовый адрес нужен, чтобы относительный `pic.png` вообще стал
                // запросом и дошёл до перехвата: у страницы, загруженной без
                // базы, относительным путям не от чего разрешаться.
                view.loadDataWithBaseURL(PREVIEW_BASE_URL, html, "text/html", "utf-8", null)
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

/**
 * Перехват ресурсов превью — платформенная половина `SL-6`.
 *
 * Решение «что это за запрос» принимает общий код ([resolvePreviewImageRequest]);
 * здесь остаётся ровно чтение байтов и упаковка ответа. Перехватывается *всё*:
 * запрос, который не является разрешённым локальным изображением, получает
 * пустой ответ, а не уходит дальше — `blockNetworkLoads` при этом остаётся
 * вторым рубежом, а не единственным.
 *
 * `shouldInterceptRequest` зовётся на рабочем потоке WebView, не на главном:
 * блокирующее чтение здесь допустимо и предусмотрено контрактом
 * [PreviewImageSource].
 */
private class PreviewWebViewClient(
    @Volatile var imageSource: PreviewImageSource?,
    @Volatile var onDocumentLink: (String) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
        val resolved = resolvePreviewImageRequest(request.url.toString())
        if (resolved !is PreviewImageRequest.Image) return blocked()
        val bytes = imageSource?.read(resolved.relativePath) ?: return blocked()
        return WebResourceResponse(resolved.mimeType, null, ByteArrayInputStream(bytes))
    }

    /**
     * Навигация по касанию ссылки — `FR-28`, решение `OQ-8`.
     *
     * Решение принимает [classifyPreviewLink] в общем коде; здесь — исполнение.
     * `true` означает «навигации не будет»: единственный случай `false` —
     * внутренний якорь, прокрутку по которому WebView делает сам.
     *
     * Во внешний запуск уходят только адреса, которые классификация признала
     * внешними (`http`/`https`/`mailto`); `intent://` и прочие схемы до
     * `startActivity` не доходят по построению. Отсутствие обработчика — не
     * падение: превью показывает документ, а не гарантирует наличие браузера.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        when (val action = classifyPreviewLink(request.url.toString())) {
            is PreviewLinkAction.ScrollInPreview -> false

            is PreviewLinkAction.OpenExternally -> {
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
                } catch (_: ActivityNotFoundException) {
                    // Обработчика нет (нет браузера или почтового клиента) —
                    // касание молча ни к чему не приводит, приложение живо (FR-9).
                }
                true
            }

            is PreviewLinkAction.DocumentLink -> {
                onDocumentLink(action.path)
                true
            }

            is PreviewLinkAction.Ignore -> true
        }

    /**
     * Пустой ответ вместо `null`: `null` означал бы «пусть WebView грузит сам»,
     * то есть решение ушло бы из-под резолвера к умолчаниям платформы.
     */
    private fun blocked(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}
