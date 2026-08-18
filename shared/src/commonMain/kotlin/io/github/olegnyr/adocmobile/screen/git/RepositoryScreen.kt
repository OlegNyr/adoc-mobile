package io.github.olegnyr.adocmobile.screen.git

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.git.FileStatus
import io.github.olegnyr.adocmobile.git.RepoFile
import io.github.olegnyr.adocmobile.git.RepoStatus
import io.github.olegnyr.adocmobile.git.RepositorySnapshot
import io.github.olegnyr.adocmobile.git.StatusEntry
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock

/**
 * Экран репозитория — макет «01» (`FR-9`, `FR-10`, `FR-12` через мост).
 *
 * Карточка ветки — чертёжный блок: имя, `↓N ↑N`, число изменений, адрес
 * remote; ниже чипы-фильтры и список файлов со статусами; внизу панель
 * действий. Из панели макета в E2 есть только `COMMIT · N`: `PULL` приходит
 * с E3, `+` — за пределами итерации (отступления названы в журнале).
 * `↓N` живёт от последнего сетевого обращения (OQ-5) — после клона это
 * честный ноль на момент клона.
 *
 * Без клона экран предлагает клонировать — пустым состоянием в языке
 * дизайна (тот же приём, что пустые состояния редактора).
 *
 * Перечитывание после commit/возврата (`FR-11`) — обязательство хостинга:
 * `LaunchedEffect(model)` стреляет на вход в композицию, возврат к живому
 * экземпляру требует явного `model.start()` (журнал `SL-5`).
 *
 * @param now источник времени для подписей «N мин назад» — параметр,
 * чтобы превью и тесты не зависели от часов устройства
 * @param onOpenFile файл выбран — хостинг открывает его в редакторе через
 * мост `RepoDocumentAccess` (врезка — этап UI)
 * @param onCloneRequested переход на экран клонирования
 * @param onCommitRequested кнопка `COMMIT · N` — переход на экран коммита
 */
@Composable
fun RepositoryScreen(
    model: RepositoryScreenModel,
    now: () -> Long,
    onOpenFile: (RepoFile) -> Unit,
    onCloneRequested: () -> Unit,
    onCommitRequested: () -> Unit,
    beforePull: suspend () -> Boolean = { true },
    onPulled: (List<String>) -> Unit = {},
    onConflict: (List<String>) -> Unit = {},
) {
    LaunchedEffect(model) { model.start() }

    // Конфликтный pull уводит на экран слияния (UC-5): решение принимает
    // хостинг, модель лишь называет файлы.
    val conflicts = model.conflictPaths
    LaunchedEffect(conflicts) {
        if (conflicts.isNotEmpty()) onConflict(conflicts)
    }

    Surface(color = AdocTheme.colors.ground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            when (val state = model.state) {
                is RepositoryScreenState.Loading -> Unit

                is RepositoryScreenState.NoRepository -> NoRepositoryState(onCloneRequested)

                is RepositoryScreenState.Failed -> FailedState(
                    message = state.message,
                    onRetry = model::start,
                )

                is RepositoryScreenState.Ready -> {
                    RepositoryAppBar(title = state.repository.directoryName)
                    ChromeDivider()
                    model.pushFailure?.let { failure -> PushFailureBanner(text = failure) }
                    // Исход pull сообщается явно (FR-14): обновлено, уже
                    // актуально, конфликт или текст отказа.
                    model.pullNotice?.let { notice -> PushFailureBanner(text = notice) }
                    BranchCard(
                        repository = state.repository,
                        status = state.status,
                        pushing = model.pushing,
                        onPush = model::pushRequested,
                    )
                    FilterChips(selected = model.filter, onSelect = { model.filter = it })
                    Column(modifier = Modifier.weight(1f)) {
                        val visible = model.visibleFiles
                        if (visible.isEmpty() && model.filter == RepoFilter.Changed) {
                            EmptyChangesHint()
                        } else {
                            FileList(
                                files = visible,
                                entryOf = model::entryOf,
                                now = now,
                                onOpenFile = onOpenFile,
                            )
                        }
                    }
                    ActionBar(
                        changeCount = state.status.changeCount,
                        pulling = model.pulling,
                        onPull = { model.pullRequested(beforePull = beforePull, onPulled = onPulled) },
                        onCommit = onCommitRequested,
                    )
                }
            }
        }
    }
}

/**
 * App bar «01»: имя репозитория; поиск и меню макета приходят со своими
 * функциями (иконки макета намеренно опущены — набора иконок в проекте нет).
 * Отступ статус-бара внутри фона `chrome` — панель уходит под него
 * (edge-to-edge, образец — app bar редактора).
 */
@Composable
private fun RepositoryAppBar(title: String) {
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
 * Карточка ветки (`FR-9`): чертёжный блок с именем ветки, счётчиками
 * `↓N ↑N`, числом изменений и адресом remote — макет «01».
 * Счётчики и статусы — текстом с числами (`NFR-9`); `↓N` живёт от последнего
 * известного состояния origin (OQ-5).
 */
@Composable
private fun BranchCard(
    repository: RepositorySnapshot,
    status: RepoStatus,
    pushing: Boolean,
    onPush: () -> Unit,
) {
    Box(modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 12.dp)) {
        AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = repository.branch,
                    style = adocTextStyle(AdocTypography.editorCode),
                    color = AdocTheme.colors.accentText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "↓${status.behind} ↑${status.ahead}",
                    style = adocTextStyle(AdocTypography.metadata),
                    color = AdocTheme.colors.textMuted,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = changeCountLabel(status.changeCount),
                    style = adocTextStyle(AdocTypography.metadata),
                    color = AdocTheme.colors.textMuted,
                )
                Text(
                    text = repository.remoteUrl,
                    style = adocTextStyle(AdocTypography.metadata),
                    color = AdocTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Путь повтора отправки (FR-21): кнопка существует, пока есть
            // неотправленные коммиты. Дополнение макета «01» — в нём кнопки
            // нет, но отказ push без пути повтора был находкой ревью E2;
            // отступление названо в журнале.
            if (status.ahead > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(40.dp)
                        .background(if (pushing) AdocTheme.colors.accentTrack else AdocTheme.colors.accent)
                        .clickable(enabled = !pushing, role = Role.Button, onClick = onPush),
                ) {
                    Text(
                        text = if (pushing) "ОТПРАВКА…" else "PUSH · ↑${status.ahead}",
                        style = adocTextStyle(AdocTypography.buttonLabel),
                        color = if (pushing) AdocTheme.colors.textMuted else AdocTheme.colors.onAccent,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

/** Плашка отказа push (`FR-21`) — язык плашек экранов Git. */
@Composable
private fun PushFailureBanner(text: String) {
    Column(modifier = Modifier.fillMaxWidth().background(AdocTheme.colors.raised)) {
        Text(
            text = text,
            style = adocTextStyle(AdocTypography.metadata),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
        ChromeDivider()
    }
}

/** Чип-фильтры `ИЗМЕНЁННЫЕ` / `ВСЕ ФАЙЛЫ` / `НЕДАВНИЕ` (`FR-10`, компонент «Чип-фильтр»). */
@Composable
private fun FilterChips(selected: RepoFilter, onSelect: (RepoFilter) -> Unit) {
    Row(
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip("ИЗМЕНЁННЫЕ", selected == RepoFilter.Changed) { onSelect(RepoFilter.Changed) }
        FilterChip("ВСЕ ФАЙЛЫ", selected == RepoFilter.All) { onSelect(RepoFilter.All) }
        FilterChip("НЕДАВНИЕ", selected == RepoFilter.Recent) { onSelect(RepoFilter.Recent) }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) AdocTheme.colors.accentSelection else AdocTheme.colors.ground)
            .border(
                1.dp,
                if (selected) AdocTheme.colors.accentTrack else AdocTheme.colors.borderChrome,
            )
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.metadata),
            color = if (selected) AdocTheme.colors.accentText else AdocTheme.colors.textMuted,
        )
    }
}

/** Пустой фильтр `ИЗМЕНЁННЫЕ` при чистом репозитории — слово вместо голой области (`UC-2` 2a). */
@Composable
private fun EmptyChangesHint() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Изменений нет",
            style = adocTextStyle(AdocTypography.body),
            color = AdocTheme.colors.textFaint,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Файлы под фильтром: метка статуса, имя, путь и время, счётчик диффа (`FR-10`). */
@Composable
private fun FileList(
    files: List<RepoFile>,
    entryOf: (RepoFile) -> StatusEntry?,
    now: () -> Long,
    onOpenFile: (RepoFile) -> Unit,
) {
    // Один снимок часов на список, не на строку: подписи соседних строк не
    // должны разъезжаться из-за времени, прошедшего между их композициями.
    val listNow = now()
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(files, key = { it.path }) { file ->
            FileRow(
                file = file,
                entry = entryOf(file),
                now = listNow,
                onClick = { onOpenFile(file) },
            )
            ListDivider()
        }
    }
}

@Composable
private fun FileRow(
    file: RepoFile,
    entry: StatusEntry?,
    now: Long,
    onClick: () -> Unit,
) {
    val status = entry?.status
    val name = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Колонка статуса фиксированной ширины; буква, не только цвет (NFR-9).
        Text(
            text = status?.letter().orEmpty(),
            style = adocTextStyle(AdocTypography.metadata),
            color = when (status) {
                FileStatus.Modified -> AdocTheme.colors.accentText
                FileStatus.Added -> AdocTheme.colors.accentSecondary
                FileStatus.Deleted, FileStatus.Untracked, null -> AdocTheme.colors.textFaint
            },
            modifier = Modifier.width(14.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = adocTextStyle(AdocTypography.listItem),
                color = AdocTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = buildString {
                if (status == FileStatus.Untracked) {
                    append("не отслеживается")
                } else {
                    if (directory.isNotEmpty()) {
                        append(directory)
                        append(" · ")
                    }
                    append(repoFileTimeLabel(now, file.lastModifiedEpochMs))
                }
            }
            Text(
                text = meta,
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        entry?.let { diffCountLabel(it) }?.let { label ->
            Text(
                text = label,
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.textFaint,
            )
        }
    }
}

/**
 * Панель действий макета «01»: вторичная `PULL` (`FR-13`) и первичная
 * `COMMIT · N`. Кнопка `+` (новый файл) — за пределами итерации: рисовать её
 * мёртвой значило бы врать о возможностях, отступление названо в журнале.
 */
@Composable
private fun ActionBar(
    changeCount: Int,
    pulling: Boolean,
    onPull: () -> Unit,
    onCommit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .border(1.dp, AdocTheme.colors.borderObject)
                .clickable(enabled = !pulling, role = Role.Button, onClick = onPull),
        ) {
            Text(
                text = if (pulling) "ЗАБИРАЮ…" else "PULL",
                style = adocTextStyle(AdocTypography.buttonLabel),
                color = if (pulling) AdocTheme.colors.textFaint else AdocTheme.colors.textSecondary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(46.dp)
                .background(if (changeCount > 0) AdocTheme.colors.accent else AdocTheme.colors.accentTrack)
                .clickable(enabled = changeCount > 0, role = Role.Button, onClick = onCommit),
        ) {
            Text(
                text = if (changeCount > 0) "COMMIT · $changeCount" else "COMMIT",
                style = adocTextStyle(AdocTypography.buttonLabel),
                color = if (changeCount > 0) AdocTheme.colors.onAccent else AdocTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** Отказ чтения диска: тот же язык пустых состояний, действие — повторить. */
@Composable
private fun FailedState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "РЕПОЗИТОРИЙ НЕ ПРОЧИТАН",
                style = adocTextStyle(AdocTypography.sectionLabel),
                color = AdocTheme.colors.textFaint,
            )
            Text(
                text = message,
                style = adocTextStyle(AdocTypography.body),
                color = AdocTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            ActionButton(label = "ПОВТОРИТЬ", onClick = onRetry)
        }
    }
}

/** Пустое состояние без клона: чертёжный блок с кнопкой перехода к клонированию. */
@Composable
private fun NoRepositoryState(onCloneRequested: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "РЕПОЗИТОРИЯ НЕТ",
                style = adocTextStyle(AdocTypography.sectionLabel),
                color = AdocTheme.colors.textFaint,
            )
            Text(
                text = "Склонируйте репозиторий, чтобы работать с его документами.",
                style = adocTextStyle(AdocTypography.body),
                color = AdocTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
            )
            ActionButton(label = "КЛОНИРОВАТЬ", onClick = onCloneRequested)
        }
    }
}

/** Первичная кнопка состояний экрана — локальный близнец кнопки редактора. */
@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
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
private fun ListDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AdocTheme.colors.borderList),
    )
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
