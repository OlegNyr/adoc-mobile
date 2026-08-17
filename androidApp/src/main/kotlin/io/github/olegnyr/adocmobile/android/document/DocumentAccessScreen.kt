package io.github.olegnyr.adocmobile.android.document

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.document.AndroidDocumentFileAccess
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentState
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import io.github.olegnyr.adocmobile.ui.AdocBlueprintBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран слайса `SL-2`: открыть файл системным диалогом и прочитать его.
 *
 * Временный. Экраны продукта относятся к потоку 5, а флоу (`1a` против `1b`)
 * ещё не выбран — здесь ровно то, что делает результат слайса наблюдаемым и
 * проверяет `TC-20`, `TC-21` и `TC-23` руками на устройстве.
 *
 * Счётчик худшего кадра на экране не украшение: `TC-23` сформулирован как
 * «открывается без замирания интерфейса», а замирание надо чем-то увидеть.
 * Если чтение уедет на главный поток, худший кадр вырастет с ~17 мс до
 * длительности чтения, и проверка перестанет зависеть от ощущений.
 */
@Composable
fun DocumentAccessScreen() {
    val context = LocalContext.current
    val access = remember { AndroidDocumentFileAccess(context) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf<AccessScreenState>(AccessScreenState.Idle) }

    // Счётчик кадров перезапускается на каждое открытие. Без этого он мерит не
    // то: пока системный диалог был на экране, кадры приложению не приходили
    // вовсе, и «худший кадр» показывал длительность выбора файла человеком.
    var frameEpoch by remember { mutableIntStateOf(0) }
    var worstFrameMs by remember { mutableLongStateOf(0L) }

    fun load(source: DocumentSource) {
        frameEpoch++
        state = AccessScreenState.Loading(source.displayName)
        scope.launch {
            val startedAt = System.nanoTime()
            val result = access.open(source)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            state = when (result) {
                is DocumentOpenResult.Opened -> AccessScreenState.Ready(result.document, elapsedMs)
                is DocumentOpenResult.Failed -> AccessScreenState.Failed(
                    result.error.userMessage(source.displayName),
                )
            }
            Log.i(LOG_TAG, "open ${source.displayName}: $elapsedMs ms -> ${state::class.simpleName}")
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            // Взятие разрешения и запрос имени файла ходят к провайдеру, то есть
            // это ввод-вывод — на главном потоке ему делать нечего даже сейчас,
            // пока он дешёвый.
            val source = withContext(Dispatchers.IO) { access.takePersistableAccess(uri) }
            load(source)
        }
    }

    // TC-20: файл, открытый в прошлый раз, поднимается сам — без диалога.
    // Если разрешение не пережило перезапуск, heldSource вернёт null, и экран
    // честно останется пустым, а не соврёт именем недоступного файла.
    LaunchedEffect(Unit) {
        access.heldSource()?.let(::load)
    }

    LaunchedEffect(frameEpoch) {
        var previous = 0L
        worstFrameMs = 0L
        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    val gap = (now - previous) / 1_000_000
                    if (gap > worstFrameMs) worstFrameMs = gap
                }
                previous = now
            }
        }
    }

    AdocTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AdocTheme.colors.ground,
            contentColor = AdocTheme.colors.textPrimary,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "ФАЙЛОВЫЙ ДОСТУП · SL-2",
                    style = adocTextStyle(AdocTypography.screenTitle),
                    color = AdocTheme.colors.textPrimary,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("ОТКРЫТЬ ФАЙЛ", Modifier.weight(1f)) { picker.launch(arrayOf("*/*")) }
                    ActionButton("ЗАКРЫТЬ", Modifier.weight(1f)) {
                        access.release()
                        state = AccessScreenState.Idle
                    }
                }

                // TC-21: отозвать доступ руками нельзя, не стерев заодно ссылку
                // на файл, — а это уже другой сценарий. Кнопка оставляет ровно
                // то состояние, которое FR-3 называет штатным: ссылка есть,
                // права нет.
                ActionButton("ПОТЕРЯТЬ ДОСТУП · ПРОВЕРКА TC-21", Modifier.fillMaxWidth()) {
                    access.loseAccessForCheck()
                    access.heldSource()?.let(::load)
                }

                AdocBlueprintBlock(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "ХУДШИЙ КАДР · $worstFrameMs МС",
                        style = adocTextStyle(AdocTypography.sectionLabel),
                        color = AdocTheme.colors.textFaint,
                    )
                    StatusBlock(state)
                }

                val ready = state as? AccessScreenState.Ready
                if (ready != null) {
                    Text(
                        text = ready.document.text.lineSequence().take(PREVIEW_LINES).joinToString("\n"),
                        style = adocTextStyle(AdocTypography.editorCode),
                        color = AdocTheme.colors.textSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(state: AccessScreenState) {
    when (state) {
        AccessScreenState.Idle -> Line("ФАЙЛ НЕ ОТКРЫТ", AdocTheme.colors.textMuted)

        is AccessScreenState.Loading -> Line("ЧТЕНИЕ · ${state.displayName}", AdocTheme.colors.accentText)

        is AccessScreenState.Ready -> {
            val lines = state.document.text.count { it == '\n' } + 1
            Line(state.document.source.displayName.uppercase(), AdocTheme.colors.accentText)
            Line(
                "$lines СТРОК · ${state.document.lineEnding} · ЧТЕНИЕ ${state.elapsedMs} МС",
                AdocTheme.colors.textSecondary,
            )
        }

        is AccessScreenState.Failed -> Line(state.message, AdocTheme.colors.accentSecondary)
    }
}

@Composable
private fun Line(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = adocTextStyle(AdocTypography.metadata), color = color)
}

@Composable
private fun ActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = label,
        style = adocTextStyle(AdocTypography.buttonLabel),
        color = AdocTheme.colors.onAccent,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(AdocTheme.colors.accent)
            .border(1.dp, AdocTheme.colors.borderChrome)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
    )
}

/** Что показывает экран. Отдельный тип, чтобы «читаем» и «не открыт» не смешивались в пару флагов. */
private sealed interface AccessScreenState {
    data object Idle : AccessScreenState
    data class Loading(val displayName: String) : AccessScreenState
    data class Ready(val document: DocumentState, val elapsedMs: Long) : AccessScreenState
    data class Failed(val message: String) : AccessScreenState
}

private const val LOG_TAG = "AdocFileAccess"
private const val PREVIEW_LINES = 60
