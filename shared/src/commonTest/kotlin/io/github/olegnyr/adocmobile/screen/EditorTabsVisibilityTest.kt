package io.github.olegnyr.adocmobile.screen

import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.TreeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * `TC-22` фичи 005: полоса вкладок принадлежит документу, а не папке (`FR-22`,
 * решение владельца 2026-08-18).
 *
 * Проверяются две чистые функции, из которых экран собирает поведение:
 * [editorTabsVisible] решает, рисовать ли полосу, [editorTabAfter] — какая
 * вкладка активна после смены состояния. Инфраструктуры compose-тестов в
 * проекте нет, поэтому решение вынесено из композиции — тем же приёмом, что
 * [undoShortcutFor]. Половина с *открытым* документом — в `EditorScreenModelTest`:
 * `EditorDocument.Open` строится настоящим holder-ом, а `sealed`-состояние
 * подделкой из другого модуля не изобразить.
 */
class EditorTabsVisibilityTest {

    private val tree = TreeSource(id = "tree://docs", displayName = "Документы")
    private val source = DocumentSource(id = "content://doc/1", displayName = "заметка.adoc")

    @Test
    fun TC_22_tabsHiddenWithoutOpenDocument() {
        assertFalse(editorTabsVisible(EditorDocument.NoFolder), "папка не выбрана — переключать нечего")
        assertFalse(
            editorTabsVisible(EditorDocument.FolderFailed("Папка недоступна")),
            "папка недоступна — переключать нечего",
        )
        assertFalse(
            editorTabsVisible(EditorDocument.Browsing(tree, listOf(source))),
            "список документов папки — вкладки относятся к документу, а не к папке (FR-22)",
        )
        assertFalse(
            editorTabsVisible(EditorDocument.Browsing(tree, emptyList())),
            "папка без документов — та же пустота, тоже без вкладок",
        )
    }

    @Test
    fun TC_22_closingDocumentResetsSelectionToEditor() {
        // Возврат к списку (FR-21) с вкладки превью: полосы больше нет, а
        // сохранённый выбор сбрасывается — следующий документ открывается
        // редактором, а не вкладкой, оставшейся от прежнего.
        assertEquals(
            EditorTab.Editor,
            editorTabAfter(EditorDocument.Browsing(tree, listOf(source)), EditorTab.Preview),
        )
        assertEquals(
            EditorTab.Editor,
            editorTabAfter(EditorDocument.NoFolder, EditorTab.Preview),
        )
        assertEquals(
            EditorTab.Editor,
            editorTabAfter(EditorDocument.FolderFailed("Папка недоступна"), EditorTab.Preview),
        )
    }
}
