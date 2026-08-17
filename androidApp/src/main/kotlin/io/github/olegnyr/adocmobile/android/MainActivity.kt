package io.github.olegnyr.adocmobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.olegnyr.adocmobile.document.AndroidDocumentFileAccess
import io.github.olegnyr.adocmobile.document.DocumentSource
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
        // Превью в слайсе SL-1 — заглушка, но движок ставится здесь же: SL-2
        // подключит пайплайн, не трогая хостинг.
        installAdocRenderer(applicationContext)

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
            // Прежняя поверхность из трёх проверочных полос ушла вместе с
            // EditorSkeleton; RenderSkeleton и DocumentAccessScreen остаются в
            // исходниках до слайсов SL-2/SL-4, которые заберут их проверочные
            // возможности на экран (план 005).
            EditorScreenHost()
        }
    }
}

/**
 * Хостинг экрана редактора: всё, что нельзя унести в `commonMain`, — системный
 * диалог выбора файла и платформенная реализация файлового шва.
 *
 * Диалог отдан экрану suspend-функцией: экран просит документ, платформа
 * показывает `OpenDocument`, берёт постоянное право и возвращает источник.
 * Когда решение владельца об открытии папки (`OQ-1` фичи 005) приедет слайсом
 * tree-доступа фичи 004, поменяется ровно эта функция.
 */
@Composable
private fun EditorScreenHost() {
    val context = LocalContext.current
    val access = remember { AndroidDocumentFileAccess(context) }
    val scope = rememberCoroutineScope()

    // Мост «callback диалога → suspend»: экран ждёт CompletableDeferred,
    // callback его завершает. Отмена диалога пользователем — честный null.
    var pending by remember { mutableStateOf<CompletableDeferred<DocumentSource?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri == null) {
            request.complete(null)
        } else {
            scope.launch {
                // Взятие разрешения и запрос имени файла ходят к провайдеру,
                // то есть это ввод-вывод — на главном потоке ему делать нечего.
                request.complete(withContext(Dispatchers.IO) { access.takePersistableAccess(uri) })
            }
        }
    }

    EditorScreen(
        access = access,
        requestDocument = {
            val request = CompletableDeferred<DocumentSource?>()
            pending?.complete(null)
            pending = request
            picker.launch(arrayOf("*/*"))
            request.await()
        },
    )
}
