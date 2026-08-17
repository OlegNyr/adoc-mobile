package io.github.olegnyr.adocmobile.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import io.github.olegnyr.adocmobile.highlight.AdocRange
import io.github.olegnyr.adocmobile.highlight.AdocSpan
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Редакторская поверхность с подсветкой — слайсы `SL-1a` и `SL-5` фичи
 * 001-syntax-highlighting: диапазоны сканера доезжают до экрана, стили ставятся
 * только для видимого окна, правка пересканирует только затронутый участок.
 *
 * Устройство продиктовано границами работ фичи и замерами `T-010`:
 *
 * * Сканирование идёт *по изменению текста*: `snapshotFlow` над `state.text`
 *   перезапускает проход, результат хранится в состоянии композиции. Внутри
 *   `transformOutput` прохода нет — он вызывается на каждую отрисовку.
 * * Проход инкрементальный (`FR-27`): [AdocEditorHighlighting] держит прежний
 *   проход и пересканирует от ближайшей чистой границы выше правки до первой
 *   сошедшейся строки. Во время активной композиции IME проход не запускается
 *   вовсе (`TC-39`) — завершение композиции даст новое значение потока.
 * * Проход выполняется вне главного потока; `collectLatest` бросает устаревший
 *   проход, когда текст успел измениться снова.
 * * Стили ставятся только для видимого окна (`FR-26`): окно считается в строках
 *   раскладки с запасом [WINDOW_MARGIN_LINES] в каждую сторону и обновляется
 *   лишь когда прокрутка изнашивает запас ближе [WINDOW_SLACK_LINES] строк —
 *   геометрия в `AdocViewportWindow`, перевод строк в смещения символов здесь,
 *   по `TextLayoutResult`. До первой раскладки окно неизвестно и ставится всё.
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
    val scrollState = rememberScrollState()

    val highlighting = remember(state) { AdocEditorHighlighting() }
    var spans by remember(state) { mutableStateOf(emptyList<AdocSpan>()) }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() to (state.composition != null) }
            .collectLatest { (source, composing) ->
                // Композиция IME активна — диапазоны не пересчитываются (TC-39);
                // её завершение меняет пару и даёт новое значение потока.
                // toString в snapshotFlow снимает неизменяемую копию до ухода с
                // главного потока: сканер не читает буфер, который пользователь
                // продолжает менять.
                spans = withContext(Dispatchers.Default) { highlighting.onText(source, composing) }
            }
    }

    // Раскладка нужна, чтобы перевести прокрутку в строки, а строки — в смещения.
    // Провайдер приходит из onTextLayout; поколение будит поток окна, потому что
    // сама ссылка на провайдер может не смениться между раскладками.
    var layoutProvider by remember { mutableStateOf<(() -> TextLayoutResult?)?>(null) }
    var layoutGeneration by remember { mutableIntStateOf(0) }
    var lineWindow by remember(state) { mutableStateOf<LineWindow?>(null) }
    var charWindow by remember(state) { mutableStateOf<AdocRange?>(null) }
    LaunchedEffect(state) {
        snapshotFlow {
            WindowInputs(scrollState.value, scrollState.viewportSize, state.text.length, layoutGeneration)
        }.collect { inputs ->
            val layout = layoutProvider?.invoke() ?: return@collect
            if (layout.lineCount == 0) return@collect
            val top = inputs.scrollTop.toFloat()
            val firstVisible = layout.getLineForVerticalPosition(top)
            val lastVisible = layout.getLineForVerticalPosition(top + inputs.viewportHeight)
            val refreshed = refreshedWindow(lineWindow, firstVisible, lastVisible, layout.lineCount - 1)
            lineWindow = refreshed
            // Смещения пересчитываются и при неизменном окне строк: правка выше
            // окна сдвигает символы. Одинаковое значение состояние не будит.
            charWindow = AdocRange(
                start = layout.getLineStart(refreshed.firstLine),
                endExclusive = layout.getLineEnd(refreshed.lastLine),
            )
        }
    }

    // Экземпляр пересоздаётся на новый список диапазонов или новое окно:
    // параметр BasicTextField меняется, и поле пересчитывает отображение.
    // Сам объект — обёртка над готовым списком, создание ничего не сканирует.
    val transformation = remember(styles, spans, charWindow) {
        AdocHighlightTransformation(styles, spans, charWindow)
    }

    BasicTextField(
        state = state,
        modifier = modifier,
        textStyle = adocTextStyle(AdocTypography.editorCode).copy(color = colors.textSecondary),
        // Каретка — акцентная, по описанию дизайна экрана редактора.
        cursorBrush = SolidColor(colors.accent),
        outputTransformation = transformation,
        onTextLayout = { getResult ->
            layoutProvider = getResult
            layoutGeneration++
        },
        lineLimits = TextFieldLineLimits.MultiLine(),
        scrollState = scrollState,
    )
}

/** Входы потока окна: прокрутка, высота экрана, длина текста, поколение раскладки. */
private data class WindowInputs(
    val scrollTop: Int,
    val viewportHeight: Int,
    val textLength: Int,
    val layoutGeneration: Int,
)
