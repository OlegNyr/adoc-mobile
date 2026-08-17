package io.github.olegnyr.adocmobile.screen

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import io.github.olegnyr.adocmobile.document.AutosavePolicy
import io.github.olegnyr.adocmobile.document.DocumentAccessError
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.DocumentWriteError
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeAccessError
import io.github.olegnyr.adocmobile.document.TreeListResult
import io.github.olegnyr.adocmobile.document.TreeSource
import io.github.olegnyr.adocmobile.document.openDocument
import io.github.olegnyr.adocmobile.preview.PreviewStatus
import io.github.olegnyr.adocmobile.render.AdocRenderer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Логика holder-а экрана с подставными швами — слайсы `SL-1` и `SL-3` фичи
 * 005-editor-screen: `TC-1`, `TC-2`, `TC-5`, `TC-6`, автоматизируемая половина
 * `TC-4`, автосохранение сквозь экран (`TC-7`…`TC-9`) и переключение на папку
 * (решение `OQ-1`, `FR-1a` фичи 004).
 *
 * Приём тот же, что в `AutosaveRunnerTest`: часы — переменная, ожидание паузы —
 * подставная функция с воротами, файловый шов — подделка интерфейса, диспетчер
 * `Unconfined` выполняет корутины синхронно до первой точки приостановки.
 * Кирпичи (`DocumentEditor`, `AutosaveRunner`, `AutosavePolicy`) здесь не
 * перепроверяются — проверяется, что holder их правильно соединяет.
 */
class EditorScreenModelTest {

    private var now = 0L
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val access = FakeAccess()
    private val waits = FakeWaits()

    private val held = DocumentSource(id = "content://doc/1", displayName = "заметка.adoc")

    private val renderer = FakeRenderer()

    private fun model(editor: DocumentEditor = DocumentEditor()): EditorScreenModel =
        EditorScreenModel(
            editor = editor,
            access = access,
            renderer = renderer,
            scope = scope,
            clock = { now },
            page = { fragment -> "<page>$fragment</page>" },
            delayUntil = waits::wait,
        )

    private fun EditorScreenModel.openRunner() =
        assertIs<EditorDocument.Open>(document, "ожидался открытый документ").runner

    @Test
    fun TC_5_startLiftsHeldSourceIntoFieldWithEmptyHistory() {
        access.held = held
        access.contents[held.id] = "= Заголовок\n\nАбзац."
        val model = model()

        model.start()

        val runner = model.openRunner()
        assertEquals("= Заголовок\n\nАбзац.", model.editor.textFieldState.text.toString())
        assertEquals(held, runner.document.source)
        assertFalse(model.editor.canUndo, "история отмены после загрузки пуста (FR-8)")
        assertNull(editorStatusLabel(runner.document), "только что открытый документ не изменён")
    }

    @Test
    fun TC_5_startWithoutHeldSourceAndTreeStaysWithoutFolder() {
        val model = model()

        model.start()

        assertIs<EditorDocument.NoFolder>(model.document)
        assertEquals(0, access.openCalls, "без удержанного источника шов не дёргается")
    }

    /**
     * Кейсы папки (этот и три следующих) заведены реализацией `SL-3` по решению
     * владельца `OQ-1` («папка, затем файл в ней»): в спеке 005 идентификаторов
     * для них нет — аналитика писалась до решения о tree-доступе. Дозаявление
     * `TC-*` — правка `doc/`, вне зоны слайса; пробел назван в отчёте слайса.
     * Наблюдаемое поведение — `FR-1a` фичи 004 на уровне экрана.
     */
    @Test
    fun startWithHeldTreeWithoutDocumentListsFolder() {
        access.tree = TreeSource(id = "tree://docs", displayName = "Документы")
        access.listing = listOf(held)
        val model = model()

        model.start()

        val browsing = assertIs<EditorDocument.Browsing>(model.document)
        assertEquals("Документы", browsing.tree.displayName)
        assertEquals(listOf(held), browsing.documents)
        assertNull(browsing.notice)
    }

    @Test
    fun startWithEmptyFolderBrowsesEmptyList() {
        access.tree = TreeSource(id = "tree://docs", displayName = "Документы")
        access.listing = emptyList()
        val model = model()

        model.start()

        val browsing = assertIs<EditorDocument.Browsing>(model.document)
        assertEquals(
            emptyList<DocumentSource>(),
            browsing.documents,
            "папка без документов — пустой список, не ошибка (FR-1a фичи 004)",
        )
    }

    @Test
    fun listingFailureShowsFolderMessage() {
        access.tree = TreeSource(id = "tree://docs", displayName = "Документы")
        access.listError = TreeAccessError.PermissionLost
        val model = model()

        model.start()

        val failed = assertIs<EditorDocument.FolderFailed>(model.document)
        assertEquals(TreeAccessError.PermissionLost.userMessage("Документы"), failed.message)
    }

    @Test
    fun folderChosenListsAndDocumentChoiceOpensEditor() {
        val model = model()
        model.start()
        assertIs<EditorDocument.NoFolder>(model.document)

        // Платформа взяла право на дерево — holder перечисляет его документы.
        access.tree = TreeSource(id = "tree://docs", displayName = "Документы")
        access.listing = listOf(held)
        access.contents[held.id] = "= Заголовок"
        model.folderChosen()
        assertIs<EditorDocument.Browsing>(model.document)

        // Выбор документа из списка открывает его в редакторе.
        model.open(held)
        val runner = model.openRunner()
        assertEquals(held, runner.document.source)
        assertEquals("= Заголовок", model.editor.textFieldState.text.toString())
    }

    @Test
    fun TC_5_openingAnotherDocumentDropsPendingWriteOfPrevious() {
        access.held = held
        access.contents[held.id] = "первый"
        val other = DocumentSource(id = "content://doc/2", displayName = "другой.adoc")
        access.contents[other.id] = "второй"
        val model = model()
        model.start()
        val firstRunner = model.openRunner()

        // Правка первого документа повисла в паузе автосохранения…
        model.textEdited("первый правленый")
        assertEquals(1, waits.requested.size)

        // …и в этот момент открывается другой документ.
        model.open(other)
        val secondRunner = model.openRunner()
        assertNotSame(firstRunner, secondRunner, "на новый документ — новый исполнитель и новая политика (FR-8)")
        assertEquals("второй", model.editor.textFieldState.text.toString())
        assertFalse(model.editor.canUndo, "история прежнего документа не применима к новому (FR-8)")

        now = AutosavePolicy.DEFAULT_PAUSE_MILLIS + 1
        waits.releaseAll()
        assertTrue(
            access.written.none { (_, text) -> text.contains("первый") },
            "хвост записи прежнего документа не уходит ни в какой файл (TC-5): ${access.written}",
        )
    }

    @Test
    fun TC_1_appBarNameThenModifiedLabelThenCleanAfterWrite() {
        access.held = held
        access.contents[held.id] = "= Заголовок"
        val model = model()
        model.start()
        val runner = model.openRunner()

        assertEquals("заметка.adoc", runner.document.source.displayName)
        assertNull(editorStatusLabel(runner.document))

        model.textEdited("= Заголовок!")
        assertEquals("ИЗМЕНЁН · НЕ СОХРАНЁН", editorStatusLabel(runner.document), "правка зажигает метку (FR-1, FR-10)")

        now = AutosavePolicy.DEFAULT_PAUSE_MILLIS
        waits.releaseLast()
        assertEquals(listOf(held.id to "= Заголовок!"), access.written, "пауза даёт ровно одну запись (FR-11)")
        assertNull(editorStatusLabel(runner.document), "после успешной записи — снова чистое имя (FR-1)")
    }

    @Test
    fun TC_2_editAndRevertTurnLabelOffWithoutWrite() {
        access.held = held
        access.contents[held.id] = "исходный"
        val model = model()
        model.start()
        val runner = model.openRunner()

        model.textEdited("исходный правленый")
        model.textEdited("исходный")
        assertNull(editorStatusLabel(runner.document), "текст совпал с записанным — метка гаснет (FR-10)")

        now = AutosavePolicy.DEFAULT_PAUSE_MILLIS + 1
        waits.releaseAll()
        assertEquals(0, access.written.size, "возврат к записанному не переписывает файл (TC-2)")
    }

    @Test
    fun TC_6_openFailureShowsUserMessageAndReturnsToFolderList() {
        access.tree = TreeSource(id = "tree://docs", displayName = "Документы")
        access.listing = listOf(held)
        access.held = held
        access.openError = DocumentAccessError.NotFound
        val model = model()

        model.start()

        val browsing = assertIs<EditorDocument.Browsing>(model.document, "отказ открытия возвращает к списку (FR-9)")
        assertEquals(DocumentAccessError.NotFound.userMessage("заметка.adoc"), browsing.notice)
        assertEquals("", model.editor.textFieldState.text.toString(), "в поле ничего не загружено (FR-9)")
    }

    @Test
    fun TC_6_openFailureWithDeadTreeShowsFolderMessage() {
        // Право на дерево отозвано: и файл не открывается, и папка не
        // перечисляется — пользователь видит сообщение о папке и выбор заново.
        access.tree = TreeSource(id = "tree://docs", displayName = "Документы")
        access.listError = TreeAccessError.PermissionLost
        access.held = held
        access.openError = DocumentAccessError.PermissionLost
        val model = model()

        model.start()

        val failed = assertIs<EditorDocument.FolderFailed>(model.document)
        assertEquals(TreeAccessError.PermissionLost.userMessage("Документы"), failed.message)
    }

    @Test
    fun TC_8_movedToBackgroundWritesImmediately() {
        access.held = held
        access.contents[held.id] = "исходный"
        val model = model()
        model.start()

        model.textEdited("исходный правленый")
        assertEquals(0, access.written.size, "до паузы записи нет")

        model.foregroundChanged(false)
        assertEquals(
            listOf(held.id to "исходный правленый"),
            access.written,
            "уход в фон пишет немедленно, не дожидаясь паузы (FR-11, FR-16 фичи 004)",
        )

        // Возврат на передний план и повторный уход без правок файл не трогают.
        model.foregroundChanged(true)
        model.foregroundChanged(false)
        assertEquals(1, access.written.size, "без расхождения с диском записи нет")
    }

    @Test
    fun TC_9_writeFailureShowsMessageKeepsTextAndDoesNotRetrySilently() {
        access.held = held
        access.contents[held.id] = "исходный"
        access.writeError = DocumentWriteError.PermissionLost
        val model = model()
        model.start()
        val runner = model.openRunner()

        model.typeText("исходный правленый")
        now = AutosavePolicy.DEFAULT_PAUSE_MILLIS + 1
        waits.releaseAll()

        assertEquals(1, access.written.size, "попытка записи была ровно одна")
        assertEquals(
            DocumentWriteError.PermissionLost.userMessage("заметка.adoc"),
            model.writeFailure,
            "отказ записи наблюдаем текстом userMessage (TC-9; OQ-3)",
        )
        assertEquals("исходный правленый", model.editor.textFieldState.text.toString(), "текст остаётся в поле (FR-12)")
        assertEquals("ИЗМЕНЁН · НЕ СОХРАНЁН", editorStatusLabel(runner.document), "метка продолжает гореть")

        // Без явной правки и без ручного повтора новых попыток нет (FR-19 фичи 004).
        now += 10_000
        waits.releaseAll()
        assertEquals(1, access.written.size, "автосохранение после отказа приостановлено")
    }

    @Test
    fun TC_9_retryRequestedWritesAgainAndSuccessClearsFailure() {
        access.held = held
        access.contents[held.id] = "исходный"
        access.writeError = DocumentWriteError.WriteFailed
        val model = model()
        model.start()

        model.textEdited("исходный правленый")
        now = AutosavePolicy.DEFAULT_PAUSE_MILLIS + 1
        waits.releaseAll()
        assertEquals(1, access.written.size)

        // Повтор при живом отказе: попытка уходит, плашка остаётся.
        model.retryWriteRequested()
        assertEquals(2, access.written.size, "ручной повтор — вторая попытка (FR-19 фичи 004)")
        assertEquals(DocumentWriteError.WriteFailed.userMessage("заметка.adoc"), model.writeFailure)

        // Причина отказа ушла — повтор записывает и гасит плашку.
        access.writeError = null
        model.retryWriteRequested()
        assertEquals(3, access.written.size)
        assertNull(model.writeFailure, "успешная запись гасит плашку отказа")
    }

    @Test
    fun TC_4_restorationKeepsFieldTextAndUndoHistory() {
        access.held = held
        access.contents[held.id] = "= Заголовок"
        // Поле восстановлено rememberSaveable: текст совпадает с диском,
        // история отмены не пуста — как после поворота без несохранённых правок.
        val state = TextFieldState("= Заголово")
        state.edit { insert(10, "к") }
        val editor = DocumentEditor(state)
        assertTrue(editor.canUndo)
        val model = model(editor)

        model.start(fieldSourceId = held.id)

        model.openRunner()
        assertEquals("= Заголовок", editor.textFieldState.text.toString())
        assertTrue(editor.canUndo, "повторная загрузка не стирает восстановленную историю (FR-7)")
    }

    @Test
    fun TC_4_restorationWithUnsavedEditsMarksModifiedAndAutosaves() {
        access.held = held
        access.contents[held.id] = "= Заголовок"
        // Восстановленное поле разошлось с диском: правка не успела записаться.
        val editor = DocumentEditor(TextFieldState("= Заголовок правленый"))
        val model = model(editor)

        model.start(fieldSourceId = held.id)

        val runner = model.openRunner()
        assertEquals("= Заголовок правленый", editor.textFieldState.text.toString(), "правка не перетёрта диском")
        assertEquals("ИЗМЕНЁН · НЕ СОХРАНЁН", editorStatusLabel(runner.document))

        now = AutosavePolicy.DEFAULT_PAUSE_MILLIS + 1
        waits.releaseAll()
        assertEquals(listOf(held.id to "= Заголовок правленый"), access.written, "восстановленная правка доезжает до файла")
    }

    @Test
    fun TC_4_startWithForeignFieldSourceLoadsFromDisk() {
        access.held = held
        access.contents[held.id] = "= Заголовок"
        // В поле — текст другого источника (например, право на прежний файл
        // сменилось между выгрузкой и возвратом): восстановление не применимо.
        val editor = DocumentEditor(TextFieldState("чужой текст"))
        val model = model(editor)

        model.start(fieldSourceId = "content://doc/устаревший")

        model.openRunner()
        assertEquals("= Заголовок", editor.textFieldState.text.toString(), "документ загружен с диска")
    }

    @Test
    fun TC_11_visibilitySignalReachesPipelineAndFollowsDocument() {
        access.held = held
        access.contents[held.id] = "первый"
        val other = DocumentSource(id = "content://doc/2", displayName = "другой.adoc")
        access.contents[other.id] = "второй"
        val model = model()
        model.start()

        // Пока превью скрыто, правки не рождают ни одного рендера (TC-11).
        // Правка идёт через поле, как в продукте: пайплайн берёт снимок текста
        // из TextFieldState — единственного источника истины (FR-6).
        model.typeText("первый правленый")
        assertEquals(0, renderer.requests.size, "при скрытом превью движок молчит")

        // Показ превью — немедленный рендер текущего текста поля.
        model.previewVisibilityChanged(visible = true)
        assertEquals(listOf("первый правленый"), renderer.requests)
        assertEquals(PreviewStatus.Content, model.openRunnerPreview().status)

        // Повторный тот же сигнал (рекомпозиция, поворот) ничего не перезапускает.
        model.previewVisibilityChanged(visible = true)
        assertEquals(1, renderer.requests.size, "неизменившаяся видимость не перезапускает рендер")

        // Новый документ при видимом превью: новый пайплайн рендерит сразу,
        // и это его первый рендер — прежний HTML не наследуется.
        model.open(other)
        assertEquals("второй", renderer.requests.last(), "пайплайн следует за документом")

        // Скрытие доезжает до пайплайна: дальше правки не рендерятся.
        model.previewVisibilityChanged(visible = false)
        val before = renderer.requests.size
        model.typeText("второй правленый")
        now += 10_000
        waits.releaseAll()
        assertEquals(before, renderer.requests.size, "скрытое превью не рендерит и по паузе")
    }

    /** Правка как в продукте: сначала поле, затем событие модели (подписка `snapshotFlow`). */
    private fun EditorScreenModel.typeText(text: String) {
        editor.textFieldState.edit { replace(0, length, text) }
        textEdited(text)
    }

    private fun EditorScreenModel.openRunnerPreview() =
        assertIs<EditorDocument.Open>(document).preview

    /** Рендерер-подделка: считает запросы; исполнителя проверяет `PreviewPipelineTest`. */
    private class FakeRenderer : AdocRenderer {
        val requests = mutableListOf<String>()

        override suspend fun render(source: String): String = "HTML($source)".also { requests += source }
    }

    /** Шов-подделка по образцу `AutosaveRunnerTest`: содержимое и исходы задаются тестом. */
    private class FakeAccess : DocumentTreeAccess {
        var held: DocumentSource? = null
        var tree: TreeSource? = null
        var listing: List<DocumentSource> = emptyList()
        var listError: TreeAccessError? = null
        val contents = mutableMapOf<String, String>()
        var openError: DocumentAccessError? = null
        var writeError: DocumentWriteError? = null
        var openCalls = 0
        val written = mutableListOf<Pair<String, String>>()

        override suspend fun open(source: DocumentSource): DocumentOpenResult {
            openCalls++
            openError?.let { return DocumentOpenResult.Failed(source, it) }
            val text = contents[source.id]
                ?: return DocumentOpenResult.Failed(source, DocumentAccessError.NotFound)
            return openDocument(source, text.encodeToByteArray())
        }

        override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult {
            written += source.id to fileText
            writeError?.let { return DocumentWriteResult.Failed(source, it) }
            return DocumentWriteResult.Written
        }

        override fun heldSource(): DocumentSource? = held

        override fun heldTree(): TreeSource? = tree

        override suspend fun listDocuments(): TreeListResult {
            val heldTree = tree
                ?: return TreeListResult.Failed(TreeSource("", "папка"), TreeAccessError.PermissionLost)
            listError?.let { return TreeListResult.Failed(heldTree, it) }
            return TreeListResult.Listed(listing)
        }

        override fun release() {
            held = null
            tree = null
        }
    }

    /** Подставное ожидание паузы: сроки записываются, продолжение — за тестом. */
    private class FakeWaits {
        val requested = mutableListOf<Long>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun wait(dueAt: Long) {
            requested += dueAt
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }

        fun releaseLast() {
            gates.last().complete(Unit)
        }

        fun releaseAll() {
            gates.forEach { it.complete(Unit) }
        }
    }
}
