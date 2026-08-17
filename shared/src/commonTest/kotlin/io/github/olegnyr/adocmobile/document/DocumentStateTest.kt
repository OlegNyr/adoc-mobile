package io.github.olegnyr.adocmobile.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 004-document-and-files, слайс SL-1: `TC-1`…`TC-5`.
 *
 * Проверяется модель документа и главное её свойство — признак несохранённых
 * изменений, который обязан отражать *реальное расхождение* с записанным на
 * диск, а не факт касания текста (`FR-8`).
 */
class DocumentStateTest {

    private val source = DocumentSource(id = "content://doc/1", displayName = "notes.adoc")

    private fun opened(text: String = "= Заметки\n\nТекст.\n") =
        DocumentState.opened(source = source, fileText = text)

    @Test
    fun TC_1_openedDocumentIsClean() {
        val document = opened()

        assertFalse(document.isModified, "только что открытый документ не может быть изменённым")
        assertEquals(source, document.source)
        assertEquals(LineEnding.Lf, document.lineEnding)
        assertNull(document.savedAt, "записи ещё не было — отметки времени быть не должно")
    }

    @Test
    fun TC_2_editRaisesModifiedFlag() {
        val document = opened().edited("= Заметки\n\nТекст и правка.\n")

        assertTrue(document.isModified)
    }

    @Test
    fun TC_3_revertingBackToSavedTextClearsFlag() {
        // Ради этого требование FR-8 и написано: метка «не сохранён» не должна
        // гореть после того, как пользователь отменил свою же правку.
        val original = "= Заметки\n\nТекст.\n"
        val document = opened(original)
            .edited("= Заметки\n\nТекст и правка.\n")
            .edited(original)

        assertFalse(document.isModified, "текст вернулся к сохранённому — признак обязан погаснуть")
    }

    @Test
    fun TC_4_successfulSaveClearsFlagAndStampsTime() {
        val document = opened()
            .edited("= Заметки\n\nНовый текст.\n")
            .saved(writtenText = "= Заметки\n\nНовый текст.\n", at = 1_755_000_000_000)

        assertFalse(document.isModified)
        assertEquals(1_755_000_000_000, document.savedAt)
        assertEquals("= Заметки\n\nНовый текст.\n", document.text)
    }

    @Test
    fun TC_5_failedSaveKeepsFlagAndText() {
        // Отказ записи не имеет права выглядеть как успех: текст остаётся в
        // памяти, признак горит, отметка времени не сдвигается (FR-17).
        val edited = opened().edited("= Заметки\n\nНесохранённое.\n")
        val afterFailure = edited.saveFailed()

        assertTrue(afterFailure.isModified)
        assertEquals("= Заметки\n\nНесохранённое.\n", afterFailure.text)
        assertNull(afterFailure.savedAt)
    }

    @Test
    fun TC_4_saveAfterFailureStampsTimeOfSuccess() {
        val document = opened()
            .edited("правка")
            .saveFailed()
            .saved(writtenText = "правка", at = 1_755_000_000_777)

        assertFalse(document.isModified)
        assertEquals(1_755_000_000_777, document.savedAt)
    }

    @Test
    fun TC_2_editWithIdenticalTextDoesNotRaiseFlag() {
        // Поле ввода может сообщить об изменении, которого не было (например,
        // при смене выделения). Признак опирается на текст, а не на событие.
        val text = "= Заметки\n\nТекст.\n"

        assertFalse(opened(text).edited(text).isModified)
    }

    @Test
    fun TC_1_lineEndingIsCapturedOnOpen() {
        val document = DocumentState.opened(source, "= Заголовок\r\n\r\nАбзац.\r\n")

        assertEquals(LineEnding.Crlf, document.lineEnding)
        assertEquals("= Заголовок\n\nАбзац.\n", document.text, "в модели текст всегда с LF")
    }

    @Test
    fun TC_6_fileTextRestoresOriginalLineEnding() {
        val raw = "= Заголовок\r\n\r\nАбзац.\r\n"
        val document = DocumentState.opened(source, raw)

        assertEquals(raw, document.fileText(), "запись обязана вернуть исходный вид файла")
    }
}
