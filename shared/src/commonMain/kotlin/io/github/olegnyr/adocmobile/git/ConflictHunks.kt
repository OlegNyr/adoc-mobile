package io.github.olegnyr.adocmobile.git

/**
 * Разбор конфликтной разметки Git и сборка результата (`FR-23`).
 *
 * Живёт в `commonMain`: разметку пишет Git, но читать её — обычный разбор
 * строк, и держать его в платформенной половине значило бы писать заново для
 * iOS и проверять только на устройстве (`NFR-2`, `NFR-10`).
 *
 * Разбирается *файл целиком*: неконфликтный текст — такая же часть
 * результата, как выбранные стороны (блокер ревью E3: сборка только из
 * участков стирала остальной файл).
 *
 * Строка хранится вместе со своим терминатором, а не отдельно от него.
 * Так round-trip побайтовый при любых переводах строк, включая смешанные и
 * случай «файл CRLF, а маркеры Git написал через LF» — нормализация же
 * переписывала бы строки, которых пользователь не трогал (`NFR-6`).
 *
 * Merge-редактора нет: участок берётся стороной целиком — решение видения.
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

/**
 * Строка файла вместе с её терминатором.
 *
 * @property text содержимое без перевода строки
 * @property terminator `"\n"`, `"\r\n"`, `"\r"` или пустая строка у
 * последней строки файла без хвостового перевода
 */
data class SourceLine(val text: String, val terminator: String) {
    override fun toString(): String = text + terminator
}

/** Кусок разобранного файла: общий текст либо конфликтный участок. */
sealed interface ConflictSegment {

    /** Строки вне конфликта — переносятся в результат дословно. */
    data class Shared(val lines: List<SourceLine>) : ConflictSegment

    /** Конфликтный участок; порядковый номер — [index] среди участков файла. */
    data class Conflict(val index: Int, val hunk: ConflictHunk) : ConflictSegment
}

/**
 * Файл, разобранный на общий текст и конфликтные участки.
 *
 * @property segments куски в порядке файла — по ним собирается результат
 * @property hunks только участки: их считает счётчик `1/N` экрана и по ним
 * идут выборы пользователя
 */
data class ConflictFile(
    val segments: List<ConflictSegment>,
    val hunks: List<ConflictHunk>,
) {

    /**
     * Текст файла с участками, разрешёнными по [choices] (`TC-22`, `TC-23`,
     * `TC-42`, `TC-43`).
     *
     * Чистая функция: предпросмотр экрана и текст, уходящий на диск, — один и
     * тот же вызов, поэтому «предпросмотр устарел» невозможно по построению.
     * Участок без выбора берёт локальную сторону; завершить слияние таким
     * файлом экран не даст (`FR-24`).
     */
    fun resolvedText(choices: List<ConflictChoice>): String = buildString {
        segments.forEach { segment ->
            when (segment) {
                is ConflictSegment.Shared -> segment.lines.forEach { append(it.text).append(it.terminator) }
                is ConflictSegment.Conflict -> {
                    val choice = choices.getOrNull(segment.index) ?: ConflictChoice.Ours
                    val lines = when (choice) {
                        ConflictChoice.Ours -> segment.hunk.ours
                        ConflictChoice.Theirs -> segment.hunk.theirs
                        ConflictChoice.Both -> segment.hunk.ours + segment.hunk.theirs
                    }
                    lines.forEach { append(it.text).append(it.terminator) }
                }
            }
        }
    }

    /** Текст одного участка при выбранной стороне — для предпросмотра фрагментом (макет «04»). */
    fun hunkPreview(index: Int, choice: ConflictChoice?): String {
        val hunk = hunks.getOrNull(index) ?: return ""
        val lines = when (choice) {
            null, ConflictChoice.Ours -> hunk.ours
            ConflictChoice.Theirs -> hunk.theirs
            ConflictChoice.Both -> hunk.ours + hunk.theirs
        }
        return lines.joinToString("") { it.text + it.terminator }.trimEnd('\n', '\r')
    }
}

/** Чем закончился разбор файла. */
sealed interface ConflictParseResult {

    /** Файл разобран; участков может не быть вовсе. */
    data class Parsed(val file: ConflictFile) : ConflictParseResult

    /**
     * Разметка не разобрана: непарные, незакрытые или вложенные маркеры.
     * Отказ вместо догадки: испорченный файл хуже честного «не понял»
     * (находки ревью E3); [reason] объясняет, что не сошлось.
     */
    data class Malformed(val reason: String) : ConflictParseResult
}

/**
 * Разобрать текст файла с конфликтной разметкой.
 *
 * Маркеры распознаются строго: *не менее семи* одинаковых символов `<`, `|`,
 * `=`, `>` в начале строки, дальше конец строки или пробел с меткой. Ровно
 * семь `=` — легальный делимитер example-блока AsciiDoc, поэтому вне участка
 * такая строка остаётся текстом, а внутри участка распознаётся разделителем
 * только если участок уже открыт.
 *
 * Маркеры разбираются везде, включая содержимое блоков листинга: отличить
 * настоящую разметку Git от литерального примера в документации по тексту
 * нельзя (для этого нужны стадии индекса). Частный случай «не разбирать
 * внутри листинга» пробовался и породил регрессию — файл с примером в
 * листинге и настоящим конфликтом ниже собирался с маркерами внутри.
 * Страхует результат сторож [containsConflictMarkers]: собранный текст с
 * маркерами на диск не уходит.
 *
 * Понимается `diff3` (`|||||||` + база): база разбирается отдельным полем и
 * в результат не попадает.
 */
fun parseConflictFile(text: String): ConflictParseResult {
    val lines = splitKeepingTerminators(text)

    val segments = mutableListOf<ConflictSegment>()
    val hunks = mutableListOf<ConflictHunk>()
    val shared = mutableListOf<SourceLine>()

    var ours: MutableList<SourceLine>? = null
    var base: MutableList<SourceLine>? = null
    var theirs: MutableList<SourceLine>? = null
    var oursLabel: String? = null

    for (line in lines) {
        val body = line.text
        val marker = markerOf(body)
        when {
            marker == Marker.Ours -> {
                if (ours != null) {
                    return ConflictParseResult.Malformed("вложенный маркер начала участка: «$body»")
                }
                if (shared.isNotEmpty()) {
                    segments += ConflictSegment.Shared(shared.toList())
                    shared.clear()
                }
                oursLabel = body.dropWhile { it == '<' }.trim().ifEmpty { null }
                ours = mutableListOf()
                base = null
                theirs = null
            }

            marker == Marker.Base && ours != null -> {
                if (base != null || theirs != null) {
                    return ConflictParseResult.Malformed("повторный маркер базы: «$body»")
                }
                base = mutableListOf()
            }

            marker == Marker.Separator && ours != null -> {
                if (theirs != null) {
                    return ConflictParseResult.Malformed("повторный разделитель участка: «$body»")
                }
                theirs = mutableListOf()
            }

            marker == Marker.Theirs && ours != null -> {
                if (theirs == null) {
                    return ConflictParseResult.Malformed("закрывающий маркер до разделителя: «$body»")
                }
                val hunk = ConflictHunk(
                    ours = ours.toList(),
                    theirs = theirs.toList(),
                    base = base?.toList().orEmpty(),
                    oursLabel = oursLabel,
                    theirsLabel = body.dropWhile { it == '>' }.trim().ifEmpty { null },
                )
                segments += ConflictSegment.Conflict(hunks.size, hunk)
                hunks += hunk
                ours = null
                base = null
                theirs = null
                oursLabel = null
            }

            ours == null -> shared += line

            theirs != null -> theirs += line

            base != null -> base += line

            else -> ours += line
        }
    }

    if (ours != null) {
        return ConflictParseResult.Malformed("участок не закрыт маркером >>>>>>>")
    }
    if (shared.isNotEmpty()) {
        segments += ConflictSegment.Shared(shared.toList())
    }

    return ConflictParseResult.Parsed(ConflictFile(segments = segments, hunks = hunks))
}

/**
 * Остались ли в тексте строки, похожие на конфликтные маркеры.
 *
 * Сторож на *выходе*, а не на входе: разобрать литеральный пример маркеров
 * (документация про Git в репозитории пользователя) и настоящую разметку
 * одинаково нельзя, но записать на диск и закоммитить текст с маркерами
 * нельзя тем более. Файл с таким содержимым в приложении не разрешается —
 * ограничение записано в аналитике (`FR-23`).
 *
 * Ищутся только ряды `<` и `>`. Разделитель `=======` и база `|||||||`
 * сторожем не проверяются намеренно: строка из семи и более `=` — штатный
 * AsciiDoc (вложенный example-блок, легаси-подчёркивание заголовка), и
 * отказ на ней запирал бы пользователя на экране с полностью разрешённым
 * файлом (блокер независимой проверки). Пропустить конфликт это не даёт:
 * Git пишет маркеры тройкой, и разделитель без `<<<<<<<` и `>>>>>>>` в
 * файле не остаётся.
 */
fun containsConflictMarkers(text: String): Boolean =
    splitKeepingTerminators(text).any { looksLikeMarker(it.text) }

/**
 * Строка «похожа на маркер стороны»: ряд `<` или `>` длиной от семи,
 * допуская любые ведущие пробельные символы.
 *
 * Сторож намеренно чувствительнее разбора: разбор берёт только маркеры с
 * начала строки (иначе он ловил бы отступы в тексте), а результату нельзя
 * содержать даже маркер с отступом — Git такой не пишет, значит он остался
 * от неразобранного конфликта.
 */
private fun looksLikeMarker(line: String): Boolean {
    val trimmed = line.trimStart()
    val first = trimmed.firstOrNull() ?: return false
    if (first != '<' && first != '>') return false
    val run = trimmed.takeWhile { it == first }.length
    if (run < MARKER_LENGTH) return false
    return trimmed.length == run || trimmed[run] == ' '
}

/** Разбить текст на строки, сохранив терминатор каждой (`NFR-6`). */
private fun splitKeepingTerminators(text: String): List<SourceLine> {
    val lines = mutableListOf<SourceLine>()
    val current = StringBuilder()
    var index = 0
    while (index < text.length) {
        val ch = text[index]
        when {
            ch == '\r' && index + 1 < text.length && text[index + 1] == '\n' -> {
                lines += SourceLine(current.toString(), "\r\n")
                current.clear()
                index += 2
            }

            ch == '\r' -> {
                lines += SourceLine(current.toString(), "\r")
                current.clear()
                index += 1
            }

            ch == '\n' -> {
                lines += SourceLine(current.toString(), "\n")
                current.clear()
                index += 1
            }

            else -> {
                current.append(ch)
                index += 1
            }
        }
    }
    if (current.isNotEmpty()) lines += SourceLine(current.toString(), "")
    return lines
}

private enum class Marker { Ours, Base, Separator, Theirs }

/**
 * Маркер строки или `null`: не менее семи одинаковых символов, дальше конец
 * строки или пробел с меткой.
 */
private fun markerOf(line: String): Marker? {
    if (line.length < MARKER_LENGTH) return null
    val first = line.first()
    val marker = when (first) {
        '<' -> Marker.Ours
        '|' -> Marker.Base
        '=' -> Marker.Separator
        '>' -> Marker.Theirs
        else -> return null
    }
    val run = line.takeWhile { it == first }.length
    if (run < MARKER_LENGTH) return null
    if (line.length == run) return marker
    return if (line[run] == ' ') marker else null
}

private const val MARKER_LENGTH = 7
