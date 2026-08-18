package io.github.olegnyr.adocmobile.insert

import io.github.olegnyr.adocmobile.highlight.AdocBlockKind
import io.github.olegnyr.adocmobile.highlight.AdocBlockScanner

/**
 * Контекст каретки для блочной вставки (`FR-15`): тип блока, внутри которого
 * стоит каретка, или `null` вне блоков.
 *
 * Разбор здесь не свой: состояние блоков по строкам уже держит блочный автомат
 * подсветки, и функция только читает его выход
 * ([io.github.olegnyr.adocmobile.highlight.AdocScan.blockStates]). Второй разбор
 * AsciiDoc — прямая не-цель видения, поэтому даже «дешёвый подсчёт
 * ограничителей» здесь заводить нельзя: он разошёлся бы с подсветкой на
 * вложенности, маскараде блока (`[listing]` над `....`) и дословных блоках,
 * внутрь которых ограничители не пускаются.
 *
 * Пакет `highlight/` при этом не тронут — используется его публичный API.
 *
 * Считается по касанию кнопки, а не по вводу: один проход сканера на касание
 * укладывается в тот же порог, что и обычная подсветка (`NFR-1`).
 */
internal fun blockKindAt(text: CharSequence, offset: Int): AdocBlockKind? {
    val states = AdocBlockScanner.scan(text.toString()).blockStates
    // Состояние *после* предыдущей строки — это состояние, в котором живёт
    // строка каретки: строка открывающего ограничителя ещё вне блока, строка
    // закрывающего — уже внутри, как их и видит подсветка.
    val lineOfCaret = lineIndexOf(text, offset)
    if (lineOfCaret == 0) return null
    return states.getOrNull(lineOfCaret - 1)?.lastOrNull()?.kind
}

/** Номер логической строки, на которой стоит [offset], считая с нуля. */
private fun lineIndexOf(text: CharSequence, offset: Int): Int {
    var lines = 0
    for (index in 0 until offset) {
        if (text[index] == '\n') lines++
    }
    return lines
}
