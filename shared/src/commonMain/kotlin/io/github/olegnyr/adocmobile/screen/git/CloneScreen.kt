package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.git.CloneProgress
import io.github.olegnyr.adocmobile.git.RepositorySnapshot
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock

/**
 * Экран клонирования — макет «05» (`FR-3`, `FR-4`, `FR-5`, `FR-7`).
 *
 * Форма: URL, способ авторизации сегментами, ветка, папка, `depth 1`.
 * В E1 доступен только сегмент `БЕЗ АВТ.` — авторизация приходит этапами
 * E2/E4, и её сегменты показаны недоступными, а не спрятаны: состав формы
 * задан макетом, а бездействие сегмента честнее его отсутствия.
 * Ветка в макете — выпадающий список; до клона списка веток неоткуда взять,
 * поэтому в E1 это поле ввода — отступление названо в журнале слайса.
 *
 * Прогресс приёма объектов прижат к низу чертёжным блоком и существует
 * только во время операции; отказ — плашкой над кнопкой (`FR-7`).
 *
 * Экран строится только при наличии реализации шва: платформа без Git не
 * показывает Git-разделов вовсе (`FR-2`) — решение принимает хостинг экрана.
 *
 * @param onClose крестик в app bar — уход с экрана без операции
 * @param onCloned клон готов: хостинг забирает [RepositorySnapshot] и уводит
 * на экран репозитория
 */
@Composable
fun CloneScreen(
    model: CloneScreenModel,
    onClose: () -> Unit,
    onCloned: (RepositorySnapshot) -> Unit,
) {
    val phase = model.phase

    if (phase is CloneScreenPhase.Done) {
        // Уход с экрана — эффект, а не вызов в композиции: onCloned меняет
        // навигацию, а менять состояние во время построения кадра нельзя.
        LaunchedEffect(phase) { onCloned(phase.repository) }
    }

    Surface(color = AdocTheme.colors.ground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            CloneAppBar(onClose = onClose)
            ChromeDivider()

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                LabeledField(
                    label = "URL",
                    value = model.url,
                    onValueChange = { model.url = it },
                    enabled = phase !is CloneScreenPhase.Cloning,
                    keyboardOptions = fieldKeyboard(KeyboardType.Uri, ImeAction.Next),
                )

                AuthorizationSegments(
                    mode = model.authMode,
                    enabled = phase !is CloneScreenPhase.Cloning,
                    onSelect = { model.authMode = it },
                )

                if (model.authMode == CloneAuthMode.Token) {
                    LabeledField(
                        label = "ТОКЕН",
                        value = model.token,
                        onValueChange = { model.token = it },
                        enabled = phase !is CloneScreenPhase.Cloning,
                        keyboardOptions = fieldKeyboard(KeyboardType.Password, ImeAction.Next),
                        // Секрет маскируется на экране (NFR-8): токен не должен
                        // читаться через плечо, значение живёт только в модели.
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }

                LabeledField(
                    label = "ВЕТКА",
                    value = model.branch,
                    onValueChange = { model.branch = it },
                    enabled = phase !is CloneScreenPhase.Cloning,
                    placeholder = "ветка по умолчанию",
                    keyboardOptions = fieldKeyboard(KeyboardType.Ascii, ImeAction.Next),
                )

                LabeledField(
                    label = "ПАПКА",
                    value = model.directoryName,
                    onValueChange = { model.directoryName = it },
                    enabled = phase !is CloneScreenPhase.Cloning,
                    placeholder = model.suggestedDirectoryName.ifEmpty { null },
                    keyboardOptions = fieldKeyboard(KeyboardType.Ascii, ImeAction.Done),
                )

                DepthToggleRow(
                    checked = model.shallow,
                    enabled = phase !is CloneScreenPhase.Cloning,
                    onToggle = { model.shallow = it },
                )
            }

            // Блок прогресса прижат к низу, как в макете «05»: вне прокрутки,
            // над кнопкой — форма может скроллиться, прогресс всегда виден.
            if (phase is CloneScreenPhase.Cloning) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    CloneProgressBlock(progress = phase.progress)
                }
            }

            if (phase is CloneScreenPhase.Editing && phase.failure != null) {
                FailureBanner(text = phase.failure)
            }

            Box(modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 8.dp)) {
                PrimaryButton(
                    label = "КЛОНИРОВАТЬ",
                    enabled = phase !is CloneScreenPhase.Cloning,
                    onClick = model::cloneRequested,
                )
            }
        }
    }
}

/**
 * Клавиатура полей формы (`NFR-7`): без автокапитализации и автокоррекции —
 * адреса, ветки и имена папок не человеческий текст, и «исправленный» IME
 * URL — это несуществующий репозиторий.
 */
private fun fieldKeyboard(type: KeyboardType, action: ImeAction) = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = type,
    imeAction = action,
)

/**
 * App bar «05»: крестик закрытия и заголовок экрана.
 * Отступ статус-бара внутри фона `chrome` — панель уходит под него
 * (edge-to-edge, образец — app bar редактора).
 */
@Composable
private fun CloneAppBar(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .statusBarsPadding()
            .height(52.dp)
            .padding(start = 4.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CloseButton(onClick = onClose)
        Text(
            text = "Клонировать репозиторий",
            style = adocTextStyle(AdocTypography.screenTitle),
            color = AdocTheme.colors.textPrimary,
        )
    }
}

/**
 * Крестик закрытия: касаемая площадка 44×44, знак `×` текстом — набора иконок
 * в проекте нет, приём тот же, что у кнопки меню редактора (отступление
 * названо в журнале 005, замена придёт с набором иконок).
 */
@Composable
private fun CloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = "×",
            style = adocTextStyle(AdocTypography.screenTitle),
            color = AdocTheme.colors.textMuted,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Поле формы: служебная метка над рамочным полем (компонент «Поле ввода»
 * описания дизайна). В фокусе рамка акцентная; содержимое моноширинное.
 *
 * `value`/`onValueChange`, а не `TextFieldState`: запрет CLAUDE.md касается
 * полотна редактора с большими документами, а это однострочные поля формы,
 * чьё состояние живёт в модели экрана.
 */
@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = AdocTheme.colors.textFaint,
        )

        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(AdocTheme.colors.raised)
                .border(
                    width = 1.dp,
                    color = if (focused) AdocTheme.colors.accent else AdocTheme.colors.borderObject,
                ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                keyboardOptions = keyboardOptions,
                visualTransformation = visualTransformation,
                interactionSource = interaction,
                textStyle = adocTextStyle(AdocTypography.editorCode)
                    .copy(color = AdocTheme.colors.textPrimary),
                cursorBrush = SolidColor(AdocTheme.colors.accentText),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 13.dp),
            )
            if (value.isEmpty() && !placeholder.isNullOrEmpty()) {
                Text(
                    text = placeholder,
                    style = adocTextStyle(AdocTypography.editorCode),
                    color = AdocTheme.colors.textFaint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
                )
            }
        }
    }
}

/**
 * Сегменты авторизации из макета «05»: в IT2 доступны `ТОКЕН` (`FR-26`) и
 * `БЕЗ АВТ.`; `SSH-КЛЮЧ` показан недоступным до этапа E4 (OQ-3) — состав
 * формы сохраняется, бездействие честнее отсутствия.
 */
@Composable
private fun AuthorizationSegments(
    mode: CloneAuthMode,
    enabled: Boolean,
    onSelect: (CloneAuthMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "АВТОРИЗАЦИЯ",
            style = adocTextStyle(AdocTypography.sectionLabel),
            color = AdocTheme.colors.textFaint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentCell(
                label = "SSH-КЛЮЧ",
                selected = false,
                enabled = false,
                modifier = Modifier.weight(1f),
                onClick = {},
            )
            SegmentCell(
                label = "ТОКЕН",
                selected = mode == CloneAuthMode.Token,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(CloneAuthMode.Token) },
            )
            SegmentCell(
                label = "БЕЗ АВТ.",
                selected = mode == CloneAuthMode.None,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(CloneAuthMode.None) },
            )
        }
    }
}

/**
 * Ячейка сегментированного переключателя — компонент описания дизайна,
 * высота 42. Состояние кодируется и семантикой (`selectable`: выбран /
 * недоступен), а не только цветом (`NFR-9`); недоступная ячейка не касаема.
 */
@Composable
private fun SegmentCell(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val border = if (selected) AdocTheme.colors.accent else AdocTheme.colors.borderChrome
    val background = if (selected) AdocTheme.colors.accentSelection else AdocTheme.colors.ground
    val text = when {
        selected -> AdocTheme.colors.accentText
        enabled -> AdocTheme.colors.textMuted
        else -> AdocTheme.colors.textFaint
    }
    Box(
        modifier = modifier
            .height(42.dp)
            .background(background)
            .border(1.dp, border)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.metadata),
            color = text,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * Строка с опцией `depth 1` (`FR-4`): рамочный контейнер с прямоугольным
 * переключателем. `toggleable`, а не `clickable`: скринридеру нужно само
 * состояние «вкл/выкл», роли без значения его не несут (`NFR-9`).
 */
@Composable
private fun DepthToggleRow(checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AdocTheme.colors.chrome)
            .border(1.dp, AdocTheme.colors.borderChrome)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Только последний коммит (depth 1)",
            style = adocTextStyle(AdocTypography.listItem),
            color = AdocTheme.colors.textParagraph,
            modifier = Modifier.weight(1f),
        )
        SquareToggle(checked = checked)
    }
}

/** Переключатель описания дизайна: трек 38×22, бегунок 16×16, всё прямоугольное. */
@Composable
private fun SquareToggle(checked: Boolean) {
    val track = if (checked) AdocTheme.colors.accentTrack else AdocTheme.colors.sunken
    val border = if (checked) AdocTheme.colors.accent else AdocTheme.colors.borderObject
    val knob = if (checked) AdocTheme.colors.accentText else AdocTheme.colors.textFaint
    Box(
        modifier = Modifier
            .size(width = 38.dp, height = 22.dp)
            .background(track)
            .border(1.dp, border)
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .background(knob),
        )
    }
}

/**
 * Чертёжный блок прогресса из макета «05»: фаза с процентом, полоса 6 px,
 * числа и скорость (`FR-5`). Проценты и скорость честно опциональны —
 * фазы без объёма показывают только счёт объектов.
 */
@Composable
private fun CloneProgressBlock(progress: CloneProgress?) {
    AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = progress?.phase?.uppercase().orEmpty().ifEmpty { "ПОДГОТОВКА" },
                style = adocTextStyle(AdocTypography.sectionLabel),
                color = AdocTheme.colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            progress?.percent?.let { percent ->
                Text(
                    text = "$percent%",
                    style = adocTextStyle(AdocTypography.sectionLabel),
                    color = AdocTheme.colors.accentText,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(6.dp)
                .background(AdocTheme.colors.sunken),
        ) {
            val fraction = (progress?.percent ?: 0) / 100f
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(AdocTheme.colors.accent),
                )
            }
        }

        progress?.let {
            val total = it.totalObjects?.let { t -> " / $t" }.orEmpty()
            val speed = it.objectsPerSecond?.let { s -> " · $s об/с" }.orEmpty()
            Text(
                text = "${it.completedObjects}$total объектов$speed",
                style = adocTextStyle(AdocTypography.metadata),
                color = AdocTheme.colors.textFaint,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Плашка отказа (`FR-7`) — язык плашек редактора: raised-поверхность, текст-метаданные. */
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

/**
 * Первичная кнопка — локальная копия кнопки редактора с состоянием
 * недоступности: во время операции кнопка приглушена и не касаема.
 * Общая кнопка для всех экранов — кандидат на вынос при этапе UI.
 */
@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(if (enabled) AdocTheme.colors.accent else AdocTheme.colors.accentTrack)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Text(
            text = label,
            style = adocTextStyle(AdocTypography.buttonLabel),
            color = if (enabled) AdocTheme.colors.onAccent else AdocTheme.colors.textMuted,
            modifier = Modifier.align(Alignment.Center),
        )
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
