package io.github.olegnyr.adocmobile.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.olegnyr.adocmobile.highlight.AdocStyle
import io.github.olegnyr.adocmobile.theme.AdocColors

/**
 * Отображение ролей сканера [AdocStyle] в стили текста Compose — слайс `SL-1a`
 * фичи 001-syntax-highlighting.
 *
 * Живёт в `ui/`, а не в `highlight/`, и это граница `FR-25`: сканер отдаёт
 * платформенно-нейтральные роли и о Compose не знает; всё, что знает о
 * `SpanStyle`, начинается здесь. Стражи сборки `verifyHighlightIsCommonOnly` и
 * `verifyHighlightIsPlatformNeutral` держат границу с той стороны.
 *
 * Цвета берутся только ролями палитры [AdocColors] — литералы запрещены задачей
 * `verifyNoColorLiterals`. Четыре отображения заданы описанием дизайна
 * (design.adoc, «Подсветка в макете»): заголовки — `accentText` полужирным,
 * атрибуты — `accentSecondary`, разделители блоков и `[NOTE]` — `comment`.
 * Роли `comment` и `accentSecondary` заведены в палитре именно под подсветку.
 *
 * Остальным ролям макет цвета не называет — для них выбраны ближайшие роли
 * палитры, и выбор задокументирован у каждой ветки. НФТ доступности требует,
 * чтобы цвет не был единственным носителем смысла, поэтому наравне с ним
 * работают насыщенность, наклон и фон.
 *
 * Таблица считается один раз на схему: стиль запрашивается на каждый диапазон
 * при каждой отрисовке, и собирать `SpanStyle` заново там незачем.
 */
class AdocHighlightStyles(colors: AdocColors) {

    private val byRole: Map<AdocStyle, SpanStyle> =
        AdocStyle.entries.associateWith { role ->
            when (role) {
                // Макет: заголовки — полужирные цветом роли accentText.
                AdocStyle.Heading0, AdocStyle.Heading1, AdocStyle.Heading2,
                AdocStyle.Heading3, AdocStyle.Heading4, AdocStyle.Heading5,
                -> SpanStyle(color = colors.accentText, fontWeight = FontWeight.Bold)

                // Макет: атрибуты — цвет роли accentSecondary.
                AdocStyle.AttributeEntry -> SpanStyle(color = colors.accentSecondary)

                // Макет: разделители блоков и [NOTE] — цвет роли comment.
                // Якорь — та же скобочная запись метаданных, что список атрибутов.
                AdocStyle.BlockDelimiter -> SpanStyle(color = colors.comment)
                AdocStyle.BlockAttributes -> SpanStyle(color = colors.comment)
                AdocStyle.Anchor -> SpanStyle(color = colors.comment)

                // Абзацная форма той же метки, что `[NOTE]`; насыщенность
                // отличает её от комментария, с которым она делит цвет.
                AdocStyle.Admonition ->
                    SpanStyle(color = colors.comment, fontWeight = FontWeight.Bold)

                // Комментарий отличает наклон — цвет у роли общий с разделителями.
                AdocStyle.Comment ->
                    SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)

                // Дословное содержимое несёт фон (роль raised — «блоки кода»),
                // а не цвет: текст внутри listing остаётся текстом.
                AdocStyle.VerbatimContent -> SpanStyle(background = colors.raised)

                // Callout — навигационная метка, как выделенный номер строки.
                AdocStyle.Callout ->
                    SpanStyle(color = colors.accentText, fontWeight = FontWeight.Bold)

                // Директива — машинная запись той же природы, что атрибут;
                // наклон отделяет её от записи атрибута.
                AdocStyle.PreprocessorDirective ->
                    SpanStyle(color = colors.accentSecondary, fontStyle = FontStyle.Italic)

                // Маркер — структурный знак; акцентная роль, как активные
                // элементы навигации.
                AdocStyle.ListMarker ->
                    SpanStyle(color = colors.accentText, fontWeight = FontWeight.Bold)

                // Термин и заголовок блока — усиленный текст, не акцент:
                // это содержимое документа, а не его разметка.
                AdocStyle.ListTerm ->
                    SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                AdocStyle.BlockTitle ->
                    SpanStyle(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)

                // Разрывы — структурные линии, как разделители блоков.
                AdocStyle.ThematicBreak, AdocStyle.PageBreak ->
                    SpanStyle(color = colors.comment, fontWeight = FontWeight.Bold)
            }
        }

    /** Стиль роли. Роль без стиля невозможна: таблица строится по всем значениям перечисления. */
    operator fun get(role: AdocStyle): SpanStyle = byRole.getValue(role)
}
