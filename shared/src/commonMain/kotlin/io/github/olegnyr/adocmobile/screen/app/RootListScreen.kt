package io.github.olegnyr.adocmobile.screen.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock

/**
 * Корневой экран приложения — единственный список файлов (`FR-1`, `FR-14`,
 * раскладка 1a `ADR-003`, `ADR-013`).
 *
 * Вёрстка строки и пустых состояний перенесена из экрана редактора без
 * изменений: она уже была согласована при закрытии `SL-4` фичи 005, и
 * переписывать её под новый дом значило бы менять поведение под видом
 * переезда. Со слайсом `SL-3` в ней изменилось ровно одно — состав действий
 * пустого состояния перестал быть фиксированным и приходит из
 * [ActiveSource] (`FR-2`). Git-частей здесь нет вовсе — они приходят с
 * `SL-4`, и до тех пор приложение остаётся полезным редактором документов из
 * папки.
 *
 * Перечитка (`FR-7`) — своя, и единственная в приложении: экран покидает
 * композицию при уходе в документ, поэтому на возврате эффект стреляет заново.
 * Второй повод перечитать — смена источника, и он приходит меткой
 * [RootListModel.sourceToken] в ключе того же эффекта, а не отдельным вызовом:
 * пока читали из двух мест, каждый вход давал по два чтения (находка ревью
 * `SL-2`). Тем и отличается от экрана репозитория, который живёт в композиции
 * всегда и перечитку получает вызовом снаружи.
 *
 * @param sources активный источник: из него берутся и действия пустого
 * состояния (`FR-2`), и пункты меню (`FR-12`) — экран их не сочиняет
 * @param onOpenDocument выбор файла — хостинг ведёт в редактор
 * @param onSourceAction пользователь выбрал действие над источниками;
 * исполняет его хостинг — он же владеет и диалогом папки, и навигацией
 */
@Composable
fun RootListScreen(
    model: RootListModel,
    sources: ActiveSource,
    onOpenDocument: (DocumentSource) -> Unit,
    onSourceAction: (SourceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ключ включает метку источника: смена папки перезапускает чтение и с
    // корня, и после возврата из документа — одним и тем же путём, ровно
    // один раз.
    LaunchedEffect(model, model.sourceToken) { model.start() }

    Surface(color = AdocTheme.colors.ground, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            val state = model.state
            // Шапка, меню и плашка — над разбором состояний: отказ открытия
            // ставится в любом состоянии, а рисовался только над списком, и
            // в остальных его нечем было погасить (находка ревью `SL-2`).
            // По той же причине здесь и меню источников: оно обязано быть
            // доступно в отказе источника (`FR-15`), а из ветки состояния его
            // легко потерять ровно в той ветке, где оно нужнее всего.
            RootAppBar(
                title = (state as? RootListState.Listed)?.title ?: APP_TITLE,
                activeKind = sources.kind,
                actions = sources.menuActions,
                onSourceAction = onSourceAction,
            )
            ChromeDivider()
            model.notice?.let { text ->
                NoticeBanner(text = text, onDismiss = model::noticeDismissed)
            }

            when (state) {
                RootListState.Loading -> Unit

                RootListState.NoSource -> EmptyState(
                    label = "ИСТОЧНИК НЕ ВЫБРАН",
                    message = noSourceMessage(sources.startActions),
                    actions = sources.startActions,
                    onSourceAction = onSourceAction,
                )

                is RootListState.Failed -> EmptyState(
                    label = "ИСТОЧНИК НЕДОСТУПЕН",
                    message = state.message,
                    actions = sources.startActions,
                    onSourceAction = onSourceAction,
                )

                is RootListState.Listed -> ListedSource(
                    state = state,
                    onOpenDocument = onOpenDocument,
                )
            }
        }
    }
}

@Composable
private fun ListedSource(
    state: RootListState.Listed,
    onOpenDocument: (DocumentSource) -> Unit,
) {
    if (state.files.isEmpty()) {
        // Действий у этого состояния нет: источник выбран и читается, менять
        // его — через меню шапки, как и в любом другом состоянии (`FR-12`).
        // Кнопка «СМЕНИТЬ» жила здесь до `SL-3` и убрана вместе с приходом
        // меню: два входа в одно действие расходятся при первой же правке.
        EmptyState(
            label = "ДОКУМЕНТОВ НЕТ",
            message = "В источнике «${state.title}» нет файлов .adoc. " +
                "Добавьте документы или выберите другой источник в меню.",
            actions = emptyList(),
            onSourceAction = {},
        )
        return
    }

    Text(
        text = "ИСТОЧНИК · ${state.title}",
        style = adocTextStyle(AdocTypography.sectionLabel),
        color = AdocTheme.colors.textFaint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
    ChromeDivider()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
    ) {
        items(state.files, key = { it.id }) { source ->
            Text(
                text = source.displayName,
                style = adocTextStyle(AdocTypography.listItem),
                color = AdocTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onOpenDocument(source) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            )
            ChromeDivider()
        }
    }
}

/**
 * Шапка корня: имя источника и меню источников (`FR-12`).
 *
 * Кнопки «назад» здесь нет намеренно — это корень, и системный «назад» на нём
 * не перехватывается (`FR-5`, `AppNavigator.canGoBack`).
 * Меню, наоборот, есть всегда: оно и есть выход из отказавшего источника
 * (`FR-15`), а спрятанное в «нормальном» состоянии оно оставило бы
 * пользователя запертым ровно тогда, когда нужнее всего.
 */
@Composable
private fun RootAppBar(
    title: String,
    activeKind: FileSourceKind?,
    actions: List<SourceAction>,
    onSourceAction: (SourceAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .statusBarsPadding()
            .height(52.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = adocTextStyle(AdocTypography.screenTitle),
            color = AdocTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SourceMenu(activeKind = activeKind, actions = actions, onSourceAction = onSourceAction)
    }
}

/**
 * Меню источников — тем же приёмом, что меню документа в `EditorScreen`:
 * своя панель на [Popup], квадратные углы, поверхность `raised` с рамкой.
 *
 * Состав пунктов приходит готовым из [ActiveSource]: правило «что предлагать»
 * проверяется в `commonTest` (`NFR-10`), и экран его не повторяет.
 * Пустого меню не бывает — `ОТКРЫТЬ ПАПКУ` есть всегда, — поэтому кнопка
 * рисуется безусловно.
 */
@Composable
private fun SourceMenu(
    activeKind: FileSourceKind?,
    actions: List<SourceAction>,
    onSourceAction: (SourceAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        MenuButton(onClick = { expanded = true })
        if (!expanded) return@Box
        Popup(
            alignment = Alignment.TopEnd,
            // Тот же сдвиг, что у меню документа: панель садится под кнопкой
            // с зазором, а не вплотную.
            offset = IntOffset(x = 0, y = with(LocalDensity.current) { 48.dp.roundToPx() }),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 200.dp)
                    .background(AdocTheme.colors.raised)
                    .border(1.dp, AdocTheme.colors.borderObject),
            ) {
                // Активный источник назван словом, а не отмечен цветом или
                // выведен пользователем из состава пунктов (`NFR-9`).
                if (activeKind != null) {
                    Text(
                        text = "ИСТОЧНИК · ${activeKind.title}",
                        style = adocTextStyle(AdocTypography.sectionLabel),
                        color = AdocTheme.colors.textFaint,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    ChromeDivider()
                }
                actions.forEach { action ->
                    MenuItem(label = action.menuLabel) {
                        expanded = false
                        onSourceAction(action)
                    }
                }
            }
        }
    }
}

/**
 * Кнопка меню — три точки по вертикали, площадка 44 (макет «02»).
 *
 * Имя узла задано `contentDescription` — образец `InsertPanel`: сами точки
 * декоративны, и без имени озвучка объявила бы безымянную кнопку (`NFR-9`).
 * `onClickLabel` для этого не годится: он подписывает действие, а не узел.
 */
@Composable
private fun MenuButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Меню источников" },
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

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        // Подписи пунктов — Barlow Condensed прописными, как в меню документа
        // и как велит язык дизайна: два меню одного приложения не должны
        // выглядеть по-разному (находка ревью `SL-3`).
        text = label,
        style = adocTextStyle(AdocTypography.buttonLabel),
        color = AdocTheme.colors.textSecondary,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * Пустое состояние в языке дизайна — чертёжный блок, как у редактора и у
 * экрана репозитория без клона. Носители: источник не выбран, источник
 * недоступен, источник без документов — различаются текстами и составом
 * действий.
 *
 * Действия приходят готовым списком (`FR-2`): первое — первичной кнопкой,
 * остальные — вторичными под ней. Пустой список — состояние без действий:
 * у источника без документов чинить нечего.
 */
@Composable
private fun EmptyState(
    label: String,
    message: String,
    actions: List<SourceAction>,
    onSourceAction: (SourceAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = adocTextStyle(AdocTypography.sectionLabel),
                color = AdocTheme.colors.textFaint,
            )
            Text(
                text = message,
                style = adocTextStyle(AdocTypography.body),
                color = AdocTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            actions.forEachIndexed { index, action ->
                if (index > 0) Spacer(modifier = Modifier.height(10.dp))
                if (index == 0) {
                    PrimaryButton(label = action.buttonLabel) { onSourceAction(action) }
                } else {
                    WideSecondaryButton(label = action.buttonLabel) { onSourceAction(action) }
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.raised),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            // Отдельная кнопка с подписью и ролью, а не касание по всей полосе
            // (находка ревью `SL-2`): невидимый аффорданс никто не найдёт, а
            // озвучка о нём не объявит — соседние кнопки роль передают
            // (`NFR-9`).
            Text(
                text = "ЗАКРЫТЬ",
                style = adocTextStyle(AdocTypography.buttonLabel),
                color = AdocTheme.colors.textFaint,
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        ChromeDivider()
    }
}

// Четвёртая копия этих примитивов в проекте: они приватны в каждом экране.
// Со слайсом `SL-3` к ним прибавилась и панель меню — она почти дословно
// повторяет меню документа из `EditorScreen`. Вынос в `ui/` — записанный долг
// (`FR-23`, слайс `SL-10`), и делать его здесь нельзя: `ui/**` вне `targets`
// фичи, а половина копий живёт в `screen/git/**`, где сейчас работает другой
// агент. Долг назван поимённо, чтобы `SL-10` знал полный список.
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
 * Вторичное действие пустого состояния: та же высота и ширина, что у
 * первичной кнопки, но рамка вместо заливки — два действия `FR-2` стоят одно
 * под другим и обязаны читаться как пара, а не как кнопка и ссылка.
 */
@Composable
private fun WideSecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .border(1.dp, AdocTheme.colors.borderObject)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.buttonLabel),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun ChromeDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AdocTheme.colors.borderChrome),
    )
}

/** Имя приложения в шапке, когда источника нет. */
private const val APP_TITLE = "ADOC MOBILE"
