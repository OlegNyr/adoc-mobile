package io.github.olegnyr.adocmobile.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Метрики одного именованного стиля: гарнитура ролью, начертание, кегль,
 * трекинг, межстрочный интервал.
 *
 * Гарнитура здесь названа ролью, а не объектом `FontFamily`, намеренно: ресурсы
 * шрифтов в Compose Multiplatform загружаются composable-функцией, поэтому
 * метрики отделены от привязки к файлам и проверяются обычным тестом.
 *
 * [lineHeight] по умолчанию не задан: описание дизайна называет межстрочный
 * интервал только двум стилям. Там, где его нет, `Unspecified` означает «считать
 * по метрикам гарнитуры» — это честнее выдуманного числа.
 */
data class AdocTextMetrics(
    val family: AdocFontRole,
    val weight: FontWeight,
    val size: TextUnit,
    val letterSpacing: TextUnit,
    val lineHeight: TextUnit = TextUnit.Unspecified,
)

/**
 * Метрики плюс готовая гарнитура — законченный стиль текста.
 *
 * Функция чистая, чтобы её можно было проверить без композиции; загрузку
 * ресурсов делает [adocTextStyle], который эту функцию и вызывает.
 */
fun AdocTextMetrics.toTextStyle(family: FontFamily): TextStyle = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size,
    letterSpacing = letterSpacing,
    lineHeight = lineHeight,
    // Лигатуры выключены везде, где показывается разметка. JetBrains Mono
    // склеивает `===`, `----`, `->` в один глиф — красиво в коде на языке
    // программирования и вредно здесь: пользователь перестаёт понимать, что
    // именно он набрал, а ограничители AsciiDoc считаются посимвольно
    // (решение владельца по итогам прогона на устройстве, 2026-08-18).
    fontFeatureSettings = "liga 0, clig 0, calt 0, dlig 0",
)

/**
 * Именованные стили текста. Значения заданы таблицей «Типографика» описания
 * дизайна; экран просит «заголовок экрана», а не «Barlow Condensed 19sp».
 */
object AdocTypography {

    // Barlow Condensed, прописные с разрядкой.

    /** Заголовок экрана в app bar. */
    val screenTitle = AdocTextMetrics(AdocFontRole.Heading, FontWeight.SemiBold, 19.sp, 0.04.em)

    /** Подпись кнопки, прописными. */
    val buttonLabel = AdocTextMetrics(AdocFontRole.Heading, FontWeight.SemiBold, 16.sp, 0.06.em)

    /** Заголовок документа в превью. */
    val previewTitle = AdocTextMetrics(AdocFontRole.Heading, FontWeight.SemiBold, 30.sp, 0.01.em)

    // Barlow: человеческий текст.

    /** Абзац. Единственный стиль, которому описание дизайна задаёт интерлиньяж: 1.5 от кегля. */
    val body = AdocTextMetrics(AdocFontRole.Body, FontWeight.Normal, 16.sp, 0.sp, lineHeight = 24.sp)

    /** Строка списка: имя файла, название настройки. */
    val listItem = AdocTextMetrics(AdocFontRole.Body, FontWeight.Normal, 16.sp, 0.sp)

    // JetBrains Mono: всё машинное.

    /**
     * Код в редакторе: 13 px / 21 px по описанию дизайна.
     * Интерлиньяж здесь не косметика — по нему считается шаг колонки номеров строк.
     */
    val editorCode = AdocTextMetrics(AdocFontRole.Mono, FontWeight.Normal, 13.sp, 0.sp, lineHeight = 21.sp)

    /** Служебная метка раздела, прописными с широким трекингом. */
    val sectionLabel = AdocTextMetrics(AdocFontRole.Mono, FontWeight.Normal, 11.sp, 0.1.em)

    /** Пути, время, счётчики диффа. */
    val metadata = AdocTextMetrics(AdocFontRole.Mono, FontWeight.Normal, 11.sp, 0.05.em)

    /**
     * Все стили по именам. Нужен проверке состава: стиль, заведённый мимо
     * описания дизайна, валит прогон. В коде экранов пользуйтесь свойствами
     * выше — доступ по строке не типизирован и опечатку не ловит.
     */
    val metrics: Map<String, AdocTextMetrics> = mapOf(
        "screenTitle" to screenTitle,
        "buttonLabel" to buttonLabel,
        "previewTitle" to previewTitle,
        "body" to body,
        "listItem" to listItem,
        "editorCode" to editorCode,
        "sectionLabel" to sectionLabel,
        "metadata" to metadata,
    )
}
