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
import io.github.olegnyr.adocmobile.App
import io.github.olegnyr.adocmobile.render.installAdocRenderer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Единственное, что рендереру нужно от приложения: доступ к ассетам, где
        // лежит бандл Asciidoctor.js. Контекст берётся приложения, а не активити:
        // движок живёт столько же, сколько процесс (FR-3), и переживает поворот.
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
            // Каркас 002 сверху, сквозной путь рендера снизу. Обе половины
            // временные: поток 5 заменит их экраном редактора с вкладками.
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    App()
                }
                RenderSkeleton(modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
