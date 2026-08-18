package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.git.FakeGitSync
import io.github.olegnyr.adocmobile.git.FileStatus
import io.github.olegnyr.adocmobile.git.RepoFile
import io.github.olegnyr.adocmobile.git.RepoStatus
import io.github.olegnyr.adocmobile.git.RepositorySnapshot
import io.github.olegnyr.adocmobile.git.StatusEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Модель экрана репозитория — слайс `SL-4` фичи 007-git-sync: `TC-10`
 * (карточка ветки из данных клона) и списочная часть `FR-10` (пути и время;
 * статусы Git приходят этапом E2).
 *
 * Приём тот же, что в `CloneScreenModelTest`: `Dispatchers.Unconfined`,
 * подделка шва, наблюдаемое состояние модели.
 */
class RepositoryScreenModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sync = FakeGitSync()

    private val snapshot = RepositorySnapshot(
        directoryName = "platform-docs",
        branch = "main",
        remoteUrl = "https://github.com/acme/platform-docs.git",
    )

    @Test
    fun TC_10_branchCardExposesBranchAndRemoteFromClone() {
        sync.repository = snapshot
        sync.files = listOf(
            RepoFile(path = "docs/guide.adoc", lastModifiedEpochMs = 2_000),
            RepoFile(path = "readme.adoc", lastModifiedEpochMs = 1_000),
        )

        val model = RepositoryScreenModel(sync = sync, scope = scope)
        model.start()

        val ready = assertIs<RepositoryScreenState.Ready>(model.state)
        assertEquals("main", ready.repository.branch, "имя ветки — из клона (TC-10)")
        assertEquals("https://github.com/acme/platform-docs.git", ready.repository.remoteUrl)
        assertEquals("platform-docs", ready.repository.directoryName)
        assertEquals(sync.files, ready.files, "файлы клона доходят до модели как отдал шов (FR-10)")
    }

    @Test
    fun TC_38_cardCountersComeFromStatus() {
        sync.repository = snapshot
        sync.status = RepoStatus(
            ahead = 1,
            behind = 2,
            entries = listOf(
                StatusEntry("readme.adoc", FileStatus.Modified, insertions = 41, deletions = 6),
                StatusEntry("new.adoc", FileStatus.Added, insertions = 128, deletions = 0),
                StatusEntry("diagrams/flow.puml", FileStatus.Untracked),
            ),
        )

        val model = RepositoryScreenModel(sync = sync, scope = scope)
        model.start()

        val ready = assertIs<RepositoryScreenState.Ready>(model.state)
        assertEquals(1, ready.status.ahead, "↑N — локальные неотправленные коммиты (FR-9)")
        assertEquals(2, ready.status.behind, "↓N — от последнего известного origin (OQ-5)")
        assertEquals(3, ready.status.changeCount, "число изменений для карточки и кнопки COMMIT · N")
    }

    @Test
    fun TC_8_changedFilterKeepsOnlyDirtyFilesAndAllReturnsEverything() {
        val files = listOf(
            RepoFile("docs/guide.adoc", 3_000),
            RepoFile("readme.adoc", 1_000),
            RepoFile("untracked.puml", 2_000),
        )
        val status = RepoStatus(
            ahead = 0,
            behind = 0,
            entries = listOf(
                StatusEntry("readme.adoc", FileStatus.Modified, 4, 1),
                StatusEntry("untracked.puml", FileStatus.Untracked),
            ),
        )

        assertEquals(
            listOf("readme.adoc", "untracked.puml"),
            filteredRepoFiles(files, status, RepoFilter.Changed).map { it.path },
            "ИЗМЕНЁННЫЕ оставляет только не-чистые статусы, порядок списка сохраняется (TC-8)",
        )
        assertEquals(
            files,
            filteredRepoFiles(files, status, RepoFilter.All),
            "ВСЕ ФАЙЛЫ возвращает полный список (TC-8)",
        )
        assertEquals(
            listOf("docs/guide.adoc", "untracked.puml", "readme.adoc"),
            filteredRepoFiles(files, status, RepoFilter.Recent).map { it.path },
            "НЕДАВНИЕ — свежие правки сверху",
        )
    }

    @Test
    fun TC_38_cleanRepositoryShowsEmptyChangedFilterNotAnError() {
        sync.repository = snapshot
        sync.files = listOf(RepoFile("readme.adoc", 1_000))

        val model = RepositoryScreenModel(sync = sync, scope = scope)
        model.start()

        assertEquals(RepoFilter.Changed, model.filter, "умолчание фильтра — ИЗМЕНЁННЫЕ, как в макете")
        assertEquals(
            emptyList(),
            model.visibleFiles,
            "чистый репозиторий под фильтром ИЗМЕНЁННЫЕ — пустой список, не ошибка (UC-2)",
        )

        model.filter = RepoFilter.All
        assertEquals(listOf("readme.adoc"), model.visibleFiles.map { it.path })
        assertEquals(null, model.entryOf(sync.files.single()), "чистый файл без строки статуса")
    }

    @Test
    fun TC_10_withoutCloneScreenOffersCloning() {
        val model = RepositoryScreenModel(sync = sync, scope = scope)
        model.start()

        assertIs<RepositoryScreenState.NoRepository>(
            model.state,
            "без клона экран предлагает клонировать, а не показывает пустой репозиторий",
        )
    }

    @Test
    fun TC_10_restartRereadsStateAfterClone() {
        val model = RepositoryScreenModel(sync = sync, scope = scope)
        model.start()
        assertIs<RepositoryScreenState.NoRepository>(model.state)

        // Появился клон (экран клонирования завершился) — повторный вход
        // перечитывает состояние с диска, а не живёт памятью первого захода.
        sync.repository = snapshot
        model.start()
        assertIs<RepositoryScreenState.Ready>(model.state)
    }

    @Test
    fun TC_10_readFailureBecomesStateNotEternalLoading() {
        // Шов, у которого диск не прочитался: подделка с исключением.
        val broken = object : io.github.olegnyr.adocmobile.git.GitSync by sync {
            override suspend fun openRepository(): RepositorySnapshot =
                throw RuntimeException("диск сломан")
        }
        val model = RepositoryScreenModel(sync = broken, scope = scope)
        model.start()

        val failed = assertIs<RepositoryScreenState.Failed>(
            model.state,
            "отказ чтения — состояние с текстом, а не вечный Loading",
        )
        assertTrue(failed.message.isNotBlank())

        // «Повторить» после починки шва выходит из отказа.
        sync.repository = snapshot
        RepositoryScreenModel(sync = sync, scope = scope).also {
            it.start()
            assertIs<RepositoryScreenState.Ready>(it.state)
        }
    }

    @Test
    fun TC_41_pushFromCardZeroesAheadOnSuccessAndReportsFailureSeparately() {
        sync.repository = snapshot
        val model = RepositoryScreenModel(sync = sync, scope = scope)
        model.start()

        // Отказ: плашка с текстом, история цела, повтор возможен (FR-21).
        sync.pushResult = io.github.olegnyr.adocmobile.git.PushResult.Failed(
            io.github.olegnyr.adocmobile.git.GitPushError.Network,
        )
        model.pushRequested()
        assertEquals(
            io.github.olegnyr.adocmobile.git.GitPushError.Network.userMessage(),
            model.pushFailure,
            "отказ push доезжает до пользователя текстом (TC-41, FR-21)",
        )

        // Успех повторной отправки гасит плашку и перечитывает состояние.
        sync.pushResult = io.github.olegnyr.adocmobile.git.PushResult.Pushed
        model.pushRequested()
        assertEquals(null, model.pushFailure, "успешный повтор гасит отказ")
        assertEquals(2, sync.pushCount)

        // Отказ, принесённый хостингом с экрана коммита, показывается той же плашкой.
        model.showPushFailure("привет с экрана коммита")
        assertEquals("привет с экрана коммита", model.pushFailure)
    }

    @Test
    fun TC_7_statusLettersMatchTheDesign() {
        // Экранная половина TC-7: буква для каждой метки (NFR-9);
        // device-половина сверяет метки с настоящим git status.
        assertEquals("M", FileStatus.Modified.letter())
        assertEquals("A", FileStatus.Added.letter())
        assertEquals("D", FileStatus.Deleted.letter(), "удаление дозаявлено SL-5 — статус не исчезает бесследно")
        assertEquals("?", FileStatus.Untracked.letter())
    }

    @Test
    fun TC_38_cardAndRowLabelsAreHumanReadable() {
        assertEquals("1 изменение", changeCountLabel(1))
        assertEquals("3 изменения", changeCountLabel(3))
        assertEquals("5 изменений", changeCountLabel(5))
        assertEquals("11 изменений", changeCountLabel(11))
        assertEquals("21 изменение", changeCountLabel(21))

        assertEquals("+41 −6", diffCountLabel(StatusEntry("a", FileStatus.Modified, 41, 6)))
        assertEquals("+128", diffCountLabel(StatusEntry("b", FileStatus.Added, 128, 0)))
        assertEquals(null, diffCountLabel(StatusEntry("c", FileStatus.Untracked)), "без счёта — нет подписи, не нули")
        assertEquals(null, diffCountLabel(StatusEntry("d", FileStatus.Modified, 0, 0)), "пустой дифф не рисует пустую строку")
    }

    @Test
    fun TC_10_fileTimeLabelIsHumanReadable() {
        // Часть FR-10 «время последней правки»: подпись строкой, по-русски,
        // без выдуманной точности — градации как в макете «01».
        val now = 1_000_000_000_000
        assertEquals("только что", repoFileTimeLabel(now, now - 30_000))
        assertEquals("4 мин назад", repoFileTimeLabel(now, now - 4 * 60_000))
        assertEquals("2 ч назад", repoFileTimeLabel(now, now - 2 * 3_600_000))
        assertEquals("вчера", repoFileTimeLabel(now, now - 30 * 3_600_000))
        assertEquals("12 дн назад", repoFileTimeLabel(now, now - 12 * 86_400_000))
        assertEquals("только что", repoFileTimeLabel(now, now + 60_000), "время из будущего не рождает отрицательных подписей")
    }
}
