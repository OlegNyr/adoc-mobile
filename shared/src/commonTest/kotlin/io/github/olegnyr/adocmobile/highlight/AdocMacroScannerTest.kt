package io.github.olegnyr.adocmobile.highlight

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Критерии приёмки фичи 001-syntax-highlighting, слайс `SL-4` — макросы, ссылки
 * и атрибуты (`FR-21`…`FR-23`).
 *
 * Оракул везде выписан руками из требования либо из прогона эталонного
 * Asciidoctor 2.0.26 от 2026-08-17, но никогда из реализации. Ключевые факты,
 * снятые прогоном:
 *
 * * инлайновый макрос не требует границы слова слева: `хвостimage:pic.png[]`
 *   у эталона срабатывает;
 * * блочная форма `name::target[]` распознаётся только в начале блока — посреди
 *   абзаца строка остаётся текстом;
 * * правила цели зависят от имени: `image` и `xref` пускают пробелы внутри
 *   цели, `link` и `footnote` — нет;
 * * автоссылка требует границу слева (`слитноhttp://…` не ссылка), обрывается
 *   на пробеле, `[`, `<` и на знаке уже размеченного форматирования — так
 *   `http://…/_подчерк_/x` даёт ссылку только до подчёркиваний, как у эталона;
 *   финальные `,`, `.`, `)`, `?` и подобные в ссылку не входят;
 * * `mailto:` работает только в форме с `[…]` — голый `mailto:адрес`
 *   у эталона остаётся текстом, вопреки FR-21, зачислившему его в автоссылки.
 */
class AdocMacroScannerTest {

    private fun Source(vararg lines: String) = HighlightSource(*lines)

    // region макросы по закрытому перечню — FR-21

    @Test
    fun TC_24_blockAndInlineMacroFormsAreMarked() {
        val src = Source(
            "image::pic.png[]", // 0  блочная форма — два двоеточия
            "", // 1
            "см. xref:doc.adoc#id[Текст] тут", // 2  инлайновая форма
        )

        // `xref:` получает роль перекрёстной ссылки, а не общую роль макроса:
        // обе формы ссылок (`<<…>>` и `xref:`) должны выглядеть одинаково.
        assertEquals(
            listOf(
                AdocSpan(src.line(0), AdocStyle.Macro),
                AdocSpan(src.inLine(2, 4, 27), AdocStyle.CrossReference),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun TC_25_unknownMacroNameIsNotAMacro() {
        // Закрытый перечень имён — решение владельца в `FR-21`. Прогон эталона:
        // `myplugin::x[]` и `myplugin:y[]` он тоже выводит текстом — макросы из
        // нерасширенной поставки ему неизвестны так же, как сканеру.
        val src = Source("myplugin::x[] тут и myplugin:y[] тоже")
        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun TC_26_definitionListIsNotAMacro() {
        val src = Source("Примечание:: см. ниже")

        // Различает пробел после разделителя и закрытый перечень: имя не из
        // перечня с `::` без пробела — просто текст, с пробелом — список.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 10), AdocStyle.ListTerm),
                AdocSpan(src.inLine(0, 10, 12), AdocStyle.ListMarker),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_inlineMacroNeedsNoLeftBoundary() {
        val src = Source("хвостimage:pic.png[] тут")

        // Прогон эталона 2026-08-17: `хвостimage:pic.png[]` выводит `хвост` и
        // картинку — границы слова слева у инлайнового макроса нет.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 5, 20), AdocStyle.Macro)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_targetRulesDependOnTheMacroName() {
        val src = Source(
            "link:внутренний путь[текст] тут", // 0  пробел в цели link — не макрос
            "", // 1
            "xref:цель с пробелом[Текст] тут", // 2  а в цели xref — можно
            "", // 3
            "kbd:[Ctrl+T] тут", // 4  цель пуста
            "", // 5
            "footnote:[сноска] и footnote:id[текст] тут", // 6  обе формы сноски
        )

        // Все четыре случая сняты прогоном эталона 2026-08-17. `kbd` размечается
        // без оглядки на `:experimental:` — атрибуты документа сканер не ведёт,
        // а имя стоит в закрытом перечне `FR-21`; у эталона без атрибута
        // `kbd:[Ctrl+T]` остаётся текстом, и это расхождение задокументировано.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(2, 0, 27), AdocStyle.CrossReference),
                AdocSpan(src.inLine(4, 0, 12), AdocStyle.Macro),
                AdocSpan(src.inLine(6, 0, 17), AdocStyle.Macro),
                AdocSpan(src.inLine(6, 20, 38), AdocStyle.Macro),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_blockMacroIsRecognizedOnlyAtBlockStart() {
        val src = Source(
            "абзац перед", // 0
            "image::pic.png[]", // 1  посреди абзаца — просто текст
            "хвост после", // 2
        )

        // Прогон эталона 2026-08-17: строка остаётся частью абзаца, картинки
        // нет. Инлайновой формой она тоже не становится: цель `image:` не
        // может начинаться с двоеточия.
        assertEquals(emptyList(), src.scan().spans)
    }

    @Test
    fun FR_21_macroInsidePassthroughIsSuppressed() {
        val src = Source("+xref:doc.adoc[] не макрос+ тут")

        // Порядок проходов `FR-18` распространяется и на макросы: passthrough
        // съедает текст раньше, чем до него дойдёт проход макросов.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 0, 27), AdocStyle.InlinePassthrough)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_macroInsideFormattingKeepsBothRoles() {
        val src = Source("*жирный с xref:doc.adoc[внутри]* тут")

        // Прогон эталона: `<strong>` вокруг `<a>` — группа `quotes` применяется
        // раньше `macros`, и обе конструкции живут одновременно.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 32), AdocStyle.Bold),
                AdocSpan(src.inLine(0, 10, 31), AdocStyle.CrossReference),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region автоссылки и перекрёстные ссылки — FR-21

    @Test
    fun FR_21_autolinkBoundariesFollowTheReference() {
        val src = Source(
            "ссылка http://example.org/a, запятая", // 0
            "", // 1
            "(см. http://example.org/b) скобка", // 2
            "", // 3
            "точка в конце http://example.org/c.", // 4
            "", // 5
            "слитноhttp://example.org тут", // 6  нет границы слева — не ссылка
            "", // 7
            "ftp://files.example.org/x тут", // 8
        )

        // Всё снято прогоном эталона 2026-08-17 на кириллическом тексте:
        // запятая, закрывающая скобка и финальная точка в ссылку не входят,
        // а приклеенная к слову схема ссылкой не становится вовсе.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 7, 27), AdocStyle.Link),
                AdocSpan(src.inLine(2, 5, 25), AdocStyle.Link),
                AdocSpan(src.inLine(4, 14, 34), AdocStyle.Link),
                AdocSpan(src.inLine(8, 0, 25), AdocStyle.Link),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_autolinkStopsAtMarkedFormatting() {
        val src = Source("http://example.org/_подчерк_/x тут")

        // Прогон эталона: `http://example.org/_подчерк_/x` даёт ссылку только до
        // подчёркиваний — группа `quotes` уже съела `_подчерк_` как курсив.
        // Разведка называет это «URL ломаются о форматирование», и сканер
        // обязан ломаться так же, а не «чинить» ссылку (`FR-28`).
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 0, 19), AdocStyle.Link),
                AdocSpan(src.inLine(0, 19, 28), AdocStyle.Italic),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_urlWithBracketTextIsOneLink() {
        val src = Source("http://example.org[текст в скобках] тут")

        assertEquals(
            listOf(AdocSpan(src.inLine(0, 0, 35), AdocStyle.Link)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_mailtoWorksOnlyWithBrackets() {
        val src = Source(
            "mailto:join@example.org[Тема] тут", // 0
            "", // 1
            "mailto:dev@example.org без скобок", // 2
        )

        // Прогон эталона 2026-08-17: голый `mailto:адрес` остаётся текстом —
        // FR-21 зачислил mailto в автоссылки, но у эталона это только макрос
        // со скобками. Расхождение спеки зафиксировано в отчёте слайса.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 0, 29), AdocStyle.Link)),
            src.scan().spans,
        )
    }

    @Test
    fun FR_21_crossReferenceForms() {
        val src = Source("см. <<цель>> и <<цель,текст>> и \\<<нет>> тут")

        // Обе формы `<<…>>` из `FR-21`; экранированная — нет (`FR-20`).
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 4, 12), AdocStyle.CrossReference),
                AdocSpan(src.inLine(0, 15, 29), AdocStyle.CrossReference),
            ),
            src.scan().spans,
        )
    }

    // endregion

    // region ссылки на атрибуты — FR-22

    @Test
    fun FR_22_attributeReferencesAreMarked() {
        val src = Source(
            "см. {attr-x} и слитно{a1}{b-2} тут", // 0
            "", // 1
            "{attr with space} не ссылка", // 2  пробел в имени недопустим
        )

        // Прогон эталона: ссылка на атрибут работает и вплотную к слову, и
        // подряд; пробел в имени делает скобки обычным текстом. Определён ли
        // атрибут — сканер не проверяет: он не ведёт таблицу атрибутов, и
        // `{undefined}` размечается по форме — расхождение с эталоном, который
        // оставляет неопределённую ссылку текстом, записано в отчёте слайса.
        assertEquals(
            listOf(
                AdocSpan(src.inLine(0, 4, 12), AdocStyle.AttributeReference),
                AdocSpan(src.inLine(0, 21, 25), AdocStyle.AttributeReference),
                AdocSpan(src.inLine(0, 25, 30), AdocStyle.AttributeReference),
            ),
            src.scan().spans,
        )
    }

    @Test
    fun FR_20_escapedMacroAndAttributeAreNotMarked() {
        val src = Source("\\{attr-x} экранировано и \\xref:doc.adoc[] тоже")

        // Один слэш экранирует и ссылку на атрибут, и макрос (`FR-20`, прогон
        // эталона 2026-08-17).
        assertEquals(emptyList(), src.scan().spans)
    }

    // endregion

    // region типографские кавычки — не-цель FR-23

    @Test
    fun TC_27_typographicQuotesStayMonospaceInsidePlainQuotes() {
        val src = Source("\"`код`\" тут")

        // Намеренное расхождение с эталоном (`FR-23`): эталон выводит
        // типографские кавычки, сканер размечает `` `код` `` моноширинным
        // внутри обычных кавычек. Типографские кавычки — не-цель.
        assertEquals(
            listOf(AdocSpan(src.inLine(0, 1, 6), AdocStyle.Monospace)),
            src.scan().spans,
        )
    }

    // endregion
}
