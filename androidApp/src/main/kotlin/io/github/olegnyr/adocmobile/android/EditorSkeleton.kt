package io.github.olegnyr.adocmobile.android

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.ui.AdocEditor

/**
 * Наблюдаемая часть слайса `SL-1a` фичи 001: подсветка, доехавшая до экрана.
 *
 * Та же роль, что у [RenderSkeleton] для фичи 003: экрана редактора ещё нет
 * (`T-051`, поток 5), поэтому сквозной путь «сканер → OutputTransformation →
 * пиксели» замыкается временной поверхностью. Документ предзаполнен
 * конструкциями закрытых слайсов `SL-1`/`SL-2` — блоки, заголовки, атрибуты,
 * списки, admonition, комментарии, — и остаётся редактируемым: подсветка
 * обязана жить под вводом, а не только на статичном тексте.
 */
@Composable
fun EditorSkeleton(modifier: Modifier = Modifier) {
    AdocTheme {
        Surface(
            modifier = modifier,
            color = AdocTheme.colors.ground,
            contentColor = AdocTheme.colors.textSecondary,
        ) {
            val state = rememberTextFieldState(SAMPLE_DOCUMENT)
            AdocEditor(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Документ подобран по ролям [io.github.olegnyr.adocmobile.highlight.AdocStyle]
 * закрытых слайсов: на одном экране видны заголовок, атрибут, комментарий,
 * admonition, метаданные блока, listing с callout, списки и разрыв. Кириллица —
 * по той же причине, что в [RenderSkeleton]: на ней ловятся огрехи, которых нет
 * на латинице.
 */
private val SAMPLE_DOCUMENT = """
    = Подсветка работает
    :status: слайс SL-1a
    // сканер — SL-1/SL-2, экран — этот слайс

    Обычный абзац остаётся обычным текстом.

    NOTE: Абзацный admonition размечен меткой.

    .Пример кода
    [source,kotlin]
    ----
    fun scan(source: String): AdocScan // дословный блок не разбирается
    include::common.adoc[]
    val spans = scanner.scan(source) <1>
    ----

    * пункт списка
    * ещё пункт

    Термин:: описание термина

    '''

    == Раздел два
""".trimIndent()
