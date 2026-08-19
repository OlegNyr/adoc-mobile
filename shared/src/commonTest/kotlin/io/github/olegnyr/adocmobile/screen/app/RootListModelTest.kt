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
import kotlin.test.fail

/**
 * Модель корневого списка — слайсы `SL-2` и `SL-3` фичи 009.
 *
 * `SL-2`: `FR-1`, `FR-14` в части одного списка, `FR-7` в части источника;
 * `TC-5`, `TC-23`…`TC-25`.
 * `SL-3`: `FR-10`, `FR-15` в части папки SAF; `TC-6`, `TC-7`, `TC-9` — здесь
 * проверяется *поведение списка* при двух источниках, а правила выбора
 * (что активно, что предлагается, что запоминается) — в `ActiveSourceTest`.
 * Номера кейсов общие намеренно: кейс один, у него две половины, и каждая
 * лежит там, где живёт её предмет.
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

    /**
     * Модель над одним источником — папкой, уже выбранной пользователем.
     *
     * Активный источник называется явно: с `SL-3` список читает не «шов», а
     * тот шов, который выбран (`FR-10`), и умолчание здесь скрыло бы условие
     * половины тестов файла.
     */
    private fun modelOn(access: FakeAccess) = RootListModel(
        sources = ActiveSource(store = FakeStore(FileSourceKind.Folder), folder = access),
        scope = scope,
    )

    private fun modelOver(sources: ActiveSource) = RootListModel(sources = sources, scope = scope)

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

    @Test
    fun TC_6_choosingAFolderTurnsTheEmptyStateIntoItsFiles() {
        // Первый запуск по букве `TC-6`: ни клона, ни удержанного дерева.
        val access = FakeAccess()
        val sources = ActiveSource(store = FakeStore(), folder = access)
        val model = modelOver(sources)
        model.start()
        assertEquals(
            RootListState.NoSource,
            model.state,
            "нет удержанного дерева — пустое состояние (FR-2), а не вечная загрузка",
        )

        // Пользователь выбрал папку в системном диалоге: право взято, хостинг
        // называет источник активным и просит перечитать.
        access.tree = documents
        access.listing = listOf(note)
        switchSource(sources, model, FileSourceKind.Folder)
        model.start()

        val listed = assertIs<RootListState.Listed>(model.state, "выбранная папка показывает свои документы (TC-6)")
        assertEquals(listOf(note), listed.files)
        assertEquals("Документы", listed.title)
    }

    @Test
    fun TC_7_listFollowsTheActiveSourceInBothDirections() {
        val folder = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val repository = FakeAccess().apply {
            tree = TreeSource(id = "repo://docs", displayName = "docs.git")
            listing = listOf(guide)
        }
        val sources = ActiveSource(store = FakeStore(FileSourceKind.Folder), folder = folder, clone = repository)
        val model = modelOver(sources)
        model.start()
        assertEquals(listOf(note), assertIs<RootListState.Listed>(model.state).files)

        switchSource(sources, model, FileSourceKind.Clone)
        assertEquals(
            RootListState.Loading,
            model.state,
            "содержимое прежнего источника снимается сразу: показывать его под новым именем нельзя",
        )
        model.start()

        val ofClone = assertIs<RootListState.Listed>(model.state)
        assertEquals(listOf(guide), ofClone.files, "переключение меняет содержимое списка (TC-7)")
        assertEquals("docs.git", ofClone.title, "и заголовок — из того же источника, что файлы")

        switchSource(sources, model, FileSourceKind.Folder)
        model.start()

        assertEquals(
            listOf(note),
            assertIs<RootListState.Listed>(model.state).files,
            "и обратно: переключение работает в обе стороны, а не в одну",
        )
    }

    /**
     * Тот же дефект «половинки двух источников», что ловит
     * `TC_5_stateNeverMixesTitleOfOneSourceWithFilesOfAnother`, но на уровне
     * выше: с `SL-3` смениться может не дерево внутри шва, а сам шов.
     *
     * Правка, от которой тест обязан покраснеть: убрать в `read()` сверку
     * `sources.access !== access` — тогда файлы папки лягут на экран уже
     * после того, как активным стал репозиторий.
     */
    @Test
    fun TC_7_readingSourceThatChangedMidwayIsDiscardedNotShown() {
        val folder = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val repository = FakeAccess().apply {
            tree = TreeSource(id = "repo://docs", displayName = "docs.git")
            listing = listOf(guide)
        }
        val sources = ActiveSource(store = FakeStore(FileSourceKind.Folder), folder = folder, clone = repository)
        folder.gateListing()
        val model = modelOver(sources)

        model.start()
        // Пользователь переключил источник, пока перечисление папки висело.
        sources.switchTo(FileSourceKind.Clone)
        folder.releaseAll()

        val listed = assertIs<RootListState.Listed>(model.state)
        assertEquals("docs.git", listed.title, "на экране источник, который активен сейчас")
        assertEquals(
            listOf(guide),
            listed.files,
            "результат чтения прежнего источника выбрасывается, а не показывается под именем нового",
        )
    }

    /**
     * `FR-15`: отказ источника не запирает пользователя.
     *
     * Оракул — весь путь наружу: из состояния отказа переключение на другой
     * источник доходит до его файлов. Правка, от которой тест обязан
     * покраснеть: убрать `this.kind = kind` в `ActiveSource.switchTo` —
     * переключение перестаёт менять активный источник, и пользователь
     * остаётся в отказе прежнего. Проверено правкой: тест краснеет.
     *
     * Сказано честно: уникальной правки у этого теста нет — он складывает
     * два уже покрытых куска (отказ становится состоянием с текстом, смена
     * источника доводит до файлов) в один путь пользователя. Ценность
     * складывания в том, что путь целиком никто больше не проходит.
     *
     * Чего тест *не* проверяет и что поэтому не считается покрытым: доступно
     * ли меню на экране в состоянии отказа. `menuActions` о состоянии списка
     * не знает конструктивно, и утверждение про него здесь было бы вакуумным
     * (находка ревью `SL-3`, раунд 2). Свойство живёт в композиции —
     * `SourceMenu` поднят над разбором состояний в `RootListScreen`, — и
     * уходит в ручной прогон `TC-28`.
     */
    @Test
    fun TC_9_failedSourceStillLetsTheUserSwitchAway() {
        val folder = FakeAccess().apply {
            tree = documents
            listError = TreeAccessError.PermissionLost
        }
        val repository = FakeAccess().apply {
            tree = TreeSource(id = "repo://docs", displayName = "docs.git")
            listing = listOf(guide)
        }
        val sources = ActiveSource(store = FakeStore(FileSourceKind.Folder), folder = folder, clone = repository)
        val model = modelOver(sources)
        model.start()

        val failed = assertIs<RootListState.Failed>(model.state, "отозванное право — состояние с текстом (FR-15)")
        assertEquals(TreeAccessError.PermissionLost.userMessage(documents.displayName), failed.message)

        switchSource(sources, model, FileSourceKind.Clone)
        model.start()

        assertEquals(
            listOf(guide),
            assertIs<RootListState.Listed>(model.state).files,
            "выход из отказа есть и он работает: пользователь не заперт (FR-15)",
        )
    }

    /**
     * Разводка действий меню (`FR-12`) — та самая ветка, которую до находки
     * ревью `SL-3` держала композиция и потому не проверял никто.
     *
     * Правка, от которой тест обязан покраснеть: перепутать местами ветки
     * `SwitchToFolder`/`SwitchToClone` или заменить любую из них на `Unit` —
     * пункт меню перестанет что-либо делать, и ни один экранный тест этого не
     * увидит, потому что экранных тестов в проекте нет.
     */
    @Test
    fun TC_7_menuActionsAreRoutedToTheirEffects() {
        val folder = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val repository = FakeAccess().apply {
            tree = TreeSource(id = "repo://docs", displayName = "docs.git")
            listing = listOf(guide)
        }
        val sources = ActiveSource(store = FakeStore(FileSourceKind.Folder), folder = folder, clone = repository)
        val model = modelOver(sources)
        var folderDialogs = 0
        var cloningEntries = 0
        fun act(action: SourceAction) = applySourceAction(
            action = action,
            sources = sources,
            rootList = model,
            openFolder = { folderDialogs++ },
            goCloning = { cloningEntries++ },
        )

        act(SourceAction.OpenFolder)
        assertEquals(1, folderDialogs, "«открыть папку» показывает системный диалог, а не меняет источник молча")
        assertEquals(FileSourceKind.Folder, sources.kind, "и до ответа диалога источник прежний")

        act(SourceAction.Clone)
        assertEquals(1, cloningEntries, "«клонировать» ведёт на экран клонирования (FR-3)")

        act(SourceAction.SwitchToClone)
        assertEquals(FileSourceKind.Clone, sources.kind, "переключение доводит выбор до источника (FR-12)")
        model.start()
        assertEquals(listOf(guide), assertIs<RootListState.Listed>(model.state).files, "и до списка (FR-7)")

        act(SourceAction.SwitchToFolder)
        assertEquals(FileSourceKind.Folder, sources.kind, "в обратную сторону — тем же путём")
        assertEquals(1, folderDialogs, "и без лишнего системного диалога: папку уже выбирали")
    }

    /**
     * Единственный сегодня доступный пользователю путь смены источника:
     * активна папка, и он выбирает *другую* папку тем же действием
     * `ОТКРЫТЬ ПАПКУ…`. Вид источника при этом не меняется — меняется папка
     * за ним, и всё поведение держится на том, что такой выбор принимается.
     *
     * Правка, от которой тест обязан покраснеть: `if (this.kind == kind)
     * return false` в `ActiveSource.switchTo` — перечитки не будет, и экран
     * останется на файлах папки, право на которую платформа уже отдала
     * (находка ревью `SL-3`).
     */
    @Test
    fun TC_7_choosingAnotherFolderWhileTheFolderIsActiveIsAccepted() {
        val access = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val sources = ActiveSource(store = FakeStore(FileSourceKind.Folder), folder = access)
        val model = modelOver(sources)
        model.start()
        val tokenBefore = model.sourceToken

        // Платформа взяла право на другую папку: тот же шов, другое дерево.
        access.tree = TreeSource(id = "tree://other", displayName = "Другая")
        access.listing = listOf(guide)
        switchSource(sources, model, FileSourceKind.Folder)

        assertEquals(tokenBefore + 1, model.sourceToken, "выбор другой папки заказывает перечитку")
        model.start()
        val listed = assertIs<RootListState.Listed>(model.state)
        assertEquals("Другая", listed.title, "на экране новая папка, а не та, право на которую отдано")
        assertEquals(listOf(guide), listed.files)
    }

    /**
     * `TC-27` — кейс, дозаявленный слайсом `SL-3` по находке ревью: в спеке
     * его не было, и носить чужой номер он не должен.
     *
     * Несостоявшаяся смена источника не должна двигать список: перечитка гасит
     * плашку отказа открытия, которую пользователь мог не успеть прочесть
     * (`TC-23`), и заказывает лишнее чтение того же самого.
     *
     * Правка, от которой тест обязан покраснеть: вернуть в `switchSource`
     * безусловный `rootList.sourceChosen()`.
     */
    @Test
    fun TC_27_refusedSwitchDoesNotDisturbTheList() {
        val folder = FakeAccess().apply {
            tree = documents
            listing = listOf(note)
        }
        val sources = ActiveSource(store = FakeStore(FileSourceKind.Folder), folder = folder, clone = null)
        val model = modelOver(sources)
        model.start()
        model.documentOpenFailed("файл не найден")
        val tokenBefore = model.sourceToken

        switchSource(sources, model, FileSourceKind.Clone)

        assertEquals("файл не найден", model.notice, "отказа открытия несостоявшаяся смена источника не гасит")
        assertEquals(tokenBefore, model.sourceToken, "и перечитки не заказывает")
        assertEquals(listOf(note), assertIs<RootListState.Listed>(model.state).files, "список остаётся на месте")
    }

    /** Хранилище признака активного источника в памяти. */
    private class FakeStore(private var kind: FileSourceKind? = null) : ActiveSourceStore {
        override fun loadActiveSource(): FileSourceKind? = kind

        override fun saveActiveSource(kind: FileSourceKind) {
            this.kind = kind
        }
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

        // Список зовёт из шва ровно два метода — `heldTree` и `listDocuments`.
        // Остальные падают, а не отдают значение: модель, полезшая за
        // удержанным документом (соблазн реальный — `editorSourceFor` берёт
        // именно его), должна быть слышна. Ручательство при этом непрямое:
        // обращения идут из `scope.launch`, и `AssertionError` уходит
        // обработчику исключений — тест краснеет на несложившемся состоянии,
        // а не на самом падении (находка ревью `SL-3`, раунд 2).
        override fun heldSource(): DocumentSource? = fail("список документа не поднимает — это дело хостинга")

        override suspend fun open(source: DocumentSource): DocumentOpenResult =
            fail("список файлов не открывает — открывает редактор")

        override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult =
            fail("список не пишет")

        override fun release() = fail("право на дерево список не отдаёт")
    }
}
