package io.github.olegnyr.adocmobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.theme.AdocTheme

/** Размеры чертёжного блока. Значения — из раздела «Метрики» описания дизайна. */
object AdocBlueprint {

    /**
     * Вынос метки «+» за рамку.
     *
     * Он же задаёт внешний отступ компонента: метка центрируется на углу рамки
     * и выступает наружу ровно на это расстояние, поэтому рамка отодвинута от
     * края компонента на столько же. Так метки остаются внутри границ
     * компонента и не обрезаются, когда блок прижат к краю экрана (`TC-6`).
     */
    val markOverhang: Dp = 6.dp

    /** Толщина рамки и штриха меток: линия в 1 px, как во всём интерфейсе. */
    val strokeWidth: Dp = 1.dp

    /** Внутренний отступ содержимого от рамки. */
    val contentPadding: Dp = 14.dp
}

/**
 * Геометрия блока: где проходит рамка и где стоят центры четырёх меток.
 *
 * Вынесена из отрисовки чистой функцией, чтобы `TC-6` проверялся тестом, а не
 * только глазами: весь риск критерия — уедет ли метка за пределы компонента.
 */
data class AdocBlueprintGeometry(
    val frameLeft: Float,
    val frameTop: Float,
    val frameRight: Float,
    val frameBottom: Float,
    val marks: List<Offset>,
) {
    /** Рисовать нечего: компонент меньше, чем нужно рамке. */
    val isEmpty: Boolean get() = frameRight <= frameLeft || frameBottom <= frameTop
}

/** Рамка, отодвинутая от края на [overhang], и метки с центрами на её углах. */
fun blueprintGeometry(size: Size, overhang: Float): AdocBlueprintGeometry {
    val left = overhang
    val top = overhang
    val right = size.width - overhang
    val bottom = size.height - overhang
    val marks = if (right <= left || bottom <= top) {
        emptyList()
    } else {
        listOf(
            Offset(left, top),
            Offset(right, top),
            Offset(left, bottom),
            Offset(right, bottom),
        )
    }
    return AdocBlueprintGeometry(left, top, right, bottom, marks)
}

/**
 * Чертёжный блок: контейнер с рамкой и четырьмя знаками `+` по углам.
 *
 * Носители по описанию дизайна — карточка состояния ветки, блок прогресса
 * клонирования, плейсхолдер диаграммы в превью.
 */
@Composable
fun AdocBlueprintBlock(
    modifier: Modifier = Modifier,
    background: Color = AdocTheme.colors.raised,
    borderColor: Color = AdocTheme.colors.borderObject,
    markColor: Color = AdocTheme.colors.accent,
    contentPadding: Dp = AdocBlueprint.contentPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    val overhang = AdocBlueprint.markOverhang
    val stroke = AdocBlueprint.strokeWidth

    Column(
        modifier = modifier
            .drawBehind {
                drawBlueprint(
                    geometry = blueprintGeometry(size, overhang.toPx()),
                    overhang = overhang.toPx(),
                    stroke = stroke.toPx(),
                    background = background,
                    borderColor = borderColor,
                    markColor = markColor,
                )
            }
            // Отступ содержимого считается от рамки: сначала вынос меток,
            // затем внутреннее поле блока. Поэтому блок у края экрана и блок
            // в середине выглядят одинаково.
            .padding(overhang + contentPadding),
        content = content,
    )
}

private fun DrawScope.drawBlueprint(
    geometry: AdocBlueprintGeometry,
    overhang: Float,
    stroke: Float,
    background: Color,
    borderColor: Color,
    markColor: Color,
) {
    if (geometry.isEmpty) return

    val topLeft = Offset(geometry.frameLeft, geometry.frameTop)
    val frameSize = Size(
        geometry.frameRight - geometry.frameLeft,
        geometry.frameBottom - geometry.frameTop,
    )

    drawRect(color = background, topLeft = topLeft, size = frameSize)
    drawRect(color = borderColor, topLeft = topLeft, size = frameSize, style = Stroke(stroke))

    for (mark in geometry.marks) {
        drawLine(
            color = markColor,
            start = Offset(mark.x - overhang, mark.y),
            end = Offset(mark.x + overhang, mark.y),
            strokeWidth = stroke,
        )
        drawLine(
            color = markColor,
            start = Offset(mark.x, mark.y - overhang),
            end = Offset(mark.x, mark.y + overhang),
            strokeWidth = stroke,
        )
    }
}
