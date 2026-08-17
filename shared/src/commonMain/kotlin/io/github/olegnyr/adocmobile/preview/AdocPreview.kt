package io.github.olegnyr.adocmobile.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Второй и последний платформенный шов фичи: показ готовой страницы.
 *
 * `FR-18`: превью — платформенный WebView, получающий готовую строку HTML. Ни
 * разбора, ни рендера, ни обращения к движку внутри нет; всё, что можно было
 * решить в общем коде, решено в [previewDocument].
 *
 * Рендер через `WebView.evaluateJavascript` запрещён видением и границами работ:
 * рендер не должен зависеть от жизненного цикла вью.
 *
 * @param html целая страница, а не фрагмент — результат [previewDocument].
 * @param imageSource откуда читать локальные изображения документа (`OQ-9`,
 *   `SL-6`). `null` — изображений нет: каждый их запрос отклоняется, и на месте
 *   картинки остаётся пустой прямоугольник. Какой именно путь запрашивается,
 *   решает не платформа, а [resolvePreviewImageRequest]; реализация источника —
 *   у владельца файлового доступа (поток 4).
 */
@Composable
expect fun AdocPreview(
    html: String,
    modifier: Modifier = Modifier,
    imageSource: PreviewImageSource? = null,
)
