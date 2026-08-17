package io.github.olegnyr.adocmobile.ui

import io.github.olegnyr.adocmobile.highlight.AdocBlockScanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Подсветка редактора между сканером и композицией — слайс `SL-5`.
 *
 * Главный кейс — `TC-39`: во время активной композиции IME диапазоны не
 * пересчитываются. Композиция здесь — булев флаг, снятый редактором с
 * `TextFieldCharSequence.composition`; сам флаг в композиции не проверить, но
 * контракт «composing = true ⇒ ни одного пересканирования» проверяется целиком.
 */
class AdocEditorHighlightingTest {

    @Test
    fun TC_39_activeImeCompositionDoesNotRescan() {
        val highlighting = AdocEditorHighlighting()
        val settled = highlighting.onText("абзац с *жирным* словом", composing = false)
        assertTrue(settled.isNotEmpty(), "исходный текст должен был дать диапазоны")

        // Пользователь набирает составной символ: текст уже изменился, но
        // композиция активна — возвращается прежний список, ничего не сканируется.
        val during = highlighting.onText("абзац с *жирным* словомあ", composing = true)
        assertSame(settled, during, "во время композиции диапазоны пересчитаны")
        assertEquals(0, highlighting.lastScannedLines)

        // Композиция завершена — подсветка догоняет текст.
        val after = highlighting.onText("абзац с *жирным* словомあ", composing = false)
        assertEquals(
            AdocBlockScanner.scan("абзац с *жирным* словомあ").spans,
            after,
            "после композиции диапазоны обязаны догнать текст",
        )
        assertTrue(highlighting.lastScannedLines > 0)
    }

    @Test
    fun TC_39_compositionAtTheVeryStartKeepsTheEmptyList() {
        val highlighting = AdocEditorHighlighting()

        // Композиция началась раньше первого прохода: списка ещё нет, и его
        // не появляется — редактор живёт без подсветки до конца композиции.
        assertEquals(emptyList(), highlighting.onText("あ", composing = true))
        assertEquals(0, highlighting.lastScannedLines)
    }
}
