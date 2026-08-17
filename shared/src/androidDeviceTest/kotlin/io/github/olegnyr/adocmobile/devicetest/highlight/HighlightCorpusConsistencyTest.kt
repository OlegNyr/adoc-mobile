package io.github.olegnyr.adocmobile.devicetest.highlight

import io.github.olegnyr.adocmobile.highlight.AdocBlockScanner
import io.github.olegnyr.adocmobile.highlight.AdocStyle

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `TC-38` (слайс `SL-6` фичи 001-syntax-highlighting): сверка подсветки с
 * эталоном на корпусе из 18 документов.
 *
 * Сверка *структурная*, не побайтовая — сканер не парсер и не обязан повторить
 * дерево документа. Проверяются два направления непротиворечивости с превью:
 *
 * сканер→эталон:: то, что сканер разметил инлайн-ролью (полужирный, курсив,
 *   моноширинный, выделение, над- и подстрочный, ссылки, заголовки), в HTML
 *   эталона несёт соответствующий тег с тем же текстом. Ловит *ложную*
 *   подсветку — то, чего превью не покажет.
 * эталон→сканер:: текст, одетый эталоном в форматирующий тег, размечен спаном
 *   соответствующей роли. Ловит *недо*подсветку относительно превью (`FR-28`).
 *
 * Текст сличается пробами — прогонами из букв, цифр, пробелов, точек и дефисов:
 * разметка, HTML-сущности и подстановки эталона в пробу не попадают. Спан без
 * пробы достаточной длины и спан со ссылкой на атрибут внутри (`{…}`
 * подставляется значением — текст заведомо разойдётся) не сверяются.
 *
 * Расхождение вне реестра [knownDeviations] — падение; запись реестра, не
 * накрывшая ни одного расхождения, — тоже падение («мёртвая запись», как в
 * `CorpusComparisonTest`). Полный список расхождений и сводка пишутся в каталог
 * внешних файлов приложения — путь печатается в сообщении, забирается `adb pull`.
 */
class HighlightCorpusConsistencyTest {

    /**
     * Реестр намеренных расхождений — все источники записаны в аналитике и
     * журнале фичи: `FR-23` (типографские кавычки — не-цель, моноширинный внутри
     * кавычек), решение `SL-1` (таблица непрозрачна целиком), недолёт `SL-3`
     * (инлайн-разбор не заходит в заголовки, заголовки блоков и значения
     * атрибутов), `FR-14` (условные директивы не вычисляются: содержимое
     * ложной ветки размечается, но в HTML отсутствует), препроцессор include
     * (сканер видит только открытый буфер, содержимое включённых файлов — нет).
     */
    private data class Known(
        val stem: String,
        val direction: Direction,
        val tag: String,
        val probeContains: String,
        val reason: String,
    )

    private enum class Direction(val label: String) {
        ScannerToReference("сканер→эталон"),
        ReferenceToScanner("эталон→сканер"),
    }

    private val knownDeviations: List<Known> = listOf(
        Known(
            stem = "01-document-attributes",
            direction = Direction.ReferenceToScanner,
            tag = "code",
            probeContains = "AsciiDoc Mobile",
            reason = "FR-22: `{project}` в моноширинном тексте — эталон подставляет значение, текст расходится",
        ),
        Known(
            stem = "02-inline-formatting",
            direction = Direction.ScannerToReference,
            tag = "code",
            probeContains = "цитата",
            reason = "FR-23/TC-27: типографские кавычки — не-цель; «\"`цитата`\"» размечается моноширинным, эталон отдаёт кавычки",
        ),
        Known(
            stem = "02-inline-formatting",
            direction = Direction.ReferenceToScanner,
            tag = "em",
            probeContains = "не экранировано",
            reason = "ДЕФЕКТ сканера, найден сверкой: экранированная unconstrained-пара `\\\\__…__` не съедает свой закрывающий знак, " +
                "и он сцепляется с открывающим следующей пары накрест; эталон съедает конструкцию целиком. " +
                "Правка — в highlight/ (закрыт для слайса), решение владельцу",
        ),
        Known(
            stem = "02-inline-formatting",
            direction = Direction.ReferenceToScanner,
            tag = "sup",
            probeContains = "b c",
            reason = "FR-22: `{sp}` внутри надстрочного эталон подставляет пробелом — текст расходится",
        ),
        Known(
            stem = "04-lists-description",
            direction = Direction.ReferenceToScanner,
            tag = "em",
            probeContains = "",
            reason = "[qanda]: эталон одевает вопросы списка в курсив стилем контейнера — это оформление стиля, в исходнике инлайн-разметки нет",
        ),
        Known(
            stem = "06-code-blocks",
            direction = Direction.ReferenceToScanner,
            tag = "code",
            probeContains = "subs",
            reason = "subs=… на listing — не-цель: дословный блок у сканера непрозрачен",
        ),
        Known(
            stem = "07-tables-basic",
            direction = Direction.ReferenceToScanner,
            tag = "strong",
            probeContains = "",
            reason = "решение SL-1: таблица непрозрачна целиком, инлайн в ячейках не разбирается — открытый вопрос владельцу",
        ),
        Known(
            stem = "07-tables-basic",
            direction = Direction.ReferenceToScanner,
            tag = "code",
            probeContains = "",
            reason = "решение SL-1: таблица непрозрачна целиком — открытый вопрос владельцу",
        ),
        Known(
            stem = "08-tables-cell-specs",
            direction = Direction.ReferenceToScanner,
            tag = "strong",
            probeContains = "",
            reason = "решение SL-1: таблица непрозрачна, спецификаторы ячеек (s|, m|, a|) не разбираются",
        ),
        Known(
            stem = "08-tables-cell-specs",
            direction = Direction.ReferenceToScanner,
            tag = "em",
            probeContains = "",
            reason = "решение SL-1: таблица непрозрачна, спецификаторы ячеек не разбираются",
        ),
        Known(
            stem = "08-tables-cell-specs",
            direction = Direction.ReferenceToScanner,
            tag = "code",
            probeContains = "",
            reason = "решение SL-1: таблица непрозрачна, спецификаторы ячеек не разбираются",
        ),
        Known(
            stem = "10-macros-inline",
            direction = Direction.ReferenceToScanner,
            tag = "strong",
            probeContains = "полужирный",
            reason = "pass:q[…] выполняет quotes внутри passthrough — список подстановок pass сканер не разбирает (FR-18 покрывает форму, не семантику)",
        ),
        Known(
            stem = "10-macros-inline",
            direction = Direction.ReferenceToScanner,
            tag = "em",
            probeContains = "сырой",
            reason = "pass:[<em>…</em>] — сырой HTML сквозь passthrough; сканер держит его непрозрачным",
        ),
        Known(
            stem = "13-footnotes",
            direction = Direction.ScannerToReference,
            tag = "h2",
            probeContains = "Сноска в заголовке",
            reason = "эталон выносит сноску из текста заголовка; инлайн и макросы в заголовках не разбираются (недолёт SL-3, записан в журнале)",
        ),
        Known(
            stem = "14-include-directives",
            direction = Direction.ReferenceToScanner,
            tag = "code",
            probeContains = "",
            reason = "include::— сканер видит только открытый буфер; содержимое включаемых файлов ему недоступно по построению",
        ),
        Known(
            stem = "14-include-directives",
            direction = Direction.ReferenceToScanner,
            tag = "h2",
            probeContains = "",
            reason = "include:: — заголовки включаемого файла не видны сканеру",
        ),
        Known(
            stem = "14-include-directives",
            direction = Direction.ReferenceToScanner,
            tag = "h3",
            probeContains = "",
            reason = "include:: — заголовки включаемого файла не видны сканеру",
        ),
        Known(
            stem = "17-unicode-and-russian",
            direction = Direction.ScannerToReference,
            tag = "code",
            probeContains = "текст",
            reason = "FR-23/TC-27: типографские кавычки — не-цель (кириллический вариант того же расхождения, что в 02)",
        ),
        Known(
            stem = "18-substitutions",
            direction = Direction.ReferenceToScanner,
            tag = "strong",
            probeContains = "форматировани",
            reason = "четыре не-цели разом: *форматирование* в listing с subs=, в заголовке блока `.Title`, в заголовке раздела и в ячейке таблицы — " +
                "всё перечислено в не-целях и недолётах журнала",
        ),
        Known(
            stem = "18-substitutions",
            direction = Direction.ReferenceToScanner,
            tag = "em",
            probeContains = "сырой",
            reason = "pass:[<em>…</em>] — сырой HTML сквозь passthrough",
        ),
        Known(
            stem = "18-substitutions",
            direction = Direction.ReferenceToScanner,
            tag = "h2",
            probeContains = "Раздел с форматированием",
            reason = "инлайн в заголовке раздела не разбирается (недолёт SL-3) плюс подстановка `{attr}` в тексте заголовка",
        ),
    )

    private data class Deviation(
        val stem: String,
        val direction: Direction,
        val tag: String,
        val probe: String,
    ) {
        override fun toString() = "$stem · ${direction.label} · <$tag> · «$probe»"
    }

    private val assets: AssetManager =
        InstrumentationRegistry.getInstrumentation().context.assets

    private fun readAsset(path: String): String =
        assets.open(path).use { it.readBytes().decodeToString() }

    private fun corpusStems(): List<String> =
        assets.list("src").orEmpty()
            .filter { it.endsWith(".adoc") }
            .map { it.removeSuffix(".adoc") }
            .sorted()

    @Test
    fun TC_38_highlightIsConsistentWithTheReferenceCorpus() {
        val stems = corpusStems()
        assertEquals(18, stems.size, "состав корпуса изменился: $stems")

        val reportDir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "highlight-corpus-report",
        ).apply { deleteRecursively(); mkdirs() }

        val deviations = mutableListOf<Deviation>()
        var checkedSpans = 0
        var checkedTags = 0
        val cleanStems = mutableListOf<String>()

        for (stem in stems) {
            val source = readAsset("src/$stem.adoc")
            val html = readAsset("expected/$stem.html")
            val scan = AdocBlockScanner.scan(source)
            val before = deviations.size

            // Направление А: инлайн-роль сканера обязана иметь след в эталоне.
            val tagContents = HtmlProbe.tagContents(html, CHECKED_TAGS)
            val anchorHrefs = HtmlProbe.anchorHrefs(html)
            for (span in scan.spans) {
                val expectedTags = tagsForRole(span.style) ?: continue
                val spanText = source.substring(span.range.start, span.range.endExclusive)
                if ('{' in spanText) continue // ссылка на атрибут: текст подставится
                val runs = HtmlProbe.runs(stripAttributePrefix(spanText), MIN_RUN_A)
                if (runs.isEmpty()) continue
                checkedSpans++
                val matched = runs.any { run ->
                    expectedTags.any { tag ->
                        tagContents[tag].orEmpty().any { run in it } ||
                            (tag == "a" && anchorHrefs.any { run in it })
                    }
                }
                if (!matched) {
                    deviations += Deviation(stem, Direction.ScannerToReference, expectedTags.first(), runs.first())
                }
            }

            // Направление Б: тег эталона обязан иметь спан соответствующей роли.
            // Пробы — без точек: иначе нумерация раздела `1. Заголовок` склеилась
            // бы с текстом в один прогон, которого в исходнике нет по построению.
            for ((tag, roles) in REVERSE_TAGS) {
                for (content in tagContents[tag].orEmpty()) {
                    val probes = HtmlProbe.runs(content, MIN_RUN_B, includeDots = false)
                    if (probes.isEmpty()) continue
                    checkedTags++
                    val matched = scan.spans.any { span ->
                        span.style in roles && probes.any { probe ->
                            probe in source.substring(span.range.start, span.range.endExclusive)
                        }
                    }
                    if (!matched) {
                        val longest = probes.maxByOrNull { it.length } ?: probes.first()
                        deviations += Deviation(stem, Direction.ReferenceToScanner, tag, longest)
                    }
                }
            }

            if (deviations.size == before) cleanStems += stem
        }

        // Разложить расхождения по реестру: новые и мёртвые записи — падение.
        val unmatched = mutableListOf<Deviation>()
        val coverage = knownDeviations.associateWith { 0 }.toMutableMap()
        for (deviation in deviations) {
            val entry = knownDeviations.firstOrNull {
                it.stem == deviation.stem &&
                    it.direction == deviation.direction &&
                    it.tag == deviation.tag &&
                    it.probeContains in deviation.probe
            }
            if (entry == null) unmatched += deviation else coverage[entry] = coverage.getValue(entry) + 1
        }
        val dead = coverage.filterValues { it == 0 }.keys

        val verdict = buildString {
            appendLine("корпус: ${stems.size} документов; чистых: ${cleanStems.size} (${cleanStems.joinToString()})")
            appendLine("сверено спанов: $checkedSpans; сверено тегов: $checkedTags")
            appendLine("расхождений всего: ${deviations.size}, из них вне реестра: ${unmatched.size}")
            coverage.forEach { (entry, count) ->
                appendLine("  известное ×$count: ${entry.stem} · ${entry.direction.label} · <${entry.tag}> · «${entry.probeContains}» — ${entry.reason}")
            }
            dead.forEach { appendLine("  МЁРТВАЯ ЗАПИСЬ: ${it.stem} · «${it.probeContains}» — ${it.reason}") }
            unmatched.forEach { appendLine("  НОВОЕ: $it") }
            appendLine("отчёт: ${reportDir.absolutePath}")
        }
        File(reportDir, "summary.txt").writeText(verdict)
        File(reportDir, "deviations.txt").writeText(deviations.joinToString("\n"))

        assertTrue(
            unmatched.isEmpty() && dead.isEmpty(),
            "подсветка противоречит эталону вне перечня намеренных расхождений:\n$verdict",
        )
    }

    /** Роль сканера → теги эталона, где обязан найтись её текст (направление А). */
    private fun tagsForRole(style: AdocStyle): List<String>? = when (style) {
        AdocStyle.Bold -> listOf("strong")
        AdocStyle.Italic -> listOf("em")
        AdocStyle.Monospace -> listOf("code")
        // Выделение с ролью (`[.underline]#…#`) эталон отдаёт как span с классом.
        AdocStyle.Highlight -> listOf("mark", "span")
        AdocStyle.Superscript -> listOf("sup")
        AdocStyle.Subscript -> listOf("sub")
        AdocStyle.Link, AdocStyle.CrossReference -> listOf("a")
        AdocStyle.Heading1 -> listOf("h2")
        AdocStyle.Heading2 -> listOf("h3")
        AdocStyle.Heading3 -> listOf("h4")
        AdocStyle.Heading4 -> listOf("h5")
        AdocStyle.Heading5 -> listOf("h6")
        // Heading0 — заголовок документа: корпус рендерится embedded без
        // showtitle, в HTML его нет по конфигурации, а не по смыслу.
        // Остальные роли либо блочные, либо без текстового следа в HTML
        // (Macro — картинки и сноски, InlinePassthrough, AttributeReference).
        else -> null
    }

    /** Префикс атрибутов `[.role]` перед парой — разметка, в пробу не входит. */
    private fun stripAttributePrefix(spanText: String): String =
        if (spanText.startsWith('[') && ']' in spanText) {
            spanText.substringAfter(']')
        } else {
            spanText
        }

    private companion object {
        /** Направление Б: тег эталона → роли сканера, любая из которых закрывает пробу. */
        val REVERSE_TAGS: Map<String, Set<AdocStyle>> = mapOf(
            "strong" to setOf(AdocStyle.Bold),
            "em" to setOf(AdocStyle.Italic),
            // `<code>` рождают и инлайн-моноширинный, и listing/литерал:
            // у сканера это Monospace, VerbatimContent или инлайн-passthrough.
            "code" to setOf(AdocStyle.Monospace, AdocStyle.VerbatimContent, AdocStyle.InlinePassthrough),
            "mark" to setOf(AdocStyle.Highlight),
            "sub" to setOf(AdocStyle.Subscript),
            "sup" to setOf(AdocStyle.Superscript),
            "h2" to setOf(AdocStyle.Heading1),
            "h3" to setOf(AdocStyle.Heading2),
            "h4" to setOf(AdocStyle.Heading3),
            "h5" to setOf(AdocStyle.Heading4),
            "h6" to setOf(AdocStyle.Heading5),
            // <a> в обратную сторону не сверяется: эталон генерирует якоря
            // сносок, оглавления и перекрёстных ссылок, которых в исходнике нет.
        )

        val CHECKED_TAGS: Set<String> =
            setOf("strong", "em", "code", "mark", "sub", "sup", "span", "a", "h2", "h3", "h4", "h5", "h6")

        /** Направление А: хватает короткого прогона — проба ищется в узком месте. */
        const val MIN_RUN_A = 2

        /** Направление Б: проба должна быть различима во всём исходнике. */
        const val MIN_RUN_B = 3
    }
}

/** Извлечение проб из HTML эталона — без DOM, регулярными выражениями по фрагменту. */
internal object HtmlProbe {

    /** Содержимое тегов по именам: вложенные теги сняты, сущности развёрнуты. */
    fun tagContents(html: String, tags: Set<String>): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        for (tag in tags) {
            val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            for (match in regex.findAll(html)) {
                result.getOrPut(tag) { mutableListOf() } += plainText(match.groupValues[1])
            }
        }
        return result
    }

    /** Значения href всех якорей — для сверки автоссылок по адресу. */
    fun anchorHrefs(html: String): List<String> =
        Regex("<a\\s[^>]*href=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()

    /**
     * Прогоны из букв, цифр, пробелов и дефисов (с точками — для адресов ссылок)
     * длиной не короче [minLength].
     */
    fun runs(text: String, minLength: Int, includeDots: Boolean = true): List<String> =
        text.split(if (includeDots) SEPARATOR_WITH_DOTS else SEPARATOR)
            .map { it.trim() }
            .filter { it.length >= minLength && it.any(Char::isLetterOrDigit) }

    private val SEPARATOR_WITH_DOTS = Regex("[^\\p{L}\\p{N} .-]+")
    private val SEPARATOR = Regex("[^\\p{L}\\p{N} -]+")

    private fun plainText(fragment: String): String {
        val untagged = fragment.replace(Regex("<[^>]+>"), "")
        return Regex("&(#x?[0-9a-fA-F]+|[a-z]+);").replace(untagged) { match ->
            when (val body = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                "nbsp" -> " "
                else ->
                    if (body.startsWith("#x") || body.startsWith("#X")) {
                        body.drop(2).toIntOrNull(16)?.let { Char(it).toString() } ?: match.value
                    } else if (body.startsWith("#")) {
                        body.drop(1).toIntOrNull()?.let { Char(it).toString() } ?: match.value
                    } else {
                        match.value
                    }
            }
        }
    }
}
