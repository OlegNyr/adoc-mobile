package io.github.olegnyr.adocmobile.preview

import io.github.olegnyr.adocmobile.diagram.DiagramOutcome
import io.github.olegnyr.adocmobile.diagram.DiagramSupport
import io.github.olegnyr.adocmobile.diagram.decodeKrokiSource
import io.github.olegnyr.adocmobile.diagram.resolveDiagramBlocks
import io.github.olegnyr.adocmobile.theme.AdocColors
import io.github.olegnyr.adocmobile.render.DiagramOptions
import io.github.olegnyr.adocmobile.theme.AdocTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Оболочка документа для превью: конвертер отдаёт фрагмент, а показывать надо
 * страницу.
 *
 * `FR-19` требует, чтобы оболочка собиралась в общем коде, а платформе
 * доставался только показ. Причина не в чистоте: оболочка задаёт кодировку,
 * ширину и стиль, то есть определяет, как документ выглядит, — а это поведение
 * продукта и обязано быть одинаковым на всех платформах.
 *
 * Стиль порождается из ролей палитры ([previewStylesheet], `SL-3`): тёмный фон
 * до первой отрисовки (`FR-20`), перечень классов конвертера (`FR-21`), цвета и
 * гарнитуры из токенов (`FR-22`), ширина по экрану (`FR-24`), ноль внешних
 * ресурсов (`FR-27`).
 */

/**
 * Заворачивает фрагмент HTML в страницу.
 *
 * Фрагмент вставляется как есть: «не подменять эталон» — граница работ фичи.
 * Экранирования тут нет и быть не может, на входе уже разметка.
 *
 * `viewport` объявлен без `user-scalable=no`: масштабирование жестом запрещать
 * нельзя (NFR доступности).
 *
 * @param stylesheet встроенный стиль страницы. Умолчание — тема из ролей палитры
 *   *без* встроенных шрифтов: их загрузка suspend ([previewFontFaces]), а эта
 *   функция чистая. Вызывающий, которому нужны гарнитуры дизайна, собирает стиль
 *   сам: `previewStylesheet(colors, previewFontFaces())`.
 */
/**
 * Страница превью целиком: фрагмент конвертера + тема + встроенные гарнитуры.
 *
 * Шаг сборки страницы в пайплайне живого превью (`SL-4`): результат
 * `AdocRenderer.render` проходит сюда и готовой страницей уходит в `AdocPreview`.
 * Suspend только из-за первого чтения шрифтов ([previewFontFaces]); дальше они
 * приходят из кэша, и сборка стоит одну конкатенацию.
 */
suspend fun previewPage(
    fragment: String,
    colors: AdocColors = AdocTheme.defaultColors,
    diagrams: DiagramOptions = DiagramOptions.Disabled,
): String = previewDocument(
    resolveDiagrams(fragment, diagrams),
    previewStylesheet(colors, previewFontFaces()),
)

/**
 * Разрешение диаграмм — шаг между конвертером и страницей (`ADR-009`).
 *
 * Место выбрано не из удобства: `ADR-009` называет швом то, что пайплайн
 * живого превью вызывает по умолчанию, а это ровно [previewPage]. Здесь же
 * гарантируется главное свойство решения — внешний адрес до `WebView` не
 * доходит ни при каком исходе, и сеть остаётся у нас в руках, а не у страницы.
 *
 * Пока исход один: диаграмма недоступна, показывается её исходник с пометкой.
 * Загрузка, кэш и режимы придут в `SL-3`…`SL-6` и заменят собой тело этой
 * функции, не трогая ни конвертер, ни превью.
 */
private suspend fun resolveDiagrams(fragment: String, diagrams: DiagramOptions): String =
    withContext(Dispatchers.Default) {
        resolveDiagramBlocks(fragment, diagrams.serverUrl) { address ->
            DiagramOutcome.Unavailable(decodeKrokiSource(address.payload, DiagramSupport.inflate))
        }
    }

fun previewDocument(
    fragment: String,
    stylesheet: String = previewStylesheet(AdocTheme.defaultColors),
): String = buildString(fragment.length + stylesheet.length + 512) {
    append("<!DOCTYPE html>\n")
    append("<html lang=\"ru\">\n")
    append("<head>\n")
    append("<meta charset=\"utf-8\">\n")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
    append("<style>\n")
    append(stylesheet)
    append("\n</style>\n")
    append("</head>\n")
    append("<body>\n")
    append(fragment)
    append("\n</body>\n")
    append("</html>\n")
}
