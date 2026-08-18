package io.github.olegnyr.adocmobile.git

import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.errors.LockFailedException
import org.eclipse.jgit.errors.NotSupportedException
import org.eclipse.jgit.lib.BranchTrackingStatus
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.patch.FileHeader.PatchType
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.eclipse.jgit.util.io.DisabledOutputStream

/**
 * JGit-реализация шва синхронизации (`SL-2` фичи 007-git-sync).
 *
 * Платформенная половина: типы JGit не покидают этот файл (`FR-1`), вся
 * долгая работа уходит на [io] (`NFR-1`), решения принимает общая половина —
 * арифметика прогресса в `CloneProgressTracker`, тексты ошибок в
 * `GitSyncError`.
 *
 * Клоны живут в [reposRoot] — каталоге внутри приватного `filesDir`
 * приложения: решение владельца `OQ-1` (клон в приватном хранилище, видимость
 * другим приложениям даст отдельный `DocumentsProvider` вторым этапом).
 * Привязку к `Context.filesDir` делает хостинг приложения при сборке экрана —
 * врезка согласуется на этапе UI.
 *
 * Сборочная особенность: в `:shared` JGit объявлен `compileOnly` — джарник с
 * Java records валит дексацию device-тестов KMP-модуля (факт спайка E0).
 * Классы в рантайм кладёт `:androidApp`; пропажу ловит его `verifyJGitPackaged`.
 *
 * @param reposRoot каталог, внутри которого создаются папки клонов
 * @param io диспетчер долгой работы. Обязан быть многопоточным пулом:
 * монитор шлёт события `trySendBlocking`, и на однопоточном диспетчере,
 * где идёт и сбор, это дедлок — в тестах подменяется только настоящим пулом
 * @param clock источник времени для скорости прогресса — параметр ради тестов
 */
class JGitSync(
    private val reposRoot: File,
    private val secrets: GitSecretStore? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : GitSync {

    /**
     * Клонирование с прогрессом (`TC-1`, `TC-2`, `TC-5`).
     *
     * `channelFlow`, а не `flow`: события шлёт колбэк `ProgressMonitor` из
     * недр блокирующего `call()`, где `emit` недоступен. Порядок сохраняет
     * канал; терминальное событие ровно одно: `Finished` уходит после того,
     * как JGit закончил и закрыт, — упасть после него уже некому.
     *
     * Отказ подчищает за собой (`FR-7`): каталог, созданный этой операцией,
     * удаляется даже при `Error` (память кончилась — полуклон всё равно не
     * должен сойти за целый), *занятая* папка не трогается вовсе — проверка
     * занятости идёт до первого касания диска, и чистка разрешена только
     * каталогу, которого до операции не было.
     *
     * Отмена собирающей корутины прерывает поток JGit ([runInterruptible])
     * и опрашивается монитором; каталог подчищается тем же путём.
     */
    override fun clone(request: CloneRequest, auth: GitAuth): Flow<CloneEvent> = channelFlow {
        if (!isSafeDirectoryName(request.directoryName)) {
            send(CloneEvent.Failed(GitSyncError.InvalidDirectory))
            return@channelFlow
        }
        // Второй рубеж FR-30 после формы — рубежи падают независимо: адрес с
        // user:secret@ ушёл бы в .git/config открытым текстом руками JGit.
        if (hasCredentialsInUrl(request.url)) {
            send(CloneEvent.Failed(GitSyncError.CredentialsInUrl))
            return@channelFlow
        }

        val target = File(reposRoot, request.directoryName)
        // Занята и папка с содержимым, и одноимённый файл, и каталог, который
        // не читается (listFiles == null): всё это не наш клон, трогать нельзя.
        val occupied = target.exists() &&
            (!target.isDirectory || target.listFiles()?.isEmpty() != true)
        if (occupied) {
            send(CloneEvent.Failed(GitSyncError.TargetExists))
            return@channelFlow
        }
        val createdByThisClone = !target.exists()

        val monitor = ThrottledMonitor(
            tracker = CloneProgressTracker(clock),
            clock = clock,
            cancelled = { !isActive },
            deliver = { progress -> trySendBlocking(CloneEvent.Progress(progress)) },
        )

        try {
            // runInterruptible: отмена корутины прерывает поток, и блокирующее
            // сетевое чтение JGit падает, а не живёт до таймаута транспорта.
            val snapshot = runInterruptible {
                Git.cloneRepository()
                    .setURI(request.url)
                    .setDirectory(target)
                    .apply { request.branch?.let { setBranch(it) } }
                    .apply { if (request.shallow) setDepth(1) }
                    .apply { credentialsFor(auth)?.let { setCredentialsProvider(it) } }
                    .setProgressMonitor(monitor)
                    .call()
                    .use { git -> snapshotOf(git, request.directoryName) }
            }
            // Токен запоминается только после успешного клона (FR-26) и
            // только в паре с хостом: единственный токен, предъявляемый
            // любому origin, утёк бы первому же чужому серверу по его 401
            // (находка security-ревью E2).
            if (auth is GitAuth.Token) {
                httpsHostOf(request.url)?.let { host -> secrets?.storeToken(host, auth.token) }
            }
            send(CloneEvent.Finished(snapshot))
        } catch (e: Throwable) {
            if (createdByThisClone) cleanUp(target)
            when {
                e is CancellationException -> throw e
                e is Exception -> send(CloneEvent.Failed(errorOf(e)))
                // Error: полуклон уже подчищен, падение остаётся честным.
                else -> throw e
            }
        }
    }.flowOn(io)

    /**
     * Первый каталог хранилища, похожий на репозиторий (`FR-8`, `TC-6`).
     *
     * «Первый», а не «единственный», — по компромиссу `OQ-7`: храним один
     * репозиторий, но единственность в модель не зашита. Каталог, который
     * JGit открыть не смог, репозиторием не считается. Сети вызов не касается
     * (`NFR-3`) — только чтение диска.
     */
    override suspend fun openRepository(): RepositorySnapshot? = withContext(io) {
        val dir = firstRepositoryDir() ?: return@withContext null
        Git.open(dir).use { git -> snapshotOf(git, dir.name) }
    }

    /**
     * Файлы рабочей копии первого репозитория (`FR-10` в части путей и
     * времени): всё, кроме служебного `.git`, путями от корня копии,
     * по пути без учёта регистра.
     *
     * Символьные ссылки не перечисляются — ни каталоги, ни файлы: содержимое
     * клона контролирует автор remote, и симлинк наружу превратил бы список
     * экрана в готовый escape-путь для моста (`RepoDocumentAccess`).
     */
    override suspend fun listFiles(): List<RepoFile> = withContext(io) {
        val workTree = firstRepositoryDir() ?: return@withContext emptyList()
        workTree.walkTopDown()
            // .git без учёта регистра: на регистронезависимом томе именной
            // фильтр иначе открылся бы (страховка повторного ревью SL-4).
            .onEnter { !it.name.equals(GIT_DIR, ignoreCase = true) && !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .map { file ->
                RepoFile(
                    path = file.relativeTo(workTree).invariantSeparatorsPath,
                    lastModifiedEpochMs = file.lastModified(),
                )
            }
            .sortedBy { it.path.lowercase() }
            .toList()
    }

    /**
     * Статус первого репозитория (`FR-9`, `FR-10`): статусы файлов из
     * `git status`, счётчики `↑`/`↓` из tracking-веток — всё от локального
     * состояния, без сети (OQ-5).
     *
     * Метки следуют `FR-10`: `M` — изменён (в копии или индексе), `A` —
     * добавлен, `?` — не отслеживается. Счётчики диффа считаются только
     * отслеживаемым текстовым файлам; бинарные и неотслеживаемые остаются
     * без счёта — честнее нулей.
     */
    override suspend fun status(): RepoStatus? = withContext(io) {
        val dir = firstRepositoryDir() ?: return@withContext null
        // Исключения не глотаются намеренно: «не прочитался» и «чистый» —
        // разные исходы (находка ревью SL-5). Отказ доезжает до модели
        // экрана, у которой для него есть состояние Failed.
        Git.open(dir).use { git ->
            val status = git.status().call()
            val tracking = BranchTrackingStatus.of(git.repository, git.repository.branch)

            // Один путь — одна строка: наборы JGit пересекаются (файл,
            // добавленный в индекс и правленный следом, попадает и в added,
            // и в modified), а changeCount обязан считать файлы, не наборы.
            val seen = mutableSetOf<String>()
            fun MutableList<StatusEntry>.addAll(paths: Set<String>, kind: FileStatus, counters: Map<String, Pair<Int, Int>>) {
                paths.toSortedSet().forEach { path ->
                    if (seen.add(path)) {
                        val diff = counters[path]
                        add(StatusEntry(path, kind, diff?.first, diff?.second))
                    }
                }
            }

            val changedPaths = status.added + status.changed + status.modified +
                status.removed + status.missing
            val counters = diffCounters(git, changedPaths)

            val entries = buildList {
                addAll(status.added, FileStatus.Added, counters)
                addAll(status.changed + status.modified, FileStatus.Modified, counters)
                addAll(status.removed + status.missing, FileStatus.Deleted, counters)
                addAll(status.untracked, FileStatus.Untracked, emptyMap())
            }

            RepoStatus(
                ahead = tracking?.aheadCount ?: 0,
                behind = tracking?.behindCount ?: 0,
                entries = entries,
            )
        }
    }

    /**
     * Авторство из конфига репозитория; `null` — не задано (`FR-22`).
     * Полупустая пара (имя без почты) тоже «не задано»: Git-коммит требует обе.
     */
    override suspend fun author(): CommitAuthor? = withContext(io) {
        val dir = firstRepositoryDir() ?: return@withContext null
        try {
            Git.open(dir).use { git ->
                val config = git.repository.config
                val name = config.getString(USER_SECTION, null, "name")
                val email = config.getString(USER_SECTION, null, "email")
                if (name.isNullOrBlank() || email.isNullOrBlank()) null else CommitAuthor(name, email)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Commit ровно перечисленных путей (`FR-17`, `TC-15`).
     *
     * `add` + `setOnly` вместе намеренно: `add` кладёт в индекс и
     * неотслеживаемые, и правленые файлы из списка, а `setOnly` гарантирует,
     * что в коммит не утечёт ничего сверх перечисленного — даже если индекс
     * уже содержал чужие staged-правки. Авторство пишется в конфиг
     * репозитория до коммита и переживает перезапуск (`FR-22`).
     */
    override suspend fun commit(
        paths: List<String>,
        message: String,
        author: CommitAuthor,
    ): CommitResult = withContext(io) {
        // Пустой список путей без setOnly закоммитил бы весь индекс, включая
        // чужие staged-правки, — рубеж дублирует валидацию модели и падает
        // независимо (находка ревью E2, прецедент isSafeDirectoryName).
        if (paths.isEmpty()) return@withContext CommitResult.Failed(GitCommitError.CommitFailed)
        val dir = firstRepositoryDir()
            ?: return@withContext CommitResult.Failed(GitCommitError.CommitFailed)
        try {
            Git.open(dir).use { git ->
                val config = git.repository.config
                config.setString(USER_SECTION, null, "name", author.name)
                config.setString(USER_SECTION, null, "email", author.email)
                config.save()

                // add стейджит новое и правленое, но НЕ удаления: отмеченный
                // D-файл стейджится rm-ом, иначе setOnly увидит «не в индексе».
                val (missing, present) = paths.partition { !File(dir, it).exists() }
                if (present.isNotEmpty()) {
                    val add = git.add()
                    present.forEach { add.addFilepattern(it) }
                    add.call()
                }
                if (missing.isNotEmpty()) {
                    val rm = git.rm().setCached(false)
                    missing.forEach { rm.addFilepattern(it) }
                    rm.call()
                }

                val commit = git.commit()
                    .setMessage(message)
                    .setAuthor(author.name, author.email)
                    .setCommitter(author.name, author.email)
                    .setSign(false)
                paths.forEach { commit.setOnly(it) }
                commit.call()
                CommitResult.Committed
            }
        } catch (e: Exception) {
            val locked = generateSequence<Throwable>(e) { it.cause }
                .any { it is LockFailedException }
            CommitResult.Failed(if (locked) GitCommitError.IndexLocked else GitCommitError.CommitFailed)
        }
    }

    /**
     * Push текущей ветки в origin (`FR-20`, `FR-21`).
     *
     * Пограничные случаи названы: без репозитория метод честно отдаёт
     * `PushFailed` (из потока commit→push недостижимо — commit упал бы
     * раньше); detached HEAD отдал бы SHA вместо имени ветки — в E2
     * недостижим, при конфликтах E3 сюда добавится явный гейт.
     *
     * Отказ классифицируется, а не глотается: non-fast-forward отличим от
     * сети и авторизации, потому что у каждого своё действие пользователя.
     * Локальную историю push не трогает никогда — JGit и не умеет иначе,
     * но контракт (`FR-21`) записан, а не подразумевается.
     */
    override suspend fun push(): PushResult = withContext(io) {
        val dir = firstRepositoryDir()
            ?: return@withContext PushResult.Failed(GitPushError.PushFailed)
        try {
            Git.open(dir).use { git ->
                val branch = git.repository.branch
                val results = git.push()
                    .setRemote(ORIGIN)
                    .add("refs/heads/$branch")
                    // Сохранённый токен переиспользуется без повторного ввода
                    // (FR-26) — только для https-хоста, которому он выдан.
                    .apply {
                        val origin = git.repository.config.getString("remote", ORIGIN, "url").orEmpty()
                        storedCredentials(origin)?.let { setCredentialsProvider(it) }
                    }
                    .call()
                val updates = results.flatMap { it.remoteUpdates }
                when {
                    updates.isEmpty() -> PushResult.Failed(GitPushError.PushFailed)

                    updates.all {
                        it.status == RemoteRefUpdate.Status.OK ||
                            it.status == RemoteRefUpdate.Status.UP_TO_DATE
                    } -> PushResult.Pushed

                    updates.any { it.status == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD } ->
                        PushResult.Failed(GitPushError.NonFastForward)

                    else -> PushResult.Failed(GitPushError.PushFailed)
                }
            }
        } catch (e: Exception) {
            PushResult.Failed(
                when (errorOf(e)) {
                    GitSyncError.Network -> GitPushError.Network
                    GitSyncError.AuthRequired -> GitPushError.AuthRequired
                    else -> GitPushError.PushFailed
                },
            )
        }
    }

    /**
     * Счётчики диффа `+N −M` по файлам: рабочая копия против HEAD.
     *
     * Один проход `DiffFormatter` на весь статус, а не дифф на файл: список
     * экрана рисует счётчики всем строкам сразу. Бинарные файлы счёта не
     * получают — их «строки» были бы враньём.
     */
    private fun diffCounters(git: Git, paths: Set<String>): Map<String, Pair<Int, Int>> {
        if (paths.isEmpty()) return emptyMap()
        val repo = git.repository
        val headTree = repo.resolve("HEAD^{tree}") ?: return emptyMap()
        val counters = mutableMapOf<String, Pair<Int, Int>>()

        DiffFormatter(DisabledOutputStream.INSTANCE).use { formatter ->
            formatter.setRepository(repo)
            formatter.isDetectRenames = false
            // Дифф только по путям статуса: без фильтра скан «дерево ↔ рабочая
            // копия» прочёл бы содержимое всех untracked-файлов, включая
            // игнорируемые, — мегабайты чтения ради выброшенного результата
            // (находка ревью SL-5).
            formatter.pathFilter = PathFilterGroup.createFromStrings(paths)
            val oldTree = CanonicalTreeParser().also { parser ->
                repo.newObjectReader().use { reader -> parser.reset(reader, headTree) }
            }
            for (entry in formatter.scan(oldTree, FileTreeIterator(repo))) {
                val header = formatter.toFileHeader(entry)
                if (header.patchType != PatchType.UNIFIED) continue
                var insertions = 0
                var deletions = 0
                for (edit in header.toEditList()) {
                    insertions += edit.endB - edit.beginB
                    deletions += edit.endA - edit.beginA
                }
                val path = entry.newPath.takeIf { it != DEV_NULL } ?: entry.oldPath
                counters[path] = insertions to deletions
            }
        }
        return counters
    }

    /**
     * Провайдер учётных данных для явно предъявленного способа (`FR-26`):
     * токен — имя пользователя с пустым паролем (механика из разведки, Q2).
     */
    private fun credentialsFor(auth: GitAuth): UsernamePasswordCredentialsProvider? = when (auth) {
        is GitAuth.None -> null
        is GitAuth.Token -> UsernamePasswordCredentialsProvider(auth.token.value, "")
    }

    /**
     * Провайдер из сохранённого токена для конкретного origin; `null` —
     * идём анонимно. Токен предъявляется только хосту, для которого был
     * сохранён, и только по `https`: чужой сервер или голый `http` секрета
     * не увидят (`NFR-8`, находка security-ревью E2).
     */
    private fun storedCredentials(remoteUrl: String): UsernamePasswordCredentialsProvider? {
        val host = httpsHostOf(remoteUrl) ?: return null
        return secrets?.tokenFor(host)?.let { UsernamePasswordCredentialsProvider(it.value, "") }
    }

    /** Хост адреса, если схема `https`; иначе `null` — токену туда нельзя. */
    private fun httpsHostOf(url: String): String? = try {
        val uri = java.net.URI(url)
        uri.host?.lowercase()?.takeIf { uri.scheme.equals("https", ignoreCase = true) }
    } catch (_: Exception) {
        null
    }

    /**
     * Первый каталог хранилища с `.git` — единый для всех операций (OQ-7:
     * репозиторий один, но единственность не зашита). Именно *первый каталог*,
     * а не «первый открывшийся»: три метода с разными правилами выбора могли
     * бы показать карточку одного репозитория и статус другого (находка ревью
     * SL-5); каталог, который не открывается, — отказ, не кандидат на пропуск.
     */
    private fun firstRepositoryDir(): File? =
        reposRoot.listFiles()
            .orEmpty()
            .sortedBy { it.name.lowercase() }
            .firstOrNull { it.isDirectory && File(it, GIT_DIR).exists() }

    private fun snapshotOf(git: Git, directoryName: String): RepositorySnapshot =
        RepositorySnapshot(
            directoryName = directoryName,
            branch = git.repository.branch,
            // Чистка кредов и здесь: пользователь мог встроить токен в URL, и
            // сырой адрес из .git/config утёк бы в карточку ветки (NFR-8).
            remoteUrl = sanitizedGitUrl(
                git.repository.config.getString("remote", "origin", "url").orEmpty(),
            ),
        )

    /**
     * Чистка каталога, созданного неудачным клоном: сначала `.git`, затем
     * остальное. Порядок важен: если удаление оборвётся на середине, каталог
     * без `.git` уже не сойдёт за репозиторий для [openRepository] (`TC-5`).
     *
     * Path-API `deleteRecursively`, а не файловый: `File.deleteRecursively`
     * *следует за симлинк-каталогами*, а checkout мог уже разложить ссылку
     * автора remote наружу — уборка огрызка снесла бы каталоги приложения
     * (security-находка повторного ревью `SL-4`). Path-вариант удаляет саму
     * ссылку, не её цель.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun cleanUp(target: File) {
        runCatching { File(target, GIT_DIR).toPath().deleteRecursively() }
        runCatching { target.toPath().deleteRecursively() }
    }

    /**
     * Исключение JGit — в платформенно-нейтральный класс отказа (`FR-7`).
     *
     * Разбор по причинам, а не по текстам сообщений, где это возможно: тексты
     * JGit локализуются и меняются между версиями. Текстовая проверка осталась
     * только для авторизации — у неё нет типизированной причины.
     */
    private fun errorOf(e: Exception): GitSyncError {
        val causes = generateSequence<Throwable>(e) { it.cause }
        return when {
            causes.any { it is URISyntaxException || it is NotSupportedException } ->
                GitSyncError.InvalidUrl

            causes.any {
                it is UnknownHostException || it is ConnectException ||
                    it is SocketTimeoutException || it is NoRouteToHostException ||
                    it is SocketException || it is SSLException
            } -> GitSyncError.Network

            causes.any {
                it is TransportException &&
                    (it.message.orEmpty().contains("auth", ignoreCase = true) ||
                        it.message.orEmpty().contains("401"))
            } -> GitSyncError.AuthRequired

            // Remote не нашёлся: с точки зрения пользователя это «сервер
            // недоступен или адрес не тот» — сеть, а не внутренняя ошибка.
            causes.any { it is InvalidRemoteException || it is TransportException } -> GitSyncError.Network

            else -> GitSyncError.CloneFailed
        }
    }

    private companion object {
        const val GIT_DIR = ".git"
        const val DEV_NULL = "/dev/null"
        const val USER_SECTION = "user"
        const val ORIGIN = "origin"
    }
}

/**
 * Имя папки клона, которому можно доверять: один сегмент пути, без
 * разделителей, `..` и управляющих символов.
 *
 * Проверка обязана жить на платформенной границе, а не только в форме:
 * `File(reposRoot, "../databases")` вышел бы за корень хранилища, а чистка
 * неудачного клона рекурсивно снесла бы чужой каталог. Тот же приём, что
 * `isSafeTreeSegment` document-слоя, — рубежи падают независимо.
 */
internal fun isSafeDirectoryName(name: String): Boolean =
    name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        name.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7F }

/**
 * Монитор JGit поверх общего трекера: считает общая половина, здесь — только
 * перекладка колбэков и дроссель.
 *
 * Дроссель нужен, потому что JGit зовёт `update(1)` на каждый объект: тысячи
 * событий на клон захлебнут канал и композицию. Наружу уходит смена фазы,
 * смена процента и не чаще раза в [minIntervalMs] — этого хватает и цифрам,
 * и полосе прогресса (`FR-5`).
 *
 * [cancelled] пробрасывает отмену собирающей корутины внутрь JGit: транспорт
 * сам прерывает операцию, оставляя обычный путь очистки.
 */
private class ThrottledMonitor(
    private val tracker: CloneProgressTracker,
    private val clock: () -> Long,
    private val cancelled: () -> Boolean,
    private val deliver: (CloneProgress) -> Unit,
    private val minIntervalMs: Long = 100,
) : ProgressMonitor {

    private var lastSentAt: Long? = null
    private var lastPercent: Int? = null

    override fun start(totalTasks: Int) {}

    override fun beginTask(title: String?, totalWork: Int) {
        send(tracker.beginPhase(title.orEmpty(), totalWork), force = true)
    }

    override fun update(completed: Int) {
        send(tracker.advance(completed), force = false)
    }

    override fun endTask() {}

    override fun isCancelled(): Boolean = cancelled()

    override fun showDuration(enabled: Boolean) {}

    private fun send(progress: CloneProgress, force: Boolean) {
        val now = clock()
        val sentAt = lastSentAt
        val due = force ||
            sentAt == null ||
            progress.percent != lastPercent ||
            now - sentAt >= minIntervalMs
        if (!due) return
        lastSentAt = now
        lastPercent = progress.percent
        deliver(progress)
    }
}
