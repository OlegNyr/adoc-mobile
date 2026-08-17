@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.olegnyr.adocmobile.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentFileAccess
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.preview.AdocPreview
import io.github.olegnyr.adocmobile.preview.PreviewFailure
import io.github.olegnyr.adocmobile.preview.PreviewStatus
import io.github.olegnyr.adocmobile.render.adocRenderer
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock
import io.github.olegnyr.adocmobile.ui.AdocEditor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Вкладки экрана редактора — раскладка 1a (ADR-003). */
enum class EditorTab { Editor, Preview }

/**
 * Экран редактора по макету «02» — слайс `SL-1` фичи 005-editor-screen.
 *
 * App bar с именем файла и состоянием документа, вкладки `РЕДАКТОР` / `ПРЕВЬЮ`,
 * полотно [AdocEditor], пустое состояние с кнопкой открытия (`OQ-1`).
 * Вкладка превью в этом слайсе — заглушка: живой пайплайн подключает `SL-2`.
 *
 * Экран целиком обёрнут в один [AdocTheme] (`FR-15`) и живёт в `commonMain`
 * (`NFR-2`): платформе остаётся хостинг и системный диалог выбора файла.
 *
 * Единственный `TextFieldState` создаётся здесь через `rememberSaveable` с его
 * `Saver` (`FR-6`, `FR-7`): текст, каретка и история отмены переживают поворот
 * и выгрузку процесса, и этим же экземпляром пользуются подсветка, undo и
 * автосохранение.
 *
 * @param requestDocument платформенный диалог выбора документа: вернуть источник
 * с удержанным правом или `null`, если пользователь передумал. Сейчас это диалог
 * файла; решение владельца — открывать папку (`OQ-1`), и когда слайс tree-доступа
 * фичи 004 приедет, поменяется ровно эта точка.
 * @param foreground приложение на переднем плане. Сигнал подаёт платформенный
 * хостинг (у Compose Multiplatform общего lifecycle-примитива в зависимостях
 * проекта нет): уход в фон гасит видимость превью, и движок молчит (`OQ-5`
 * фичи 003).
 */
@Composable
fun EditorScreen(
    access: DocumentFileAccess,
    requestDocument: suspend () -> DocumentSource?,
    modifier: Modifier = Modifier,
    foreground: Boolean = true,
) {
    AdocTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = AdocTheme.colors.ground,
            contentColor = AdocTheme.colors.textPrimary,
        ) {
            val textFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() }
            val editor = remember(textFieldState) { DocumentEditor(textFieldState) }
            val scope = rememberCoroutineScope()
            val model = remember(editor) {
                EditorScreenModel(
                    editor = editor,
                    access = access,
                    // Движок-синглтон процесса (FR-3 фичи 003): пайплайну
                    // достаётся контракт, а не платформа.
                    renderer = adocRenderer(),
                    scope = scope,
                    clock = { Clock.System.now().toEpochMilliseconds() },
                )
            }

            // Какой источник уже загружен в поле: после поворота или выгрузки
            // процесса rememberSaveable восстановит и текст, и этот id — start
            // не станет перетирать восстановленное поле чтением с диска (FR-7).
            var fieldSourceId by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(model) { model.start(fieldSourceId) }

            val document = model.document
            LaunchedEffect(document) {
                if (document is EditorDocument.Open) {
                    fieldSourceId = document.runner.document.source.id
                }
            }

            // Каждое изменение текста — в модель (FR-10). Подписка через
            // snapshotFlow, не в кадре композиции (NFR-1).
            LaunchedEffect(model) {
                snapshotFlow { textFieldState.text }.collect { model.textEdited(it.toString()) }
            }

            var selectedTabName by rememberSaveable { mutableStateOf(EditorTab.Editor.name) }
            val selectedTab = EditorTab.valueOf(selectedTabName)
            val focusManager = LocalFocusManager.current

            // Честный сигнал видимости превью (FR-13): вкладка выбрана, документ
            // открыт и приложение на переднем плане — всё три сразу. Модель
            // идемпотентна, поэтому рекомпозиция с тем же значением безвредна.
            LaunchedEffect(document, selectedTab, foreground) {
                model.previewVisibilityChanged(
                    visible = document is EditorDocument.Open &&
                        selectedTab == EditorTab.Preview &&
                        foreground,
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // Обе хром-панели на одном фоне chrome; отступ статус-бара
                // внутри фона, чтобы панель уходила под него (edge-to-edge).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AdocTheme.colors.chrome)
                        .statusBarsPadding(),
                ) {
                    EditorAppBar(document)
                    ChromeDivider()
                    EditorTabs(
                        selected = selectedTab,
                        onSelect = { tab ->
                            selectedTabName = tab.name
                            // На превью клавиатуре делать нечего; каретка при
                            // этом остаётся в TextFieldState (FR-3).
                            if (tab == EditorTab.Preview) focusManager.clearFocus()
                        },
                    )
                    ChromeDivider()
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (document) {
                        is EditorDocument.Open -> {
                            // Полотно остаётся в композиции и на вкладке превью:
                            // так каретка и прокрутка не сбрасываются (FR-3,
                            // якорь «не пересоздавать полотно» из спеки).
                            AdocEditor(
                                state = textFieldState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .navigationBarsPadding()
                                    // Каретка не уходит под клавиатуру (NFR-7).
                                    .imePadding()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                            // Панель превью не покидает композицию при уходе на
                            // вкладку редактора, а паркуется за краем экрана:
                            // пересоздание уничтожило бы WebView вместе с его
                            // прокруткой, и возврат на превью начинался бы с
                            // верха документа (FR-4). Сохранение прокрутки
                            // внутри AdocPreview работает только на живой вью.
                            PreviewPane(
                                preview = document.preview,
                                onRetry = model::previewRetryRequested,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .parkedOffscreenWhen(selectedTab != EditorTab.Preview),
                            )
                        }

                        EditorDocument.None -> EmptyState(
                            message = null,
                            onOpen = { scope.launch { requestDocument()?.let(model::open) } },
                        )

                        is EditorDocument.OpenFailed -> EmptyState(
                            message = document.message,
                            onOpen = { scope.launch { requestDocument()?.let(model::open) } },
                        )
                    }
                }
            }
        }
    }
}

/**
 * App bar: имя файла и метка состояния (`FR-1`), высота 52 по макету «02».
 *
 * Правая часть (меню документа) придёт слайсом `SL-4` по решению `OQ-4`;
 * стрелка «назад» из макета не рисуется — экрана репозитория в MVP нет.
 */
@Composable
private fun EditorAppBar(document: EditorDocument) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (document is EditorDocument.Open) {
            val runner = document.runner
            // Метка рекомпозируется по смене isModified, а не на каждый
            // символ (NFR-1): подписка на производный признак, не на документ.
            val modified by remember(runner) {
                runner.documents.map { it.isModified }.distinctUntilChanged()
            }.collectAsState(initial = runner.document.isModified)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = runner.document.source.displayName,
                    style = adocTextStyle(AdocTypography.screenTitle),
                    color = AdocTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (modified) {
                    Text(
                        text = EDITOR_MODIFIED_LABEL,
                        style = adocTextStyle(AdocTypography.metadata),
                        color = AdocTheme.colors.textFaint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Полоса вкладок высотой 44; активная помечена акцентной линией снизу и цветом (`FR-2`, `NFR-9`). */
@Composable
private fun EditorTabs(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(44.dp)) {
        TabCell(
            label = "РЕДАКТОР",
            selected = selected == EditorTab.Editor,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(EditorTab.Editor) },
        )
        TabCell(
            label = "ПРЕВЬЮ",
            selected = selected == EditorTab.Preview,
            modifier = Modifier.weight(1f),
            onClick = { onSelect(EditorTab.Preview) },
        )
    }
}

@Composable
private fun TabCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = if (selected) AdocTheme.colors.accentText else AdocTheme.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        if (selected) {
            // Линия из макета: 2 px акцента по нижней кромке активной вкладки.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(AdocTheme.colors.accent),
            )
        }
    }
}

/** Разделитель хром-панелей: линия в 1 px, как во всём интерфейсе. */
@Composable
private fun ChromeDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AdocTheme.colors.borderChrome),
    )
}

/**
 * Пустое состояние — решение `OQ-1`: чертёжный блок в языке дизайна с кнопкой
 * открытия; макета у состояния нет, значения — из готовых токенов и компонентов.
 *
 * @param message текст отказа открытия (`FR-9`) или `null`, когда файла просто нет
 */
@Composable
private fun EmptyState(message: String?, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "ФАЙЛ НЕ ОТКРЫТ",
                style = adocTextStyle(AdocTypography.sectionLabel),
                color = AdocTheme.colors.textFaint,
            )
            Text(
                text = message ?: "Откройте документ AsciiDoc, чтобы начать работу.",
                style = adocTextStyle(AdocTypography.body),
                color = AdocTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            PrimaryButton(label = "ОТКРЫТЬ ФАЙЛ", onClick = onOpen)
        }
    }
}

/**
 * Первичная кнопка по описанию дизайна: заливка акцентом, высота 46,
 * Barlow Condensed прописными; на экране такая одна.
 */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .background(AdocTheme.colors.accent)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.buttonLabel),
            color = AdocTheme.colors.onAccent,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Вкладка превью — живой пайплайн (`FR-13`, `FR-14`).
 *
 * Непрозрачный фон роли `ground` перекрывает полотно редактора, которое
 * осталось в композиции ради каретки и прокрутки (`FR-3`); фон превью и фон
 * экрана — одна роль, вспышки нет (`FR-15`). Пустой обработчик касаний не
 * пускает их сквозь свободные места в поле ввода под превью.
 *
 * Состояния — по [PreviewStatus] (решение `OQ-1` фичи 003): индикатор только
 * первому рендеру; дальше на время перерендера молча держится предыдущий HTML.
 * Отказ рендера — плашка с «Повторить» поверх последнего удачного HTML
 * (решение `OQ-2` фичи 003; язык плашки един с плашкой отказа записи, `OQ-3`).
 */
@Composable
private fun PreviewPane(
    preview: PreviewPipeline,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Уведомление о межфайловой ссылке (FR-28 фичи 003): документ по xref не
    // открыт. Живёт до касания, до следующей ссылки или до смены документа.
    var documentLinkNotice by remember(preview) { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .background(AdocTheme.colors.ground)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        val html = preview.html
        if (html != null) {
            AdocPreview(
                html = html,
                modifier = Modifier.fillMaxSize(),
                onDocumentLink = { path -> documentLinkNotice = "Документ не открыт: $path" },
            )
        } else if (preview.status == PreviewStatus.FirstRender) {
            // Индикатор первого рендера: показать ещё нечего (OQ-1 фичи 003).
            Text(
                text = "РЕНДЕР…",
                style = adocTextStyle(AdocTypography.sectionLabel),
                color = AdocTheme.colors.textFaint,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        Column(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            preview.failure?.let { failure ->
                PreviewFailureBanner(failure = failure, onRetry = onRetry)
            }
            documentLinkNotice?.let { notice ->
                NoticeBanner(text = notice, onDismiss = { documentLinkNotice = null })
            }
        }
    }
}

/**
 * Плашка отказа рендера — решения `OQ-2` фичи 003 и `OQ-3` этой фичи: полоса
 * под панелями с человеческим текстом и кнопкой «Повторить»; техника — по
 * касанию текста. Последний удачный HTML под плашкой продолжает показываться.
 */
@Composable
private fun PreviewFailureBanner(failure: PreviewFailure, onRetry: () -> Unit) {
    var showDetail by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().background(AdocTheme.colors.raised)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { showDetail = !showDetail },
            ) {
                Text(
                    text = failure.userMessage,
                    style = adocTextStyle(AdocTypography.listItem),
                    color = AdocTheme.colors.textSecondary,
                )
                if (showDetail) {
                    Text(
                        text = failure.technicalDetail,
                        style = adocTextStyle(AdocTypography.metadata),
                        color = AdocTheme.colors.textFaint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            SecondaryButton(label = "ПОВТОРИТЬ", onClick = onRetry)
        }
        ChromeDivider()
    }
}

/** Однострочное уведомление в языке плашек; касание убирает его. */
@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.raised)
            .clickable(onClick = onDismiss),
    ) {
        Text(
            text = text,
            style = adocTextStyle(AdocTypography.metadata),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        ChromeDivider()
    }
}

/**
 * Спрятать компонент, не выводя его из композиции: он измеряется как обычно,
 * но помещается за правым краем родителя — вне экрана и вне касаний.
 *
 * Нужен вкладке превью: `WebView` хранит прокрутку в самом себе, и пережить
 * переключение вкладок она может только вместе с живой вью (`FR-4`).
 * Альфа и `zIndex` не подходят: платформенная вью с нулевой альфой продолжает
 * получать касания поверх редактора.
 */
private fun Modifier.parkedOffscreenWhen(parked: Boolean): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        placeable.place(x = if (parked) placeable.width else 0, y = 0)
    }
}

/** Вторичная кнопка по описанию дизайна: прозрачный фон, рамка, текст вторичный. */
@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(1.dp, AdocTheme.colors.borderObject)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.buttonLabel),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
