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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.olegnyr.adocmobile.git.FileStatus
import io.github.olegnyr.adocmobile.git.StatusEntry
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock

/**
 * Экран коммита — макет «03» (`FR-17`…`FR-19`, `FR-22`).
 *
 * Индекс с чекбоксами и счётчиками диффа, поле сообщения на свободную
 * высоту, счётчик `50 / 72 симв.`, переключатель «Отправить в origin после
 * коммита» и пара `ОТМЕНА` / `COMMIT & PUSH`.
 * Подпись `подпись: gpg off` — честная константа: подписи коммитов в
 * итерации нет (`setSign(false)`).
 *
 * Диалог авторства (`FR-22`, OQ-8) — чертёжный блок поверх экрана;
 * дополнение макета в языке дизайна, отступление названо в журнале.
 */
/**
 * @param onCommitted коммит создан, экран закончил работу; параметр — текст
 * отказа push (`Done.pushFailure`), `null` — push прошёл или не запрашивался.
 * Хостинг обязан показать непустой текст на экране репозитория (`FR-21`):
 * отказ, съеденный навигацией, был блокером ревью E2.
 */
@Composable
fun CommitScreen(
    model: CommitScreenModel,
    branch: String,
    onCancel: () -> Unit,
    onCommitted: (pushFailure: String?) -> Unit,
) {
    LaunchedEffect(model) { model.start() }

    val phase = model.phase
    if (phase is CommitScreenPhase.Done) {
        LaunchedEffect(phase) { onCommitted(phase.pushFailure) }
    }

    Surface(color = AdocTheme.colors.ground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            CommitAppBar(branch = branch, onBack = onCancel)
            ChromeDivider()

            Text(
                text = model.indexLabel,
                style = adocTextStyle(AdocTypography.sectionLabel),
                color = AdocTheme.colors.textFaint,
                modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 8.dp),
            )
            Column {
                model.entries.forEach { entry ->
                    IndexRow(
                        entry = entry,
                        checked = entry.path in model.checkedPaths,
                        enabled = phase is CommitScreenPhase.Editing,
                        onToggle = { model.toggle(entry.path) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "СООБЩЕНИЕ",
                    style = adocTextStyle(AdocTypography.sectionLabel),
                    color = AdocTheme.colors.textFaint,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(AdocTheme.colors.raised)
                        .border(1.dp, AdocTheme.colors.borderObject),
                ) {
                    BasicTextField(
                        value = model.message,
                        onValueChange = { model.message = it },
                        enabled = phase is CommitScreenPhase.Editing,
                        textStyle = adocTextStyle(AdocTypography.editorCode)
                            .copy(color = AdocTheme.colors.textSecondary),
                        cursorBrush = SolidColor(AdocTheme.colors.accentText),
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = messageCounterLabel(model.message),
                        style = adocTextStyle(AdocTypography.metadata),
                        color = AdocTheme.colors.textFaint,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "подпись: gpg off",
                        style = adocTextStyle(AdocTypography.metadata),
                        color = AdocTheme.colors.textFaint,
                    )
                }
            }

            if (phase is CommitScreenPhase.Editing && phase.failure != null) {
                FailureBanner(text = phase.failure)
            }

            PushToggleRow(
                checked = model.pushAfterCommit,
                enabled = phase is CommitScreenPhase.Editing,
                onToggle = { model.pushAfterCommit = it },
            )

            Row(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SecondaryButton(label = "ОТМЕНА", onClick = onCancel)
                PrimaryButton(
                    label = if (model.pushAfterCommit) "COMMIT & PUSH" else "COMMIT",
                    enabled = phase is CommitScreenPhase.Editing,
                    onClick = model::commitRequested,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (phase is CommitScreenPhase.AwaitingAuthor) {
        AuthorDialog(model = model)
    }
}

/** App bar «03»: стрелка назад, заголовок, имя ветки справа. */
@Composable
private fun CommitAppBar(branch: String, onBack: () -> Unit) {
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
                .clickable(role = Role.Button, onClick = onBack),
        ) {
            Text(
                text = "‹",
                style = adocTextStyle(AdocTypography.screenTitle),
                color = AdocTheme.colors.textMuted,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            text = "Коммит",
            style = adocTextStyle(AdocTypography.screenTitle),
            color = AdocTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = branch,
            style = adocTextStyle(AdocTypography.metadata),
            color = AdocTheme.colors.accentText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Строка индекса: чекбокс, метка статуса, путь, счётчик диффа (`FR-17`). */
@Composable
private fun IndexRow(
    entry: StatusEntry,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SquareCheckbox(checked = checked)
        Text(
            text = entry.status.letter(),
            style = adocTextStyle(AdocTypography.metadata),
            color = when (entry.status) {
                FileStatus.Modified -> AdocTheme.colors.accentText
                FileStatus.Added -> AdocTheme.colors.accentSecondary
                FileStatus.Deleted, FileStatus.Untracked -> AdocTheme.colors.textFaint
            },
            modifier = Modifier.width(14.dp),
        )
        Text(
            text = entry.path,
            style = adocTextStyle(AdocTypography.listItem),
            color = if (checked) AdocTheme.colors.textPrimary else AdocTheme.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        diffCountLabel(entry)?.let { label ->
            Text(
                text = label,
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.textFaint,
            )
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AdocTheme.colors.borderList),
    )
}

/** Чекбокс описания дизайна: квадрат 18×18, галочка цветом фона на акценте. */
@Composable
private fun SquareCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .background(if (checked) AdocTheme.colors.accent else AdocTheme.colors.ground)
            .border(1.dp, if (checked) AdocTheme.colors.accent else AdocTheme.colors.borderObject),
    ) {
        if (checked) {
            Text(
                text = "✓",
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.onAccent,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * Диалог авторства перед первым коммитом (`FR-22`, OQ-8): чертёжный блок
 * поверх экрана, два поля, `СОХРАНИТЬ И ЗАКОММИТИТЬ`. Касание мимо блока
 * закрывает диалог без коммита.
 */
@Composable
private fun AuthorDialog(model: CommitScreenModel) {
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = model::authorDismissed,
        properties = PopupProperties(focusable = true),
    ) {
        Box(modifier = Modifier.padding(24.dp)) {
            AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "АВТОРСТВО КОММИТОВ",
                    style = adocTextStyle(AdocTypography.sectionLabel),
                    color = AdocTheme.colors.textFaint,
                )
                Text(
                    text = "Имя и почта попадут в историю Git. Запоминаются для этого репозитория.",
                    style = adocTextStyle(AdocTypography.body),
                    color = AdocTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                )
                DialogField(label = "ИМЯ", value = model.authorName, onValueChange = { model.authorName = it })
                Box(modifier = Modifier.height(12.dp))
                DialogField(label = "ПОЧТА", value = model.authorEmail, onValueChange = { model.authorEmail = it })
                Box(modifier = Modifier.height(16.dp))
                PrimaryButton(
                    label = "СОХРАНИТЬ И ЗАКОММИТИТЬ",
                    enabled = model.authorName.isNotBlank() && model.authorEmail.isNotBlank(),
                    onClick = model::authorConfirmed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DialogField(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = AdocTheme.colors.textFaint,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AdocTheme.colors.sunken)
                .border(1.dp, AdocTheme.colors.borderObject),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = adocTextStyle(AdocTypography.editorCode)
                    .copy(color = AdocTheme.colors.textPrimary),
                cursorBrush = SolidColor(AdocTheme.colors.accentText),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            )
        }
    }
}

/**
 * «Отправить в origin после коммита» (`FR-20`) — рамочный контейнер с
 * прямоугольным переключателем, как в макете «03»; `toggleable` несёт
 * состояние семантикой (`NFR-9`).
 */
@Composable
private fun PushToggleRow(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .border(1.dp, AdocTheme.colors.borderChrome)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Отправить в origin после коммита",
            style = adocTextStyle(AdocTypography.listItem),
            color = AdocTheme.colors.textParagraph,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(width = 38.dp, height = 22.dp)
                .background(if (checked) AdocTheme.colors.accentTrack else AdocTheme.colors.sunken)
                .border(1.dp, if (checked) AdocTheme.colors.accent else AdocTheme.colors.borderObject)
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(if (checked) AdocTheme.colors.accentText else AdocTheme.colors.textFaint),
            )
        }
    }
}

/** Плашка отказа — язык плашек экранов Git. */
@Composable
private fun FailureBanner(text: String) {
    Column(modifier = Modifier.fillMaxWidth().background(AdocTheme.colors.raised)) {
        ChromeDivider()
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
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .background(if (enabled) AdocTheme.colors.accent else AdocTheme.colors.accentTrack)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.buttonLabel),
            color = if (enabled) AdocTheme.colors.onAccent else AdocTheme.colors.textMuted,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 12.dp),
        )
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .border(1.dp, AdocTheme.colors.borderObject)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.buttonLabel),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
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
