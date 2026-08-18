package io.github.olegnyr.adocmobile.git

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git

/**
 * Pull, слияние и жизненный цикл на устройстве — этап E3 фичи 007-git-sync:
 * `TC-11`, `TC-12`, `TC-14` (pull), `TC-21`, `TC-25`, `TC-26` (конфликт),
 * `TC-32` (stale-lock после смерти процесса).
 *
 * Всё офлайн, против `file://`-remote: сеть в автотестах запрещена
 * (`NFR-10`). «Чужие правки» пишет seed-репозиторий фикстуры и отправляет в
 * тот же bare-remote — так воспроизводится и обновление, и конфликт.
 */
class JGitPullDeviceTest {

    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val root = File(filesDir, "devicetest-pull-root")
    private val remote = File(filesDir, "devicetest-pull-remote")
    private val seed = File(filesDir, "devicetest-pull-seed")

    private lateinit var remoteUri: String
    private lateinit var workTree: File

    private val author = CommitAuthor("Тест", "test@example.com")

    @BeforeTest
    fun setUp() {
        listOf(root, remote, seed).forEach { deleteTreeNoFollow(it) }
        root.mkdirs()
        remoteUri = "file://" + remote.absolutePath

        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        Git.init().setInitialBranch("main").setDirectory(seed).call().use { git ->
            File(seed, "readme.adoc").writeText("= Репозиторий\n\nБаза.\n")
            git.add().addFilepattern(".").call()
            commit(git, "база")
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }

        // Полный клон: pull по shallow-границе — отдельная история, для
        // слияния нужна общая база (`depth 1` её обрезает).
        runBlocking {
            sync().clone(
                CloneRequest(url = remoteUri, directoryName = "repo", branch = "main", shallow = false),
            ).toList()
        }
        workTree = File(root, "repo")
    }

    @AfterTest
    fun cleanUp() {
        listOf(root, remote, seed).forEach { deleteTreeNoFollow(it) }
    }

    private fun sync(): GitSync = JGitSync(reposRoot = root)

    private fun commit(git: Git, message: String) {
        git.commit().setMessage(message).setAuthor(author.name, author.email)
            .setCommitter(author.name, author.email).setSign(false).call()
    }

    /** Чужая правка: пишется в seed и уходит в общий remote. */
    private fun pushFromSeed(file: String, text: String, message: String) {
        Git.open(seed).use { git ->
            File(seed, file).writeText(text)
            git.add().addFilepattern(file).call()
            commit(git, message)
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun deleteTreeNoFollow(dir: File) {
        runCatching { dir.toPath().deleteRecursively() }
    }

    @Test
    fun TC_11_pullBringsRemoteChangesIntoWorkingCopy() {
        pushFromSeed("readme.adoc", "= Репозиторий\n\nЧужая правка.\n", "чужой коммит")

        val result = runBlocking { sync().pull() }

        val updated = assertIs<PullResult.Updated>(result, "pull сообщает «обновлено» (FR-14, TC-11)")
        assertEquals(listOf("readme.adoc"), updated.files, "названы файлы, которые pull изменил (FR-15)")
        assertEquals(
            "= Репозиторий\n\nЧужая правка.\n",
            File(workTree, "readme.adoc").readText(),
            "содержимое совпало с версией remote (TC-11)",
        )
        assertEquals(0, runBlocking { sync().status() }!!.behind, "↓ обнулился после pull (FR-11)")
    }

    @Test
    fun TC_12_pullWithoutDivergenceReportsAlreadyUpToDate() {
        val headBefore = Git.open(workTree).use { it.repository.resolve("HEAD") }

        val result = runBlocking { sync().pull() }

        assertIs<PullResult.AlreadyUpToDate>(result, "«уже актуально» (TC-12)")
        val headAfter = Git.open(workTree).use { it.repository.resolve("HEAD") }
        assertEquals(headBefore, headAfter, "коммитов не создано (TC-12)")
    }

    @Test
    fun TC_14_pullWithUnreachableRemoteFailsAndLeavesStateIntact() {
        val statusBefore = runBlocking { sync().status() }!!
        val headBefore = Git.open(workTree).use { it.repository.resolve("HEAD") }
        remote.deleteRecursively()

        val result = runBlocking { sync().pull() }

        val failed = assertIs<PullResult.Failed>(result)
        assertEquals(GitPullError.Network, failed.error, "недостижимый remote — сетевой отказ (TC-14)")
        val statusAfter = runBlocking { sync().status() }!!
        assertEquals(statusBefore, statusAfter, "status до и после отказа идентичен (TC-14)")
        assertEquals(headBefore, Git.open(workTree).use { it.repository.resolve("HEAD") })
    }

    @Test
    fun TC_21_conflictingPullStopsMergeAndNamesConflictedFiles() {
        pushFromSeed("readme.adoc", "= Репозиторий\n\nИх версия.\n", "их правка")
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nНаша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "наша правка")
        }

        val result = runBlocking { sync().pull() }

        val conflicted = assertIs<PullResult.Conflicted>(result, "конфликт — исход, не ошибка (TC-21)")
        assertEquals(listOf("readme.adoc"), conflicted.paths)

        val hunks = runBlocking { sync().conflictHunks("readme.adoc") }
        assertEquals(1, hunks.size, "участок разобран общим кодом (FR-23)")
        assertTrue(hunks.single().ours.any { "Наша" in it }, "локальная сторона — наша версия")
        assertTrue(hunks.single().theirs.any { "Их" in it }, "удалённая сторона — версия origin")
    }

    @Test
    fun TC_25_resolvingAllHunksFinishesMergeWithCleanStatus() {
        pushFromSeed("readme.adoc", "= Репозиторий\n\nИх версия.\n", "их правка")
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nНаша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "наша правка")
        }
        runBlocking { sync().pull() }

        val hunks = runBlocking { sync().conflictHunks("readme.adoc") }
        val resolvedText = parseConflictFile(File(workTree, "readme.adoc").readText())
            .resolvedText(List(hunks.size) { ConflictChoice.Theirs })

        runBlocking {
            assertIs<CommitResult.Committed>(sync().resolveConflict("readme.adoc", resolvedText))
            assertIs<CommitResult.Committed>(sync().finishMerge(author))
        }

        val status = runBlocking { sync().status() }!!
        assertEquals(0, status.changeCount, "после слияния рабочая копия чиста (TC-25)")
        val text = File(workTree, "readme.adoc").readText()
        assertTrue("Их версия." in text, "выбранная сторона легла в файл")
        assertTrue(
            listOf("<<<<<<<", "=======", ">>>>>>>").none { it in text },
            "маркеров конфликта в файле не осталось (TC-25)",
        )
        Git.open(workTree).use { git ->
            assertEquals(2, git.log().setMaxCount(1).call().first().parentCount, "создан merge-коммит")
        }
    }

    @Test
    fun TC_26_abortReturnsRepositoryToPrePullStateKeepingLocalCommits() {
        pushFromSeed("readme.adoc", "= Репозиторий\n\nИх версия.\n", "их правка")
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nНаша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "наша правка")
        }
        val headBeforePull = Git.open(workTree).use { it.repository.resolve("HEAD") }
        runBlocking { sync().pull() }

        runBlocking { assertIs<CommitResult.Committed>(sync().abortMerge()) }

        assertEquals(
            headBeforePull,
            Git.open(workTree).use { it.repository.resolve("HEAD") },
            "HEAD вернулся к состоянию до pull, локальный коммит цел (TC-26)",
        )
        assertEquals(
            "= Репозиторий\n\nНаша версия.\n",
            File(workTree, "readme.adoc").readText(),
            "рабочая копия — наша версия без маркеров (TC-26)",
        )
        assertEquals(0, runBlocking { sync().status() }!!.changeCount, "состояние согласовано")
    }

    @Test
    fun TC_32_staleIndexLockIsDetectedAndClearedThenOperationsResume() {
        // Замок, оставшийся от убитого процесса (механика проверена спайком E0).
        val lock = File(File(workTree, ".git"), "index.lock")
        assertTrue(lock.createNewFile())

        File(workTree, "readme.adoc").appendText("правка после смерти процесса\n")
        val blocked = runBlocking {
            sync().commit(listOf("readme.adoc"), "после смерти", author)
        }
        val failed = assertIs<CommitResult.Failed>(blocked, "занятый индекс не даёт коммитить")
        assertEquals(GitCommitError.IndexLocked, failed.error, "отказ распознан как замок (NFR-5)")

        assertTrue(runBlocking { sync().clearStaleLock() }, "замок найден и снят по подтверждению (TC-32)")
        assertTrue(!lock.exists())

        val afterHealing = runBlocking {
            sync().commit(listOf("readme.adoc"), "после лечения", author)
        }
        assertIs<CommitResult.Committed>(afterHealing, "следующая операция проходит (TC-32)")
        assertEquals(0, runBlocking { sync().status() }!!.changeCount, "репозиторий цел")

        assertTrue(!runBlocking { sync().clearStaleLock() }, "снимать нечего — метод честно говорит «замка не было»")
    }
}
