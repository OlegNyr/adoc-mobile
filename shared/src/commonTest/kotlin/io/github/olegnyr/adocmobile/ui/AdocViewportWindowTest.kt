package io.github.olegnyr.adocmobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Геометрия видимого окна — слайс `SL-5` фичи 001-syntax-highlighting (`FR-26`).
 *
 * Окно шире экрана на [WINDOW_MARGIN_LINES] в каждую сторону и живёт, пока
 * видимая область не подошла к его краю ближе [WINDOW_SLACK_LINES] строк —
 * прокрутка внутри запаса не меняет окно и не порождает новой трансформации.
 */
class AdocViewportWindowTest {

    @Test
    fun windowIsWiderThanTheViewportByTheMargin() {
        val window = windowAround(firstVisible = 100, lastVisible = 130, lastLineIndex = 1000)

        assertEquals(LineWindow(100 - WINDOW_MARGIN_LINES, 130 + WINDOW_MARGIN_LINES), window)
    }

    @Test
    fun windowIsClampedToTheDocumentEdges() {
        assertEquals(
            LineWindow(0, 10 + WINDOW_MARGIN_LINES),
            windowAround(firstVisible = 5, lastVisible = 10, lastLineIndex = 1000),
        )
        assertEquals(
            LineWindow(990 - WINDOW_MARGIN_LINES, 1000),
            windowAround(firstVisible = 990, lastVisible = 1000, lastLineIndex = 1000),
        )
        // Документ короче запаса — окно накрывает его целиком и не выходит за края.
        assertEquals(LineWindow(0, 8), windowAround(firstVisible = 0, lastVisible = 8, lastLineIndex = 8))
    }

    @Test
    fun scrollingInsideTheSlackKeepsTheSameWindow() {
        val window = windowAround(firstVisible = 100, lastVisible = 130, lastLineIndex = 1000)

        // Прокрутка на десяток строк: запас не изношен, окно то же самое —
        // возвращается тот же экземпляр, состояние композиции не будится.
        assertSame(window, refreshedWindow(window, firstVisible = 110, lastVisible = 140, lastLineIndex = 1000))
        assertSame(window, refreshedWindow(window, firstVisible = 90, lastVisible = 120, lastLineIndex = 1000))
    }

    @Test
    fun approachingTheWindowEdgeRefreshesIt() {
        val window = windowAround(firstVisible = 100, lastVisible = 130, lastLineIndex = 1000)

        // Видимая область в WINDOW_SLACK_LINES от нижнего края окна — износ.
        val nearBottom = window.lastLine - WINDOW_SLACK_LINES + 1
        assertFalse(window.covers(nearBottom - 30, nearBottom, lastLineIndex = 1000))

        val refreshed = refreshedWindow(window, nearBottom - 30, nearBottom, lastLineIndex = 1000)
        assertEquals(windowAround(nearBottom - 30, nearBottom, 1000), refreshed)
    }

    @Test
    fun windowEdgeAtTheDocumentEdgeDoesNotWearOut() {
        // Окно упёрлось в конец документа: снизу строк больше нет, и близость
        // к краю окна не повод пересчитывать его.
        val window = windowAround(firstVisible = 990, lastVisible = 1000, lastLineIndex = 1000)

        assertTrue(window.covers(firstVisible = 995, lastVisible = 1000, lastLineIndex = 1000))
        assertSame(window, refreshedWindow(window, 995, 1000, 1000))
    }

    @Test
    fun visibleAreaOutsideTheWindowAlwaysRefreshes() {
        val window = windowAround(firstVisible = 100, lastVisible = 130, lastLineIndex = 10_000)

        // Резкий прыжок прокрутки: видимая область целиком вне окна.
        assertFalse(window.covers(firstVisible = 5000, lastVisible = 5030, lastLineIndex = 10_000))
        assertEquals(
            windowAround(5000, 5030, 10_000),
            refreshedWindow(window, 5000, 5030, 10_000),
        )
    }
}
