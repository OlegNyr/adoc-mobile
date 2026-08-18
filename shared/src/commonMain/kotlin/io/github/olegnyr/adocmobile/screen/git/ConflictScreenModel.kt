package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.git.CommitAuthor
import io.github.olegnyr.adocmobile.git.CommitResult
import io.github.olegnyr.adocmobile.git.ConflictChoice
import io.github.olegnyr.adocmobile.git.ConflictFile
import io.github.olegnyr.adocmobile.git.ConflictHunk
import io.github.olegnyr.adocmobile.git.ConflictParseResult
import io.github.olegnyr.adocmobile.git.containsConflictMarkers
import io.github.olegnyr.adocmobile.git.GitSync
import io.github.olegnyr.adocmobile.git.parseConflictFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Что происходит на экране слияния (`FR-23`…`FR-25`, макет «04»). */
sealed interface ConflictScreenPhase {

    /** Участки читаются с диска. */
    data object Loading : ConflictScreenPhase

    /** Идёт разрешение; [failure] — текст последнего отказа операции. */
    data class Resolving(val failure: String? = null) : ConflictScreenPhase

    /** Идёт запись и коммит слияния — действия заблокированы (`TC-45`). */
    data object Merging : ConflictScreenPhase

    /** Слияние завершено merge-коммитом (`TC-25`). */
    data object Merged : ConflictScreenPhase

    /** Слияние отменено: состояние до pull, локальные коммиты целы (`TC-26`). */
    data object Aborted : ConflictScreenPhase

    /**
     * Спрошено подтверждение отмены слияния (`FR-25`).
     *
     * Отдельная фаза, потому что отмена отбрасывает работу: выборы участков
     * и слияние целиком. Крестик в app bar читается как «закрыть», и
     * выполнять по нему разрушающее действие молча нельзя — находка ревью E3.
     */
    data object ConfirmingAbort : ConflictScreenPhase
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

    /** Счётчик участков из макета «04»: `1/2`; без участков — `0/0`. */
    val hunkCounter: String
        get() = if (hunks.isEmpty()) "0/0" else "${currentIndex + 1}/${hunks.size}"

    /** Все ли участки разрешены — завершение доступно только тогда (`FR-24`). */
    val allResolved: Boolean get() = hunks.isNotEmpty() && choices.none { it == null }

    /**
     * Предпросмотр *текущего участка* (`FR-23`, макет «04»): блок
     * `РЕЗУЛЬТАТ` показывает фрагмент, а не весь файл — целый документ на
     * каждой рекомпозиции пересобирался бы и рисовался одним элементом,
     * и это заметная цена на реальном документе (замечание ревью E3).
     */
    val preview: String
        get() = file?.hunkPreview(currentIndex, choices.getOrNull(currentIndex)).orEmpty()

    /** Полный текст файла с применёнными выборами — то, что уйдёт на диск. */
    private val resolvedFileText: String
        get() = file?.resolvedText(choices.map { it ?: ConflictChoice.Ours }).orEmpty()

    /**
     * Вход на экран: прочитать конфликтный файл *целиком* и разобрать.
     *
     * Именно целиком: результат собирается из участков вместе с
     * неконфликтным текстом, иначе «ЗАВЕРШИТЬ» стирал бы заголовок,
     * преамбулу и всё между участками — блокер ревью E3.
     */
    fun start() {
        if (phase !is ConflictScreenPhase.Loading) return
        scope.launch {
            try {
                val text = sync.conflictedText(path)
                if (text == null) {
                    // Файла нет, путь не прошёл рубежи или он не UTF-8 —
                    // во всех случаях правка испортила бы чужой файл.
                    phase = ConflictScreenPhase.Resolving(failure = READ_FAILED_MESSAGE)
                    return@launch
                }
                when (val parsed = parseConflictFile(text)) {
                    is ConflictParseResult.Malformed -> {
                        // Разметку не поняли — разрешать нечего: догадка
                        // испортила бы файл сильнее, чем честный отказ.
                        phase = ConflictScreenPhase.Resolving(failure = MALFORMED_MESSAGE)
                    }

                    is ConflictParseResult.Parsed -> {
                        file = parsed.file
                        hunks = parsed.file.hunks
                        choices = List(parsed.file.hunks.size) { null }
                        phase = ConflictScreenPhase.Resolving()
                    }
                }
            } catch (_: Exception) {
                phase = ConflictScreenPhase.Resolving(failure = READ_FAILED_MESSAGE)
            }
        }
    }

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
            // Ноль участков — это не «остались неразрешённые»: файл уже без
            // разметки, и сказать так значило бы соврать (ревью E3).
            phase = ConflictScreenPhase.Resolving(
                failure = if (hunks.isEmpty()) NO_HUNKS_MESSAGE else UNRESOLVED_MESSAGE,
            )
            return
        }
        val text = resolvedFileText
        // Сторож результата (регрессия ревью E3): маркеры в собранном тексте
        // означают, что часть конфликта осталась неразобранной — например
        // литеральный пример разметки в документации. Такой текст на диск не
        // уходит, и коммита с маркерами не случается.
        if (containsConflictMarkers(text)) {
            phase = ConflictScreenPhase.Resolving(failure = MARKERS_LEFT_MESSAGE)
            return
        }
        // Фаза занимается синхронно, до первой точки приостановки: двойной
        // тап иначе дал бы две записи и два merge-коммита (приём TC-40).
        phase = ConflictScreenPhase.Merging
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
        phase = ConflictScreenPhase.ConfirmingAbort
    }

    /** Отмена отменена — вернуться к разрешению участков. */
    fun abortDismissed() {
        if (phase is ConflictScreenPhase.ConfirmingAbort) {
            phase = ConflictScreenPhase.Resolving()
        }
    }

    /**
     * Отмена подтверждена (`FR-25`, `TC-26`): репозиторий возвращается в
     * состояние до pull. Незакоммиченные правки вне слияния сохраняются —
     * за это отвечает платформенная сторона (`reset --merge`).
     */
    fun abortConfirmed() {
        if (phase !is ConflictScreenPhase.ConfirmingAbort) return
        phase = ConflictScreenPhase.Merging
        scope.launch {
            phase = when (val result = sync.abortMerge()) {
                is CommitResult.Committed -> ConflictScreenPhase.Aborted
                is CommitResult.Failed -> ConflictScreenPhase.Resolving(failure = result.error.userMessage())
            }
        }
    }

    private companion object {
        const val READ_FAILED_MESSAGE =
            "Конфликтный файл не прочитан: он недоступен или не в кодировке UTF-8. " +
                "Разрешите конфликт вручную или отмените слияние."
        const val UNRESOLVED_MESSAGE = "Остались неразрешённые участки — выберите версию для каждого."
        const val NO_HUNKS_MESSAGE =
            "В файле нет конфликтных участков. Отмените слияние или разрешите конфликт вручную."
        const val MARKERS_LEFT_MESSAGE =
            "В файле остались строки с разметкой конфликта — приложение не станет его коммитить. " +
                "Разрешите конфликт вручную или отмените слияние."
        const val MALFORMED_MESSAGE =
            "Разметку конфликта не удалось разобрать. Откройте файл и разрешите конфликт вручную."
    }
}
