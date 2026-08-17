package io.github.olegnyr.adocmobile.highlight

/**
 * Инкрементальное сканирование — слайс `SL-5` фичи 001-syntax-highlighting,
 * требование `FR-27`: правка пересканирует участок от ближайшей границы блока
 * выше изменения до первой строки, где состояние совпало с прежним, а не весь
 * документ.
 *
 * Устройство:
 *
 * . Правка находится сравнением префикса и суффикса старого и нового текста —
 *   редактор не обязан сообщать, что именно изменилось.
 * . *Рестарт* — ближайшая выше строка, перед которой стоит пустая строка (или
 *   начало документа). Пустая строка — единственная дешёвая точка, где всё
 *   построчное состояние канонично: инлайн-копилка пуста, перенос атрибута
 *   оборван, метка стиля забыта. Границы блока самой по себе мало: логическая
 *   строка инлайн-разбора и перенос значения атрибута пересекают строки без
 *   пустых между ними — `FR-27` в этой части неполон, уточнение записано в
 *   отчёте слайса.
 * . Стек блоков в точке рестарта берётся из [AdocScan.blockStates] прежнего
 *   прохода — они заведены в `SL-1` ровно под это.
 * . *Сходимость* — первая строка в общем суффиксе, перед которой стоит пустая
 *   строка и стек блоков совпал с прежним ([AdocBlockScanner.scanRegion]
 *   спрашивает об этом на каждой чистой границе). Дальше переиспользуются
 *   старые диапазоны со сдвигом на разницу длин и старые стеки как есть.
 * . Правка в незакрытом блоке сходимости не находит и сканирует до конца —
 *   ожидаемая деградация `TC-32`/`UC-4`, а не дефект.
 *
 * Класс держит один предыдущий проход и не потокобезопасен: вызывающий обязан
 * звать [update] последовательно (в редакторе это гарантирует `collectLatest`).
 * О видимом окне здесь не знают намеренно — `FR-25`/`FR-26` держат окно в UI.
 */
class AdocIncrementalHighlighter {

    private var lastText: String? = null
    private var lastScan: AdocScan? = null

    /**
     * Число строк, просканированных последним вызовом [update], — оракул
     * `TC-31`, `TC-34`…`TC-36`: инкрементальность измеряется строками, не временем.
     */
    var lastScannedLines: Int = 0
        private set

    /** Полный или инкрементальный проход — по тому, что известно о прошлом. */
    fun update(newText: String): AdocScan {
        val oldText = lastText
        val oldScan = lastScan
        val next = when {
            oldText == null || oldScan == null -> {
                lastScannedLines = countLines(newText)
                AdocBlockScanner.scan(newText)
            }

            oldText == newText -> {
                lastScannedLines = 0
                oldScan
            }

            else -> rescan(oldText, oldScan, newText)
        }
        lastText = newText
        lastScan = next
        return next
    }

    private fun rescan(oldText: String, oldScan: AdocScan, newText: String): AdocScan {
        val oldStarts = lineStarts(oldText)
        val newStarts = lineStarts(newText)

        val prefix = commonPrefixLength(oldText, newText)
        val suffix = commonSuffixLength(oldText, newText, prefix)
        val lineDelta = newStarts.size - oldStarts.size
        val charDelta = newText.length - oldText.length
        val suffixStart = newText.length - suffix

        // Рестарт: от строки первой правки вверх до чистой границы.
        var restartLine = lineOf(newStarts, prefix)
        while (restartLine > 0 && !isBlankLine(newText, newStarts, restartLine - 1)) restartLine--
        val restartOffset = newStarts[restartLine]
        val initialStack =
            if (restartLine == 0) emptyList() else oldScan.blockStates[restartLine - 1]

        val region = AdocBlockScanner.scanRegion(newText, restartOffset, initialStack) { scanned, _, stack ->
            val lineIndex = restartLine + scanned
            val alignedOld = lineIndex - lineDelta
            alignedOld in 1 until oldStarts.size &&
                newStarts[lineIndex - 1] >= suffixStart &&
                stack == oldScan.blockStates[alignedOld - 1]
        }
        lastScannedLines = region.scannedLines

        // Голова — старые диапазоны выше рестарта. Чистая граница гарантирует,
        // что ни один диапазон её не пересекает: пустая строка обрывает и
        // логическую строку инлайн-разбора, и любой построчный диапазон.
        val spans = ArrayList<AdocSpan>(oldScan.spans.size + region.spans.size)
        for (span in oldScan.spans) {
            if (span.range.start >= restartOffset) break
            spans += span
        }
        spans += region.spans

        val blockStates = ArrayList<List<AdocBlockFrame>>(newStarts.size)
        for (i in 0 until restartLine) blockStates += oldScan.blockStates[i]
        blockStates += region.blockStates

        // Хвост — старые результаты после точки сходимости, диапазоны со
        // сдвигом на разницу длин, стеки как есть: строки там идентичны.
        if (region.stopped) {
            val stopLine = restartLine + region.scannedLines
            val stopOffsetOld = newStarts[stopLine] - charDelta
            for (span in oldScan.spans) {
                if (span.range.start < stopOffsetOld) continue
                spans += AdocSpan(
                    range = AdocRange(span.range.start + charDelta, span.range.endExclusive + charDelta),
                    style = span.style,
                )
            }
            for (i in (stopLine - lineDelta) until oldScan.blockStates.size) {
                blockStates += oldScan.blockStates[i]
            }
        }

        return AdocScan(spans = spans, blockStates = blockStates)
    }

    // region служебное

    /** Смещения начал строк; строка нумеруется по `\n`, как в сканере. */
    private fun lineStarts(text: String): IntArray {
        var count = 1
        for (symbol in text) if (symbol == '\n') count++
        val starts = IntArray(count)
        var line = 1
        for (i in text.indices) {
            if (text[i] == '\n') starts[line++] = i + 1
        }
        return starts
    }

    private fun countLines(text: String): Int {
        var count = 1
        for (symbol in text) if (symbol == '\n') count++
        return count
    }

    /** Индекс строки, содержащей смещение [offset]. */
    private fun lineOf(starts: IntArray, offset: Int): Int {
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (starts[middle] <= offset) low = middle else high = middle - 1
        }
        return low
    }

    /** Пуста ли строка [line]: только пробельные символы, включая `\r`. */
    private fun isBlankLine(text: String, starts: IntArray, line: Int): Boolean {
        val end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
        for (i in starts[line] until end) {
            if (!text[i].isWhitespace()) return false
        }
        return true
    }

    private fun commonPrefixLength(old: String, new: String): Int {
        val limit = minOf(old.length, new.length)
        var i = 0
        while (i < limit && old[i] == new[i]) i++
        return i
    }

    private fun commonSuffixLength(old: String, new: String, prefix: Int): Int {
        val limit = minOf(old.length, new.length) - prefix
        var i = 0
        while (i < limit && old[old.length - 1 - i] == new[new.length - 1 - i]) i++
        return i
    }

    // endregion
}
