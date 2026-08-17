package io.github.olegnyr.adocmobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.olegnyr.adocmobile.App

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
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
            App()
        }
    }
}
