package io.github.olegnyr.adocmobile.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.olegnyr.adocmobile.document.AndroidDocumentTreeAccess
import io.github.olegnyr.adocmobile.document.TreeSource
import io.github.olegnyr.adocmobile.render.installAdocRenderer
import io.github.olegnyr.adocmobile.screen.EditorScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Единственное, что рендереру нужно от приложения: доступ к ассетам, где
        // лежит бандл Asciidoctor.js. Контекст берётся приложения, а не активити:
        // движок живёт столько же, сколько процесс, и переживает поворот.
        installAdocRenderer(applicationContext)

        // Диагностический вход для ручных кейсов 004 (TC-21, TC-27): отзыв права
        // и перечень удержанных разрешений недостижимы с экрана до меню SL-4, а
        // без них кейсы непроверяемы (требование интеграции спеки 005). Только
        // debug-сборка: в релизе интент игнорируется.
        runDebugDiagnostics()

        // Стиль системных панелей задан явно тёмным. Без аргументов
        // enableEdgeToEdge определяет светлость иконок по системному ночному
        // режиму, а приложение тёмное независимо от него: в светлом системном
        // режиме часы и батарея стали бы тёмными поверх тёмного фона.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            // Экран редактора (T-051, фича 005) — единственный экран приложения.
            // RenderSkeleton и DocumentAccessScreen остаются в исходниках до
            // слайса SL-4, который заберёт их последние проверочные возможности
            // на экран (план 005); из хоста они уже не вызываются.
            EditorScreenHost()
        }
    }

    /**
     * Ручные проверки с adb — только в debug-сборке:
     *
     * * `--ez adoc.debug.loseAccess true` — отдать право на дерево, оставив
     *   ссылки ([AndroidDocumentTreeAccess.loseAccessForCheck]): воспроизведение
     *   потери доступа для `FR-3` фичи 004 — отзыв руками стёр бы и ссылку.
     * * `--ez adoc.debug.logGrants true` — перечень `persistedUriPermissions`
     *   в logcat: правило «ровно одно право» (TC-27) видно без root.
     */
    private fun runDebugDiagnostics() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return

        if (intent?.getBooleanExtra("adoc.debug.loseAccess", false) == true) {
            AndroidDocumentTreeAccess(applicationContext).loseAccessForCheck()
            Log.i(DEBUG_TAG, "Право на дерево отдано, ссылки оставлены (loseAccessForCheck)")
        }
        if (intent?.getBooleanExtra("adoc.debug.logGrants", false) == true) {
            val grants = contentResolver.persistedUriPermissions
            Log.i(DEBUG_TAG, "persistedUriPermissions: ${grants.size}")
            grants.forEach { Log.i(DEBUG_TAG, "  ${it.uri} r=${it.isReadPermission} w=${it.isWritePermission}") }
        }
    }

    private companion object {
        const val DEBUG_TAG = "AdocDiagnostics"
    }
}

/**
 * Хостинг экрана редактора: всё, что нельзя унести в `commonMain`, — системный
 * диалог выбора папки и платформенная реализация tree-доступа.
 *
 * Диалог отдан экрану suspend-функцией: экран просит папку, платформа
 * показывает `ACTION_OPEN_DOCUMENT_TREE`, берёт постоянное право на дерево
 * (правило «ровно одно право» — внутри шва) и возвращает [TreeSource].
 * Файл дальше выбирается без платформы — из списка документов папки
 * (решение `OQ-1` фичи 005); прежний путь одиночного файла из хоста ушёл,
 * как и помечено в `FR-1` фичи 004.
 */
@Composable
private fun EditorScreenHost() {
    val context = LocalContext.current
    val access = remember { AndroidDocumentTreeAccess(context) }
    val scope = rememberCoroutineScope()

    // Мост «callback диалога → suspend»: экран ждёт CompletableDeferred,
    // callback его завершает. Отмена диалога пользователем — честный null.
    var pending by remember { mutableStateOf<CompletableDeferred<TreeSource?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) {
            request.complete(null)
        } else {
            scope.launch {
                // Взятие разрешения и запрос имени папки ходят к провайдеру,
                // то есть это ввод-вывод — на главном потоке ему делать нечего.
                request.complete(withContext(Dispatchers.IO) { access.takePersistableTreeAccess(uri) })
            }
        }
    }

    // Передний план — сигнал для превью (OQ-5 фичи 003) и немедленной записи
    // (FR-11 фичи 005). ON_STOP, а не ON_PAUSE: разделённый экран и системные
    // диалоги не должны гасить превью, пока активность видна; запись при этом
    // уходит, когда активность действительно скрыта.
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    EditorScreen(
        access = access,
        requestFolder = {
            val request = CompletableDeferred<TreeSource?>()
            pending?.complete(null)
            pending = request
            picker.launch(null)
            request.await()
        },
        foreground = foreground,
        // Картинки рядом с документом в превью (TC-34 фичи 003): байты достаёт
        // та же платформенная половина tree-доступа, что читает документы.
        imageSource = remember(access) { access.imageSource() },
    )
}
