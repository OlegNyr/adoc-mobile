package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.git.CommitAuthor
import io.github.olegnyr.adocmobile.git.CommitResult
import io.github.olegnyr.adocmobile.git.FileStatus
import io.github.olegnyr.adocmobile.git.GitSync
import io.github.olegnyr.adocmobile.git.PushResult
import io.github.olegnyr.adocmobile.git.StatusEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Что происходит на экране коммита (`FR-17`…`FR-22`, макет «03»). */
sealed interface CommitScreenPhase {

    /** Индекс и сообщение доступны; [failure] — текст последнего отказа. */
    data class Editing(val failure: String? = null) : CommitScreenPhase

    /**
     * Авторство не задано — диалог `user.name`/`user.email` до первого
     * коммита (`FR-22`, решение OQ-8). Дополнение макета «03» в языке
     * дизайна — отступление названо в журнале.
     */
    data object AwaitingAuthor : CommitScreenPhase

    /** Операция идёт: commit, а при включённом переключателе — и push следом. */
    data object Committing : CommitScreenPhase

    /**
     * Коммит создан; [pushFailure] — текст отказа push, если отправка была
     * включена и не прошла (`FR-21`): коммит при этом на месте, и сообщение
     * об отказе отдельное от успеха коммита. `null` — push прошёл или не
     * запрашивался.
     */
    data class Done(val pushFailure: String? = null) : CommitScreenPhase
}

/**
 * Holder экрана коммита — приёмом `CloneScreenModel`: наблюдаемое состояние,
 * шов интерфейсом, запуск только из формы.
 *
 * Чекбоксы: отмечены отслеживаемые (`M`/`A`), неотслеживаемые сняты
 * (`FR-18`); в коммит уходят ровно отмеченные пути (`FR-17`, `TC-15`).
 * Пустой индекс и пустое сообщение отвергаются до шва — с текстом, не тишиной.
 */
class CommitScreenModel(
    private val sync: GitSync,
    private val scope: CoroutineScope,
) {

    /** Строки индекса — статус репозитория на момент входа. */
    var entries: List<StatusEntry> by mutableStateOf(emptyList())
        private set

    /** Отмеченные пути; порядок коммита — порядок [entries]. */
    var checkedPaths: Set<String> by mutableStateOf(emptySet())
        private set

    /** Сообщение коммита; ввод не ограничивается (`FR-19`). */
    var message: String by mutableStateOf("")

    /** Поля диалога авторства (`FR-22`). */
    var authorName: String by mutableStateOf("")
    var authorEmail: String by mutableStateOf("")

    /**
     * «Отправить в origin после коммита» (`FR-20`) — включён по умолчанию,
     * как в макете «03»: сценарий фичи — довести правку до команды.
     */
    var pushAfterCommit: Boolean by mutableStateOf(true)

    var phase: CommitScreenPhase by mutableStateOf(CommitScreenPhase.Editing())
        private set

    /** Подпись `ИНДЕКС · N ИЗ M` из макета «03». */
    val indexLabel: String get() = "ИНДЕКС · ${checkedPaths.size} ИЗ ${entries.size}"

    /**
     * Вход на экран: перечитать статус и расставить чекбоксы по `FR-18`.
     *
     * Работает только в фазе формы: перечитка во время операции или поверх
     * отметок пользователя (пересоздание композиции) снесла бы его выбор.
     * Отказ чтения — текст на форме, не падение области корутин (находка
     * ревью E2, прецедент — `RepositoryScreenState.Failed`).
     */
    fun start() {
        if (phase !is CommitScreenPhase.Editing) return
        if (entries.isNotEmpty()) return
        scope.launch {
            try {
                val status = sync.status() ?: return@launch
                entries = status.entries
                checkedPaths = status.entries
                    .filter { it.status != FileStatus.Untracked }
                    .mapTo(mutableSetOf()) { it.path }
            } catch (e: Exception) {
                phase = CommitScreenPhase.Editing(failure = READ_FAILED_MESSAGE)
            }
        }
    }

    /** Чекбокс строки индекса. */
    fun toggle(path: String) {
        checkedPaths = if (path in checkedPaths) checkedPaths - path else checkedPaths + path
    }

    /**
     * Кнопка коммита: валидация до шва, авторство — диалогом при первом
     * коммите (`FR-22`), затем сам commit.
     */
    fun commitRequested() {
        if (phase !is CommitScreenPhase.Editing) return
        if (checkedPaths.isEmpty()) {
            phase = CommitScreenPhase.Editing(failure = NO_FILES_MESSAGE)
            return
        }
        if (message.isBlank()) {
            phase = CommitScreenPhase.Editing(failure = NO_MESSAGE_MESSAGE)
            return
        }
        // Фаза занята СИНХРОННО, до первой точки приостановки: второй тап в
        // окно IO-хопа sync.author() иначе породил бы второй коммит и push
        // (TC-40, приём TC-34 экрана клонирования). Снимок путей и сообщения —
        // тогда же: правка в окно не должна менять уже запрошенный коммит.
        phase = CommitScreenPhase.Committing
        val orderedPaths = entries.map { it.path }.filter { it in checkedPaths }
        val snapshotMessage = message.trim()
        scope.launch {
            val author = sync.author()
            if (author == null) {
                pendingPaths = orderedPaths
                pendingMessage = snapshotMessage
                phase = CommitScreenPhase.AwaitingAuthor
            } else {
                commit(orderedPaths, snapshotMessage, author)
            }
        }
    }

    /** Диалог авторства подтверждён — валидация и коммит (`FR-22`). */
    fun authorConfirmed() {
        if (phase !is CommitScreenPhase.AwaitingAuthor) return
        val name = authorName.trim()
        val email = authorEmail.trim()
        if (name.isEmpty() || email.isEmpty()) {
            // Диалог остаётся открытым: без имени и почты Git-коммита не бывает.
            return
        }
        // Тот же синхронный гейт, что в commitRequested (TC-40): двойной тап
        // по «СОХРАНИТЬ И ЗАКОММИТИТЬ» не должен родить два коммита.
        val paths = pendingPaths
        val snapshotMessage = pendingMessage
        phase = CommitScreenPhase.Committing
        scope.launch { commit(paths, snapshotMessage, CommitAuthor(name = name, email = email)) }
    }

    /** Отказ от диалога авторства — назад к форме, коммита не было. */
    fun authorDismissed() {
        if (phase is CommitScreenPhase.AwaitingAuthor) {
            phase = CommitScreenPhase.Editing()
        }
    }

    // Снимок запроса между commitRequested и подтверждением авторства:
    // диалог не должен коммитить то, что пользователь успел переотметить.
    private var pendingPaths: List<String> = emptyList()
    private var pendingMessage: String = ""

    private suspend fun commit(orderedPaths: List<String>, snapshotMessage: String, author: CommitAuthor) {
        when (val result = sync.commit(orderedPaths, snapshotMessage, author)) {
            is CommitResult.Failed ->
                phase = CommitScreenPhase.Editing(failure = result.error.userMessage())

            is CommitResult.Committed -> {
                // Отказ push коммита не откатывает и сообщается отдельно
                // (FR-21): фаза всё равно Done, отказ едет в pushFailure.
                phase = if (pushAfterCommit) {
                    when (val push = sync.push()) {
                        is PushResult.Pushed -> CommitScreenPhase.Done()
                        is PushResult.Failed -> CommitScreenPhase.Done(pushFailure = push.error.userMessage())
                    }
                } else {
                    CommitScreenPhase.Done()
                }
            }
        }
    }

    private companion object {
        const val NO_FILES_MESSAGE = "Отметьте хотя бы один файл — пустой коммит не создаётся."
        const val NO_MESSAGE_MESSAGE = "Введите сообщение коммита."
        const val READ_FAILED_MESSAGE = "Не удалось прочитать индекс репозитория. Попробуйте ещё раз."
    }
}

/**
 * Счётчик первой строки сообщения — `50 / 72 симв.` из макета «03» (`FR-19`).
 * Считается только первая строка (соглашение Git о теме коммита); ввод
 * длиннее не блокируется — счётчик подсказывает, а не запрещает.
 */
fun messageCounterLabel(message: String): String =
    "${message.lineSequence().firstOrNull().orEmpty().length} / 72 симв."
