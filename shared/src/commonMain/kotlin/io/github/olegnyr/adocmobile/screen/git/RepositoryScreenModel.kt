package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.git.FileStatus
import io.github.olegnyr.adocmobile.git.GitSync
import io.github.olegnyr.adocmobile.git.PullResult
import io.github.olegnyr.adocmobile.git.PushResult
import io.github.olegnyr.adocmobile.git.RepoFile
import io.github.olegnyr.adocmobile.git.RepoStatus
import io.github.olegnyr.adocmobile.git.RepositorySnapshot
import io.github.olegnyr.adocmobile.git.StatusEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Что показывает экран репозитория (`FR-9` в объёме E1, `FR-10` в части
 * путей и времени).
 */
sealed interface RepositoryScreenState {

    /** Диск ещё читается. */
    data object Loading : RepositoryScreenState

    /** Клона на устройстве нет — экран предлагает клонировать (`TC-5`, `FR-8`). */
    data object NoRepository : RepositoryScreenState

    /**
     * Диск не прочитался; [message] — готовый текст для пользователя.
     * Отдельный исход, а не вечный `Loading`: упавшее чтение без состояния
     * оставило бы экран пустым навсегда (находка ревью `SL-4`).
     */
    data class Failed(val message: String) : RepositoryScreenState

    /** Репозиторий открыт: карточка ветки, файлы рабочей копии и статус (`FR-9`, `FR-10`). */
    data class Ready(
        val repository: RepositorySnapshot,
        val files: List<RepoFile>,
        val status: RepoStatus,
    ) : RepositoryScreenState
}

/** Чип-фильтры списка файлов из макета «01» (`FR-10`). */
enum class RepoFilter {
    /** `ИЗМЕНЁННЫЕ` — только файлы с не-чистым статусом; умолчание макета. */
    Changed,

    /** `ВСЕ ФАЙЛЫ` — полный список рабочей копии. */
    All,

    /** `НЕДАВНИЕ` — все файлы, свежие правки сверху; семантика макетом не задана, выбрана реализацией и названа в журнале. */
    Recent,
}

/**
 * Список под выбранным фильтром (`FR-10`, `TC-8`).
 *
 * Чистая функция, а не метод модели, — по прецеденту `adocDocumentsOf`:
 * правила фильтра проверяются без модели и экрана.
 * `ИЗМЕНЁННЫЕ` сохраняет порядок основного списка; `НЕДАВНИЕ` пересортировывает
 * по времени правки, свежие сверху.
 */
fun filteredRepoFiles(files: List<RepoFile>, status: RepoStatus, filter: RepoFilter): List<RepoFile> =
    when (filter) {
        RepoFilter.Changed -> {
            val changed = status.entries.mapTo(mutableSetOf()) { it.path }
            files.filter { it.path in changed }
        }

        RepoFilter.All -> files

        RepoFilter.Recent -> files.sortedByDescending { it.lastModifiedEpochMs }
    }

/**
 * Holder экрана репозитория — образец `EditorScreenModel`: несоставной класс,
 * наблюдаемое состояние, шов интерфейсом, события с главного потока.
 *
 * Показывает карточку ветки со счётчиками, файлы с метками статуса и
 * чипы-фильтры (`FR-9`, `FR-10`); всё локально, сети нет (`NFR-3`, OQ-5).
 */
class RepositoryScreenModel(
    private val sync: GitSync,
    private val scope: CoroutineScope,
) {

    /** Наблюдаемое состояние для композиции. */
    var state: RepositoryScreenState by mutableStateOf(RepositoryScreenState.Loading)
        private set

    /** Активный чип-фильтр; умолчание — `ИЗМЕНЁННЫЕ`, как в макете «01». */
    var filter: RepoFilter by mutableStateOf(RepoFilter.Changed)

    /** Файлы под активным фильтром — то, что рисует список. */
    val visibleFiles: List<RepoFile>
        get() {
            val ready = state as? RepositoryScreenState.Ready ?: return emptyList()
            return filteredRepoFiles(ready.files, ready.status, filter)
        }

    /**
     * Строка статуса файла — единственный источник для метки и счётчика
     * диффа строки; `null` — файл чист. Индекс по пути строится один раз на
     * состояние, а не проходом по списку на каждую строку (ревью `SL-5`).
     */
    fun entryOf(file: RepoFile): StatusEntry? = entryIndex[file.path]

    private val entryIndex: Map<String, StatusEntry>
        get() {
            val ready = state as? RepositoryScreenState.Ready ?: return emptyMap()
            if (indexedFor !== ready.status) {
                indexedFor = ready.status
                cachedIndex = ready.status.entries.associateBy { it.path }
            }
            return cachedIndex
        }

    private var indexedFor: RepoStatus? = null
    private var cachedIndex: Map<String, StatusEntry> = emptyMap()

    /**
     * Текст отказа push для плашки (`FR-21`): свой последний (кнопка `PUSH`)
     * или принесённый хостингом с экрана коммита ([showPushFailure]).
     * Гаснет успешным push или заходом на экран коммита.
     */
    var pushFailure: String? by mutableStateOf(null)
        private set

    /** Идёт ли push с карточки — кнопка на это время недоступна. */
    var pushing: Boolean by mutableStateOf(false)
        private set

    /** Хостинг принёс отказ push с экрана коммита (`FR-21`, контракт `onCommitted`). */
    fun showPushFailure(message: String) {
        pushFailure = message
    }

    /**
     * Результат последнего pull для строки состояния (`FR-14`): «обновлено»,
     * «уже актуально» или текст отказа. `null` — pull ещё не запускали.
     */
    var pullNotice: String? by mutableStateOf(null)
        private set

    /** Идёт ли pull — кнопка на это время недоступна (`NFR-1`). */
    var pulling: Boolean by mutableStateOf(false)
        private set

    /**
     * Конфликтные файлы последнего pull; непустой список — хостинг ведёт на
     * экран слияния (`UC-5`). Гасится завершением или отменой слияния.
     */
    var conflictPaths: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * Кнопка `PULL` экрана репозитория (`FR-13`).
     *
     * Единственное сетевое обращение чтения — fetch отдельной кнопкой не
     * существует (решение OQ-5). Исход сообщается явно (`FR-14`): обновление
     * перечитывает состояние и зовёт [onPulled] со списком изменённых файлов
     * (перечитка открытого документа, `FR-15`); конфликт поднимает
     * [conflictPaths]; отказ — текст, состояние репозитория не тронуто
     * (`FR-16`).
     *
     * Перед сетью — [beforePull]: хостинг успевает сохранить несохранённые
     * правки открытого документа тем же механизмом немедленной записи, что
     * «Поделиться» и «Назад» (решение OQ-4). Отказ записи отменяет pull:
     * лучше не тронуть репозиторий, чем слить поверх потерянной правки.
     *
     * @param beforePull подготовка перед сетью; `false` — pull отменён
     * @param onPulled пути, изменённые pull-ом
     */
    fun pullRequested(
        beforePull: suspend () -> Boolean = { true },
        onPulled: (List<String>) -> Unit = {},
    ) {
        if (pulling) return
        pulling = true
        pullNotice = null
        scope.launch {
            try {
                if (!beforePull()) {
                    pullNotice = PULL_CANCELLED_MESSAGE
                    return@launch
                }
                when (val result = sync.pull()) {
                    is PullResult.Updated -> {
                        pullNotice = PULL_UPDATED_MESSAGE
                        conflictPaths = emptyList()
                        start()
                        onPulled(result.files)
                    }

                    is PullResult.AlreadyUpToDate -> {
                        pullNotice = PULL_UP_TO_DATE_MESSAGE
                        conflictPaths = emptyList()
                    }

                    is PullResult.Conflicted -> {
                        pullNotice = PULL_CONFLICT_MESSAGE
                        conflictPaths = result.paths
                        start()
                    }

                    is PullResult.Failed -> {
                        pullNotice = result.error.userMessage()
                        conflictPaths = emptyList()
                    }
                }
            } finally {
                pulling = false
            }
        }
    }

    /** Слияние закрыто (завершено или отменено) — снять состояние конфликта и перечитать. */
    fun mergeFinished() {
        conflictPaths = emptyList()
        pullNotice = null
        start()
    }

    /**
     * Кнопка `PUSH ↑N` на карточке — путь повтора после отказа (`FR-21`,
     * находка A ревью E2: текст отказа велит «отправьте снова», и этой
     * возможности не существовало). Успех перечитывает состояние — `↑`
     * обнуляется; отказ — новым текстом плашки, история цела.
     */
    fun pushRequested() {
        if (pushing) return
        pushing = true
        scope.launch {
            try {
                when (val result = sync.push()) {
                    is PushResult.Pushed -> {
                        pushFailure = null
                        start()
                    }

                    is PushResult.Failed -> pushFailure = result.error.userMessage()
                }
            } finally {
                pushing = false
            }
        }
    }

    private var loading = false

    /**
     * Вход на экран — перечитать состояние с диска.
     *
     * Не идемпотентен намеренно: возврат с экрана клонирования обязан увидеть
     * новый клон, поэтому каждый вызов читает заново; защита только от
     * параллельного чтения. Сети здесь нет (`NFR-3`) — только диск.
     */
    fun start() {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val repository = sync.openRepository()
                state = if (repository == null) {
                    RepositoryScreenState.NoRepository
                } else {
                    RepositoryScreenState.Ready(
                        repository = repository,
                        files = sync.listFiles(),
                        // Статуса нет только у отсутствующего клона; репозиторий
                        // без статуса читается как чистый, не как отказ.
                        status = sync.status() ?: RepoStatus(ahead = 0, behind = 0, entries = emptyList()),
                    )
                }
            } catch (e: Exception) {
                // Отказ чтения — состояние, а не падение области корутин:
                // у пользователя остаётся текст и кнопка «Повторить».
                state = RepositoryScreenState.Failed(READ_FAILED_MESSAGE)
            } finally {
                loading = false
            }
        }
    }

    private companion object {
        const val READ_FAILED_MESSAGE =
            "Не удалось прочитать репозиторий с устройства. Попробуйте ещё раз."
        const val PULL_UPDATED_MESSAGE = "Обновлено: правки из origin забраны."
        const val PULL_UP_TO_DATE_MESSAGE = "Уже актуально: новых правок в origin нет."
        const val PULL_CONFLICT_MESSAGE = "Конфликт слияния: нужно выбрать версии участков."
        const val PULL_CANCELLED_MESSAGE =
            "Забирать правки не стали: сначала сохраните изменения открытого документа."
    }
}

/** Буква статуса для колонки списка — `M`/`A`/`D`/`?` (`FR-10` + дозаявка `SL-5`, `NFR-9`). */
fun FileStatus.letter(): String = when (this) {
    FileStatus.Modified -> "M"
    FileStatus.Added -> "A"
    FileStatus.Deleted -> "D"
    FileStatus.Untracked -> "?"
}

/**
 * «3 изменения» для карточки ветки (`FR-9`) — русские формы числительного:
 * подпись читается человеком, «3 изменений» глаз спотыкает.
 */
fun changeCountLabel(count: Int): String {
    val form = when {
        count % 100 in 11..14 -> "изменений"
        count % 10 == 1 -> "изменение"
        count % 10 in 2..4 -> "изменения"
        else -> "изменений"
    }
    return "$count $form"
}

/**
 * Счётчик диффа строки файла: `+41 −6`, `+128` — как в макете «01».
 * `null` — счёта нет (неотслеживаемый или бинарный файл, `FR-10`); нулевая
 * половина опускается, минус — типографский `−`.
 */
fun diffCountLabel(entry: StatusEntry): String? {
    val insertions = entry.insertions
    val deletions = entry.deletions
    if (insertions == null && deletions == null) return null
    val parts = buildList {
        if (insertions != null && insertions > 0) add("+$insertions")
        if (deletions != null && deletions > 0) add("−$deletions")
    }
    return parts.joinToString(" ").ifEmpty { null }
}

/**
 * Подпись времени правки для строки файла (`FR-10`) — градации макета «01»:
 * «4 мин назад», «2 ч назад», «вчера». Точной арифметики календаря здесь нет
 * намеренно: подпись ориентирует, а не документирует, и до суток считается
 * часами, дальше — днями.
 */
fun repoFileTimeLabel(nowEpochMs: Long, thenEpochMs: Long): String {
    val minutes = (nowEpochMs - thenEpochMs) / 60_000
    return when {
        minutes < 1 -> "только что"
        minutes < 60 -> "$minutes мин назад"
        minutes < 24 * 60 -> "${minutes / 60} ч назад"
        minutes < 48 * 60 -> "вчера"
        else -> "${minutes / (24 * 60)} дн назад"
    }
}
