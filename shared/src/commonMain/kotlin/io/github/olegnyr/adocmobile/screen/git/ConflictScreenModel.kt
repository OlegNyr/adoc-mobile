package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.git.CommitAuthor
import io.github.olegnyr.adocmobile.git.CommitResult
import io.github.olegnyr.adocmobile.git.ConflictChoice
import io.github.olegnyr.adocmobile.git.ConflictFile
import io.github.olegnyr.adocmobile.git.ConflictHunk
import io.github.olegnyr.adocmobile.git.GitSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Что происходит на экране слияния (`FR-23`…`FR-25`, макет «04»). */
sealed interface ConflictScreenPhase {

    /** Участки читаются с диска. */
    data object Loading : ConflictScreenPhase

    /** Идёт разрешение; [failure] — текст последнего отказа операции. */
    data class Resolving(val failure: String? = null) : ConflictScreenPhase

    /** Слияние завершено merge-коммитом (`TC-25`). */
    data object Merged : ConflictScreenPhase

    /** Слияние отменено: состояние до pull, локальные коммиты целы (`TC-26`). */
    data object Aborted : ConflictScreenPhase
}

/**
 * Holder экрана слияния — приёмом `CommitScreenModel`: наблюдаемое состояние,
 * шов интерфейсом, одна операция за раз.
 *
 * Разрешается один файл за проход — тот, что пришёл конфликтным из pull;
 * при нескольких файлах хостинг ведёт их по очереди (`conflictPaths`).
 * Merge-редактора нет: участок берётся стороной целиком (решение видения).
 *
 * Предпросмотр и текст, уходящий на диск, — один и тот же вызов
 * `ConflictFile.resolvedText`, поэтому «устаревший предпросмотр» невозможен
 * по построению (`FR-23`, `TC-23`).
 */
class ConflictScreenModel(
    private val sync: GitSync,
    private val path: String,
    private val scope: CoroutineScope,
) {

    var phase: ConflictScreenPhase by mutableStateOf(ConflictScreenPhase.Loading)
        private set

    /** Участки файла в порядке появления (`FR-24`: по ним идёт счётчик `1/N`). */
    var hunks: List<ConflictHunk> by mutableStateOf(emptyList())
        private set

    /** Выбор пользователя по участкам; `null` — участок ещё не разрешён (`FR-24`). */
    var choices: List<ConflictChoice?> by mutableStateOf(emptyList())
        private set

    /** Текущий участок — индекс для счётчика `1/N` и панели действий. */
    var currentIndex: Int by mutableStateOf(0)
        private set

    private var file: ConflictFile? = null

    /** Счётчик участков из макета «04»: `1/2`. */
    val hunkCounter: String get() = "${currentIndex + 1}/${hunks.size}"

    /** Все ли участки разрешены — завершение доступно только тогда (`FR-24`). */
    val allResolved: Boolean get() = hunks.isNotEmpty() && choices.none { it == null }

    /**
     * Предпросмотр результата (`FR-23`): собирается сразу при каждом выборе.
     * Неразрешённые участки показываются локальной стороной — предпросмотр
     * обязан быть целым текстом, а не дырой; завершить слияние это не даёт.
     */
    val preview: String
        get() = file?.resolvedText(choices.map { it ?: ConflictChoice.Ours }).orEmpty()

    /** Вход на экран: прочитать участки конфликтного файла. */
    fun start() {
        if (phase !is ConflictScreenPhase.Loading) return
        scope.launch {
            try {
                val parsed = sync.conflictHunks(path)
                hunks = parsed
                choices = List(parsed.size) { null }
                file = conflictFileFor(parsed)
                phase = ConflictScreenPhase.Resolving()
            } catch (_: Exception) {
                phase = ConflictScreenPhase.Resolving(failure = READ_FAILED_MESSAGE)
            }
        }
    }

    /**
     * Файл для сборки результата.
     *
     * Шов отдаёт только участки, поэтому общий текст между ними здесь
     * недоступен — модель собирает предпросмотр из участков подряд.
     * Полный файл соберёт платформенная сторона при записи (`resolveConflict`
     * получает текст участков), а показ «участки + общий текст» придёт, когда
     * шов начнёт отдавать разобранный файл целиком — названо в журнале `SL-12`.
     */
    private fun conflictFileFor(parsed: List<ConflictHunk>): ConflictFile =
        ConflictFile(
            segments = parsed.mapIndexed { index, hunk ->
                io.github.olegnyr.adocmobile.git.ConflictSegment.Conflict(index, hunk)
            },
            hunks = parsed,
        )

    /** Выбор стороны для текущего участка — предпросмотр пересобирается сразу (`TC-23`). */
    fun choose(choice: ConflictChoice) {
        if (phase !is ConflictScreenPhase.Resolving) return
        val index = currentIndex
        if (index !in choices.indices) return
        choices = choices.toMutableList().also { it[index] = choice }
    }

    /** `ДАЛЕЕ` — к следующему участку; на последнем остаётся на месте (`FR-24`). */
    fun nextHunk() {
        if (currentIndex < hunks.lastIndex) currentIndex += 1
    }

    /** Назад к предыдущему участку — выбор можно переиграть до завершения. */
    fun previousHunk() {
        if (currentIndex > 0) currentIndex -= 1
    }

    /**
     * Завершить слияние (`FR-24`, `TC-25`): записать разрешённый файл, снять
     * конфликт и создать merge-коммит. Недоступно, пока есть неразрешённые
     * участки — проверка до шва, а не только недоступной кнопкой.
     */
    fun finishRequested(author: CommitAuthor) {
        if (phase !is ConflictScreenPhase.Resolving) return
        if (!allResolved) {
            phase = ConflictScreenPhase.Resolving(failure = UNRESOLVED_MESSAGE)
            return
        }
        val text = preview
        phase = ConflictScreenPhase.Resolving()
        scope.launch {
            when (val written = sync.resolveConflict(path, text)) {
                is CommitResult.Failed ->
                    phase = ConflictScreenPhase.Resolving(failure = written.error.userMessage())

                is CommitResult.Committed -> {
                    phase = when (val merged = sync.finishMerge(author)) {
                        is CommitResult.Committed -> ConflictScreenPhase.Merged
                        is CommitResult.Failed ->
                            ConflictScreenPhase.Resolving(failure = merged.error.userMessage())
                    }
                }
            }
        }
    }

    /**
     * Отменить слияние (`FR-25`, `TC-26`): репозиторий возвращается в
     * состояние до pull, локальные коммиты целы.
     */
    fun abortRequested() {
        if (phase !is ConflictScreenPhase.Resolving) return
        scope.launch {
            phase = when (val result = sync.abortMerge()) {
                is CommitResult.Committed -> ConflictScreenPhase.Aborted
                is CommitResult.Failed -> ConflictScreenPhase.Resolving(failure = result.error.userMessage())
            }
        }
    }

    private companion object {
        const val READ_FAILED_MESSAGE = "Не удалось прочитать конфликтный файл. Попробуйте ещё раз."
        const val UNRESOLVED_MESSAGE = "Остались неразрешённые участки — выберите версию для каждого."
    }
}
