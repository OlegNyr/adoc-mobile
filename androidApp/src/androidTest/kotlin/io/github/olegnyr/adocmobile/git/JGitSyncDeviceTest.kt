package io.github.olegnyr.adocmobile.git

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Инструментальные тесты JGit-реализации шва (`SL-2` фичи 007-git-sync):
 * `TC-1`, `TC-2`, `TC-5`, `TC-6` против локального `file://`-remote, без сети
 * (`NFR-10`: сеть и авторизация недетерминированы — им место в ручных кейсах).
 *
 * Живут в `androidApp/src/androidTest`, а не в `androidDeviceTest` KMP-модуля:
 * конвейер дексации device-тестов библиотеки не пропускает JGit — факт спайка
 * E0 (research 007), отражён риском в плане.
 *
 * `file://`-remote строится руками через JGit — тем же способом, что в спайке:
 * bare-репозиторий с историей из двух коммитов, чтобы `depth 1` было что резать.
 */
class JGitSyncDeviceTest {

    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val root = File(filesDir, "devicetest-git-root")
    private val remote = File(filesDir, "devicetest-git-remote")
    private val seed = File(filesDir, "devicetest-git-seed")

    private lateinit var remoteUri: String

    @BeforeTest
    fun setUpRemote() {
        listOf(root, remote, seed).forEach { deleteTreeNoFollow(it) }
        root.mkdirs()
        remoteUri = "file://" + remote.absolutePath

        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        Git.init().setInitialBranch("main").setDirectory(seed).call().use { git ->
            File(seed, "readme.adoc").writeText("= Репозиторий\n\nПервая версия.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("первый").setAuthor("Тест", "test@example.com")
                .setCommitter("Тест", "test@example.com").setSign(false).call()

            File(seed, "docs").mkdirs()
            File(seed, "docs/guide.adoc").writeText("= Руководство\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("второй").setAuthor("Тест", "test@example.com")
                .setCommitter("Тест", "test@example.com").setSign(false).call()

            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }
    }

    @AfterTest
    fun cleanUp() {
        listOf(root, remote, seed).forEach { deleteTreeNoFollow(it) }
    }

    /**
     * Уборка без следования за симлинками: содержимое клона задаёт remote,
     * и `File.deleteRecursively` пошёл бы по ссылке-каталогу за пределы
     * фикстуры (урок повторного security-ревью SL-4).
     */
    @OptIn(ExperimentalPathApi::class)
    private fun deleteTreeNoFollow(dir: File) {
        runCatching { dir.toPath().deleteRecursively() }
    }

    private fun sync(): GitSync = JGitSync(reposRoot = root)

    private fun request(shallow: Boolean = true) = CloneRequest(
        url = remoteUri,
        directoryName = "repo",
        branch = "main",
        shallow = shallow,
    )

    @Test
    fun TC_1_cloneCreatesWorkingCopyMatchingRemoteAndEndsWithFinished() {
        val events = runBlocking { sync().clone(request()).toList() }

        val finished = assertIs<CloneEvent.Finished>(events.last(), "поток завершается Finished")
        assertEquals("repo", finished.repository.directoryName)
        assertEquals("main", finished.repository.branch)
        assertEquals(remoteUri, finished.repository.remoteUrl)

        val workTree = File(root, "repo")
        assertEquals(
            "= Репозиторий\n\nПервая версия.\n",
            File(workTree, "readme.adoc").readText(),
            "содержимое рабочей копии совпадает с remote",
        )
        assertTrue(File(workTree, "docs/guide.adoc").isFile, "вложенный файл доехал")

        assertTrue(
            events.dropLast(1).all { it is CloneEvent.Progress },
            "до терминального события идут только события прогресса",
        )
        assertTrue(events.filterIsInstance<CloneEvent.Progress>().isNotEmpty(), "прогресс был показан (FR-5)")
    }

    @Test
    fun TC_2_shallowCloneLeavesSingleCommitInHistory() {
        runBlocking { sync().clone(request(shallow = true)).toList() }

        Git.open(File(root, "repo")).use { git ->
            assertEquals(1, git.log().call().count(), "depth=1 оставляет ровно один коммит (FR-4)")
        }
    }

    @Test
    fun TC_2_fullCloneKeepsWholeHistoryWhenShallowIsOff() {
        runBlocking { sync().clone(request(shallow = false)).toList() }

        Git.open(File(root, "repo")).use { git ->
            assertEquals(2, git.log().call().count(), "без depth история приезжает целиком")
        }
    }

    @Test
    fun TC_5_failedCloneLeavesNoDirectoryPretendingToBeARepository() {
        val badRemote = "file://" + File(filesDir, "no-such-remote").absolutePath
        val events = runBlocking {
            sync().clone(CloneRequest(url = badRemote, directoryName = "repo")).toList()
        }

        val failed = assertIs<CloneEvent.Failed>(events.last(), "клон с несуществующего remote — отказ")
        assertEquals(
            GitSyncError.Network,
            failed.error,
            "недостижимый remote классифицируется как сетевой отказ (FR-7)",
        )
        assertTrue(!File(root, "repo").exists(), "частичный клон не остался на диске (FR-7)")
        assertNull(runBlocking { sync().openRepository() }, "приложение не считает каталог валидным репозиторием")
    }

    @Test
    fun TC_5_cancelledCloneLeavesNoDirectory() {
        // Отмена посреди операции — сборщик уходит после первого события.
        runBlocking {
            withTimeout(30_000) {
                sync().clone(request()).first()
            }
        }

        // Чистка идёт в потоке операции после прерывания — дожидаемся её.
        val deadline = System.currentTimeMillis() + 10_000
        while (File(root, "repo").exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue(!File(root, "repo").exists(), "отменённый клон не остался на диске (FR-7, TC-5)")
        assertNull(runBlocking { sync().openRepository() })
    }

    @Test
    fun TC_5_occupiedByPlainFileIsRefusedAndFileSurvives() {
        val occupied = File(root, "repo")
        occupied.writeText("я файл, а не каталог")

        val events = runBlocking { sync().clone(request()).toList() }

        val failed = assertIs<CloneEvent.Failed>(events.last())
        assertEquals(GitSyncError.TargetExists, failed.error, "файл на месте папки — занято, а не мишень для чистки")
        assertEquals("я файл, а не каталог", occupied.readText(), "чужой файл пережил отказ")
    }

    @Test
    fun TC_5_directoryNameWithPathSeparatorsIsRefusedBeforeTouchingDisk() {
        val events = runBlocking {
            sync().clone(CloneRequest(url = remoteUri, directoryName = "../escaped")).toList()
        }

        val failed = assertIs<CloneEvent.Failed>(events.single())
        assertEquals(GitSyncError.InvalidDirectory, failed.error)
        assertTrue(!File(filesDir, "escaped").exists(), "выход за корень хранилища невозможен")
    }

    @Test
    fun TC_5_occupiedTargetIsRefusedAndSurvivesUntouched() {
        val occupied = File(root, "repo").apply { mkdirs() }
        File(occupied, "чужое.txt").writeText("не трогать")

        val events = runBlocking { sync().clone(request()).toList() }

        val failed = assertIs<CloneEvent.Failed>(events.last())
        assertEquals(GitSyncError.TargetExists, failed.error)
        assertEquals(
            "не трогать",
            File(occupied, "чужое.txt").readText(),
            "занятая папка не зачищена под клон: удалять чужие файлы нельзя",
        )
    }

    @Test
    fun TC_7_statusLabelsAndCountersMatchGitStatus() {
        runBlocking { sync().clone(request(shallow = false)).toList() }
        val workTree = File(root, "repo")

        // Изменённый, добавленный и неотслеживаемый — три метки FR-10.
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nВторая версия, длиннее.\nНовая строка.\n")
        File(workTree, "staged.adoc").writeText("= Добавленный\n")
        Git.open(workTree).use { it.add().addFilepattern("staged.adoc").call() }
        File(workTree, "notes.txt").writeText("черновик\n")

        val status = runBlocking { sync().status() }!!
        val byPath = status.entries.associateBy { it.path }

        assertEquals(FileStatus.Modified, byPath["readme.adoc"]?.status, "правленый файл — M (TC-7)")
        assertEquals(FileStatus.Added, byPath["staged.adoc"]?.status, "добавленный в индекс — A (TC-7)")
        assertEquals(FileStatus.Untracked, byPath["notes.txt"]?.status, "новый без индекса — ? (TC-7)")
        assertEquals(3, status.changeCount)

        val modified = byPath["readme.adoc"]!!
        assertTrue((modified.insertions ?: 0) > 0, "у правленого файла есть счётчик добавленных строк")
        assertTrue((modified.deletions ?: 0) > 0, "и счётчик удалённых")
        assertNull(byPath["notes.txt"]!!.insertions, "неотслеживаемому счёт не рисуется")
    }

    @Test
    fun TC_9_localCommitIncrementsAheadCounterWithoutNetwork() {
        // Штатная форма клона — shallow depth 1 (умолчание продукта):
        // счётчики и статус обязаны работать на shallow-границе (ревью SL-5).
        runBlocking { sync().clone(request()).toList() }
        val workTree = File(root, "repo")

        assertEquals(0, runBlocking { sync().status() }!!.ahead, "свежий клон ничего не должен origin")

        File(workTree, "readme.adoc").appendText("\nПравка.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            git.commit().setMessage("локальный").setAuthor("Тест", "test@example.com")
                .setCommitter("Тест", "test@example.com").setSign(false).call()
        }

        val after = runBlocking { sync().status() }!!
        assertEquals(1, after.ahead, "локальный коммит увеличивает ↑ на 1 (TC-9, без сети — OQ-5)")
        assertEquals(0, after.behind)
        assertEquals(0, after.changeCount, "после коммита рабочая копия чиста")
    }

    @Test
    fun TC_15_commitTakesOnlyListedPathsAndLeavesTheRestModified() {
        runBlocking { sync().clone(request(shallow = false)).toList() }
        val workTree = File(root, "repo")
        File(workTree, "readme.adoc").appendText("правка раз\n")
        File(workTree, "docs/guide.adoc").appendText("правка два\n")

        val result = runBlocking {
            sync().commit(
                paths = listOf("readme.adoc"),
                message = "только readme",
                author = CommitAuthor("Тест", "test@example.com"),
            )
        }
        assertIs<CommitResult.Committed>(result)

        val status = runBlocking { sync().status() }!!
        assertEquals(
            listOf("docs/guide.adoc"),
            status.entries.map { it.path },
            "снятый файл остаётся изменённым после коммита (TC-15)",
        )
        assertEquals(1, status.ahead, "коммит поднял ↑ (TC-9, FR-11)")
    }

    @Test
    fun TC_15_untrackedFileIsCommittableWhenExplicitlyChecked() {
        runBlocking { sync().clone(request(shallow = false)).toList() }
        val workTree = File(root, "repo")
        File(workTree, "новый.adoc").writeText("= Новый\n")

        val result = runBlocking {
            sync().commit(
                paths = listOf("новый.adoc"),
                message = "новый файл",
                author = CommitAuthor("Тест", "test@example.com"),
            )
        }
        assertIs<CommitResult.Committed>(result)
        assertEquals(0, runBlocking { sync().status() }!!.changeCount, "неотслеживаемый закоммичен целиком")
    }

    @Test
    fun TC_20_commitCarriesAuthorAndItIsRememberedForTheNextOne() {
        runBlocking { sync().clone(request(shallow = false)).toList() }
        val workTree = File(root, "repo")

        assertNull(runBlocking { sync().author() }, "свежий клон авторства не знает (FR-22)")

        File(workTree, "readme.adoc").appendText("правка\n")
        runBlocking {
            sync().commit(listOf("readme.adoc"), "с авторством", CommitAuthor("Инженер", "eng@example.com"))
        }

        Git.open(workTree).use { git ->
            val head = git.log().setMaxCount(1).call().first()
            assertEquals("Инженер", head.authorIdent.name, "коммит несёт заданное имя (TC-20)")
            assertEquals("eng@example.com", head.authorIdent.emailAddress)
        }
        assertEquals(
            CommitAuthor("Инженер", "eng@example.com"),
            runBlocking { sync().author() },
            "авторство запомнено в конфиге репозитория и переживает новый экземпляр шва (FR-22)",
        )
    }

    @Test
    fun TC_18_pushDeliversLocalCommitToRemote() {
        runBlocking { sync().clone(request()).toList() }
        val workTree = File(root, "repo")
        File(workTree, "readme.adoc").appendText("к отправке\n")
        runBlocking {
            sync().commit(listOf("readme.adoc"), "к отправке", CommitAuthor("Тест", "test@example.com"))
        }

        val result = runBlocking { sync().push() }

        assertIs<PushResult.Pushed>(result, "push из shallow-клона доходит до remote (TC-18, H2)")
        val localHead = Git.open(workTree).use { it.repository.resolve("refs/heads/main") }
        val remoteHead = Git.open(remote).use { it.repository.resolve("refs/heads/main") }
        assertEquals(localHead, remoteHead, "лог remote содержит новый коммит (TC-18)")
        assertEquals(0, runBlocking { sync().status() }!!.ahead, "после push ↑ обнулился (FR-11)")
    }

    @Test
    fun TC_19_pushToUnreachableRemoteFailsAndKeepsLocalCommit() {
        runBlocking { sync().clone(request()).toList() }
        val workTree = File(root, "repo")
        File(workTree, "readme.adoc").appendText("останется локально\n")
        runBlocking {
            sync().commit(listOf("readme.adoc"), "останется локально", CommitAuthor("Тест", "test@example.com"))
        }

        // Remote исчезает — «нет сети» в офлайн-постановке file://.
        remote.deleteRecursively()
        val result = runBlocking { sync().push() }

        val failed = assertIs<PushResult.Failed>(result)
        assertEquals(GitPushError.Network, failed.error, "недостижимый remote — сетевой класс отказа (TC-19)")
        assertEquals(1, runBlocking { sync().status() }!!.ahead, "локальный коммит цел (FR-21, TC-19)")
    }

    @Test
    fun TC_19_nonFastForwardIsClassifiedAndLocalHistoryIntact() {
        runBlocking { sync().clone(request()).toList() }
        val workTree = File(root, "repo")

        // Конкурирующий push из seed: origin уходит вперёд без сети.
        File(seed, "readme.adoc").appendText("чужая правка\n")
        Git.open(seed).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            git.commit().setMessage("чужой").setAuthor("Другой", "other@example.com")
                .setCommitter("Другой", "other@example.com").setSign(false).call()
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }

        File(workTree, "readme.adoc").appendText("наша правка\n")
        runBlocking {
            sync().commit(listOf("readme.adoc"), "наш", CommitAuthor("Тест", "test@example.com"))
        }
        val result = runBlocking { sync().push() }

        val failed = assertIs<PushResult.Failed>(result)
        assertEquals(GitPushError.NonFastForward, failed.error, "origin впереди — non-fast-forward (TC-19)")
        assertEquals(1, runBlocking { sync().status() }!!.ahead, "коммит не откатан (FR-21)")
    }

    @Test
    fun TC_15_deletedFileCommitStagesTheDeletion() {
        runBlocking { sync().clone(request(shallow = false)).toList() }
        val workTree = File(root, "repo")
        File(workTree, "docs/guide.adoc").delete()

        val statusBefore = runBlocking { sync().status() }!!
        assertEquals(
            FileStatus.Deleted,
            statusBefore.entries.single { it.path == "docs/guide.adoc" }.status,
            "пропавший файл виден меткой D (дозаявка SL-5)",
        )

        val result = runBlocking {
            sync().commit(listOf("docs/guide.adoc"), "удаление", CommitAuthor("Тест", "test@example.com"))
        }
        assertIs<CommitResult.Committed>(result)
        assertEquals(0, runBlocking { sync().status() }!!.changeCount, "удаление закоммичено, копия чиста")
        Git.open(workTree).use { git ->
            val head = git.log().setMaxCount(1).call().first()
            assertEquals("удаление", head.shortMessage)
        }
    }

    @Test
    fun TC_15_foreignPreStagedFileDoesNotLeakIntoCommit() {
        runBlocking { sync().clone(request(shallow = false)).toList() }
        val workTree = File(root, "repo")

        // «Чужая» staged-правка мимо нашего экрана.
        File(workTree, "foreign.adoc").writeText("= Чужое\n")
        Git.open(workTree).use { it.add().addFilepattern("foreign.adoc").call() }

        File(workTree, "readme.adoc").appendText("наше\n")
        runBlocking {
            sync().commit(listOf("readme.adoc"), "только наше", CommitAuthor("Тест", "test@example.com"))
        }

        val status = runBlocking { sync().status() }!!
        assertEquals(
            listOf("foreign.adoc"),
            status.entries.map { it.path },
            "чужой staged-файл не утёк в коммит и остался в индексе (FR-17, KDoc commit)",
        )
        Git.open(workTree).use { git ->
            val tree = git.repository.resolve("HEAD^{tree}")
            val walk = org.eclipse.jgit.treewalk.TreeWalk.forPath(git.repository, "foreign.adoc", tree)
            assertNull(walk, "foreign.adoc в HEAD отсутствует")
        }
    }

    @Test
    fun TC_15_emptyPathListIsRefusedByTheSeamItself() {
        runBlocking { sync().clone(request()).toList() }
        val result = runBlocking {
            sync().commit(emptyList(), "пустой", CommitAuthor("Тест", "test@example.com"))
        }
        assertIs<CommitResult.Failed>(result, "пустой список путей не коммитит весь индекс — рубеж шва (FR-17)")
    }

    @Test
    fun TC_6_clonedRepositoryOpensAgainWithoutRecloning() {
        runBlocking { sync().clone(request()).toList() }

        // Новый экземпляр шва — вся память прежнего потеряна, как при
        // перезапуске процесса; состояние читается только с диска.
        val reopened = runBlocking { sync().openRepository() }

        val snapshot = assertNotNull(reopened, "клон найден после «перезапуска» (FR-8)")
        assertEquals("repo", snapshot.directoryName)
        assertEquals("main", snapshot.branch)
        assertEquals(remoteUri, snapshot.remoteUrl)
    }

    @Test
    fun TC_6_openRepositoryWithoutCloneReturnsNull() {
        assertNull(runBlocking { sync().openRepository() }, "пустое хранилище — репозитория нет")
    }
}
