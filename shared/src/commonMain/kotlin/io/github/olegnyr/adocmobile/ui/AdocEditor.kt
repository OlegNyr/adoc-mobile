package io.github.olegnyr.adocmobile.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import io.github.olegnyr.adocmobile.highlight.AdocBlockScanner
import io.github.olegnyr.adocmobile.highlight.AdocSpan
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Редакторская поверхность с подсветкой — слайс `SL-1a` фичи
 * 001-syntax-highlighting: диапазоны сканера доезжают до экрана.
 *
 * Устройство продиктовано границами работ фичи и замерами `T-010`:
 *
 * * Сканирование идёт *по изменению текста*: `snapshotFlow` над `state.text`
 *   перезапускает проход, результат хранится в состоянии композиции. Внутри
 *   `transformOutput` прохода нет — он вызывается на каждую отрисовку.
 * * Проход выполняется вне главного потока: полный проход стоит до 100 мс
 *   (НФТ фичи), и в кадр его класть нельзя. `collectLatest` бросает устаревший
 *   проход, когда текст успел измениться снова.
 * * Пока новый список не приехал, кадр рисуется с прежним — устаревшие
 *   диапазоны за концом буфера обрезает трансформация.
 *
 * Прямой полный проход и подсветка всего документа — сознательный недолёт
 * слайса: видимое окно (`FR-26`) и инкрементальность (`FR-27`) приходят в
 * `SL-5`, здесь сквозной путь.
 *
 * Поле — `BasicTextField` с `TextFieldState` и `OutputTransformation`, как
 * требует архитектура (видение, «Editor»); `value`/`onValueChange` с
 * `VisualTransformation` не используются намеренно.
 */
@Composable
fun AdocEditor(
    state: TextFieldState,
    modifier: Modifier = Modifier,
) {
    val colors = AdocTheme.colors
    val styles = remember(colors) { AdocHighlightStyles(colors) }

    var spans by remember(state) { mutableStateOf(emptyList<AdocSpan>()) }
    LaunchedEffect(state) {
        snapshotFlow { state.text }.collectLatest { text ->
            // toString снимает неизменяемую копию до ухода с главного потока:
            // сканер не должен читать буфер, который пользователь продолжает менять.
            val source = text.toString()
            spans = withContext(Dispatchers.Default) { AdocBlockScanner.scan(source).spans }
        }
    }

    // Экземпляр пересоздаётся на каждый новый список диапазонов: параметр
    // BasicTextField меняется, и поле пересчитывает отображение. Сам объект —
    // обёртка над готовым списком, создание ничего не сканирует.
    val transformation = remember(styles, spans) { AdocHighlightTransformation(styles, spans) }

    BasicTextField(
        state = state,
        modifier = modifier,
        textStyle = adocTextStyle(AdocTypography.editorCode).copy(color = colors.textSecondary),
        // Каретка — акцентная, по описанию дизайна экрана редактора.
        cursorBrush = SolidColor(colors.accent),
        outputTransformation = transformation,
        lineLimits = TextFieldLineLimits.MultiLine(),
        scrollState = rememberScrollState(),
    )
}
