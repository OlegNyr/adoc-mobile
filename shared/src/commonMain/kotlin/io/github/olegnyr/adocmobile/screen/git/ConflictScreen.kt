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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.git.CommitAuthor
import io.github.olegnyr.adocmobile.git.ConflictChoice
import io.github.olegnyr.adocmobile.git.ConflictHunk
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle

/**
 * Экран слияния — макет «04» (`FR-23`…`FR-25`).
 *
 * Две версии участка с подписями сторон, выбранная получает акцентную рамку
 * (двойное кодирование: рамка плюс фон, `NFR-9`), блок `РЕЗУЛЬТАТ`
 * пересобирается сразу при выборе, счётчик участков `1/N` в app bar, панель
 * действий `ЛОКАЛЬНОЕ` / `УДАЛЁННОЕ` / `ОБА` и первичная `ДАЛЕЕ`.
 *
 * На последнем разрешённом участке `ДАЛЕЕ` уступает место `ЗАВЕРШИТЬ` —
 * дополнение макета: в нём завершения не нарисовано, а `FR-24` требует
 * доступного действия только после разрешения всех участков; отступление
 * названо в журнале `SL-12`. Отмена слияния (`FR-25`) — вторичной кнопкой
 * в app bar, там же по той же причине.
 *
 * @param author авторство merge-коммита — берётся у того же хранилища, что
 * и обычный коммит (`FR-22`); хостинг обязан спросить его до входа сюда
 * @param onFinished слияние завершено или отменено: хостинг возвращается на
 * экран репозитория и зовёт `mergeFinished()`
 */
@Composable
fun ConflictScreen(
    model: ConflictScreenModel,
    fileName: String,
    author: CommitAuthor,
    onFinished: () -> Unit,
) {
    LaunchedEffect(model) { model.start() }

    val phase = model.phase
    if (phase is ConflictScreenPhase.Merged || phase is ConflictScreenPhase.Aborted) {
        LaunchedEffect(phase) { onFinished() }
    }

    Surface(color = AdocTheme.colors.ground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            ConflictAppBar(
                fileName = fileName,
                counter = model.hunkCounter,
                onAbort = model::abortRequested,
            )
            ChromeDivider()

            if (phase is ConflictScreenPhase.Resolving && phase.failure != null) {
                FailureBanner(text = phase.failure)
            }

            val hunk = model.hunks.getOrNull(model.currentIndex)
            val choice = model.choices.getOrNull(model.currentIndex)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (hunk != null) {
                    SideBlock(
                        label = "ЛОКАЛЬНО · " + (hunk.oursLabel ?: "HEAD"),
                        labelColor = AdocTheme.colors.accentText,
                        lines = hunk.ours,
                        selected = choice == ConflictChoice.Ours || choice == ConflictChoice.Both,
                    )
                    SideBlock(
                        label = (hunk.theirsLabel ?: "ORIGIN").uppercase(),
                        labelColor = AdocTheme.colors.accentSecondary,
                        lines = hunk.theirs,
                        selected = choice == ConflictChoice.Theirs || choice == ConflictChoice.Both,
                    )
                }

                Text(
                    text = "РЕЗУЛЬТАТ",
                    style = adocTextStyle(AdocTypography.sectionLabel),
                    color = AdocTheme.colors.textFaint,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AdocTheme.colors.sunken)
                        .border(1.dp, AdocTheme.colors.borderObject)
                        .padding(12.dp),
                ) {
                    Text(
                        text = model.preview,
                        style = adocTextStyle(AdocTypography.editorCode),
                        color = AdocTheme.colors.accentText,
                    )
                }
            }

            ActionBar(
                choice = choice,
                allResolved = model.allResolved,
                isLastHunk = model.currentIndex >= model.hunks.lastIndex,
                onChoose = model::choose,
                onNext = model::nextHunk,
                onFinish = { model.finishRequested(author) },
            )
        }
    }
}

/** App bar «04»: имя файла, номер участка, отмена слияния. */
@Composable
private fun ConflictAppBar(fileName: String, counter: String, onAbort: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .statusBarsPadding()
            .height(52.dp)
            .padding(start = 4.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(role = Role.Button, onClick = onAbort),
        ) {
            Text(
                text = "×",
                style = adocTextStyle(AdocTypography.screenTitle),
                color = AdocTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Конфликт слияния",
                style = adocTextStyle(AdocTypography.screenTitle),
                color = AdocTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$fileName · участок $counter",
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .border(1.dp, AdocTheme.colors.accentTrack)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                text = counter,
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.accentText,
            )
        }
    }
}

/** Блок одной стороны участка: подпись, строки, акцентная рамка у выбранной. */
@Composable
private fun SideBlock(
    label: String,
    labelColor: androidx.compose.ui.graphics.Color,
    lines: List<String>,
    selected: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AdocTheme.colors.accentSelection else AdocTheme.colors.raised)
            .border(
                1.dp,
                if (selected) AdocTheme.colors.accent else AdocTheme.colors.borderChrome,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = adocTextStyle(AdocTypography.metadata),
                color = labelColor,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                Text(
                    text = "ВЫБРАНО",
                    style = adocTextStyle(AdocTypography.metadata),
                    color = AdocTheme.colors.accentText,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AdocTheme.colors.borderList),
        )
        Text(
            text = lines.joinToString("\n").ifEmpty { "(пусто)" },
            style = adocTextStyle(AdocTypography.editorCode),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Панель действий «04»: три варианта выбора и первичная кнопка перехода. */
@Composable
private fun ActionBar(
    choice: ConflictChoice?,
    allResolved: Boolean,
    isLastHunk: Boolean,
    onChoose: (ConflictChoice) -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChoiceCell("ЛОКАЛЬНОЕ", choice == ConflictChoice.Ours, Modifier.weight(1f)) {
            onChoose(ConflictChoice.Ours)
        }
        ChoiceCell("УДАЛЁННОЕ", choice == ConflictChoice.Theirs, Modifier.weight(1f)) {
            onChoose(ConflictChoice.Theirs)
        }
        ChoiceCell("ОБА", choice == ConflictChoice.Both, Modifier.weight(1f)) {
            onChoose(ConflictChoice.Both)
        }

        // На последнем участке кнопка становится завершением слияния и
        // доступна только когда разрешены все участки (FR-24).
        val finishing = isLastHunk
        val enabled = if (finishing) allResolved else choice != null
        Box(
            modifier = Modifier
                .height(46.dp)
                .background(if (enabled) AdocTheme.colors.accent else AdocTheme.colors.accentTrack)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = if (finishing) onFinish else onNext,
                ),
        ) {
            Text(
                text = if (finishing) "ЗАВЕРШИТЬ" else "ДАЛЕЕ",
                style = adocTextStyle(AdocTypography.buttonLabel),
                color = if (enabled) AdocTheme.colors.onAccent else AdocTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun ChoiceCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .background(if (selected) AdocTheme.colors.accentSelection else AdocTheme.colors.ground)
            .border(1.dp, if (selected) AdocTheme.colors.accent else AdocTheme.colors.borderObject)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.metadata),
            color = if (selected) AdocTheme.colors.accentText else AdocTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/** Плашка отказа — язык плашек экранов Git. */
@Composable
private fun FailureBanner(text: String) {
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

@Composable
private fun ChromeDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AdocTheme.colors.borderChrome),
    )
}
