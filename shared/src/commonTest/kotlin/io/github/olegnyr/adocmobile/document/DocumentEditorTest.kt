package io.github.olegnyr.adocmobile.document

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 004-document-and-files, слайс SL-3: `TC-12`…`TC-14`.
 *
 * Проверяется не наш алгоритм — своей истории правок в проекте нет (`FR-10`), —
 * а то, что встроенная в `TextFieldState` история ведёт себя так, как обещано
 * требованиями. Отсюда и тон утверждений: они описывают поведение Compose.
 *
 * `TC-10` и `TC-11` здесь отсутствуют намеренно, и это не забывчивость.
 * Правила слияния (`TextUndoManager.merge`) требуют `canMerge = true` у *обеих*
 * соседних операций, а записать такую операцию умеет единственный путь —
 * `TextFieldState.editAsUser`, `internal inline`-функция, которую дёргают сессия
 * ввода, жесты и аппаратная клавиатура. Через публичный API каждая новая
 * операция получает `canMerge = false` (`commitEdit` → `NeverMerge`), поэтому
 * слияние из `commonTest` не воспроизвести: проверить группировку непрерывного
 * ввода можно только прогнав настоящий `BasicTextField` через сессию ввода, а
 * это отдельная тестовая инфраструктура, которой в проекте пока нет.
 *
 * Метки `TC-10` и `TC-11` не навешены ни на один здешний тест сознательно:
 * ярлык не на том кейсе хуже отсутствующего — сверка спеки с кодом отчиталась
 * бы о покрытии, которого нет.
 */
class DocumentEditorTest {

    private val source = DocumentSource(id = "content://doc/1", displayName = "notes.adoc")

    private fun editorWith(text: String): DocumentEditor =
        DocumentEditor().apply { load(DocumentState.opened(source, text)) }

    private fun DocumentEditor.currentText(): String = textFieldState.text.toString()

    /**
     * Тот же документ и та же история, прогнанные через публичный
     * `TextFieldState.Saver` — так состояние переживает выгрузку приложения.
     */
    private fun DocumentEditor.copyThroughSaver(): DocumentEditor {
        val scope = SaverScope { true }
        val saved = requireNotNull(with(TextFieldState.Saver) { scope.save(textFieldState) }) {
            "TextFieldState.Saver отказался сохранять состояние — форма сохранения изменилась"
        }
        val restored = requireNotNull(with(TextFieldState.Saver) { restore(saved) }) {
            "TextFieldState.Saver не восстановил состояние из собственного же снимка"
        }
        return DocumentEditor(restored)
    }

    /**
     * `TC-12`, он же `FR-15` — сторож чужого поведения, а не проверка нашего кода.
     *
     * Разведка (`research.adoc`) зафиксировала два факта по исходникам Compose
     * 1.11.2, и `FR-12` держится целиком на них:
     *
     * . `commitEdit` записывает программную правку в историю с `NeverMerge` —
     * то есть одним нераздельным шагом;
     * . KDoc перечисления `TextFieldEditUndoBehavior` при этом утверждает
     * обратное: «Programmatic updates should clear the history completely».
     *
     * Мы опираемся на код, а не на документацию, — значит расхождение обязано
     * быть под тестом. Если Compose однажды пойдёт за собственным KDoc, вставка
     * с панели начнёт стирать всю историю правок, и узнать об этом нужно от
     * прогона, а не от пользователя.
     *
     * Две вставки подряд подобраны так, что удовлетворяют *всем* условиям
     * слияния из `TextUndoManager.merge`: обе — вставки (`Insert`), вторая
     * продолжает первую (каретка идёт вперёд), между ними заведомо меньше 5000 мс,
     * ни одна не является переводом строки. Помешать слиянию может ровно одно —
     * флаг `canMerge = false`, который ставит `NeverMerge`. Тот же флаг делает
     * операцию несливаемой и с ручным вводом: `merge` отказывает симметрично,
     * `if (!canMerge || !next.canMerge) return null`, независимо от того, чем
     * является соседняя операция.
     */
    @Test
    fun TC_12_programmaticEditIsOneUndoStepAndNeverMerges() {
        val editor = editorWith("== Раздел\n")
        val opened = editor.currentText()

        editor.textFieldState.edit { insert(length, "IMPORTANT: ") }
        val afterFirst = editor.currentText()
        editor.textFieldState.edit { insert(length, "текст") }
        val afterSecond = editor.currentText()

        assertTrue(
            editor.canUndo,
            "После программной правки история пуста. В Compose 1.11.2 `commitEdit` передавал " +
                "`NeverMerge`, то есть правка попадала в историю. Пустая история означает переход " +
                "на `ClearHistory` — поведение, обещанное KDoc `TextFieldEditUndoBehavior`. " +
                "Тогда FR-12 встроенным механизмом больше не обеспечивается: вставка с панели " +
                "будет стирать все предыдущие правки пользователя.",
        )

        editor.undo()
        assertEquals(
            afterFirst,
            editor.currentText(),
            "Отмена обязана снять ровно одну программную вставку. Получено «${editor.currentText()}». " +
                "Если это «$opened» — две программные вставки слились в один шаг, значит `commitEdit` " +
                "перестал передавать `NeverMerge`, и вставка с панели больше не отменяется отдельно " +
                "от соседних правок (FR-12).",
        )

        editor.undo()
        assertEquals(
            opened,
            editor.currentText(),
            "Вторая отмена обязана вернуть документ к загруженному тексту: " +
                "программных правок было две, значит и шагов отмены два.",
        )
        assertFalse(
            editor.canUndo,
            "История исчерпана: обе программные правки отменены, а сама загрузка документа " +
                "в историю попадать не должна (FR-14).",
        )

        editor.redo()
        editor.redo()
        assertEquals(
            afterSecond,
            editor.currentText(),
            "Повтор обязан вернуть текст теми же шагами, что и отмена: " +
                "гранулярность шага у истории одна на оба направления (FR-10).",
        )
    }

    /**
     * `TC-12`, вторая половина: программная правка не сливается с *сливаемым* соседом.
     *
     * Предыдущий тест сталкивает две программные правки, у обеих `canMerge = false`,
     * поэтому он не отличает «программная правка несливаема» от «несливаем сосед».
     * Здесь сосед заведомо сливаем: состояние прогоняется через публичный
     * `TextFieldState.Saver`, а тот не сохраняет флаг `canMerge` — восстановленная
     * операция получает значение по умолчанию, `true`, то есть ровно то, с каким
     * попадает в историю ручной ввод.
     *
     * Что тест доказывает: правка, записанная через `TextFieldState.edit {}`, не
     * сливается с предшествующей операцией, даже когда та удовлетворяет всем
     * условиям `TextUndoManager.merge` — тот же тип, каретка идёт вперёд, разрыв
     * меньше 5000 мс, перевода строки нет.
     *
     * Чего не доказывает: что сосед был *ручным вводом*. Породить ручной ввод из
     * `commonTest` нельзя (см. комментарий к классу), но в `merge` источник
     * операции и не участвует — только `canMerge`, тип, индекс, время и перевод
     * строки. Слабое место у теста одно: если будущая версия Compose начнёт
     * сохранять `canMerge` через `Saver`, сосед станет несливаемым и тест
     * незаметно ослабнет, оставшись зелёным. Заметить это можно только чтением
     * исходников при обновлении Compose — что `FR-15` и предписывает.
     */
    @Test
    fun TC_12_programmaticEditDoesNotMergeWithMergeableNeighbour() {
        val seeded = DocumentState.opened(source, "== Раздел\n")
        val editor = DocumentEditor().apply { load(seeded) }
        editor.textFieldState.edit { insert(length, "начало") }

        // Прогон через Saver возвращает ту же операцию, но уже со сливаемым флагом.
        val withMergeableHistory = editor.copyThroughSaver()
        val beforeProgrammatic = withMergeableHistory.currentText()
        withMergeableHistory.textFieldState.edit { insert(length, " вставка") }

        withMergeableHistory.undo()
        assertEquals(
            beforeProgrammatic,
            withMergeableHistory.currentText(),
            "Программная вставка слилась с предыдущей операцией, хотя записывается с " +
                "`NeverMerge`. Получено «${withMergeableHistory.currentText()}». Это значит, что " +
                "`canMerge = false` больше не блокирует слияние (или `commitEdit` перестал его " +
                "выставлять), и вставка с панели быстрой вставки отменяется вместе с тем, что " +
                "пользователь набрал перед ней (FR-12).",
        )
    }

    /** `TC-13`: отмена при пустой истории — ничего не делает и не бросает исключение. */
    @Test
    fun TC_13_undoAndRedoOnEmptyHistoryDoNothing() {
        val fresh = DocumentEditor()

        assertFalse(fresh.canUndo)
        assertFalse(fresh.canRedo)
        fresh.undo()
        fresh.redo()
        assertEquals("", fresh.currentText(), "пустой редактор не имеет права породить текст из истории")

        val editor = editorWith("= Заметки\n\nТекст.\n")

        assertFalse(
            editor.canUndo,
            "загрузка документа — не правка пользователя и в историю попадать не должна",
        )
        assertFalse(editor.canRedo)
        editor.undo()
        editor.redo()
        assertEquals("= Заметки\n\nТекст.\n", editor.currentText())
    }

    /** `TC-14`, `FR-14`: загрузка нового документа очищает историю. */
    @Test
    fun TC_14_loadingAnotherDocumentClearsHistory() {
        val editor = editorWith("= Первый\n")
        editor.textFieldState.edit { insert(length, "правка первого документа\n") }
        assertTrue(editor.canUndo, "предусловие теста: правка обязана попасть в историю")

        val other = DocumentSource(id = "content://doc/2", displayName = "other.adoc")
        editor.load(DocumentState.opened(other, "= Второй\n"))

        assertEquals("= Второй\n", editor.currentText())
        assertFalse(
            editor.canUndo,
            "отменить правку в файл, который уже закрыт, нельзя (FR-14)",
        )
        assertFalse(editor.canRedo, "повтор в закрытый документ бессмыслен ровно так же, как отмена")

        editor.undo()
        editor.redo()
        assertEquals(
            "= Второй\n",
            editor.currentText(),
            "ни отмена, ни повтор не имеют права подменить текст открытого документа текстом прежнего",
        )
    }

    /**
     * `TC-14`, вторая половина: очистка не ломает историю *следующего* документа.
     *
     * Отдельным тестом, потому что `clearHistory` легко поставить так, что он
     * снесёт и операцию, записанную уже после загрузки.
     */
    @Test
    fun TC_14_historyWorksAgainAfterLoadingAnotherDocument() {
        val editor = editorWith("= Первый\n")
        editor.textFieldState.edit { insert(length, "правка\n") }

        val other = DocumentSource(id = "content://doc/2", displayName = "other.adoc")
        editor.load(DocumentState.opened(other, "= Второй\n"))
        editor.textFieldState.edit { insert(length, "правка второго\n") }

        assertTrue(editor.canUndo)
        editor.undo()
        assertEquals("= Второй\n", editor.currentText())
        assertFalse(editor.canUndo, "глубже загруженного документа история уходить не должна")
    }
}
