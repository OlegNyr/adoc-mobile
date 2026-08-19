@file:OptIn(kotlin.time.ExperimentalTime::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.olegnyr.adocmobile.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.TreeSource
import io.github.olegnyr.adocmobile.insert.InsertPanel
import io.github.olegnyr.adocmobile.preview.AdocPreview
import io.github.olegnyr.adocmobile.preview.PreviewFailure
import io.github.olegnyr.adocmobile.preview.PreviewImageSource
import io.github.olegnyr.adocmobile.preview.PreviewStatus
import io.github.olegnyr.adocmobile.render.adocRenderer
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocEditor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Вкладки экрана редактора — раскладка 1a (ADR-003). */
enum class EditorTab { Editor, Preview }

/**
 * Показывать ли полосу вкладок (`FR-22`, решение владельца 2026-08-18):
 * только при открытом документе.
 *
 * Уточняет `FR-2`, писавшийся до появления состояний списка: вкладки
 * переключают представления *документа*, и пока документ ещё читается,
 * переключать им нечего.
 */
internal fun editorTabsVisible(document: EditorDocument): Boolean = document is EditorDocument.Open

/**
 * Какая вкладка активна после смены состояния экрана.
 *
 * Пока документ открыт, выбор пользователя неприкосновенен (`FR-2`): поворот и
 * рекомпозиция не сбрасывают превью на редактор. Закрытие документа (`FR-21`)
 * сбрасывает выбор: полосы всё равно нет, а следующий документ открывается
 * редактором — иначе он открылся бы превью, оставшимся от прежнего.
 *
 * Чистая функция, а не ветка в композиции, — тем же приёмом, что
 * [undoShortcutFor]: инфраструктуры compose-тестов в проекте нет, и решение
 * обязано быть проверяемым в `commonTest`.
 */
internal fun editorTabAfter(document: EditorDocument, selected: EditorTab): EditorTab =
    if (document is EditorDocument.Open) selected else EditorTab.Editor

/**
 * Экран редактора по макету «02» — фича 005-editor-screen, слайсы `SL-1`…`SL-3`.
 *
 * App bar с именем файла и состоянием документа, вкладки `РЕДАКТОР` / `ПРЕВЬЮ`,
 * полотно [AdocEditor]. Экран существует только при документе: пустое
 * состояние, отказ папки и список её файлов уехали на корень приложения
 * (`FR-14` фичи 009, решение `OQ-2` там, ADR-013), и до открытого документа
 * здесь остаётся лишь имя файла в шапке, пока он читается.
 *
 * Экран целиком обёрнут в один [AdocTheme] (`FR-15`) и живёт в `commonMain`
 * (`NFR-2`): платформе остаётся хостинг и системный диалог выбора папки.
 *
 * Единственный `TextFieldState` создаётся здесь через `rememberSaveable` с его
 * `Saver` (`FR-6`, `FR-7`): текст, каретка и история отмены переживают поворот
 * и выгрузку процесса, и этим же экземпляром пользуются подсветка, undo и
 * автосохранение.
 *
 * @param requestFolder платформенный диалог выбора папки
 * (`ACTION_OPEN_DOCUMENT_TREE`) для пункта меню «Открыть папку…»: вернуть
 * дерево с удержанным правом или `null`, если пользователь передумал. Смена
 * папки закрывает открытый документ — новый файл выбирается уже на корне.
 * @param foreground приложение на переднем плане. Сигнал подаёт платформенный
 * хостинг (у Compose Multiplatform общего lifecycle-примитива в зависимостях
 * проекта нет): уход в фон гасит видимость превью (`OQ-5` фичи 003) и
 * записывает документ немедленно (`FR-11`).
 * @param imageSource источник байтов картинок рядом с документом для превью
 * (`TC-34` фичи 003); реализация — у платформенной половины tree-доступа.
 * @param shareDocument системная отправка документа (`FR-20`): экран объявляет
 * намерение источником уже записанного файла, платформа показывает диалог
 * `ACTION_SEND` — тем же разделением, что у [requestFolder].
 * @param openSource документ, который просит открыть хостинг навигации
 * (`FR-1` фичи 009). Обязателен: экран существует только при документе —
 * список файлов принадлежит корневому экрану (`FR-14` фичи 009, ADR-013).
 * @param onDocumentClosed документ закрыт («назад», `FR-21`) или не открылся;
 * аргумент — текст отказа открытия (`FR-9`) либо `null`. Хостинг возвращается
 * на корневой список и показывает текст там.
 */
@Composable
fun EditorScreen(
    access: DocumentTreeAccess,
    requestFolder: suspend () -> TreeSource?,
    modifier: Modifier = Modifier,
    foreground: Boolean = true,
    imageSource: PreviewImageSource? = null,
    shareDocument: (DocumentSource) -> Unit = {},
    openSource: DocumentSource,
    onDocumentClosed: (notice: String?) -> Unit,
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
                    onDocumentClosed = onDocumentClosed,
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

            // Что открыто, решает хостинг навигации: своего подъёма и своего
            // списка у экрана больше нет (`FR-14` фичи 009).
            LaunchedEffect(model, openSource) {
                model.open(openSource, keepField = openSource.id == fieldSourceId)
            }

            // Выбранная вкладка объявлена до подписки на состояние: закрытие
            // документа её сбрасывает (FR-22), и эффекту нужен доступ к ней.
            var selectedTabName by rememberSaveable { mutableStateOf(EditorTab.Editor.name) }

            val document = model.document
            LaunchedEffect(document) {
                if (document is EditorDocument.Open) {
                    fieldSourceId = document.runner.document.source.id
                }
                // Закрытие документа сбрасывает выбор вкладки (FR-22): следующий
                // документ открывается редактором, а не превью прежнего.
                selectedTabName = editorTabAfter(document, EditorTab.valueOf(selectedTabName)).name
            }

            // Системная кнопка и жест «назад» Android — тот же путь, что
            // кнопка в app bar (FR-21). Перехват только при открытом документе,
            // и только потому, что здесь у «назад» есть своя работа:
            // немедленная запись перед закрытием. Всё остальное время, включая
            // чтение документа, «назад» держит перехват уровня приложения по
            // признаку стека (`AdocApp`, `FR-5` фичи 009); оба обработчика
            // одного API, и вложенный выигрывает, поэтому пока документ открыт
            // побеждает этот.
            BackHandler(enabled = document is EditorDocument.Open) { model.closeRequested() }

            // Каждое изменение текста — в модель (FR-10). Подписка через
            // snapshotFlow, не в кадре композиции (NFR-1).
            LaunchedEffect(model) {
                snapshotFlow { textFieldState.text }.collect { model.textEdited(it.toString()) }
            }

            val selectedTab = EditorTab.valueOf(selectedTabName)
            val focusManager = LocalFocusManager.current

            // Фокус поля ввода управляет видимостью панели вставки (OQ-3 фичи
            // 006): фокус есть — панель есть, независимо от IME. Не в
            // rememberSaveable намеренно: фокус — преходящее состояние, после
            // поворота его выдаёт заново само поле.
            var fieldFocused by remember { mutableStateOf(false) }

            // Возврат с превью возвращает полю фокус (FR-23): каретка встаёт на
            // прежнее место, клавиатура и панель вставки открываются, и набор
            // продолжается без лишнего касания. Условие — именно смена вкладки
            // с превью на редактор: открытие документа фокуса не просит, иначе
            // клавиатура вылезала бы на каждый вход в файл.
            val fieldFocus = remember { FocusRequester() }
            var previousTab by remember { mutableStateOf(selectedTab) }
            LaunchedEffect(selectedTab, document) {
                val returned = previousTab == EditorTab.Preview && selectedTab == EditorTab.Editor
                previousTab = selectedTab
                if (returned && document is EditorDocument.Open) {
                    // Полотно не покидает композицию на превью, поэтому узел жив
                    // и запрос доходит; на всякий отказ окружения падать экраном
                    // из-за фокуса нельзя — правки при этом целы.
                    runCatching { fieldFocus.requestFocus() }
                }
            }

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

            // Уход в фон — немедленная запись (FR-11): сигнал тоже идемпотентен,
            // модель реагирует только на смену значения.
            LaunchedEffect(foreground) { model.foregroundChanged(foreground) }

            // Единственный носитель этого действия на экране документа —
            // пункт меню «Открыть папку…»: смена папки закрывает документ
            // (`folderChosen`), и дальше файл выбирается на корне. Отмена
            // диалога — честный null, документ остаётся открытым.
            val openFolder: () -> Unit = {
                scope.launch { if (requestFolder() != null) model.folderChosen() }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // Undo/redo с аппаратной клавиатуры (FR-17): перехват на
                    // предпросмотре события — раньше встроенной обработки поля,
                    // чтобы отмена шла тем же путём, что пункт меню, а не двумя.
                    .onPreviewKeyEvent { event -> event.undoShortcutHandled(model) },
            ) {
                // Обе хром-панели на одном фоне chrome; отступ статус-бара
                // внутри фона, чтобы панель уходила под него (edge-to-edge).
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AdocTheme.colors.chrome)
                        .statusBarsPadding(),
                ) {
                    EditorAppBar(
                        document = document,
                        editor = editor,
                        // Имя показывается, только пока документ открывается
                        // хостингом: без хостинга состояния без документа
                        // рисуют себя сами и в подписи не нуждаются.
                        openingName = openSource.displayName,
                        retryWriteVisible = model.writeFailure != null,
                        onBack = model::closeRequested,
                        onUndo = model::undoRequested,
                        onRedo = model::redoRequested,
                        onShare = { model.shareRequested(shareDocument) },
                        onOpenFolder = openFolder,
                        onRetryWrite = model::retryWriteRequested,
                    )
                    ChromeDivider()
                    // Полоса вкладок — только при открытом документе (FR-22):
                    // пока документ читается, переключать нечего. Разделитель
                    // под app bar остаётся один — нижний принадлежит полосе и
                    // уходит вместе с ней.
                    if (editorTabsVisible(document)) {
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
                }

                // Плашка отказа записи — под app bar, над содержимым, на обеих
                // вкладках (FR-12, решение OQ-3): молчаливая потеря правок —
                // единственный непрощаемый отказ, заметность важнее места.
                model.writeFailure?.let { message ->
                    WriteFailureBanner(message = message, onRetry = model::retryWriteRequested)
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (document) {
                        is EditorDocument.Open -> {
                            // Полотно остаётся в композиции и на вкладке превью:
                            // так каретка и прокрутка не сбрасываются (FR-3,
                            // якорь «не пересоздавать полотно» из спеки).
                            // Отступы навигации и IME полотно больше не несёт:
                            // их несёт нижний элемент колонки — панель вставки
                            // или спейсер (фича 006, FR-1: между панелью и
                            // клавиатурой не остаётся разрыва). Каретка видима
                            // над панелью, потому что вьюпорт поля кончается на
                            // её верхней кромке (NFR-7, FR-2 фичи 006).
                            AdocEditor(
                                state = textFieldState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(fieldFocus)
                                    .onFocusChanged { fieldFocused = it.isFocused }
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
                                imageSource = imageSource,
                                onRetry = model::previewRetryRequested,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .parkedOffscreenWhen(selectedTab != EditorTab.Preview),
                            )
                        }

                        // Документ ещё читается: показывать нечего, шапка
                        // уже назвала файл, а выйти можно — «назад» держит
                        // перехват уровня приложения (`FR-5` фичи 009).
                        EditorDocument.Opening -> Unit
                    }
                }

                if (document is EditorDocument.Open && selectedTab == EditorTab.Editor) {
                    // Слот строки проблем (поток 9, US-E2-05): встанет здесь,
                    // между полотном и панелью вставки, — раскладка место
                    // оставляет, содержимого у слота пока нет.

                    if (fieldFocused) {
                        // Панель вставки (фича 006): отступы навигации и IME
                        // несёт её контейнер — добавлять imePadding здесь
                        // нельзя, зазор удвоится.
                        InsertPanel(
                            state = textFieldState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        // Без панели те же отступы держит спейсер: полотно не
                        // уходит ни под навигацию, ни под клавиатуру (NFR-7).
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .imePadding(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * App bar: имя файла и метка состояния (`FR-1`), высота 52 по макету «02»;
 * справа — меню документа (`FR-16`, состав — решение `OQ-4`).
 *
 * Слева от имени открытого документа — кнопка «назад» на корневой список
 * (`FR-21`, решение `OQ-8`); в макете здесь стрелка, и с решением по пути
 * «документ → список» она обрела назначение. Пока документ ещё читается,
 * кнопки нет — не потому, что уходить некуда, а потому, что уводить нечем:
 * своей работы («дописать несохранённое перед уходом») у неё в этот момент
 * нет, а сам выход держит перехват уровня приложения по признаку стека
 * (`FR-5` фичи 009). Рассуждение «назад отсюда некуда» в этом файле стояло
 * раньше и трижды оборачивалось экраном без выхода — оно про корень, и
 * переносить его на экран второго уровня нельзя.
 * Иконка-переключатель превью из макета опущена: дизайн-вопрос `OQ-4` о её
 * судьбе при живых вкладках не решён, а дублировать вкладку по умолчанию —
 * значит решить его за дизайнера.
 *
 * @param retryWriteVisible пункт «Повторить запись» уместен только пока отказ
 * записи актуален (`FR-19` фичи 004): плашка горит — пункт есть.
 */
@Composable
private fun EditorAppBar(
    document: EditorDocument,
    editor: DocumentEditor,
    openingName: String?,
    retryWriteVisible: Boolean,
    onBack: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShare: () -> Unit,
    onOpenFolder: () -> Unit,
    onRetryWrite: () -> Unit,
) {
    val open = document is EditorDocument.Open
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            // При открытом документе слева стоит кнопка «назад» со своей
            // площадкой — отступ у неё внутри, как у кнопки меню справа.
            .padding(start = if (open) 0.dp else 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (document is EditorDocument.Open) {
            BackButton(onClick = onBack)
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
        } else if (openingName != null) {
            // Документ ещё читается: имя в шапке вместо пустоты. Чтение через
            // SAF занимает заметное время, и голый экран без единой подписи
            // читается как сбой (замечание ревью `SL-2` фичи 009).
            Text(
                text = openingName,
                style = adocTextStyle(AdocTypography.screenTitle),
                color = AdocTheme.colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(modifier = Modifier.weight(1f))
        }

        DocumentMenu(
            editor = editor,
            shareEnabled = document is EditorDocument.Open,
            retryWriteVisible = retryWriteVisible,
            onUndo = onUndo,
            onRedo = onRedo,
            onShare = onShare,
            onOpenFolder = onOpenFolder,
            onRetryWrite = onRetryWrite,
        )
    }
}

/**
 * Меню документа (`FR-16`) — состав решён владельцем (`OQ-4`): «Отменить»,
 * «Повторить», «Поделиться» (`FR-20`, дозаявлен на приёмке шлюза),
 * «Открыть папку…», «Повторить запись» при живом отказе.
 *
 * Макет «02» рисует только кнопку меню, содержимого у меню в хэндоффе нет —
 * панель собрана в языке дизайна: квадратные углы, поверхность `raised` с
 * рамкой `borderObject`, пункты Barlow Condensed прописными. Своя панель на
 * [Popup] вместо Material `DropdownMenu` — той же логикой, что весь экран:
 * компоненты Material несут скругления и цвета схемы, которые пришлось бы
 * подавлять по месту.
 *
 * Недоступные пункты ([DocumentEditor.canUndo]/[canRedo], «Поделиться» без
 * открытого документа) показываются приглушённо и не реагируют на касание —
 * не прячутся и не бросают ошибку (`FR-16`); касание доступного пункта
 * закрывает меню.
 */
@Composable
private fun DocumentMenu(
    editor: DocumentEditor,
    shareEnabled: Boolean,
    retryWriteVisible: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onShare: () -> Unit,
    onOpenFolder: () -> Unit,
    onRetryWrite: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        MenuButton(onClick = { expanded = true })

        if (expanded) {
            val dismiss = { expanded = false }
            // Панель — под кнопкой, правым краем к правому краю app bar.
            val offsetY = with(LocalDensity.current) { 48.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, offsetY),
                onDismissRequest = dismiss,
                properties = PopupProperties(focusable = true),
            ) {
                Column(
                    modifier = Modifier
                        // Панель по ширине самого длинного пункта, не во весь
                        // экран: fillMaxWidth пунктов разрешается в интринсику.
                        .width(IntrinsicSize.Max)
                        .widthIn(min = 200.dp)
                        .background(AdocTheme.colors.raised)
                        .border(1.dp, AdocTheme.colors.borderObject),
                ) {
                    MenuItem("ОТМЕНИТЬ", enabled = editor.canUndo) {
                        onUndo()
                        dismiss()
                    }
                    MenuItem("ПОВТОРИТЬ", enabled = editor.canRedo) {
                        onRedo()
                        dismiss()
                    }
                    MenuItem("ПОДЕЛИТЬСЯ", enabled = shareEnabled) {
                        onShare()
                        dismiss()
                    }
                    MenuItem("ОТКРЫТЬ ПАПКУ…") {
                        onOpenFolder()
                        dismiss()
                    }
                    if (retryWriteVisible) {
                        MenuItem("ПОВТОРИТЬ ЗАПИСЬ") {
                            onRetryWrite()
                            dismiss()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Кнопка «назад» в app bar (`FR-21`): касаемая площадка 44×44, значок —
 * символ `‹`, тем же приёмом, что кнопка возврата раскрытого ряда панели
 * вставки. Макет рисует стрелку Lucide; набора иконок в проекте нет
 * (см. [MenuButton]) — замена придёт с набором.
 */
@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = "‹",
            style = adocTextStyle(AdocTypography.screenTitle),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Кнопка меню в app bar: касаемая площадка 44×44, значок — три квадратные
 * точки. Макет рисует здесь kebab-иконку Lucide; набора иконок в проекте нет,
 * и точки собраны из квадратов в визуальном языке (везде прямые углы) —
 * отступление названо в журнале, замена придёт с набором иконок.
 */
@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(3) {
                Box(modifier = Modifier.size(3.dp).background(AdocTheme.colors.textSecondary))
            }
        }
    }
}

/** Пункт меню документа; недоступный — приглушён и не касаем, не спрятан (`FR-16`). */
@Composable
private fun MenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        style = adocTextStyle(AdocTypography.buttonLabel),
        color = if (enabled) AdocTheme.colors.textSecondary else AdocTheme.colors.textFaint,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/** Действие сочетания аппаратной клавиатуры (`FR-17`, `FR-13` фичи 004). */
internal enum class UndoShortcut { Undo, Redo }

/**
 * Соответствие «клавиши → действие» — решение владельца 2026-08-17, записано
 * в `FR-13` фичи 004: `Ctrl+Z` — отменить; `Ctrl+Shift+Z` и `Ctrl+Y` —
 * повторить. Чистая функция, отделённая от [KeyEvent]: платформенное событие
 * в `commonTest` не собрать, а таблица сочетаний обязана быть под смоуком.
 */
internal fun undoShortcutFor(key: Key, ctrl: Boolean, shift: Boolean): UndoShortcut? = when {
    !ctrl -> null
    key == Key.Y -> UndoShortcut.Redo
    key == Key.Z && shift -> UndoShortcut.Redo
    key == Key.Z -> UndoShortcut.Undo
    else -> null
}

/**
 * Undo/redo с аппаратной клавиатуры (`FR-17`): сочетания — [undoShortcutFor].
 * Событие поглощается, чтобы встроенная обработка поля не отменила второй раз;
 * недоступное действие — тот же тихий no-op, что в меню.
 */
private fun KeyEvent.undoShortcutHandled(model: EditorScreenModel): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val shortcut = undoShortcutFor(key, ctrl = isCtrlPressed, shift = isShiftPressed) ?: return false
    when (shortcut) {
        UndoShortcut.Undo -> model.undoRequested()
        UndoShortcut.Redo -> model.redoRequested()
    }
    return true
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
 * Плашка отказа записи — решение `OQ-3`: та же полоса, что у отказа рендера,
 * с текстом `DocumentWriteError.userMessage` и кнопкой «Повторить» (ручной
 * повтор — второй способ снять паузу `FR-19` фичи 004). Несохранённый текст
 * при этом остаётся в поле (`FR-12`) — плашка живёт до успешной записи.
 */
@Composable
private fun WriteFailureBanner(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(AdocTheme.colors.raised)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = adocTextStyle(AdocTypography.listItem),
                color = AdocTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(label = "ПОВТОРИТЬ", onClick = onRetry)
        }
        ChromeDivider()
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
    imageSource: PreviewImageSource?,
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
                imageSource = imageSource,
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
 * Полоса-уведомление: человеческий текст и касание, чтобы убрать.
 *
 * Осталась после выноса списка в корень (`SL-2b` фичи 009) ради превью:
 * переход по ссылке на другой документ сообщает результат этой же полосой.
 */
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
