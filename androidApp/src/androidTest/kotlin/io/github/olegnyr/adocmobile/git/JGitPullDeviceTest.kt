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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import io.github.olegnyr.adocmobile.screen.git.ConflictScreenModel
import io.github.olegnyr.adocmobile.screen.git.ConflictScreenPhase
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

        val text = assertNotNull(
            runBlocking { sync().conflictedText("readme.adoc") },
            "шов отдаёт текст конфликтного файла целиком",
        )
        val parsed = assertIs<ConflictParseResult.Parsed>(parseConflictFile(text)).file
        assertEquals(1, parsed.hunks.size, "участок разобран общим кодом (FR-23)")
        assertTrue(parsed.hunks.single().oursText().any { "Наша" in it }, "локальная сторона — наша версия")
        assertTrue(parsed.hunks.single().theirsText().any { "Их" in it }, "удалённая сторона — версия origin")
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

        // Через продуктовый путь целиком: модель экрана читает файл швом,
        // разбирает, собирает и пишет. Прежний кейс звал parseConflictFile
        // сам и потому маскировал блокер (ревью E3).
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Шов на Unconfined: модель зовёт suspend-методы, и с Dispatchers.IO
        // тест проверял бы состояние раньше, чем оно появится. Клон здесь не
        // делается, а для чтения и записи однопоточность безопасна.
        val model = ConflictScreenModel(
            sync = JGitSync(reposRoot = root, io = Dispatchers.Unconfined),
            path = "readme.adoc",
            scope = scope,
        )
        model.start()
        assertTrue(model.hunks.isNotEmpty(), "участки прочитаны швом и разобраны")
        repeat(model.hunks.size) { index ->
            model.choose(ConflictChoice.Theirs)
            if (index < model.hunks.lastIndex) model.nextHunk()
        }
        model.finishRequested(author)
        assertIs<ConflictScreenPhase.Merged>(model.phase, "слияние завершено через модель экрана")

        val status = runBlocking { sync().status() }!!
        assertEquals(0, status.changeCount, "после слияния рабочая копия чиста (TC-25)")
        val text = File(workTree, "readme.adoc").readText()
        assertTrue("Их версия." in text, "выбранная сторона легла в файл")
        assertTrue("= Репозиторий" in text, "неконфликтный текст файла сохранён (TC-42, блокер ревью E3)")
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
    fun TC_26_abortKeepsUncommittedWorkInOtherFiles() {
        // Штатный сценарий из-за OQ-4: перед pull приложение записывает
        // правки открытого документа на диск некоммитнутыми.
        pushFromSeed("readme.adoc", "= Репозиторий\n\nИх версия.\n", "их правка")
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nНаша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "наша правка")
        }
        // Файл ОТСЛЕЖИВАЕМЫЙ и изменён без коммита — именно этот случай уносил
        // reset --hard. Нетрекаемый файл его бы и не заметил (ревью E3).
        File(workTree, "заметка.adoc").writeText("= Заметка\n\nБаза.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("заметка.adoc").call()
            commit(git, "заметка в истории")
        }
        File(workTree, "заметка.adoc").writeText("= Заметка\n\nНесохранённая работа.\n")
        runBlocking { sync().pull() }

        runBlocking { assertIs<CommitResult.Committed>(sync().abortMerge()) }

        assertEquals(
            "= Заметка\n\nНесохранённая работа.\n",
            File(workTree, "заметка.adoc").readText(),
            "отмена слияния не стирает правки вне слияния (блокер ревью E3, reset --merge)",
        )
    }

    @Test
    fun TC_46_abortWithoutMergeInProgressIsRefusedNotFalselyReported() {
        // Ложный успех уводил файл с маркерами в историю следующим коммитом.
        val result = runBlocking { sync().abortMerge() }

        val failed = assertIs<CommitResult.Failed>(result, "слияния нет — отменять нечего")
        assertEquals(GitCommitError.MergeNotInProgress, failed.error)
    }

    @Test
    fun TC_46_abortRestoresFileDeletedByTheMerge() {
        // origin удалил файл, локально он не менялся, конфликт в другом файле.
        Git.open(seed).use { git ->
            File(seed, "удаляемый.adoc").writeText("= Удаляемый\n")
            git.add().addFilepattern("удаляемый.adoc").call()
            commit(git, "добавили файл")
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }
        runBlocking { sync().pull() }
        assertTrue(File(workTree, "удаляемый.adoc").isFile, "файл приехал в копию")

        Git.open(seed).use { git ->
            File(seed, "удаляемый.adoc").delete()
            git.rm().addFilepattern("удаляемый.adoc").call()
            File(seed, "readme.adoc").writeText("= Репозиторий\n\nИх версия.\n")
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "удалили файл и правка")
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nНаша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "наша правка")
        }
        runBlocking { sync().pull() }

        runBlocking { assertIs<CommitResult.Committed>(sync().abortMerge()) }

        assertTrue(
            File(workTree, "удаляемый.adoc").isFile,
            "отмена вернула файл, удалённый слиянием (блокер ревью E3)",
        )
        assertEquals(0, runBlocking { sync().status() }!!.changeCount, "состояние согласовано")
    }

    @Test
    fun TC_47_conflictSeamRefusesEscapesSymlinksAndNonUtf8() {
        // Свой кейс рубежей: прежде работа приписывалась кейсу про мост
        // документов, который про конфликтный шов ничего не говорит.
        assertNull(runBlocking { sync().conflictedText("../побег.adoc") }, "выход за копию отвергнут")
        assertNull(runBlocking { sync().conflictedText(".git/config") }, "служебный каталог закрыт")
        assertIs<CommitResult.Failed>(
            runBlocking { sync().resolveConflict("../побег.adoc", "текст") },
            "запись за пределы копии отвергнута",
        )

        // Симлинк наружу — рубеж NOFOLLOW.
        val secret = File(filesDir, "devicetest-pull-secret.txt")
        secret.writeText("секрет вне копии")
        val link = File(workTree, "ссылка.adoc")
        try {
            android.system.Os.symlink(secret.absolutePath, link.absolutePath)
            assertNull(runBlocking { sync().conflictedText("ссылка.adoc") }, "чтение по симлинку отвергнуто")
            assertIs<CommitResult.Failed>(
                runBlocking { sync().resolveConflict("ссылка.adoc", "перезапись") },
                "запись по симлинку отвергнута",
            )
            assertEquals("секрет вне копии", secret.readText(), "файл за копией не тронут")
        } finally {
            link.delete()
            secret.delete()
        }

        // Не-UTF-8: readText() заменил бы байты на U+FFFD и закоммитил мусор.
        val cp1251 = File(workTree, "кириллица.adoc")
        cp1251.writeBytes(byteArrayOf(0xCF.toByte(), 0xF0.toByte(), 0xE8.toByte(), 0xE2.toByte(), 0xE5.toByte(), 0xF2.toByte()))
        assertNull(
            runBlocking { sync().conflictedText("кириллица.adoc") },
            "файл не в UTF-8 не читается — переписать его значит испортить (блокер ревью E3)",
        )
        cp1251.delete()
    }

    @Test
    fun TC_48_seamRefusesTextWithMarkersOnItsOwn() {
        // Сторож шва — независимо от модели экрана: половина «проверка в
        // модели и в шве» прежде держалась на чтении кода (замечание
        // независимой проверки), подделка шва сторожа не воспроизводит.
        pushFromSeed("readme.adoc", "= Репозиторий\n\nИх версия.\n", "их правка")
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nНаша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "наша правка")
        }
        runBlocking { sync().pull() }

        val withMarkers = "= Репозиторий\n\n<<<<<<< HEAD\nнаша\n=======\nих\n>>>>>>> origin/main\n"
        val result = runBlocking { sync().resolveConflict("readme.adoc", withMarkers) }

        val failed = assertIs<CommitResult.Failed>(result, "шов сам отвергает текст с маркерами")
        assertEquals(GitCommitError.MarkersLeft, failed.error)
        assertTrue(
            "<<<<<<<" in File(workTree, "readme.adoc").readText(),
            "файл не переписан — на диске остался конфликтный оригинал",
        )
    }

    @Test
    fun TC_48_asciidocWithNestedExampleBlockCanBeResolvedAndMerged() {
        // Блокер независимой проверки: сторож считал маркером любой ряд «=»,
        // и на легальном AsciiDoc пользователь навсегда получал отказ.
        // Здесь путь идёт целиком: pull → модель → шов → merge-коммит.
        val withExample = listOf(
            "= Документ",
            "",
            "[NOTE]",
            "====",
            "Внешний блок.",
            "",
            "=======",
            "Вложенный блок.",
            "=======",
            "====",
            "",
        ).joinToString("\n")

        Git.open(seed).use { git ->
            File(seed, "пример.adoc").writeText(withExample + "Их версия.\n")
            git.add().addFilepattern("пример.adoc").call()
            commit(git, "их правка примера")
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }
        File(workTree, "пример.adoc").writeText(withExample + "Наша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("пример.adoc").call()
            commit(git, "наша правка примера")
        }
        assertIs<PullResult.Conflicted>(runBlocking { sync().pull() })

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val model = ConflictScreenModel(
            sync = JGitSync(reposRoot = root, io = Dispatchers.Unconfined),
            path = "пример.adoc",
            scope = scope,
        )
        model.start()
        assertTrue(model.hunks.isNotEmpty(), "участок разобран, несмотря на «=======» в тексте")
        repeat(model.hunks.size) { index ->
            model.choose(ConflictChoice.Ours)
            if (index < model.hunks.lastIndex) model.nextHunk()
        }
        model.finishRequested(author)

        assertIs<ConflictScreenPhase.Merged>(
            model.phase,
            "легальный AsciiDoc с вложенным example-блоком доходит до merge-коммита, а не упирается в сторож",
        )
        val text = File(workTree, "пример.adoc").readText()
        assertTrue("Вложенный блок." in text, "вложенный блок остался в файле")
        assertTrue("Наша версия." in text, "выбранная сторона применена")
        assertEquals(0, runBlocking { sync().status() }!!.changeCount, "рабочая копия чиста после слияния")
    }

    @Test
    fun TC_49_abortKeepsLocalWorkThatMergeDidNotBring() {
        // Файл изменён локальным коммитом (значит различается между HEAD и
        // MERGE_HEAD — прежний набор его включал), а поверх лежит
        // несохранённая в истории правка. Отмена не должна её трогать: чужая
        // сторона этот файл не приносила.
        Git.open(seed).use { git ->
            File(seed, "локальный.adoc").writeText("= Локальный\n\nБаза.\n")
            git.add().addFilepattern("локальный.adoc").call()
            commit(git, "база локального файла")
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }
        runBlocking { sync().pull() }

        File(workTree, "локальный.adoc").writeText("= Локальный\n\nНаша закоммиченная версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("локальный.adoc").call()
            commit(git, "наша правка локального файла")
        }

        pushFromSeed("readme.adoc", "= Репозиторий\n\nИх версия.\n", "их правка")
        File(workTree, "readme.adoc").writeText("= Репозиторий\n\nНаша версия.\n")
        Git.open(workTree).use { git ->
            git.add().addFilepattern("readme.adoc").call()
            commit(git, "наша правка readme")
        }

        // Правка в рабочей копии, не в индексе: с застейдженной JGit вовсе
        // отказывается начинать слияние (выяснено пробой на устройстве), и
        // сценарий рецензента живьём не воспроизводится — записано в журнале.
        File(workTree, "локальный.adoc").writeText("= Локальный\n\nНесохранённая работа.\n")

        runBlocking { sync().pull() }
        runBlocking { assertIs<CommitResult.Committed>(sync().abortMerge()) }

        assertEquals(
            "= Локальный\n\nНесохранённая работа.\n",
            File(workTree, "локальный.adoc").readText(),
            "правка в файле, которого слияние не приносило, пережила отмену",
        )
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
