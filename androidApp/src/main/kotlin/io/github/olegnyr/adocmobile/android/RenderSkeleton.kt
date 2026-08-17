package io.github.olegnyr.adocmobile.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import io.github.olegnyr.adocmobile.preview.AdocPreview
import io.github.olegnyr.adocmobile.preview.previewDocument
import io.github.olegnyr.adocmobile.render.adocRenderer

/**
 * Наблюдаемая часть слайса `SL-1` фичи 003: документ, доехавший до пикселей.
 *
 * Экрана редактора ещё нет — вкладки `РЕДАКТОР` / `ПРЕВЬЮ` делает поток 5
 * (`T-051`), — поэтому сквозной путь нужно чем-то замкнуть. Этот composable и
 * есть то самое замыкание: он берёт постоянный документ, прогоняет его через
 * контракт `render`, заворачивает результат в оболочку и отдаёт превью.
 *
 * Живёт в `androidApp`, а не в общем коде, намеренно: это временная поверхность,
 * которую `T-051` заменит настоящим экраном, и общему коду про неё знать нечего.
 * Дебаунсов, отмены и обработки отказа здесь нет — это `SL-4` и `SL-5`;
 * исключение движка сознательно не гасится, чтобы отказ был виден, а не съеден.
 */
@Composable
fun RenderSkeleton(modifier: Modifier = Modifier) {
    val html by produceState(previewDocument("")) {
        value = previewDocument(adocRenderer().render(SKELETON_DOCUMENT))
    }
    AdocPreview(html = html, modifier = modifier)
}

/**
 * Документ подобран по перечню `FR-21`: заголовок (он же проверка `showtitle` из
 * `FR-7`), абзац, admonition, блок кода с языком, таблица и список. Кириллица
 * везде — на ней в разведке ловились и кодировка, и якоря заголовков.
 *
 * Это не корпус сверки: корпус — `SL-2`, у него свои документы в `testdata/`.
 */
private val SKELETON_DOCUMENT = """
    = Превью работает
    :attr: значение атрибута

    Первый абзац со *жирным* и `моноширинным` текстом, и с {attr}.

    NOTE: Admonition рендерится таблицей с ячейкой значка.

    [source,kotlin]
    ----
    fun render(source: String): String = "HTML"
    ----

    .Таблица
    |===
    | Колонка | Значение

    | строки
    | 1000
    | рендер
    | 142 мс
    |===

    * первый пункт
    * второй пункт
""".trimIndent()
