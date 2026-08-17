package io.github.olegnyr.adocmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle

/**
 * Корневой composable приложения. Пока это заглушка каркаса: экраны появятся
 * в потоке 5, чертёжный блок — в SL-3.
 *
 * Показанные строки подобраны так, чтобы на снимке экрана были видны все три
 * гарнитуры — это наблюдаемый результат слайса SL-2.
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
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "ADOC-MOBILE",
                    style = adocTextStyle(AdocTypography.previewTitle),
                    color = AdocTheme.colors.textPrimary,
                )
                Text(
                    text = "Barlow Condensed · заголовки",
                    style = adocTextStyle(AdocTypography.screenTitle),
                    color = AdocTheme.colors.accentText,
                )
                Text(
                    text = "Barlow — человеческий текст",
                    style = adocTextStyle(AdocTypography.body),
                    color = AdocTheme.colors.textSecondary,
                )
                Text(
                    text = "= JetBrains Mono :attr: ${platformName()}",
                    style = adocTextStyle(AdocTypography.editorCode),
                    color = AdocTheme.colors.accentSecondary,
                )
                Text(
                    text = "СЛУЖЕБНАЯ МЕТКА · 11 PX",
                    style = adocTextStyle(AdocTypography.sectionLabel),
                    color = AdocTheme.colors.textFaint,
                )
            }
        }
    }
}
