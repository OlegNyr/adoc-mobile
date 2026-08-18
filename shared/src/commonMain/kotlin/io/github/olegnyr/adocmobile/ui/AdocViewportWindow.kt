package io.github.olegnyr.adocmobile.ui

/**
 * Видимое окно подсветки — слайс `SL-5` фичи 001-syntax-highlighting,
 * требование `FR-26`: стили ставятся только для видимого окна.
 *
 * Обоснование — замеры `T-010`: стоимость кадра определяется числом диапазонов
 * в буфере и не зависит от их видимости, поэтому «поставим всё, отрисуется
 * видимое» не работает. Окно считается в *строках раскладки* и шире экрана на
 * [WINDOW_MARGIN_LINES] в каждую сторону; при прокрутке оно не пересчитывается
 * каждый пиксель — пока видимая область не подошла к краю окна ближе, чем на
 * [WINDOW_SLACK_LINES], прежнее окно остаётся в силе. Так прокрутка внутри
 * запаса не порождает ни новой трансформации, ни перерисовки стилей.
 *
 * Здесь только чистая геометрия — она проверяется в `commonTest` без
 * композиции. Перевод строк раскладки в смещения символов делает редактор по
 * `TextLayoutResult`; сканер об окне не знает вовсе (`FR-25`).
 */
internal data class LineWindow(val firstLine: Int, val lastLine: Int) {
    init {
        require(firstLine in 0..lastLine) { "окно строк вырождено: $firstLine..$lastLine" }
    }
}

/** Окно вокруг видимых строк с запасом, обрезанное по краям документа. */
internal fun windowAround(
    firstVisible: Int,
    lastVisible: Int,
    lastLineIndex: Int,
    margin: Int = WINDOW_MARGIN_LINES,
): LineWindow = LineWindow(
    firstLine = (firstVisible - margin).coerceAtLeast(0),
    lastLine = (lastVisible + margin).coerceAtMost(lastLineIndex).coerceAtLeast((firstVisible - margin).coerceAtLeast(0)),
)

/**
 * Годно ли прежнее окно для текущей видимой области.
 *
 * Окно живо, пока видимая область не подошла к его краю ближе, чем на [slack]
 * строк; край, совпадающий с краем документа, не изнашивается — у документа
 * дальше строк нет.
 */
internal fun LineWindow.covers(
    firstVisible: Int,
    lastVisible: Int,
    lastLineIndex: Int,
    slack: Int = WINDOW_SLACK_LINES,
): Boolean {
    // Документ мог усохнуть: backspace в начале строки склеивает её с
    // предыдущей, и строк становится меньше. Окно за концом документа не годно
    // ни при какой видимой области — раскладка такой строки уже не знает, и
    // запрос смещения по ней роняет приложение (дефект найден на устройстве).
    if (lastLine > lastLineIndex) return false
    if (firstVisible < firstLine || lastVisible > lastLine) return false
    val topWornOut = firstLine > 0 && firstVisible - firstLine < slack
    val bottomWornOut = lastLine < lastLineIndex && lastLine - lastVisible < slack
    return !topWornOut && !bottomWornOut
}

/** Прежнее окно, пока оно годно, иначе новое вокруг видимой области. */
internal fun refreshedWindow(
    current: LineWindow?,
    firstVisible: Int,
    lastVisible: Int,
    lastLineIndex: Int,
): LineWindow =
    if (current != null && current.covers(firstVisible, lastVisible, lastLineIndex)) current
    else windowAround(firstVisible, lastVisible, lastLineIndex)

/** Запас окна за краями экрана, в строках раскладки. */
internal const val WINDOW_MARGIN_LINES = 60

/** Износ: окно обновляется, когда видимая область ближе этого к его краю. */
internal const val WINDOW_SLACK_LINES = 20
