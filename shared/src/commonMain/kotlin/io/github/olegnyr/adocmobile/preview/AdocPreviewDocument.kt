package io.github.olegnyr.adocmobile.preview

import io.github.olegnyr.adocmobile.theme.AdocColors
import io.github.olegnyr.adocmobile.theme.AdocTheme

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
): String = previewDocument(fragment, previewStylesheet(colors, previewFontFaces()))

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
