package io.github.olegnyr.adocmobile.screen.app

import io.github.olegnyr.adocmobile.document.DocumentAccessError
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeAccessError
import io.github.olegnyr.adocmobile.document.TreeListResult
import io.github.olegnyr.adocmobile.document.TreeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Модель корневого списка — слайс `SL-2` фичи 009 (`FR-1`, `FR-14` в части
 * одного списка, `FR-7` в части источника; `TC-1`, `TC-5`).
 *
 * Приём тот же, что в `EditorScreenModelTest`: шов — подделка интерфейса,
 * диспетчер `Unconfined` выполняет корутины синхронно до первой точки
 * приостановки, композиции нет (`NFR-10`).
 */
class RootListModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val documents = TreeSource(id = "tree://docs", displayName = "Документы")
    private val note = DocumentSource(id = "doc/1", displayName = "заметка.adoc")
    private val guide = DocumentSource(id = "doc/2", displayName = "руководство.adoc")

    private fun modelOn(access: FakeAccess) = RootListModel(access = access, scope = scope)

    @Test
    fun TC_1_rootShowsDocumentsOfHeldSource() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note, guide)
        }
        val model = modelOn(access)

        model.start()

        val listed = assertIs<RootListState.Listed>(model.state, "корень открывается списком файлов (FR-1)")
        assertEquals(listOf(note, guide), listed.files)
        assertEquals("Документы", listed.title, "заголовок называет источник")
        assertNull(listed.notice)
    }

    @Test
    fun TC_1_rootWithoutSourceIsEmptyState() {
        val model = modelOn(FakeAccess())

        model.start()

        assertEquals(
            RootListState.NoSource,
            model.state,
            "источника нет — пустое состояние, а не вечная загрузка и не ошибка",
        )
    }

    @Test
    fun TC_1_emptyFolderIsAListNotAFailure() {
        val access = FakeAccess().apply { tree = documents }
        val model = modelOn(access)

        model.start()

        val listed = assertIs<RootListState.Listed>(model.state, "папка без документов — состояние, не отказ")
        assertEquals(emptyList(), listed.files)
    }

    @Test
    fun TC_1_unreadableSourceBecomesStateNotHang() {
        val access = FakeAccess().apply {
            tree = documents
            listError = TreeAccessError.PermissionLost
        }
        val model = modelOn(access)

        model.start()

        val failed = assertIs<RootListState.Failed>(model.state, "отказ источника — состояние с текстом")
        assertEquals(
            TreeAccessError.PermissionLost.userMessage(documents.displayName),
            failed.message,
            "текст берётся у шва, а не сочиняется экраном",
        )
    }

    @Test
    fun TC_5_returningToRootRereadsTheSource() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val model = modelOn(access)
        model.start()

        // Пока пользователь был в документе, папка изменилась снаружи.
        access.listing = listOf(note, guide)
        model.start()

        val listed = assertIs<RootListState.Listed>(model.state)
        assertEquals(listOf(note, guide), listed.files, "возврат на корень перечитывает источник (FR-7)")
        assertEquals(2, access.listCalls, "перечитка — повторное обращение к шву, а не кэш")
    }

    @Test
    fun TC_5_parallelReadsAreGuarded() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        access.gateListing()
        val model = modelOn(access)

        model.start()
        model.start()
        access.releaseListing()

        assertEquals(1, access.listCalls, "второй запрос поверх идущего чтения до шва не доходит")
        assertIs<RootListState.Listed>(model.state)
    }

    @Test
    fun TC_1_documentOpenFailureIsShownAsNoticeInTheList() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val model = modelOn(access)
        model.start()

        // Редактор не смог открыть выбранный документ и вернул пользователя
        // к списку с готовым текстом (`FR-9` фичи 005).
        val message = DocumentAccessError.NotFound.userMessage(note.displayName)
        model.documentOpenFailed(message)

        val withNotice = assertIs<RootListState.Listed>(model.state)
        assertEquals(message, withNotice.notice, "отказ открытия виден в списке, а не теряется")
        assertEquals(listOf(note), withNotice.files, "список при этом остаётся на месте")

        model.start()
        val reread = assertIs<RootListState.Listed>(model.state)
        assertNull(reread.notice, "перечитка гасит отказ: он был про прошлую попытку")
    }

    @Test
    fun TC_1_sourceChosenRereadsWithoutNotice() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val model = modelOn(access)
        model.start()
        model.documentOpenFailed("старый отказ")

        access.tree = TreeSource(id = "tree://other", displayName = "Другая")
        access.listing = listOf(guide)
        model.sourceChosen()

        val listed = assertIs<RootListState.Listed>(model.state)
        assertEquals("Другая", listed.title, "новый источник — новый заголовок")
        assertEquals(listOf(guide), listed.files)
        assertNull(listed.notice, "чужой отказ не переезжает на новый источник")
    }

    /** Шов-подделка по образцу `EditorScreenModelTest.FakeAccess`. */
    private class FakeAccess : DocumentTreeAccess {
        var tree: TreeSource? = null
        var listing: List<DocumentSource> = emptyList()
        var listError: TreeAccessError? = null
        var listCalls = 0

        private var gate: CompletableDeferred<Unit>? = null

        fun gateListing() {
            gate = CompletableDeferred()
        }

        fun releaseListing() {
            gate?.complete(Unit)
        }

        override suspend fun listDocuments(): TreeListResult {
            listCalls++
            gate?.await()
            val heldTree = tree
                ?: return TreeListResult.Failed(TreeSource("", "папка"), TreeAccessError.PermissionLost)
            listError?.let { return TreeListResult.Failed(heldTree, it) }
            return TreeListResult.Listed(listing)
        }

        override fun heldTree(): TreeSource? = tree

        override fun heldSource(): DocumentSource? = null

        override suspend fun open(source: DocumentSource): DocumentOpenResult =
            DocumentOpenResult.Failed(source, DocumentAccessError.NotFound)

        override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult =
            DocumentWriteResult.Written

        override fun release() {
            tree = null
        }
    }
}
