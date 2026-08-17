package io.github.olegnyr.adocmobile.screen

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `TC-15` фичи 005: undo/redo с аппаратной клавиатуры — смоук разбора сочетаний.
 *
 * Сочетания записаны решением владельца в `FR-13` фичи 004: `Ctrl+Z` —
 * отменить; `Ctrl+Shift+Z` и `Ctrl+Y` — повторить. Здесь проверяется чистая
 * функция соответствия «клавиши → действие»; сами действия ([EditorScreenModel.undoRequested],
 * [EditorScreenModel.redoRequested]) покрыты `TC_15_*` в `EditorScreenModelTest`,
 * а поглощение события — обвязка `onPreviewKeyEvent`, проверяемая руками.
 */
class UndoShortcutTest {

    @Test
    fun TC_15_ctrlZMapsToUndo() {
        assertEquals(UndoShortcut.Undo, undoShortcutFor(Key.Z, ctrl = true, shift = false))
    }

    @Test
    fun TC_15_ctrlShiftZMapsToRedo() {
        assertEquals(UndoShortcut.Redo, undoShortcutFor(Key.Z, ctrl = true, shift = true))
    }

    @Test
    fun TC_15_ctrlYMapsToRedo() {
        assertEquals(
            UndoShortcut.Redo,
            undoShortcutFor(Key.Y, ctrl = true, shift = false),
            "Ctrl+Y — второе сочетание повтора (FR-13 фичи 004, решение владельца 2026-08-17).",
        )
    }

    @Test
    fun TC_15_plainKeysAndForeignCombinationsAreNotShortcuts() {
        assertNull(undoShortcutFor(Key.Z, ctrl = false, shift = false), "голый Z — обычный ввод")
        assertNull(undoShortcutFor(Key.Y, ctrl = false, shift = false), "голый Y — обычный ввод")
        assertNull(undoShortcutFor(Key.Z, ctrl = false, shift = true), "Shift+Z — обычный ввод")
        assertNull(undoShortcutFor(Key.X, ctrl = true, shift = false), "чужое сочетание не перехватывается")
    }
}
