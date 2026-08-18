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
import kotlin.test.assertTrue

/**
 * Модель корневого списка — слайс `SL-2` фичи 009 (`FR-1`, `FR-14` в части
 * одного списка, `FR-7` в части источника; `TC-5`, `TC-23`…`TC-25`).
 *
 * `TC-1` здесь *не* проверяется, хотя раньше эти тесты носили его номер:
 * кейс говорит о том, что стартовый экран — корень, а это свойство хостинга и
 * навигатора, а не содержимого списка. Покрытие сверх реального сверка
 * записала бы как настоящее (замечание ревью `SL-2`).
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
    fun TC_24_rootShowsDocumentsOfHeldSource() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note, guide)
        }
        val model = modelOn(access)

        model.start()

        val listed = assertIs<RootListState.Listed>(model.state, "корень открывается списком файлов (FR-1)")
        assertEquals(listOf(note, guide), listed.files)
        assertEquals("Документы", listed.title, "заголовок называет источник")
        assertNull(model.notice)
    }

    @Test
    fun TC_24_rootWithoutSourceIsEmptyState() {
        val model = modelOn(FakeAccess())

        model.start()

        assertEquals(
            RootListState.NoSource,
            model.state,
            "источника нет — пустое состояние, а не вечная загрузка и не ошибка",
        )
    }

    @Test
    fun TC_24_emptyFolderIsAListNotAFailure() {
        val access = FakeAccess().apply { tree = documents }
        val model = modelOn(access)

        model.start()

        val listed = assertIs<RootListState.Listed>(model.state, "папка без документов — состояние, не отказ")
        assertEquals(emptyList(), listed.files)
    }

    @Test
    fun TC_24_unreadableSourceBecomesStateNotHang() {
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
    fun TC_5_requestOverAnOngoingReadIsDeferredNotDropped() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        access.gateListing()
        val model = modelOn(access)

        model.start()
        // Пока первое чтение висит, источник сменился и пришёл второй запрос.
        access.listing = listOf(note, guide)
        model.start()
        access.releaseAll()

        val listed = assertIs<RootListState.Listed>(model.state)
        assertEquals(
            listOf(note, guide),
            listed.files,
            "запрос поверх идущего чтения откладывается, а не теряется: " +
                "потерянный оставил бы на экране снимок первого чтения",
        )
    }

    @Test
    fun TC_5_stateNeverMixesTitleOfOneSourceWithFilesOfAnother() {
        // Находка ревью SL-2: дерево спрашивалось трижды за одно чтение, и
        // смена источника посреди чтения давала заголовок новой папки над
        // файлами старой.
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        access.gateListing()
        val model = modelOn(access)

        model.start()
        // Источник сменился, пока перечисление висело.
        access.tree = TreeSource(id = "tree://other", displayName = "Другая")
        access.listing = listOf(guide)
        access.releaseAll()

        val listed = assertIs<RootListState.Listed>(model.state)
        assertEquals("Другая", listed.title)
        assertEquals(
            listOf(guide),
            listed.files,
            "заголовок и файлы — из одного источника; половинки разных источников на экран не выпускаются",
        )
    }

    @Test
    fun TC_23_openFailureIsKeptEvenWhileTheSourceIsStillLoading() {
        // Восстановление после выгрузки процесса: документ пропал раньше, чем
        // прочитался источник. Прежде постановка уведомления в этот момент
        // была полным no-op, и причина терялась навсегда (находка ревью SL-2).
        val access = FakeAccess().apply { tree = documents }
        access.gateListing()
        val model = modelOn(access)
        model.start()

        model.documentOpenFailed("файл не найден")
        access.releaseAll()

        assertEquals("файл не найден", model.notice, "причина переживает чтение источника")
        assertIs<RootListState.Listed>(model.state, "а само чтение доходит до конца")
    }

    @Test
    fun TC_23_documentOpenFailureSurvivesTheReread() {
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

        assertEquals(message, model.notice, "отказ открытия виден, а не теряется")
        val withNotice = assertIs<RootListState.Listed>(model.state)
        assertEquals(listOf(note), withNotice.files, "список при этом остаётся на месте")

        // Возврат на корень порождает чтение всегда — и уведомление обязано
        // его пережить, иначе причина исчезает раньше, чем её прочтут
        // (находка ревью SL-2: два правила гасили друг друга).
        model.start()
        assertEquals(message, model.notice, "перечитка списка отказ открытия не гасит")

        model.noticeDismissed()
        assertNull(model.notice, "гаснет явным действием пользователя")
    }

    @Test
    fun TC_23_sourceChosenClearsTheNoticeAndAsksForOneReread() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val model = modelOn(access)
        model.start()
        model.documentOpenFailed("старый отказ")
        val tokenBefore = model.sourceToken

        access.tree = TreeSource(id = "tree://other", displayName = "Другая")
        access.listing = listOf(guide)
        model.sourceChosen()

        assertNull(model.notice, "чужой отказ не переезжает на новый источник")
        assertEquals(
            tokenBefore + 1,
            model.sourceToken,
            "смена источника просит перечитать сменой метки, а не собственным чтением: " +
                "читает тот, кто показывает список, и делает это один раз (находка ревью SL-2)",
        )
        assertEquals(1, access.listCalls, "самим `sourceChosen` второго обращения к шву не порождается")

        // Экран отзывается на метку — и вот тогда появляется новый источник.
        model.start()
        val listed = assertIs<RootListState.Listed>(model.state)
        assertEquals("Другая", listed.title, "новый источник — новый заголовок")
        assertEquals(listOf(guide), listed.files)
    }

    @Test
    fun TC_25_editorSourceComesFromTheListFirstAndFromTheHeldSourceSecond() {
        val listed = RootListState.Listed(title = "Документы", files = listOf(note, guide))

        assertEquals(
            guide,
            editorSourceFor(guide.id, listed, held = null),
            "выбранный в списке файл опознаётся списком",
        )
        assertEquals(
            note,
            editorSourceFor(note.id, listed = null, held = note),
            "после выгрузки процесса список ещё не прочитан — выручает удержанный источник (FR-6)",
        )
        assertEquals(
            guide,
            editorSourceFor(guide.id, listed, held = note),
            "список главнее: удержан прежний документ, а открыть просят выбранный",
        )
        assertNull(
            editorSourceFor("doc/пропал", listed, held = note),
            "неопознанный документ — null: хостинг вернёт корень, а не покажет пустой редактор",
        )
        assertNull(
            editorSourceFor(note.id, listed = null, held = null),
            "опознать нечем",
        )
    }

    @Test
    fun TC_25_editorIsLeftForRootOnlyAfterTheSourceHasBeenRead() {
        val listed = RootListState.Listed(title = "Документы", files = listOf(note))

        assertEquals(
            false,
            shouldLeaveEditorForRoot(source = null, state = RootListState.Loading),
            "пока источник читается, уходить нельзя: документ ещё может опознаться (FR-6)",
        )
        assertEquals(
            true,
            shouldLeaveEditorForRoot(source = null, state = listed),
            "источник прочитан, опознать нечем — корень честнее пустого редактора",
        )
        assertEquals(
            true,
            shouldLeaveEditorForRoot(source = null, state = RootListState.NoSource),
            "источника нет вовсе — ждать нечего",
        )
        assertEquals(
            false,
            shouldLeaveEditorForRoot(source = note, state = listed),
            "документ опознан — экран остаётся",
        )
    }

    @Test
    fun TC_25_navigatorKeepsSomewhereToReturnWhileTheDocumentIsUnresolved() {
        // Проверяется ровно одно: пока документ не опознан, стек говорит «есть
        // куда возвращаться», и возврат исполним. Что перехват «назад» это
        // читает и срабатывает — здесь *не* проверяется и проверено быть не
        // может: инфраструктуры тестов композиции в проекте нет, и эта
        // половина уходит в ручной `TC-3`.
        //
        // Оговорка не формальность: прежний комментарий обещал, что тест
        // ловит «пользователь заперт на экране», — он этого не делал, и такое
        // обещание маскирует дефект вместо того, чтобы его ловить (находка
        // ревью `SL-2`).
        val navigator = AppNavigator()
        navigator.go(AppScreen.Editor("doc/1"))

        assertEquals(
            false,
            shouldLeaveEditorForRoot(source = null, state = RootListState.Loading),
            "пока источник читается, уходить нельзя",
        )
        assertTrue(
            navigator.canGoBack,
            "и ровно в это время стеку есть куда возвращаться — признак, " +
                "на котором стоит перехват (FR-5)",
        )
        assertTrue(navigator.back(), "возврат исполним, а не только объявлен")
        assertEquals(AppScreen.Root, navigator.current)
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

        /** Открыть ворота и больше не задерживать: повторные чтения проходят. */
        fun releaseAll() {
            gate?.complete(Unit)
            gate = null
        }

        /**
         * Перечисление отвечает тем, что видело *на входе*, а не тем, что стало
         * к моменту ответа.
         *
         * Так ведёт себя настоящий провайдер: он читает каталог, а не следит
         * за подменой дерева под собой. Прежняя подделка брала `listing` уже
         * после ворот и потому всегда отдавала свежее — на ней дефект
         * «заголовок новой папки над файлами старой» был невоспроизводим в
         * принципе, и регрессионный тест зеленел на коде до починки (находка
         * ревью `SL-2`).
         */
        override suspend fun listDocuments(): TreeListResult {
            listCalls++
            val treeAtEntry = tree
            val listingAtEntry = listing
            val errorAtEntry = listError
            gate?.await()
            val heldTree = treeAtEntry
                ?: return TreeListResult.Failed(TreeSource("", "папка"), TreeAccessError.PermissionLost)
            errorAtEntry?.let { return TreeListResult.Failed(heldTree, it) }
            return TreeListResult.Listed(listingAtEntry)
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
