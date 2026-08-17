package io.github.olegnyr.adocmobile.ui

import io.github.olegnyr.adocmobile.highlight.AdocIncrementalHighlighter
import io.github.olegnyr.adocmobile.highlight.AdocSpan

/**
 * Подсветка редактора между сканером и композицией — слайс `SL-5`.
 *
 * Обязанности две, и обе — требования:
 *
 * * `FR-27` — текст уходит в [AdocIncrementalHighlighter], который пересканирует
 *   только затронутый правкой участок;
 * * `TC-39`, НФТ «ввод и IME» — во время активной композиции IME диапазоны не
 *   пересчитываются: [onText] с `composing = true` возвращает прежний список
 *   как есть. Завершение композиции меняет состояние поля, редактор позовёт
 *   [onText] снова уже без флага, и подсветка догонит текст.
 *
 * Класс не потокобезопасен: вызывающий обязан звать [onText] последовательно —
 * в редакторе это гарантирует `collectLatest` одного потока данных.
 */
internal class AdocEditorHighlighting(
    private val highlighter: AdocIncrementalHighlighter = AdocIncrementalHighlighter(),
) {

    /** Последний выданный список диапазонов; до первого прохода пуст. */
    var spans: List<AdocSpan> = emptyList()
        private set

    /** Строк, просканированных последним [onText], — оракул `TC-31`/`TC-39`. */
    var lastScannedLines: Int = 0
        private set

    /** Актуальные диапазоны для [text]; при активной композиции — прежние. */
    fun onText(text: String, composing: Boolean): List<AdocSpan> {
        if (composing) {
            lastScannedLines = 0
            return spans
        }
        spans = highlighter.update(text).spans
        lastScannedLines = highlighter.lastScannedLines
        return spans
    }
}
