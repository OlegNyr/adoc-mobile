package io.github.olegnyr.adocmobile.screen.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock

/**
 * Корневой экран приложения — единственный список файлов (`FR-1`, `FR-14`,
 * раскладка 1a xref ADR-003, xref ADR-013).
 *
 * Вёрстка строки и пустых состояний перенесена из экрана редактора без
 * изменений: она уже была согласована при закрытии `SL-4` фичи 005, и
 * переписывать её под новый дом значило бы менять поведение под видом
 * переезда. Git-частей здесь нет вовсе — они приходят с `SL-4`, и до тех пор
 * приложение остаётся полезным редактором документов из папки.
 *
 * Перечитка (`FR-7`) — своя, и единственная в приложении: экран покидает
 * композицию при уходе в документ, поэтому на возврате эффект стреляет заново.
 * Второй повод перечитать — смена источника, и он приходит меткой
 * [RootListModel.sourceToken] в ключе того же эффекта, а не отдельным вызовом:
 * пока читали из двух мест, каждый вход давал по два чтения (находка ревью
 * `SL-2`). Тем и отличается от экрана репозитория, который живёт в композиции
 * всегда и перечитку получает вызовом снаружи.
 *
 * @param onOpenDocument выбор файла — хостинг ведёт в редактор
 * @param onChooseSource системный диалог выбора папки; с приходом второго
 * источника (`SL-3`) рядом встанет «Клонировать»
 */
@Composable
fun RootListScreen(
    model: RootListModel,
    onOpenDocument: (DocumentSource) -> Unit,
    onChooseSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ключ включает метку источника: смена папки перезапускает чтение и с
    // корня, и после возврата из документа — одним и тем же путём, ровно
    // один раз.
    LaunchedEffect(model, model.sourceToken) { model.start() }

    Surface(color = AdocTheme.colors.ground, modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            val state = model.state
            // Шапка и плашка — над разбором состояний: отказ открытия
            // ставится в любом состоянии, а рисовался только над списком, и
            // в остальных его нечем было погасить (находка ревью `SL-2`).
            RootAppBar(title = (state as? RootListState.Listed)?.title ?: APP_TITLE)
            ChromeDivider()
            model.notice?.let { text ->
                NoticeBanner(text = text, onDismiss = model::noticeDismissed)
            }

            when (state) {
                RootListState.Loading -> Unit

                RootListState.NoSource -> EmptyState(
                    label = "ПАПКА НЕ ВЫБРАНА",
                    message = "Выберите папку с документами — приложение покажет её файлы " +
                        "и запомнит выбор.",
                    onChooseSource = onChooseSource,
                )

                is RootListState.Failed -> EmptyState(
                    label = "ПАПКА НЕДОСТУПНА",
                    message = state.message,
                    onChooseSource = onChooseSource,
                )

                is RootListState.Listed -> ListedSource(
                    state = state,
                    onOpenDocument = onOpenDocument,
                    onChooseSource = onChooseSource,
                )
            }
        }
    }
}

@Composable
private fun ListedSource(
    state: RootListState.Listed,
    onOpenDocument: (DocumentSource) -> Unit,
    onChooseSource: () -> Unit,
) {
    if (state.files.isEmpty()) {
        EmptyState(
            label = "ДОКУМЕНТОВ НЕТ",
            message = "В папке «${state.title}» нет файлов .adoc. " +
                "Выберите другую папку или добавьте документы в эту.",
            onChooseSource = onChooseSource,
        )
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ИСТОЧНИК · ${state.title}",
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = AdocTheme.colors.textFaint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(label = "СМЕНИТЬ", onClick = onChooseSource)
    }
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
 * Шапка корня: только имя источника.
 *
 * Кнопки «назад» здесь нет намеренно — это корень, и системный «назад» на нём
 * не перехватывается (`FR-5`, `AppNavigator.canGoBack`).
 */
@Composable
private fun RootAppBar(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .statusBarsPadding()
            .height(52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = adocTextStyle(AdocTypography.screenTitle),
            color = AdocTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Пустое состояние в языке дизайна — чертёжный блок, как у редактора и у
 * экрана репозитория без клона. Носители: источник не выбран, источник
 * недоступен, источник без документов — различаются только текстами.
 */
@Composable
private fun EmptyState(label: String, message: String, onChooseSource: () -> Unit) {
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
            PrimaryButton(label = "ОТКРЫТЬ ПАПКУ", onClick = onChooseSource)
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

// Четвёртая копия этих трёх примитивов в проекте: они приватны в каждом
// экране. Вынос в `ui/` — записанный долг (`FR-23`, слайс `SL-10`), и делать
// его здесь нельзя: `ui/**` вне `targets` фичи, а половина копий живёт в
// `screen/git/**`, где сейчас работает другой агент.
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
