package io.github.olegnyr.adocmobile.insert

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.insert
import androidx.compose.ui.text.TextRange
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Критерии приёмки фичи 006-quick-insert-panel, слайс SL-1: `TC-1`, `TC-2`,
 * `TC-8` и сторож одного шага отмены `TC-3`.
 *
 * Проверяется логика вставки на настоящем [TextFieldState] — устройству здесь
 * делать нечего (`NFR-10`): и текст, и выделение, и история отмены живут в
 * обычном объекте Kotlin, что уже доказал `DocumentEditorTest`.
 *
 * Первый же тест заодно закрывает допущение аналитики: `TextFieldBuffer` внутри
 * `edit {}` позволяет и заменить диапазон, и поставить каретку одним вызовом.
 * Если бы это было не так, тесты не позеленели бы, и правился бы `FR-8`
 * (запасной вариант — каретка в конец вставки), а не городился обход.
 */
class InsertApplierTest {

    /**
     * `TC-1`, `FR-7`: обёртка непустого выделения — маркеры вокруг текста,
     * сам текст не меняется, каретка сразу за закрывающим маркером (`OQ-6`).
     */
    @Test
    fun TC_1_wrapSelectionPutsMarkersAroundAndCaretAfterClosingMarker() {
        val state = TextFieldState("абв слово где", initialSelection = TextRange(4, 9))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("абв *слово* где", state.text.toString())
        assertEquals(
            TextRange(11),
            state.selection,
            "Каретка обязана встать сразу за закрывающим маркером (OQ-6): " +
                "продолжение набора — самый частый случай после обёртки.",
        )
    }

    /**
     * `TC-1`, обратное выделение: пользователь вёл палец справа налево, и
     * `start > end`. Обёртка обязана вести себя так же, как при прямом.
     */
    @Test
    fun TC_1_reversedSelectionWrapsIdentically() {
        val state = TextFieldState("абв слово где", initialSelection = TextRange(9, 4))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("абв *слово* где", state.text.toString())
        assertEquals(TextRange(11), state.selection)
    }

    /**
     * `TC-2`, `FR-8`: пустое выделение — вставка заготовки в позицию каретки,
     * каретка между маркерами, где пользователь продолжит набор.
     */
    @Test
    fun TC_2_emptySelectionInsertsMarkersWithCaretBetween() {
        val state = TextFieldState("абв ", initialSelection = TextRange(4))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("абв **", state.text.toString())
        assertEquals(
            TextRange(5),
            state.selection,
            "Каретка обязана встать между маркерами: содержимое набирается сразу после касания (FR-8).",
        )
    }

    /**
     * `TC-8`, `FR-7`: многострочное выделение оборачивается буквально — панель
     * не пытается «чинить» разметку, интерпретация остаётся сканеру и рендеру.
     */
    @Test
    fun TC_8_multilineSelectionIsWrappedLiterally() {
        val state = TextFieldState("одна\nдве три", initialSelection = TextRange(0, 8))

        state.applyInsert(InsertConstructs.bold)

        assertEquals("*одна\nдве* три", state.text.toString())
        assertEquals(TextRange(10), state.selection)
    }

    /**
     * `TC-4`, `FR-10`: строчная конструкция применяется к логической строке
     * каретки — префикс в начало строки, текст строки сохраняется, каретка на
     * прежнем символе с учётом сдвига.
     */
    @Test
    fun TC_4_linePrefixInsertsAtLineStartAndShiftsCaret() {
        val state = TextFieldState("заголовок\nтекст", initialSelection = TextRange(12))

        state.applyInsert(InsertConstructs.sectionTitle)

        assertEquals("заголовок\n== текст", state.text.toString())
        assertEquals(
            TextRange(15),
            state.selection,
            "Каретка обязана остаться на прежнем символе с учётом сдвига на длину префикса (FR-10).",
        )
    }

    /**
     * `TC-4`, решение `OQ-2`: маркер списка на многострочном выделении — на
     * каждую строку выделения; выделение остаётся на том же тексте.
     * Всё — одна правка (один шаг отмены — сторож `TC_3_lineConstruct…`).
     */
    @Test
    fun TC_4_listMarkerOnMultilineSelectionPrefixesEachLine() {
        val state = TextFieldState("одна\nдве\nтри", initialSelection = TextRange(2, 10))

        state.applyInsert(InsertConstructs.listItem)

        assertEquals("* одна\n* две\n* три", state.text.toString())
        assertEquals(
            TextRange(4, 16),
            state.selection,
            "Выделение обязано остаться на тех же символах, сдвинутых вставленными маркерами.",
        )
    }

    /**
     * `TC-4`, граница многострочного случая: строка, которой выделение касается
     * только позицией конца в её начале, маркер не получает — выделено в ней
     * ноль символов.
     */
    @Test
    fun TC_4_lineTouchedOnlyBySelectionEndGetsNoMarker() {
        val state = TextFieldState("одна\nдве\n", initialSelection = TextRange(0, 9))

        state.applyInsert(InsertConstructs.listItem)

        assertEquals("* одна\n* две\n", state.text.toString())
    }

    /**
     * `TC-4`, заголовок при многострочном выделении: решение `OQ-2` задаёт
     * многострочное поведение только списку, заголовок применяется к строке
     * начала выделения (`FR-10`: логическая строка каретки).
     */
    @Test
    fun TC_4_headingOnMultilineSelectionAppliesToLineOfSelectionStart() {
        val state = TextFieldState("одна\nдве", initialSelection = TextRange(1, 7))

        state.applyInsert(InsertConstructs.sectionTitle)

        assertEquals("== одна\nдве", state.text.toString())
        assertEquals(TextRange(4, 10), state.selection)
    }

    /**
     * `TC-5`, решение `OQ-2`: повтор — переключатель. Касание `==` на строке,
     * уже начинающейся с `== `, снимает префикс; каретка сдвигается на его
     * длину назад.
     */
    @Test
    fun TC_5_repeatedHeadingTapRemovesPrefix() {
        val state = TextFieldState("== текст", initialSelection = TextRange(5))

        state.applyInsert(InsertConstructs.sectionTitle)

        assertEquals("текст", state.text.toString())
        assertEquals(TextRange(2), state.selection)
    }

    /** `TC-5`: каретка внутри снимаемого префикса прижимается к началу строки, а не уходит в минус. */
    @Test
    fun TC_5_caretInsideRemovedPrefixClampsToLineStart() {
        val state = TextFieldState("абв\n== текст", initialSelection = TextRange(5))

        state.applyInsert(InsertConstructs.sectionTitle)

        assertEquals("абв\nтекст", state.text.toString())
        assertEquals(TextRange(4), state.selection)
    }

    /**
     * `TC-5`: переключатель — свойство строчных кнопок вообще (`OQ-2`), маркер
     * списка на строке с маркером тоже снимается.
     */
    @Test
    fun TC_5_repeatedListMarkerTapRemovesMarker() {
        val state = TextFieldState("* пункт", initialSelection = TextRange(4))

        state.applyInsert(InsertConstructs.listItem)

        assertEquals("пункт", state.text.toString())
        assertEquals(TextRange(2), state.selection)
    }

    /**
     * `TC-5`, обратная сторона переключателя: строка с *другим* префиксом —
     * не повтор, конструкция вставляется буквально, ничего не «чинится»
     * (философия `TC-8`: интерпретация остаётся сканеру и рендеру).
     */
    @Test
    fun TC_5_differentPrefixIsNotAToggleAndInsertsLiterally() {
        val state = TextFieldState("= текст", initialSelection = TextRange(4))

        state.applyInsert(InsertConstructs.sectionTitle)

        assertEquals("== = текст", state.text.toString())
        assertEquals(TextRange(7), state.selection)
    }

    /**
     * `TC-6`, решение `OQ-5`: заготовка `link` без выделения — макро-форма URL
     * без `link:`; русский заместитель адреса *выделен*, печать заменяет его
     * сразу (каретка в позиции адреса).
     */
    @Test
    fun TC_6_linkTemplateInsertsWithAddressPlaceholderSelected() {
        val state = TextFieldState("абв ", initialSelection = TextRange(4))

        state.applyInsert(InsertConstructs.link)

        assertEquals("абв https://адрес[текст]", state.text.toString())
        assertEquals(
            TextRange(12, 17),
            state.selection,
            "Заместитель «адрес» обязан быть выделен: печать заменяет его без лишних движений (OQ-5).",
        )
    }

    /**
     * `TC-6`, обёртка выделения (`FR-7` называет `link` инлайн-конструкцией):
     * выделенный текст встаёт на место цели (`⌶` таблицы заготовок), выделенным
     * остаётся заместитель текста ссылки.
     */
    @Test
    fun TC_6_linkWrapsSelectionAsTargetAndSelectsTextPlaceholder() {
        val state = TextFieldState("см example.com тут", initialSelection = TextRange(3, 14))

        state.applyInsert(InsertConstructs.link)

        assertEquals("см https://example.com[текст] тут", state.text.toString())
        assertEquals(TextRange(23, 28), state.selection)
    }

    /**
     * `TC-6`, решение `OQ-5`: картинка — блочная `image::`. В середине непустой
     * строки заготовка отделяется переводами строк с обеих сторон (правило
     * блочных заготовок из аналитики), заместитель пути выделен.
     */
    @Test
    fun TC_6_imageTemplateIsBlockFormOnItsOwnLine() {
        val state = TextFieldState("абвгде", initialSelection = TextRange(3))

        state.applyInsert(InsertConstructs.image)

        assertEquals("абв\nimage::путь[]\nгде", state.text.toString())
        assertEquals(TextRange(11, 15), state.selection)
    }

    /** `TC-6`: в начале пустой строки блочной заготовке переводы строк не нужны. */
    @Test
    fun TC_6_imageAtLineStartInsertsWithoutExtraNewlines() {
        val state = TextFieldState("", initialSelection = TextRange(0))

        state.applyInsert(InsertConstructs.image)

        assertEquals("image::путь[]", state.text.toString())
        assertEquals(TextRange(7, 11), state.selection)
    }

    /**
     * `TC-6`, обёртка выделения картинкой: выделенное — путь, каретка между
     * скобками атрибутов; хвост строки уезжает на свою строку — блочная форма
     * не терпит текста после `[]`.
     */
    @Test
    fun TC_6_imageWrapsSelectionAsPathWithCaretInAttributes() {
        val state = TextFieldState("logo.png тут", initialSelection = TextRange(0, 8))

        state.applyInsert(InsertConstructs.image)

        assertEquals("image::logo.png[]\n тут", state.text.toString())
        assertEquals(TextRange(16), state.selection)
    }

    /**
     * `TC-3`, `FR-9` для строчных конструкций: маркер на каждую строку
     * выделения — три вставки, но один шаг отмены (решение `OQ-2`: «всё — один
     * шаг отмены»).
     */
    @Test
    fun TC_3_lineConstructUndoesInOneStep() {
        val source = DocumentSource(id = "content://doc/1", displayName = "notes.adoc")
        val editor = DocumentEditor().apply { load(DocumentState.opened(source, "")) }
        editor.textFieldState.edit {
            insert(0, "одна\nдве\nтри")
            selection = TextRange(0, 12)
        }

        editor.textFieldState.applyInsert(InsertConstructs.listItem)
        assertEquals("* одна\n* две\n* три", editor.textFieldState.text.toString())

        editor.undo()
        assertEquals(
            "одна\nдве\nтри",
            editor.textFieldState.text.toString(),
            "Маркеры на всех строках обязаны сняться одним undo (OQ-2: всё — один шаг отмены).",
        )
        editor.redo()
        assertEquals("* одна\n* две\n* три", editor.textFieldState.text.toString())
    }

    /** `TC-3`, `FR-9` для заготовок: вставка `link` с заместителями — один шаг отмены. */
    @Test
    fun TC_3_templateUndoesInOneStep() {
        val source = DocumentSource(id = "content://doc/1", displayName = "notes.adoc")
        val editor = DocumentEditor().apply { load(DocumentState.opened(source, "")) }
        editor.textFieldState.edit {
            insert(0, "абв ")
            selection = TextRange(4)
        }

        editor.textFieldState.applyInsert(InsertConstructs.link)
        assertEquals("абв https://адрес[текст]", editor.textFieldState.text.toString())

        editor.undo()
        assertEquals("абв ", editor.textFieldState.text.toString(), "заготовка обязана сняться одним undo")
        editor.redo()
        assertEquals("абв https://адрес[текст]", editor.textFieldState.text.toString())
    }

    /**
     * `TC-3`, `FR-9`: вставка с панели — один нераздельный шаг отмены.
     *
     * Первый undo убирает всю вставку и только её: набранный до неё текст на
     * месте. Redo возвращает вставку. Сам механизм — забота Compose, и его
     * сторожит `DocumentEditorTest.TC_12_*`; здесь проверяется наш вклад: что
     * [applyInsert] остался *одним* вызовом `edit {}` — дробление на два
     * раздробило бы и шаг отмены.
     */
    @Test
    fun TC_3_panelInsertUndoesInOneStepAndRedoReturnsIt() {
        val source = DocumentSource(id = "content://doc/1", displayName = "notes.adoc")
        val editor = DocumentEditor().apply { load(DocumentState.opened(source, "")) }
        // «Набрано абв»: отдельный шаг истории с кареткой в конце текста.
        editor.textFieldState.edit {
            insert(0, "абв")
            selection = TextRange(3)
        }

        editor.textFieldState.applyInsert(InsertConstructs.bold)
        assertEquals("абв**", editor.textFieldState.text.toString())

        assertTrue(editor.canUndo, "предусловие: вставка обязана попасть в историю отмены")
        editor.undo()
        assertEquals(
            "абв",
            editor.textFieldState.text.toString(),
            "Первый undo обязан убрать всю вставку и только её (FR-9): " +
                "если текста меньше — вставка слиплась с набором, если маркер остался — раздробилась.",
        )

        editor.redo()
        assertEquals("абв**", editor.textFieldState.text.toString(), "redo обязан вернуть вставку целиком")

        editor.undo()
        editor.undo()
        assertEquals("", editor.textFieldState.text.toString())
        assertFalse(editor.canUndo, "глубже загруженного документа история уходить не должна")
    }
}
