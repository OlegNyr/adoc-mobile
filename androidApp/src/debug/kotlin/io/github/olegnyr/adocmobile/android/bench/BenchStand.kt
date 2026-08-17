package io.github.olegnyr.adocmobile.android.bench

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle

/**
 * Стенд для замеров T-010: где предел BasicTextField на большом документе.
 *
 * Это измерительный инструмент, а не часть продукта: живёт в debug-исходниках и
 * в релизную сборку не попадает. Настоящий сканер подсветки появится в потоке 2;
 * здесь стоит заглушка, которая нагружает трансформацию так же, как будет он —
 * проход по всему буферу на каждую отрисовку.
 */
const val BENCH_EDITOR_TAG = "bench-editor"

/**
 * Заглушка подсветки. Красит первый токен каждой строки — то есть выполняет
 * работу, пропорциональную размеру документа, как реальный сканер в худшем случае.
 *
 * @param stylesPerDocument сколько диапазонов ставить; 0 отключает стилизацию,
 * что даёт базовую линию «поле без подсветки».
 */
class StubHighlight(private val stylesPerDocument: Int) : OutputTransformation {

    override fun TextFieldBuffer.transformOutput() {
        if (stylesPerDocument <= 0) return
        val text = toString()
        var placed = 0
        var lineStart = 0
        while (lineStart < text.length && placed < stylesPerDocument) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it == -1) text.length else it }
            val tokenEnd = minOf(lineStart + TOKEN_LENGTH, lineEnd)
            if (tokenEnd > lineStart) {
                addStyle(HIGHLIGHT, lineStart, tokenEnd)
                placed++
            }
            lineStart = lineEnd + 1
        }
    }

    private companion object {
        const val TOKEN_LENGTH = 8
        val HIGHLIGHT = SpanStyle(fontWeight = FontWeight.Bold)
    }
}

/** Экран стенда: поле с документом заданного размера и заглушкой подсветки. */
@Composable
fun BenchStand(lines: Int, styles: Int) {
    AdocTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AdocTheme.colors.ground,
            contentColor = AdocTheme.colors.textSecondary,
        ) {
            val state: TextFieldState = rememberTextFieldState(remember(lines) { generateDocument(lines) })
            val transformation = remember(styles) { StubHighlight(styles) }

            BasicTextField(
                state = state,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
                    .testTag(BENCH_EDITOR_TAG),
                textStyle = adocTextStyle(AdocTypography.editorCode)
                    .copy(color = AdocTheme.colors.textSecondary),
                outputTransformation = transformation,
                lineLimits = TextFieldLineLimits.MultiLine(),
                scrollState = rememberScrollState(),
            )
        }
    }
}

/**
 * Документ на заданное число строк, похожий на реальный AsciiDoc: заголовки,
 * атрибуты, абзацы, блоки кода. Однородная простыня дала бы заниженный результат.
 */
internal fun generateDocument(lines: Int): String = buildString {
    append("= Стенд замеров\n:status: черновик\n\n")
    var i = 3
    var section = 1
    while (i < lines) {
        append("== Раздел ").append(section).append('\n').append('\n')
        i += 2
        repeat(6) {
            if (i >= lines) return@repeat
            append("Абзац с *жирным* и `моноширинным` текстом, ссылка https://asciidoctor.org и атрибут {status}.\n")
            i++
        }
        if (i < lines) {
            append("\n[source,kotlin]\n----\nfun sample").append(section).append("() = Unit\n----\n\n")
            i += 6
        }
        section++
    }
}
