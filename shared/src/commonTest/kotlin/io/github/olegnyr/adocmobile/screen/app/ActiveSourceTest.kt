package io.github.olegnyr.adocmobile.screen.app

import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeListResult
import io.github.olegnyr.adocmobile.document.TreeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Активный источник и его хранение — слайс `SL-3` фичи 009 (`FR-2`, `FR-10`,
 * `FR-11`, `FR-12`; `TC-6`, `TC-7`, `TC-10`), плюс общая половина гейта
 * `FR-17` (`TC-12`): «Git-разделов нет вовсе» начинается с того, что источнику
 * нет реализации.
 *
 * Номера `TC-6` и `TC-7` носит и `RootListModelTest`: кейс один, половины
 * разные — там поведение списка, здесь правила выбора.
 *
 * Проверяются правила выбора, а не экран: какой шов активен, что предлагается
 * пользователю на первом запуске и в меню, и переживает ли выбор перезапуск.
 * Композиции здесь нет по `NFR-10` — приём тот же, что у `AppNavigator`.
 *
 * «Перезапуск процесса» изображается вторым экземпляром [ActiveSource] над тем
 * же хранилищем: именно так и происходит на устройстве — состояние в памяти
 * умирает, хранилище остаётся.
 */
class ActiveSourceTest {

    private val folder = FakeAccess("tree://saf")
    private val clone = FakeAccess("repo://docs")

    private fun sources(
        store: FakeStore = FakeStore(),
        clone: DocumentTreeAccess? = this.clone,
    ) = ActiveSource(store = store, folder = folder, clone = clone)

    @Test
    fun TC_10_emptyStorageWithNothingHeldMeansNoSourceRatherThanASilentChoice() {
        val sources = sources()

        assertNull(sources.kind, "ни клона, ни удержанной папки — «источника нет» (FR-2, FR-11)")
        assertNull(sources.access, "и швом это состояние не притворяется: читать нечего")
    }

    /**
     * `FR-2` и `TC-6` ставят условие пустого состояния на источники, а не на
     * хранилище: «нет ни клона, ни удержанной папки».
     *
     * Правка, от которой тест обязан покраснеть: убрать в [ActiveSource]
     * подъём единственного удержанного источника (`?: soleHeld()`) — тогда
     * обновление приложения выглядело бы потерей папки, право на которую
     * никуда не делось.
     */
    @Test
    fun TC_6_withoutAStoredChoiceTheOnlyHeldSourceBecomesActive() {
        val store = FakeStore()
        folder.tree = TreeSource(id = "tree://saf", displayName = "Документы")

        val sources = sources(store, clone = null)

        assertEquals(FileSourceKind.Folder, sources.kind, "папку уже отдали — её файлы и надо показать (FR-2)")
        assertSame(folder, sources.access)
        assertNull(
            store.saved,
            "но прочтение обстановки не выдаётся за решение человека: в хранилище оно не уезжает",
        )
    }

    @Test
    fun TC_10_withoutAStoredChoiceAndTwoHeldSourcesNothingIsChosenSilently() {
        val store = FakeStore()
        folder.tree = TreeSource(id = "tree://saf", displayName = "Документы")
        clone.tree = TreeSource(id = "repo://docs", displayName = "docs.git")

        val sources = sources(store)

        assertNull(sources.kind, "выбрать за пользователя один из двух нельзя — это и есть молчаливый выбор (TC-10)")
        assertNull(store.saved, "и прочтение обстановки в хранилище не уезжает: решение принимает человек")
        assertEquals(
            listOf(
                SourceAction.OpenFolder,
                SourceAction.Clone,
                SourceAction.SwitchToFolder,
                SourceAction.SwitchToClone,
            ),
            sources.menuActions,
            "но решать пользователю есть чем: к каждому удержанному источнику предложен переход — " +
                "иначе единственным путём к уже существующему источнику была бы попытка завести его заново",
        )
    }

    @Test
    fun TC_10_theChosenSourceIsLiftedByANewInstanceOverTheSameStorage() {
        val store = FakeStore()
        sources(store).switchTo(FileSourceKind.Clone)

        // Процесс выгружен и запущен заново: состояние в памяти потеряно.
        val afterRestart = sources(store)

        assertEquals(FileSourceKind.Clone, afterRestart.kind, "приложение открывается тем источником, каким закрыли (FR-11)")
        assertSame(clone, afterRestart.access, "и активен именно его шов")
    }

    @Test
    fun TC_10_storedCloneWithoutGitImplementationReadsAsNoSourceAndIsNotErased() {
        val store = FakeStore()
        sources(store).switchTo(FileSourceKind.Clone)

        // Та же сборка, но без реализации Git (`FR-17`): клона у неё нет.
        val withoutGit = sources(store, clone = null)

        assertNull(withoutGit.kind, "источник, которому нет реализации, — не источник: остаётся пустое состояние (FR-2)")
        assertEquals(
            FileSourceKind.Clone,
            store.saved,
            "но записанный выбор не стирается: сборка с Git обязана поднять его прежним (FR-11)",
        )
    }

    @Test
    fun TC_6_firstRunOffersExactlyTwoActions() {
        val sources = sources()

        assertEquals(
            listOf(SourceAction.OpenFolder, SourceAction.Clone),
            sources.startActions,
            "первый запуск: ОТКРЫТЬ ПАПКУ и КЛОНИРОВАТЬ, и ничего больше (FR-2)",
        )
        assertEquals(
            sources.startActions,
            sources.menuActions,
            "переключать нечего, пока не выбран ни один источник: меню равно пустому состоянию",
        )
        assertTrue(
            "склонируйте" in noSourceMessage(sources.startActions),
            "пояснение называет оба пути, потому что оба предложены",
        )
    }

    @Test
    fun TC_12_withoutGitImplementationOnlyTheFolderActionIsOffered() {
        val sources = sources(clone = null)

        assertEquals(
            listOf(SourceAction.OpenFolder),
            sources.startActions,
            "на платформе без Git-реализации Git-действий нет вовсе, а не серыми (FR-17, UC-1 1a)",
        )
        assertEquals(listOf(SourceAction.OpenFolder), sources.menuActions)
        assertFalse(
            "склонируйте" in noSourceMessage(sources.startActions),
            "и пояснение не зовёт туда, куда из этой сборки не попасть",
        )
    }

    @Test
    fun TC_7_menuOffersTheOtherSourceInBothDirections() {
        val store = FakeStore()
        val sources = sources(store)

        sources.switchTo(FileSourceKind.Folder)
        assertEquals(
            listOf(SourceAction.OpenFolder, SourceAction.Clone, SourceAction.SwitchToClone),
            sources.menuActions,
            "из папки предлагается уйти в репозиторий (FR-12)",
        )

        sources.switchTo(FileSourceKind.Clone)
        assertEquals(
            listOf(SourceAction.OpenFolder, SourceAction.Clone, SourceAction.SwitchToFolder),
            sources.menuActions,
            "и обратно — переключение работает в обе стороны (TC-7)",
        )
    }

    @Test
    fun TC_7_switchingChangesTheActiveAccessAndIsRemembered() {
        val store = FakeStore()
        val sources = sources(store)

        sources.switchTo(FileSourceKind.Folder)
        assertSame(folder, sources.access, "список читает папку")
        assertEquals(FileSourceKind.Folder, store.saved, "выбор запоминается сразу, а не при выходе (FR-11)")

        sources.switchTo(FileSourceKind.Clone)
        assertSame(clone, sources.access, "после переключения список читает уже репозиторий (TC-7)")
        assertEquals(FileSourceKind.Clone, store.saved)
    }

    @Test
    fun TC_10_switchingToASourceWithoutImplementationIsRefusedNotSilentlyAccepted() {
        val store = FakeStore()
        val sources = sources(store, clone = null)

        sources.switchTo(FileSourceKind.Clone)

        assertNull(sources.kind, "источника, которому нет реализации, не выбрать: он и в меню не предлагался")
        assertNull(store.saved, "и в хранилище такой выбор не уезжает")
    }

    /** Хранилище признака в памяти: изображает `SharedPreferences` платформы. */
    private class FakeStore : ActiveSourceStore {
        var saved: FileSourceKind? = null

        override fun loadActiveSource(): FileSourceKind? = saved

        override fun saveActiveSource(kind: FileSourceKind) {
            saved = kind
        }
    }

    /**
     * Шов-пустышка: из всего интерфейса [ActiveSource] спрашивает одно —
     * есть ли у источника дерево ([heldTree]), и только пока записанного
     * выбора нет. Остальное падает: обращение отсюда означало бы, что правило
     * выбора полезло читать файлы.
     */
    private class FakeAccess(private val id: String) : DocumentTreeAccess {
        /** Что источник уже держит; `null` — у него пока ничего нет. */
        var tree: TreeSource? = null

        override fun heldTree(): TreeSource? = tree

        override suspend fun open(source: DocumentSource): DocumentOpenResult = fail("$id: не читается")

        override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult =
            fail("$id: не пишется")

        override fun heldSource(): DocumentSource? = fail("$id: выбор источника документов не спрашивает")

        override suspend fun listDocuments(): TreeListResult = fail("$id: выбор источника не перечисляет файлы")

        override fun release() = fail("$id: право отдаёт не выбор источника")
    }
}
