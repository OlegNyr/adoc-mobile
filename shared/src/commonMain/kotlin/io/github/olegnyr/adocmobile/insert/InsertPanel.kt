package io.github.olegnyr.adocmobile.insert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle

/**
 * Панель быстрой вставки — ряд кнопок между полотном редактора и клавиатурой
 * по макету «02», слайс `SL-1` фичи 006-quick-insert-panel.
 *
 * Состав и порядок кнопок — `FR-3`: `=`, `==`, `*B*`, `_I_`, `` `c` ``, маркер
 * списка, `link`, `img` и `›` последней. Ряд прокручивается горизонтально,
 * если не умещается (`FR-4`). Метрики и цвета — из хэндоффа, экран «02»:
 * кнопка 44 × 38 (`›` — 30), подпись JetBrains Mono, фон панели — роль
 * `toolbar`, рамка кнопки — `borderObject`, `=`/`==` — акцентным текстом,
 * `›` — приглушённым (`FR-5`).
 *
 * `WindowInsets.ime` панель учитывает сама: контейнер несёт `imePadding` после
 * `navigationBarsPadding`, поэтому она стоит вплотную к верхней кромке
 * клавиатуры, а при закрытой — над навигационной панелью; фон под вырезами
 * дорисован (`FR-1`, `NFR-7`). Экрану-владельцу добавлять свой `imePadding`
 * над панелью не нужно — и нельзя, иначе зазор удвоится.
 *
 * Видимость панели решает экран, не панель: по решению `OQ-3` она показывается
 * при фокусе поля ввода и отсутствует на вкладке превью и без документа
 * (`FR-13`).
 *
 * Касание кнопки не забирает фокус у поля — [Box] с [clickable] не фокусируем
 * касанием, поэтому клавиатура остаётся открытой и набор продолжается в
 * позиции каретки из заготовки (`FR-12`).
 *
 * Вставку несёт весь базовый ряд (`SL-2`, решения `OQ-2` и `OQ-5`); одна
 * кнопка без действия — раскрытие `›`, его подключает `SL-3` (`OQ-1`).
 *
 * @param state единственный `TextFieldState` экрана (`FR-6`) — тот же
 * экземпляр, что у полотна редактора; своего состояния текста панель не
 * заводит.
 */
@Composable
fun InsertPanel(state: TextFieldState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Верхняя граница панели — как в бандле: 1 px цветом границы хрома.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AdocTheme.colors.borderChrome),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AdocTheme.colors.toolbar)
                // Порядок обязателен: сначала фон (он дорисовывается под
                // вырезы), затем навигационная панель, затем IME. imePadding
                // считает уже отнятое и добавляет разницу — вместе выходит
                // max(навигация, клавиатура), без двойного зазора.
                .navigationBarsPadding()
                .imePadding()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelButton(
                label = "=",
                description = "Заголовок документа",
                labelColor = AdocTheme.colors.accentText,
                onTap = { state.applyInsert(InsertConstructs.documentTitle) },
            )
            PanelButton(
                label = "==",
                description = "Заголовок раздела",
                labelColor = AdocTheme.colors.accentText,
                onTap = { state.applyInsert(InsertConstructs.sectionTitle) },
            )
            PanelButton(
                label = "*B*",
                description = "Полужирный",
                labelColor = AdocTheme.colors.textSecondary,
                fontWeight = FontWeight.Bold,
                onTap = { state.applyInsert(InsertConstructs.bold) },
            )
            PanelButton(
                label = "_I_",
                description = "Курсив",
                labelColor = AdocTheme.colors.textSecondary,
                fontStyle = FontStyle.Italic,
                onTap = { state.applyInsert(InsertConstructs.italic) },
            )
            PanelButton(
                label = "`c`",
                description = "Моноширинный",
                labelColor = AdocTheme.colors.textSecondary,
                onTap = { state.applyInsert(InsertConstructs.monospace) },
            )
            PanelButton(
                label = "*",
                description = "Маркер списка",
                labelColor = AdocTheme.colors.textSecondary,
                onTap = { state.applyInsert(InsertConstructs.listItem) },
            )
            PanelButton(
                label = "link",
                description = "Ссылка",
                labelColor = AdocTheme.colors.textSecondary,
                fontSize = 12.sp,
                onTap = { state.applyInsert(InsertConstructs.link) },
            )
            PanelButton(
                label = "img",
                description = "Изображение",
                labelColor = AdocTheme.colors.textSecondary,
                fontSize = 12.sp,
                onTap = { state.applyInsert(InsertConstructs.image) },
            )
            // Раскрытие дополнительных конструкций — SL-3 (OQ-1).
            PanelButton(
                label = "›",
                description = "Дополнительные конструкции",
                labelColor = AdocTheme.colors.textFaint,
                fontSize = 12.sp,
                minWidth = 30.dp,
                onTap = null,
            )
        }
    }
}

/**
 * Кнопка панели: 44 × 38 по метрике дизайна, подпись JetBrains Mono
 * ([AdocTypography.editorCode]; кегль 12 для словесных подписей — из бандла),
 * рамка ролью `borderObject` (`FR-5`).
 *
 * Нажатие показано фоном роли `pressed` — «Состояния и взаимодействие»
 * описания дизайна описывают нажатие вторичных объектов фоном, рамка не
 * меняется; постоянная рамка и смена фона вместе дают отличие не только
 * цветом текста (`NFR-9`). Системная индикация (ripple) выключена —
 * «браузерные и системные дефолты не используются».
 *
 * У кнопки — русское семантическое описание конструкции, а не только глиф
 * (`NFR-9`): скринридер читает [description].
 *
 * @param onTap обработчик касания или `null`, пока конструкцию не подключил
 * свой слайс: кнопка нарисована по макету, но не кликабельна и не объявляет
 * себя кнопкой скринридеру — мёртвая кнопка хуже отсутствующей.
 */
@Composable
private fun PanelButton(
    label: String,
    description: String,
    labelColor: Color,
    fontSize: TextUnit = 13.sp,
    minWidth: Dp = 44.dp,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    onTap: (() -> Unit)?,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = if (pressed) AdocTheme.colors.pressed else Color.Transparent

    val clickModifier = if (onTap != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            role = Role.Button,
            onClick = onTap,
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .widthIn(min = minWidth)
            .height(38.dp)
            .background(fill)
            .border(1.dp, AdocTheme.colors.borderObject)
            .then(clickModifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val base = adocTextStyle(AdocTypography.editorCode)
        Text(
            text = label,
            style = base.copy(
                fontSize = fontSize,
                fontWeight = fontWeight ?: base.fontWeight,
                fontStyle = fontStyle ?: base.fontStyle,
            ),
            color = labelColor,
            maxLines = 1,
        )
    }
}
