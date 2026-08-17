package io.github.olegnyr.adocmobile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.olegnyr.adocmobile.theme.AdocTheme

/**
 * Корневой composable приложения. Пока это заглушка каркаса: экраны появятся
 * в потоке 5, типографика — в SL-2, чертёжный блок — в SL-3.
 */
@Composable
fun App() {
    AdocTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AdocTheme.colors.ground,
            // Задаётся явно: иначе цвет содержимого выводится из схемы, и при
            // несовпадении фона ни с одной её ролью откатывается на чёрный.
            contentColor = AdocTheme.colors.textPrimary,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "adoc-mobile · ${platformName()}",
                    color = AdocTheme.colors.textPrimary,
                )
            }
        }
    }
}
