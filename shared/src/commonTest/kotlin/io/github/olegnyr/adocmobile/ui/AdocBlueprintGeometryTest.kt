package io.github.olegnyr.adocmobile.ui

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Критерий приёмки `TC-6` фичи 002-design-system, автоматизируемая часть.
 *
 * Метки «+» выносятся за рамку, и весь вопрос критерия в том, не окажутся ли
 * они за пределами компонента — тогда у края экрана их обрежет. Геометрия
 * вынесена в чистую функцию именно ради этой проверки: она отвечает на вопрос
 * числом, а не снимком экрана.
 */
class AdocBlueprintGeometryTest {

    private val overhang = 6f

    @Test
    fun TC_6_frameIsInsetByOverhang() {
        val geometry = blueprintGeometry(Size(200f, 120f), overhang)

        assertEquals(overhang, geometry.frameLeft)
        assertEquals(overhang, geometry.frameTop)
        assertEquals(200f - overhang, geometry.frameRight)
        assertEquals(120f - overhang, geometry.frameBottom)
    }

    @Test
    fun TC_6_marksSitOnFrameCornersAndStayInsideComponent() {
        val size = Size(200f, 120f)
        val geometry = blueprintGeometry(size, overhang)

        assertEquals(4, geometry.marks.size, "меток должно быть четыре, по одной на угол")

        val corners = setOf(
            geometry.frameLeft to geometry.frameTop,
            geometry.frameRight to geometry.frameTop,
            geometry.frameLeft to geometry.frameBottom,
            geometry.frameRight to geometry.frameBottom,
        )
        assertEquals(corners, geometry.marks.map { it.x to it.y }.toSet(), "метки не на углах рамки")

        // Главное в критерии: штрих метки не выходит за пределы компонента.
        // Пока это так, блок можно прижать вплотную к краю экрана.
        for (mark in geometry.marks) {
            assertTrue(mark.x - overhang >= 0f, "метка выходит за левый край: ${mark.x - overhang}")
            assertTrue(mark.y - overhang >= 0f, "метка выходит за верхний край: ${mark.y - overhang}")
            assertTrue(mark.x + overhang <= size.width, "метка выходит за правый край")
            assertTrue(mark.y + overhang <= size.height, "метка выходит за нижний край")
        }
    }

    @Test
    fun TC_6_contentInsetDoesNotDependOnPosition() {
        // Внутренние отступы содержимого считаются от рамки, а не от края
        // компонента, поэтому вынос меток на них не влияет — блок у края экрана
        // и блок в середине выглядят одинаково.
        val wide = blueprintGeometry(Size(400f, 120f), overhang)
        val narrow = blueprintGeometry(Size(200f, 120f), overhang)

        assertEquals(wide.frameLeft, narrow.frameLeft)
        assertEquals(wide.frameTop, narrow.frameTop)
    }

    @Test
    fun TC_6_degenerateSizeProducesNoFrame() {
        // Компонент уже, чем двойной вынос: рисовать нечего, и это не должно
        // превращаться в рамку с отрицательной шириной.
        assertTrue(blueprintGeometry(Size(8f, 120f), overhang).isEmpty)
        assertTrue(blueprintGeometry(Size(200f, 0f), overhang).isEmpty)
    }
}
