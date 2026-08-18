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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.olegnyr.adocmobile.git.SshKeyInfo
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock

/**
 * Экран SSH-ключа — раздел SSH макета «06 · Настройки» (`FR-27`).
 *
 * Показывает подпись ключа и отпечаток `SHA256:…` для сверки с тем, что
 * покажет сервер, даёт скопировать публичную строку в буфер и создать или
 * удалить ключ. Приватная часть на экран не попадает никогда: она живёт
 * только в хранилище секретов (`ADR-015`).
 *
 * В макете «06» раздел SSH намечен строкой настройки; полный экран с
 * блоком ключа — дополнение в языке дизайна (чертёжный блок, моноширинный
 * ключ), отступление названо в журнале `SL-17`.
 *
 * @param deviceName подпись ключа по умолчанию — имя устройства; владелец
 * увидит её в списке ключей сервера
 * @param onCopy платформенное копирование в буфер обмена: системный сервис
 * вызывает хостинг, модель его не знает
 */
@Composable
fun SshKeyScreen(
    model: SshKeyScreenModel,
    deviceName: String,
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
) {
    LaunchedEffect(model) { model.start() }

    val state = model.state

    Surface(color = AdocTheme.colors.ground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            SshKeyAppBar(onBack = onBack)
            ChromeDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (state) {
                    is SshKeyScreenState.Loading -> Unit

                    is SshKeyScreenState.Working -> Text(
                        text = "РАБОТАЮ…",
                        style = adocTextStyle(AdocTypography.sectionLabel),
                        color = AdocTheme.colors.textFaint,
                    )

                    is SshKeyScreenState.NoKey -> NoKeyBlock(
                        failure = state.failure,
                        onGenerate = { model.generateRequested(deviceName) },
                    )

                    is SshKeyScreenState.Ready -> KeyBlock(
                        key = state.key,
                        notice = state.notice,
                        onCopy = { model.copyRequested(onCopy) },
                        onReplace = { model.generateRequested(deviceName) },
                        onDelete = model::deleteRequested,
                    )

                    is SshKeyScreenState.ConfirmingReplace -> KeyBlock(
                        key = state.key,
                        notice = null,
                        onCopy = { model.copyRequested(onCopy) },
                        onReplace = {},
                        onDelete = {},
                    )

                    is SshKeyScreenState.ConfirmingDelete -> KeyBlock(
                        key = state.key,
                        notice = null,
                        onCopy = { model.copyRequested(onCopy) },
                        onReplace = {},
                        onDelete = {},
                    )
                }
            }
        }
    }

    when (state) {
        is SshKeyScreenState.ConfirmingReplace -> Confirmation(
            label = "ЗАМЕНИТЬ КЛЮЧ",
            message = "Прежний ключ перестанет подходить серверу. Новый нужно будет прописать в настройках " +
                "репозитория заново — до этого синхронизация по SSH работать не будет.",
            confirmLabel = "ЗАМЕНИТЬ",
            onConfirm = model::replaceConfirmed,
            onDismiss = model::replaceDismissed,
        )

        is SshKeyScreenState.ConfirmingDelete -> Confirmation(
            label = "УДАЛИТЬ КЛЮЧ",
            message = "Приватный ключ будет стёрт с устройства безвозвратно. Синхронизация по SSH перестанет " +
                "работать, пока вы не создадите новый ключ и не пропишете его на сервере.",
            confirmLabel = "УДАЛИТЬ",
            onConfirm = model::deleteConfirmed,
            onDismiss = model::deleteDismissed,
        )

        else -> Unit
    }
}

@Composable
private fun SshKeyAppBar(onBack: () -> Unit) {
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
            text = "SSH-ключ",
            style = adocTextStyle(AdocTypography.screenTitle),
            color = AdocTheme.colors.textPrimary,
        )
    }
}

/** Состояние «ключа нет»: объяснение и кнопка создания. */
@Composable
private fun NoKeyBlock(failure: String?, onGenerate: () -> Unit) {
    AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "КЛЮЧА НЕТ",
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = AdocTheme.colors.textFaint,
        )
        Text(
            text = "Создайте ключ ed25519 на этом устройстве. Приватная часть останется здесь и " +
                "никуда не отправляется; публичную вы пропишете в настройках репозитория.",
            style = adocTextStyle(AdocTypography.body),
            color = AdocTheme.colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
        )
        failure?.let {
            Text(
                text = it,
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.textSecondary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        PrimaryButton(label = "СОЗДАТЬ КЛЮЧ", onClick = onGenerate)
    }
}

/** Блок ключа: подпись, отпечаток, сама строка и действия. */
@Composable
private fun KeyBlock(
    key: SshKeyInfo,
    notice: String?,
    onCopy: () -> Unit,
    onReplace: () -> Unit,
    onDelete: () -> Unit,
) {
    AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "КЛЮЧ УСТРОЙСТВА",
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = AdocTheme.colors.textFaint,
        )
        Text(
            text = key.publicKeyLine.substringAfterLast(' ').ifEmpty { "ed25519" },
            style = adocTextStyle(AdocTypography.editorCode),
            color = AdocTheme.colors.accentText,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = key.fingerprint,
            style = adocTextStyle(AdocTypography.metadata),
            color = AdocTheme.colors.textMuted,
            modifier = Modifier.padding(top = 4.dp),
        )

        Text(
            text = "ПУБЛИЧНЫЙ КЛЮЧ",
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = AdocTheme.colors.textFaint,
            modifier = Modifier.padding(top = 14.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(AdocTheme.colors.sunken)
                .border(1.dp, AdocTheme.colors.borderObject)
                .padding(12.dp),
        ) {
            Text(
                text = key.publicKeyLine,
                style = adocTextStyle(AdocTypography.editorCode),
                color = AdocTheme.colors.textSecondary,
            )
        }

        notice?.let {
            Text(
                text = it,
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.accentText,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(label = "КОПИРОВАТЬ", onClick = onCopy, modifier = Modifier.weight(1f))
            SecondaryButton(label = "ЗАМЕНИТЬ", onClick = onReplace, modifier = Modifier.weight(1f))
            SecondaryButton(label = "УДАЛИТЬ", onClick = onDelete, modifier = Modifier.weight(1f))
        }
    }
}

/** Подтверждение разрушающего действия — чертёжный блок поверх экрана. */
@Composable
private fun Confirmation(
    label: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(modifier = Modifier.padding(24.dp)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(label = "ОТМЕНА", onClick = onDismiss, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .background(AdocTheme.colors.accent)
                            .clickable(role = Role.Button, onClick = onConfirm),
                    ) {
                        Text(
                            text = confirmLabel,
                            style = adocTextStyle(AdocTypography.buttonLabel),
                            color = AdocTheme.colors.onAccent,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }
    }
}

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
private fun SecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(46.dp)
            .border(1.dp, AdocTheme.colors.borderObject)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.metadata),
            color = AdocTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
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
