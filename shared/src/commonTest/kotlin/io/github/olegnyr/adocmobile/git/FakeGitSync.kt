package io.github.olegnyr.adocmobile.git

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Подделка интерфейса синхронизации для `commonTest` (`NFR-10`): экраны и
 * модели проверяются без JGit и без устройства.
 *
 * Сценарий задаётся списком событий на каждый вызов [clone]: очередная
 * подписка съедает очередной сценарий, что позволяет проверять повторный
 * запуск после отказа (`TC-4`). Запросы записываются — тест сверяет, что до
 * шва дошло ровно то, что ввёл пользователь.
 *
 * Подделка *валидирует* сценарии контрактом интерфейса — «сколько угодно
 * `Progress`, затем ровно один терминал»: тест модели экрана, построенный на
 * невозможном сценарии (без терминала, с событиями после терминала), обязан
 * упасть здесь, а не молча зазеленеть.
 */
class FakeGitSync : GitSync {

    /** Сценарии событий: подписка номер N проигрывает элемент номер N. */
    val scripts = mutableListOf<List<CloneEvent>>()

    /** Запросы, дошедшие до шва, в порядке подписок. */
    val requests = mutableListOf<CloneRequest>()

    /** Способы авторизации тех же подписок, параллельно [requests]. */
    val auths = mutableListOf<GitAuth>()

    /** Ответ [openRepository]: `null` — клона на устройстве нет. */
    var repository: RepositorySnapshot? = null

    /** Ответ [listFiles]: файлы рабочей копии, как их отдал бы платформенный шов. */
    var files: List<RepoFile> = emptyList()

    /** Ответ [status]: `null` — клона нет (по умолчанию согласован с [repository]). */
    var status: RepoStatus? = null

    /**
     * Ворота перед каждым событием — для тестов, наблюдающих промежуточные
     * состояния модели: реальный шов отдаёт события во времени, и тест с
     * синхронным `Unconfined` иначе увидит только конечное состояние.
     * `null` — события эмитятся без пауз.
     */
    var beforeEach: (suspend (CloneEvent) -> Unit)? = null

    private var subscriptions = 0

    override fun clone(request: CloneRequest, auth: GitAuth): Flow<CloneEvent> = flow {
        // Номер подписки фиксируется первым действием билдера: два потока,
        // собираемые вперемежку, получат каждый свой сценарий.
        val index = subscriptions++
        val script = scripts.getOrNull(index)
            ?: error("сценарий для подписки №${index + 1} не задан")
        requireWellFormed(script)
        requests += request
        auths += auth
        script.forEach { event ->
            beforeEach?.invoke(event)
            emit(event)
        }
    }

    override suspend fun openRepository(): RepositorySnapshot? = repository

    override suspend fun listFiles(): List<RepoFile> = files

    override suspend fun status(): RepoStatus? = status ?: repository?.let {
        RepoStatus(ahead = 0, behind = 0, entries = emptyList())
    }

    /** Сохранённое авторство; тест выставляет `null`, чтобы проверить запрос первого коммита. */
    var author: CommitAuthor? = null

    /** Ответ [commit]; запросы записываются в [commits]. */
    var commitResult: CommitResult = CommitResult.Committed

    /** Записанные вызовы [commit] в порядке поступления. */
    val commits = mutableListOf<CommitCall>()

    data class CommitCall(val paths: List<String>, val message: String, val author: CommitAuthor)

    /** Ворота перед ответом [author] — для тестов гонки двойного тапа (TC-40). */
    var beforeAuthor: (suspend () -> Unit)? = null

    override suspend fun author(): CommitAuthor? {
        beforeAuthor?.invoke()
        return author
    }

    override suspend fun commit(
        paths: List<String>,
        message: String,
        author: CommitAuthor,
    ): CommitResult {
        commits += CommitCall(paths, message, author)
        // Авторство запоминается до исхода — как в JGit-реализации, которая
        // пишет конфиг перед коммитом (находка ревью E2: расхождение фейка
        // с контрактом рождает ложно-зелёные тесты).
        this.author = author
        return commitResult
    }

    /** Ответ [pull]; число вызовов — [pullCount]. */
    var pullResult: PullResult = PullResult.AlreadyUpToDate
    var pullCount: Int = 0
        private set

    /** Ворота перед ответом [pull] — для наблюдения промежуточных фаз модели. */
    var beforePull: (suspend () -> Unit)? = null

    override suspend fun pull(): PullResult {
        beforePull?.invoke()
        pullCount += 1
        return pullResult
    }

    /** Тексты конфликтных файлов по путям — ответ [conflictedText]. */
    var conflictedTexts: Map<String, String> = emptyMap()

    override suspend fun conflictedText(path: String): String? = conflictedTexts[path]

    /** Разрешённые файлы: путь → записанный текст. */
    val resolved = mutableMapOf<String, String>()
    var resolveResult: CommitResult = CommitResult.Committed

    /** Ворота перед записью разрешённого файла — для тестов гонки (TC-45). */
    var beforeResolve: (suspend () -> Unit)? = null

    override suspend fun resolveConflict(path: String, resolvedText: String): CommitResult {
        beforeResolve?.invoke()
        if (resolveResult is CommitResult.Committed) resolved[path] = resolvedText
        return resolveResult
    }

    /** Исходы завершения и отмены слияния; счётчики вызовов — для проверки FR-24/FR-25. */
    var finishResult: CommitResult = CommitResult.Committed
    var finishCount: Int = 0
        private set
    var abortCount: Int = 0
        private set

    override suspend fun finishMerge(author: CommitAuthor): CommitResult {
        finishCount += 1
        return finishResult
    }

    override suspend fun abortMerge(): CommitResult {
        abortCount += 1
        return CommitResult.Committed
    }

    /** Ответ [clearStaleLock]: был ли замок. */
    var staleLock: Boolean = false

    override suspend fun clearStaleLock(): Boolean {
        val had = staleLock
        staleLock = false
        return had
    }

    /** Ответ [push]; число вызовов — [pushCount]. */
    var pushResult: PushResult = PushResult.Pushed
    var pushCount: Int = 0
        private set

    override suspend fun push(): PushResult {
        pushCount += 1
        return pushResult
    }

    private fun requireWellFormed(script: List<CloneEvent>) {
        require(script.isNotEmpty() && script.last() !is CloneEvent.Progress) {
            "сценарий обязан завершаться терминальным событием (контракт GitSync.clone)"
        }
        require(script.dropLast(1).all { it is CloneEvent.Progress }) {
            "терминальное событие в сценарии ровно одно, и оно последнее (контракт GitSync.clone)"
        }
    }
}
