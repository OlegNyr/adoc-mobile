package io.github.olegnyr.adocmobile.git

/**
 * Разбор конфликтной разметки Git и сборка результата (`FR-23`) — слайс
 * `SL-11` фичи 007-git-sync.
 *
 * Живёт в `commonMain` намеренно: разметку пишет Git, но читать её —
 * обычный разбор строк, и держать его в платформенной половине значило бы
 * писать заново для iOS и проверять только на устройстве (`NFR-2`, `NFR-10`).
 *
 * Merge-редактора здесь нет и не будет: участок разрешается выбором стороны
 * целиком — решение видения, построчное слияние осознанно за бортом.
 */

/** Что выбрал пользователь для участка (`FR-23`, макет «04»). */
enum class ConflictChoice {
    /** `ЛОКАЛЬНОЕ` — версия HEAD. */
    Ours,

    /** `УДАЛЁННОЕ` — версия origin. */
    Theirs,

    /** `ОБА` — обе версии подряд: сначала локальная, затем удалённая. */
    Both,
}

/** Кусок разобранного файла: общий текст либо конфликтный участок. */
sealed interface ConflictSegment {

    /** Строки вне конфликта — переносятся в результат дословно. */
    data class Shared(val lines: List<String>) : ConflictSegment

    /** Конфликтный участок; порядковый номер — [index] среди участков файла. */
    data class Conflict(val index: Int, val hunk: ConflictHunk) : ConflictSegment
}

/**
 * Файл, разобранный на общий текст и конфликтные участки.
 *
 * @property segments куски в порядке файла — по ним собирается результат
 * @property hunks только участки, в порядке появления: их считает счётчик
 * `1/N` экрана и по ним идут выборы пользователя
 */
data class ConflictFile(
    val segments: List<ConflictSegment>,
    val hunks: List<ConflictHunk>,
) {

    /**
     * Текст файла с участками, разрешёнными по [choices] (`TC-22`, `TC-23`).
     *
     * Чистая функция: предпросмотр экрана и текст, уходящий на диск, — один
     * и тот же вызов, поэтому «предпросмотр устарел» невозможно по построению.
     * Участок без выбора (список короче числа участков) берёт локальную
     * сторону — но завершение слияния такой файл не пропустит: полноту
     * выборов сторожит экран (`FR-24`).
     */
    fun resolvedText(choices: List<ConflictChoice>): String {
        val lines = segments.flatMap { segment ->
            when (segment) {
                is ConflictSegment.Shared -> segment.lines
                is ConflictSegment.Conflict -> {
                    val choice = choices.getOrNull(segment.index) ?: ConflictChoice.Ours
                    when (choice) {
                        ConflictChoice.Ours -> segment.hunk.ours
                        ConflictChoice.Theirs -> segment.hunk.theirs
                        ConflictChoice.Both -> segment.hunk.ours + segment.hunk.theirs
                    }
                }
            }
        }
        return lines.joinToString("\n")
    }
}

/**
 * Разобрать текст файла с конфликтной разметкой.
 *
 * Разметка Git: `<<<<<<< метка`, `=======`, `>>>>>>> метка`. Метки сторон
 * сохраняются как подписи участка — из них экран берёт «ЛОКАЛЬНО · HEAD» и
 * «ORIGIN/MAIN». Файл без маркеров даёт ноль участков и неизменный текст.
 *
 * Незакрытая разметка (обрыв на середине) не роняет разбор: недописанный
 * участок уходит в общий текст как есть — пользователю лучше увидеть файл
 * с маркерами, чем пустой экран.
 */
fun parseConflictFile(text: String): ConflictFile {
    val segments = mutableListOf<ConflictSegment>()
    val hunks = mutableListOf<ConflictHunk>()
    val shared = mutableListOf<String>()

    fun flushShared() {
        if (shared.isNotEmpty()) {
            segments += ConflictSegment.Shared(shared.toList())
            shared.clear()
        }
    }

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (!line.startsWith(OURS_MARKER)) {
            shared += line
            i += 1
            continue
        }

        val oursLabel = line.removePrefix(OURS_MARKER).trim().ifEmpty { null }
        val ours = mutableListOf<String>()
        val theirs = mutableListOf<String>()
        var theirsLabel: String? = null
        var cursor = i + 1
        var separatorSeen = false
        var closed = false

        while (cursor < lines.size) {
            val current = lines[cursor]
            when {
                current.startsWith(SEPARATOR_MARKER) && !separatorSeen -> separatorSeen = true

                current.startsWith(THEIRS_MARKER) && separatorSeen -> {
                    theirsLabel = current.removePrefix(THEIRS_MARKER).trim().ifEmpty { null }
                    closed = true
                }

                separatorSeen -> theirs += current

                else -> ours += current
            }
            cursor += 1
            if (closed) break
        }

        if (!closed) {
            // Разметка не закрыта: отдаём строки как обычный текст.
            shared += line
            i += 1
            continue
        }

        flushShared()
        val hunk = ConflictHunk(
            ours = ours.toList(),
            theirs = theirs.toList(),
            oursLabel = oursLabel,
            theirsLabel = theirsLabel,
        )
        segments += ConflictSegment.Conflict(hunks.size, hunk)
        hunks += hunk
        i = cursor
    }

    flushShared()
    return ConflictFile(segments = segments, hunks = hunks)
}

private const val OURS_MARKER = "<<<<<<<"
private const val SEPARATOR_MARKER = "======="
private const val THEIRS_MARKER = ">>>>>>>"
