package io.github.olegnyr.adocmobile.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Роли палитры проекта. Набор ролей отделён от конкретных значений: тёмная схема —
 * одна из его реализаций, светлая появится второй, не меняя состава.
 *
 * Значения задаёт doc/design/design.adoc, раздел «Палитра». Цвет в коде экранов
 * берётся только отсюда — литералы в обход роли запрещены границами работ.
 */
@Immutable
data class AdocColors(
    /** Основной фон всех экранов. */
    val ground: Color,
    /** Верхняя и нижняя хром-панели. */
    val chrome: Color,
    /** Поля ввода, карточки, блоки кода. */
    val raised: Color,
    /** Блок «Результат» на экране конфликта. */
    val sunken: Color,
    /** Тулбар над клавиатурой. */
    val toolbar: Color,
    /** Разделители хром-панелей, неактивные сегменты. */
    val borderChrome: Color,
    /** Рамки карточек, полей, вторичных кнопок. */
    val borderObject: Color,
    /** Строки файлов, строки настроек. */
    val borderList: Color,
    /** Заливка первичной кнопки, метки «+», прогресс, активная граница. */
    val accent: Color,
    /** Наведение на первичную кнопку, иконки в чертёжных блоках. */
    val accentHover: Color,
    /** Активная вкладка, имя ветки, ссылки, выделенный номер строки. */
    val accentText: Color,
    /** Атрибуты документа в подсветке, статус `A`. */
    val accentSecondary: Color,
    /** Выбранный сегмент, выбранный вариант конфликта. */
    val accentSelection: Color,
    /** Включённый переключатель, рамка чипа. */
    val accentTrack: Color,
    /** Подпись первичной кнопки, галочка чекбокса. */
    val onAccent: Color,
    /** Заголовки, имена файлов. */
    val textPrimary: Color,
    /** Код в редакторе, подписи вторичных кнопок. */
    val textSecondary: Color,
    /** Тело admonition, абзацы панели превью. */
    val textParagraph: Color,
    /** Иконки хром-панели, вспомогательные подписи. */
    val textMuted: Color,
    /** Метки разделов, время, счётчики диффа. */
    val textFaint: Color,
    /** Комментарии и разделители блоков в подсветке. */
    val comment: Color,
    /** Колонка номеров строк. */
    val lineNumber: Color,
)

/** Тёмная схема — основная и единственная реализованная. Светлая заведена отдельной задачей. */
val darkColors: AdocColors = AdocColors(
    ground = Color(0xFF131518),
    chrome = Color(0xFF17191C),
    raised = Color(0xFF16191D),
    sunken = Color(0xFF101317),
    toolbar = Color(0xFF1A1D21),
    borderChrome = Color(0xFF262C33),
    borderObject = Color(0xFF2C3A47),
    borderList = Color(0xFF1F242A),
    accent = Color(0xFF5980A6),
    accentHover = Color(0xFF749DC4),
    accentText = Color(0xFF94BCE3),
    accentSecondary = Color(0xFF7E9CB8),
    accentSelection = Color(0xFF1A2530),
    accentTrack = Color(0xFF2C455D),
    onAccent = Color(0xFF0D1318),
    textPrimary = Color(0xFFE6E8EA),
    textSecondary = Color(0xFFC6CBD0),
    textParagraph = Color(0xFFB9BFC5),
    textMuted = Color(0xFF8B9299),
    textFaint = Color(0xFF6F767D),
    comment = Color(0xFF5D6A75),
    lineNumber = Color(0xFF414A53),
)
