package io.github.olegnyr.adocmobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import io.github.olegnyr.adocmobile.android.document.DocumentAccessScreen
import io.github.olegnyr.adocmobile.render.installAdocRenderer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Единственное, что рендереру нужно от приложения: доступ к ассетам, где
        // лежит бандл Asciidoctor.js. Контекст берётся приложения, а не активити:
        // движок живёт столько же, сколько процесс, и переживает поворот.
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
            // Временная поверхность из трёх полос: сверху редактор с подсветкой
            // (SL-1a фичи 001), в середине файловый доступ (SL-2 фичи 004, на
            // нём проверяются TC-20, TC-21, TC-23), снизу сквозной путь рендера
            // (SL-1 фичи 003).
            //
            // Каркас темы App() отсюда убран: он своё отработал, тема и шрифты
            // видны на всех полосах. Всё это заменит экран редактора из
            // потока 5 — до него экран собран так, чтобы проверять слайсы, а не
            // выглядеть продуктом.
            Column(modifier = Modifier.fillMaxSize()) {
                EditorSkeleton(modifier = Modifier.fillMaxWidth().weight(1f))
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    DocumentAccessScreen()
                }
                RenderSkeleton(modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
