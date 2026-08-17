package io.github.olegnyr.adocmobile.insert

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.TextRange
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 006-quick-insert-panel, слайс SL-1: `TC-1`, `TC-2`,
 * `TC-8` и сторож одного шага отмены `TC-3`.
 *
 * Проверяется логика вставки на настоящем [TextFieldState] — устройству здесь
 * делать нечего (`NFR-10`): и текст, и выделение, и история отмены живут в
 * обычном объекте Kotlin, что уже доказал `DocumentEditorTest`.
 *
 * Первый же тест заодно закрывает допущение аналитики: `TextFieldBuffer` внутри
 * `edit {}` позволяет и заменить диапазон, и поставить каретку одним вызовом.
 * Если бы это было не так, тесты не позеленели бы, и правился бы `FR-8`
 * (запасной вариант — каретка в конец вставки), а не городился обход.
 */
class InsertApplierTest {

    /**
     * `TC-1`, `FR-7`: обёртка непустого выделения — маркеры вокруг текста,
     * сам текст не меняется, каретка сразу за закрывающим маркером (`OQ-6`).
     */
    @Test
    fun TC_1_wrapSelectionPutsMarkersAroundAndCaretAfterClosingMarker() {
        val state = TextFieldState("абв слово где", initialSelection = TextRange(4, 9))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("абв *слово* где", state.text.toString())
        assertEquals(
            TextRange(11),
            state.selection,
            "Каретка обязана встать сразу за закрывающим маркером (OQ-6): " +
                "продолжение набора — самый частый случай после обёртки.",
        )
    }

    /**
     * `TC-1`, обратное выделение: пользователь вёл палец справа налево, и
     * `start > end`. Обёртка обязана вести себя так же, как при прямом.
     */
    @Test
    fun TC_1_reversedSelectionWrapsIdentically() {
        val state = TextFieldState("абв слово где", initialSelection = TextRange(9, 4))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("абв *слово* где", state.text.toString())
        assertEquals(TextRange(11), state.selection)
    }

    /**
     * `TC-2`, `FR-8`: пустое выделение — вставка заготовки в позицию каретки,
     * каретка между маркерами, где пользователь продолжит набор.
     */
    @Test
    fun TC_2_emptySelectionInsertsMarkersWithCaretBetween() {
        val state = TextFieldState("абв ", initialSelection = TextRange(4))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("абв **", state.text.toString())
        assertEquals(
            TextRange(5),
            state.selection,
            "Каретка обязана встать между маркерами: содержимое набирается сразу после касания (FR-8).",
        )
    }

    /**
     * `TC-8`, `FR-7`: многострочное выделение оборачивается буквально — панель
     * не пытается «чинить» разметку, интерпретация остаётся сканеру и рендеру.
     */
    @Test
    fun TC_8_multilineSelectionIsWrappedLiterally() {
        val state = TextFieldState("одна\nдве три", initialSelection = TextRange(0, 8))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("*одна\nдве* три", state.text.toString())
        assertEquals(TextRange(10), state.selection)
    }

    /**
     * `TC-3`, `FR-9`: вставка с панели — один нераздельный шаг отмены.
     *
     * Первый undo убирает всю вставку и только её: набранный до неё текст на
     * месте. Redo возвращает вставку. Сам механизм — забота Compose, и его
     * сторожит `DocumentEditorTest.TC_12_*`; здесь проверяется наш вклад: что
     * [applyInsert] остался *одним* вызовом `edit {}` — дробление на два
     * раздробило бы и шаг отмены.
     */
    @Test
    fun TC_3_panelInsertUndoesInOneStepAndRedoReturnsIt() {
        val source = DocumentSource(id = "content://doc/1", displayName = "notes.adoc")
        val editor = DocumentEditor().apply { load(DocumentState.opened(source, "")) }
        // «Набрано абв»: отдельный шаг истории с кареткой в конце текста.
        editor.textFieldState.edit {
            insert(0, "абв")
            selection = TextRange(3)
        }

        editor.textFieldState.applyInsert(InsertConstructs.bold)
        assertEquals("абв**", editor.textFieldState.text.toString())

        assertTrue(editor.canUndo, "предусловие: вставка обязана попасть в историю отмены")
        editor.undo()
        assertEquals(
            "абв",
            editor.textFieldState.text.toString(),
            "Первый undo обязан убрать всю вставку и только её (FR-9): " +
                "если текста меньше — вставка слиплась с набором, если маркер остался — раздробилась.",
        )

        editor.redo()
        assertEquals("абв**", editor.textFieldState.text.toString(), "redo обязан вернуть вставку целиком")

        editor.undo()
        editor.undo()
        assertEquals("", editor.textFieldState.text.toString())
        assertFalse(editor.canUndo, "глубже загруженного документа история уходить не должна")
    }
}
