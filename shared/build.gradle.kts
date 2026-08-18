import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // Не com.android.library: с AGP 9.0 он несовместим с KMP-плагином.
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
}

/**
 * TC-8 фичи 002-design-system: цвет в коде берётся только через роли палитры.
 *
 * Проверка сделана задачей сборки, а не тестом: тест из commonTest не имеет
 * доступа к исходникам. Область — production-код; тесты исключены намеренно,
 * они обязаны содержать ожидаемые значения, иначе им нечего сверять.
 */
val verifyNoColorLiterals = tasks.register("verifyNoColorLiterals") {
    group = "verification"
    description = "Литералы цвета вне файла определения палитры"

    val paletteFile = "AdocColors.kt"
    val sources = files(
        rootDir.resolve("shared/src/commonMain"),
        rootDir.resolve("shared/src/androidMain"),
        rootDir.resolve("androidApp/src/main"),
    )
    inputs.files(sources).withPropertyName("sources")

    doLast {
        val literal = Regex("""Color\(0x[0-9a-fA-F]{8}|#[0-9a-fA-F]{6}\b""")
        val offenders = sources.asFileTree
            .matching { include("**/*.kt") }
            .filter { it.name != paletteFile }
            .mapNotNull { file ->
                val hits = file.readLines()
                    .withIndex()
                    .filter { (_, line) -> literal.containsMatchIn(line) }
                    .map { (i, line) -> "  ${file.name}:${i + 1}  ${line.trim()}" }
                if (hits.isEmpty()) null else hits
            }
            .flatten()

        if (offenders.isNotEmpty()) {
            error(
                "Литерал цвета в обход роли палитры (TC-8, границы работ 002-design-system):\n" +
                    offenders.joinToString("\n") +
                    "\nЦвет берётся из AdocTheme.colors, а не задаётся по месту.",
            )
        }
    }
}

/**
 * TC-9 фичи 002-design-system: тексты лицензий сопровождают файлы шрифтов.
 *
 * Проверка автоматическая намеренно: SIL OFL требует прикладывать лицензию, и
 * это обязательство не должно держаться на памяти разработчика.
 */
val verifyFontLicenses = tasks.register("verifyFontLicenses") {
    group = "verification"
    description = "Лицензии и происхождение встроенных шрифтов"

    val fontsDir = layout.projectDirectory.dir("src/commonMain/composeResources/font")
    val licensesDir = layout.projectDirectory.dir("licenses/fonts")
    inputs.dir(fontsDir).withPropertyName("fonts")
    inputs.dir(licensesDir).withPropertyName("licenses")

    doLast {
        val fonts = fontsDir.asFile.listFiles { f -> f.extension == "ttf" }?.toList().orEmpty()
        check(fonts.isNotEmpty()) { "Шрифты не найдены в ${fontsDir.asFile}" }

        val licenseTexts = licensesDir.asFile.listFiles { f -> f.name.startsWith("OFL") }?.toList().orEmpty()
        check(licenseTexts.isNotEmpty()) {
            "Нет ни одного текста лицензии в ${licensesDir.asFile}, а шрифты встроены (TC-9)"
        }

        val sources = licensesDir.asFile.resolve("SOURCES.adoc")
        check(sources.isFile) { "Нет ${sources}: для каждой гарнитуры должен быть указан источник (TC-9)" }

        val sourcesText = sources.readText()
        val undocumented = fonts.map { it.name }.filterNot { sourcesText.contains(it) }
        check(undocumented.isEmpty()) {
            "Шрифты без указания источника в SOURCES.adoc (TC-9): ${undocumented.joinToString()}"
        }
    }
}

/**
 * TC-4 фичи 002-design-system, первое звено цепочки «объявление → файл → APK».
 *
 * `AdocFonts` перечисляет файлы шрифтов, из которых собираются семейства. Если
 * объявление разойдётся с содержимым каталога ресурсов, Compose не найдёт
 * ресурс — но узнается это на устройстве, а не на сборке. Здесь списки
 * сверяются в обе стороны: объявленное без файла и файл без объявления одинаково
 * валят прогон (второе означает вес в APK, который никто не рисует).
 */
val verifyFontDeclarations = tasks.register("verifyFontDeclarations") {
    group = "verification"
    description = "Объявления шрифтов совпадают с каталогом ресурсов"

    val fontsDir = layout.projectDirectory.dir("src/commonMain/composeResources/font")
    val declarations = layout.projectDirectory
        .file("src/commonMain/kotlin/io/github/olegnyr/adocmobile/theme/AdocFonts.kt")
    inputs.dir(fontsDir).withPropertyName("fonts")
    inputs.file(declarations).withPropertyName("declarations")

    doLast {
        val declared = Regex("""\"([a-z0-9_]+\.ttf)\"""")
            .findAll(declarations.asFile.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
        check(declared.isNotEmpty()) { "В ${declarations.asFile.name} не найдено ни одного объявленного шрифта" }

        val present = fontsDir.asFile.listFiles { f -> f.extension == "ttf" }
            ?.map { it.name }?.toSortedSet().orEmpty().toSortedSet()

        val missing = declared - present
        val unused = present - declared
        check(missing.isEmpty() && unused.isEmpty()) {
            buildString {
                append("Объявления шрифтов разошлись с каталогом ресурсов (TC-4):")
                if (missing.isNotEmpty()) append("\n  объявлены, но файлов нет: ${missing.joinToString()}")
                if (unused.isNotEmpty()) append("\n  файлы есть, но не объявлены: ${unused.joinToString()}")
            }
        }
    }
}

/**
 * Каталоги, в которых легально живёт код подсветки, и способы его опознать.
 *
 * Опознание идёт двумя признаками сразу — по пути и по объявлению пакета.
 * Одного пути мало: файл, положенный в androidMain под другим каталогом, но с
 * пакетом фичи, проверку бы обошёл.
 */
val highlightPackage = "io.github.olegnyr.adocmobile.highlight"
val highlightAllowedRoots = listOf(
    "shared/src/commonMain/kotlin/io/github/olegnyr/adocmobile/highlight",
    "shared/src/commonTest/kotlin/io/github/olegnyr/adocmobile/highlight",
)
val highlightSearchRoots = files(rootDir.resolve("shared/src"), rootDir.resolve("androidApp/src"))

/** Все файлы фичи подсветки, где бы они ни лежали. */
fun highlightSources(): List<File> = highlightSearchRoots.asFileTree
    .matching { include("**/*.kt") }
    .filter { file ->
        val path = file.invariantSeparatorsPath
        path.contains("/adocmobile/highlight/") || file.readText().contains("package $highlightPackage")
    }
    .toList()

/**
 * TC-40 фичи 001-syntax-highlighting: код фичи целиком в `commonMain`/`commonTest`.
 *
 * Нефункциональное требование «платформенного кода в этой фиче быть не должно
 * вовсе» проверяемо только составом файлов, а состава файлов тест из commonTest
 * не видит. Отсюда задача сборки — тем же приёмом, что verifyNoColorLiterals.
 *
 * Проверка двусторонняя. Файл фичи за пределами общих исходников валит прогон —
 * это прямое нарушение. Пустой результат валит прогон тоже: молча зеленеющая
 * проверка, которая перестала что-либо находить (переименовали пакет, перенесли
 * каталог), хуже отсутствующей.
 */
val verifyHighlightIsCommonOnly = tasks.register("verifyHighlightIsCommonOnly") {
    group = "verification"
    description = "Код подсветки не покидает commonMain и commonTest"

    inputs.files(highlightSearchRoots).withPropertyName("sources")

    doLast {
        val sources = highlightSources()
        check(sources.isNotEmpty()) {
            "Не найдено ни одного файла пакета $highlightPackage — проверка TC-40 перестала что-либо проверять"
        }

        val rootPath = rootDir.invariantSeparatorsPath
        val offenders = sources
            .map { it.invariantSeparatorsPath.removePrefix("$rootPath/") }
            .filterNot { path -> highlightAllowedRoots.any { path.startsWith("$it/") } }

        check(offenders.isEmpty()) {
            buildString {
                append("Код подсветки вне общих исходников (TC-40, NFR «доля общего кода»):")
                offenders.forEach { append("\n  $it") }
                append("\nДопустимы только:")
                highlightAllowedRoots.forEach { append("\n  $it/") }
            }
        }
    }
}

/**
 * TC-30 фичи 001-syntax-highlighting, вторая половина: в сканере нет типов Compose.
 *
 * Первую половину — что результат собирается из обычных значений Kotlin —
 * держит тест `TC_30_outputIsBuiltFromPlainKotlinValues`: он просто перестанет
 * компилироваться, если в модель приедет `SpanStyle` или `TextRange`. Но тест
 * не увидит типа Compose, спрятанного во внутренней функции сканера, а именно
 * оттуда он обычно и расползается. Состав импортов виден только сборке.
 *
 * Цена нарушения — не стиль: `FR-25` называет список диапазонов точкой, в
 * которой поле ввода заменяется на платформенное. Замеры `T-010` показали, что
 * такая замена вполне вероятна, и тип Compose в сканере закрывает этот путь.
 */
val verifyHighlightIsPlatformNeutral = tasks.register("verifyHighlightIsPlatformNeutral") {
    group = "verification"
    description = "Сканер подсветки не знает о Compose"

    inputs.files(highlightSearchRoots).withPropertyName("sources")

    doLast {
        val forbidden = Regex("""\bandroidx\.compose\b""")
        val production = highlightSources().filter {
            it.invariantSeparatorsPath.contains("/commonMain/")
        }
        check(production.isNotEmpty()) {
            "Не найдено production-файлов пакета $highlightPackage — проверка TC-30 перестала что-либо проверять"
        }

        val offenders = production.flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> forbidden.containsMatchIn(line) }
                .map { (i, line) -> "  ${file.name}:${i + 1}  ${line.trim()}" }
        }

        check(offenders.isEmpty()) {
            "Тип Compose в сканере подсветки (TC-30, FR-25, границы работ 001-syntax-highlighting):\n" +
                offenders.joinToString("\n") +
                "\nВыход сканера платформенно-нейтрален: список (range, style) и ничего сверх него."
        }
    }
}

/**
 * TC-28 фичи 003-render-preview: платформенных швов ровно два.
 *
 * NFR «доля общего кода» называет их поимённо — реализация `render` и обёртка
 * WebView, — и это не пожелание: третий шов означает, что часть поведения
 * продукта переехала в платформенный код и на второй платформе будет написана
 * заново. Составом файлов такое не поймать (реализация движка *обязана* жить в
 * androidMain), поэтому считаются объявления `expect`/`actual`.
 *
 * Проверка двусторонняя и по именам: и лишний шов, и молчаливое переименование
 * существующего валят прогон. Пустой результат валит тоже — проверка, которая
 * перестала что-либо находить, хуже отсутствующей.
 */
val verifyRenderSeams = tasks.register("verifyRenderSeams") {
    group = "verification"
    description = "У рендера и превью ровно два платформенных шва"

    val featureRoots = files(rootDir.resolve("shared/src"), rootDir.resolve("androidApp/src"))
    val expectedSeams = setOf("adocRenderer", "AdocPreview")
    inputs.files(featureRoots).withPropertyName("sources")

    doLast {
        val featureFiles = featureRoots.asFileTree
            .matching { include("**/*.kt") }
            .filter { file ->
                val path = file.invariantSeparatorsPath
                path.contains("/adocmobile/render/") || path.contains("/adocmobile/preview/")
            }
            .toList()
        check(featureFiles.isNotEmpty()) {
            "Не найдено ни одного файла пакетов render/preview — проверка TC-28 перестала что-либо проверять"
        }

        // Имя объявления берётся из той же строки: `expect fun adocRenderer(…)`.
        fun namesOf(keyword: String, sourceSet: String): Set<String> {
            val declaration = Regex("""^$keyword\s+(?:@\w+\s+)*(?:fun|class|object|val|var)\s+(\w+)""")
            return featureFiles
                .filter { it.invariantSeparatorsPath.contains("/$sourceSet/") }
                .flatMap { file -> file.readLines().mapNotNull { declaration.find(it)?.groupValues?.get(1) } }
                .toSet()
        }

        val declared = namesOf("expect", "commonMain")
        val implemented = namesOf("actual", "androidMain")

        check(declared == expectedSeams) {
            "Состав швов рендера и превью изменился (TC-28, NFR «доля общего кода»).\n" +
                "  ожидались: ${expectedSeams.sorted()}\n" +
                "  объявлены: ${declared.sorted()}\n" +
                "Третий шов — это поведение продукта, уехавшее в платформенный код."
        }
        check(implemented == expectedSeams) {
            "Реализации швов в androidMain разошлись с объявлениями (TC-28).\n" +
                "  ожидались: ${expectedSeams.sorted()}\n" +
                "  реализованы: ${implemented.sorted()}"
        }
    }
}

/**
 * Граница работ фичи 007-git-sync: типы JGit не пересекают общий код.
 *
 * Запрет из 🚫-списка плана («типы JGit в `commonMain`») ловится составом
 * импортов, а не ревью: `GitSync` — это ещё и точка подключения libgit2-пути
 * iOS, и просочившийся JVM-тип закрыл бы его молча. Тем же приёмом, что
 * verifyHighlightIsPlatformNeutral; область — commonMain и commonTest целиком:
 * подделке и тестам моделей JGit нужен не больше, чем интерфейсу.
 */
val verifyGitSeamIsPlatformNeutral = tasks.register("verifyGitSeamIsPlatformNeutral") {
    group = "verification"
    description = "Типы JGit не пересекают commonMain и commonTest"

    val commonRoots = files(
        rootDir.resolve("shared/src/commonMain"),
        rootDir.resolve("shared/src/commonTest"),
    )
    inputs.files(commonRoots).withPropertyName("sources")

    doLast {
        val forbidden = Regex("""\borg\.eclipse\.jgit\b""")
        val offenders = commonRoots.asFileTree
            .matching { include("**/*.kt") }
            .flatMap { file ->
                file.readLines()
                    .withIndex()
                    .filter { (_, line) -> forbidden.containsMatchIn(line) }
                    .map { (i, line) -> "  ${file.name}:${i + 1}  ${line.trim()}" }
            }

        check(offenders.isEmpty()) {
            "Тип JGit в общем коде (границы работ 007-git-sync):\n" +
                offenders.joinToString("\n") +
                "\nПлатформенным типам место в androidMain; общий код видит только GitSync."
        }
    }
}

/**
 * TC-4 фичи 008-diagrams: ассет расширения Kroki собран по рецепту ADR-008.
 *
 * Рецепт — не деталь реализации, а условие работоспособности: без обёртки в
 * IIFE два бандла в одной глобальной области падают на
 * `SyntaxError: redeclaration of 'packageJson'`, а без полифила `btoa`
 * расширение не может закодировать диаграмму в адрес. И то и другое видно
 * только на устройстве и только когда до диаграммы дошла очередь, поэтому
 * проверка вынесена на сборку — тем же приёмом, что verifyFontDeclarations.
 *
 * Ассет собирается скриптом androidApp/src/debug/tools/patch-asciidoctor.py и
 * коммитится, как и ядро: сборка JS в Gradle-конвейер не входит.
 */
/**
 * TC-33 фичи 008-diagrams, вторая половина: пакет диаграмм платформенно нейтрален.
 *
 * Первую половину — «швов у рендера и превью по-прежнему ровно два» — держит
 * verifyRenderSeams, но он смотрит только на пакеты render и preview и о новом
 * пакете не знает вовсе. Это и есть тот случай, когда требование помечено
 * закрытым, а проверять его некому.
 *
 * Что проверяется: в commonMain пакета diagram нет платформенных типов. Ошибка
 * тут не стилистическая — `NFR-2` называет логику диаграмм общей, и уехавший в
 * неё `java.util.zip` означал бы, что на iOS всё это придётся писать заново.
 * Распаковка живёт за интерфейсом (ADR-014) именно поэтому.
 *
 * Проверка двусторонняя: пустой результат поиска файлов валит прогон так же,
 * как найденный платформенный тип. Проверка, переставшая что-либо находить
 * (пакет переименовали, каталог перенесли), хуже отсутствующей.
 */
val verifyDiagramIsPlatformNeutral = tasks.register("verifyDiagramIsPlatformNeutral") {
    group = "verification"
    description = "Пакет diagram в commonMain не знает о платформе"

    val commonRoots = files(
        rootDir.resolve("shared/src/commonMain/kotlin/io/github/olegnyr/adocmobile/diagram"),
        rootDir.resolve("shared/src/commonTest/kotlin/io/github/olegnyr/adocmobile/diagram"),
    )
    inputs.files(commonRoots).withPropertyName("sources")

    doLast {
        val sources = commonRoots.asFileTree.matching { include("**/*.kt") }.toList()
        check(sources.isNotEmpty()) {
            "Не найдено ни одного файла пакета diagram — проверка TC-33 перестала что-либо проверять"
        }

        val forbidden = Regex("""^import\s+(java\.|javax\.|android\.|androidx\.|kotlinx\.cinterop|platform\.)""")
        val offenders = sources.flatMap { file ->
            file.readLines()
                .withIndex()
                .filter { (_, line) -> forbidden.containsMatchIn(line.trim()) }
                .map { (i, line) -> "  ${file.name}:${i + 1}  ${line.trim()}" }
        }

        check(offenders.isEmpty()) {
            "Платформенный тип в общем коде диаграмм (TC-33, NFR-2, ADR-014):\n" +
                offenders.joinToString("\n") +
                "\nПлатформенная возможность подключается интерфейсом, как Inflate; иначе на iOS это пишется заново."
        }
    }
}

val verifyKrokiAsset = tasks.register("verifyKrokiAsset") {
    group = "verification"
    description = "Бандлы движка собраны по рецепту ADR-008"

    val bundleDir = layout.projectDirectory.dir("src/androidMain/assets/asciidoctor")
    val coreFile = bundleDir.file("asciidoctor.js").asFile
    val krokiFile = bundleDir.file("asciidoctor-kroki.js").asFile
    // Именно files, а не dir: на отсутствующем каталоге снимок входов падает
    // раньше doLast, и заботливо написанные сообщения про пропавший файл никогда
    // не показываются — а это самый вероятный сценарий (свежий клон, сбой слияния).
    inputs.files(coreFile, krokiFile).withPropertyName("bundles").optional()

    doLast {
        check(coreFile.isFile) { "Нет бандла ядра $coreFile (TC-4)" }
        check(krokiFile.isFile) { "Нет ассета расширения $krokiFile (TC-4, FR-2)" }

        val coreText = coreFile.readText()
        val preludeEnd = coreText.indexOf("// --- Конец добавленного блока ---")
        check(preludeEnd > 0) { "В бандле ядра нет пролога patch-asciidoctor.py (TC-4, FR-2)" }
        // Полифил обязан стоять *в прологе*, а не «где-нибудь в файле»: порядок и
        // есть предмет рецепта — к моменту, когда расширение вызовет btoa, он
        // должен уже существовать. Совпадение в теле бандла или в комментарии
        // ничего не гарантирует.
        check(coreText.lastIndexOf("globalThis.btoa", preludeEnd) > 0) {
            "В прологе ядра нет полифила btoa (TC-4, FR-2): расширение не сможет закодировать диаграмму в адрес"
        }
        check(coreText.contains("globalThis.Asciidoctor = {")) {
            "Бандл ядра не выставляет globalThis.Asciidoctor (TC-4, ADR-008)"
        }
        check(!coreText.contains("import.meta")) {
            "В бандле ядра остался import.meta (TC-4): вне модуля это синтаксическая ошибка, движок не поднимется"
        }

        val krokiText = krokiFile.readText().trim()
        check(krokiText.startsWith("(function () {")) {
            "Ассет расширения не завёрнут в IIFE (TC-4, ADR-008): два бандла в одной глобальной области дадут redeclaration of 'packageJson'"
        }
        // Именно в хвосте, а не «где-нибудь»: присваивание — последнее, что делает
        // скрипт, и упоминание имени в середине файла ничего не значит. И именно
        // `default`: движок зовёт AsciidoctorKroki.default.register, и переход
        // апстрима на именованный экспорт обязан валить сборку, а не устройство.
        check(Regex("""globalThis\.AsciidoctorKroki = \{[^}]*\bdefault:[^}]*\};\s*\}\)\(\);$""").containsMatchIn(krokiText)) {
            "Ассет расширения не заканчивается присваиванием globalThis.AsciidoctorKroki с полем default (TC-4, ADR-008): движку нечего регистрировать"
        }
        check(!Regex("""^\s*export\s*\{""", RegexOption.MULTILINE).containsMatchIn(krokiText)) {
            "В ассете расширения остался export (TC-4, ADR-008): скрипт исполняется глобально, модулей у движка нет"
        }
    }
}
tasks.named("check") {
    dependsOn(
        verifyNoColorLiterals,
        verifyFontLicenses,
        verifyFontDeclarations,
        verifyHighlightIsCommonOnly,
        verifyHighlightIsPlatformNeutral,
        verifyRenderSeams,
        verifyGitSeamIsPlatformNeutral,
        verifyKrokiAsset,
        verifyDiagramIsPlatformNeutral,
    )
}

kotlin {
    // Блок называется android, а не androidLibrary: последний уже помечен устаревшим.
    android {
        namespace = "io.github.olegnyr.adocmobile.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // Тесты в новом плагине по умолчанию выключены и включаются явно.
        // Без этого commonTest не во что компилировать на Android.
        withHostTestBuilder {}.configure {}

        // Инструментальный прогон на устройстве (ADR-006): engine-кейсы фичи 003,
        // корпус сверки, перф-пороги. Source set называется androidDeviceTest —
        // имя фиксировано плагином (androidInstrumentedTest — имя из старого мира
        // KMP+AGP<9, здесь его нет). commonTest сюда не входит намеренно
        // (sourceSetTreeName по умолчанию null): его дом — быстрый host-прогон,
        // гонять его на устройстве значит платить устройством за уже покрытое.
        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        // Ресурсы Android в новом плагине тоже выключены по умолчанию.
        // Без этого шрифты из composeResources не попадают в APK, и приложение
        // молча рисует системным шрифтом.
        androidResources {
            enable = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(libs.versions.jvmTarget.get()))
        }
    }

    // iOS-таргет не заведён осознанно: сборка Kotlin/Native требует macOS.
    // Решение и условия возврата — doc/adr/adr-002-android-first.adoc.

    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: модуль приложения строит UI поверх этих
            // же артефактов, и версии должны приходить из одного места — каталога.
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.material3)
            api(libs.compose.ui)
            api(libs.compose.components.resources)

            // Системный «назад» из commonMain (FR-21 фичи 005): общий
            // BackHandler, на Android он делегирует OnBackPressedDispatcher
            // активности. implementation, а не api: приложение работает через
            // экран, самому ему перехват не нужен.
            implementation(libs.compose.ui.backhandler)

            // Явно, хотя приезжает и транзитивно с Compose: Flow — часть
            // публичного контракта GitSync, suspend-швы и модели экранов
            // зависят от корутин напрямую. api по той же причине, что Compose.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            // JGit — compileOnly, а не implementation, вынужденно (SL-2 фичи
            // 007): в runtime-classpath device-тестов этого модуля джарник с
            // Java records валит дексацию (нет потребителя глобальных синтетик
            // D8 — факт спайка E0, research 007). Классы в рантайме приложению
            // даёт :androidApp своей implementation(libs.jgit); страж пропажи —
            // verifyJGitPackaged там же. Вернуть implementation, когда AGP
            // починит конвейер device-тестов KMP-модуля.
            compileOnly(libs.jgit)
            // SSH-транспорт (E4) — тем же приёмом, что сам JGit.
            compileOnly(libs.jgit.ssh.apache)

            // Движок рендера (FR-2). Он приезжает вместе с нативной библиотекой
            // QuickJS, поэтому объявлен implementation: приложению видеть его
            // незачем, оно работает через контракт AdocRenderer.
            implementation(libs.quickjs.kt)
        }
        getByName("androidHostTest").dependencies {
            // JGit и sshd — только на тестовый classpath: в androidMain они
            // compileOnly (SL-2), а compileOnly в тестовые наборы не
            // наследуется. Нужны host-прогону базы ключей сервера
            // (`ServerKeyDatabaseHostTest`, SL-20): это обычный JVM-код, и
            // гонять его на устройстве значит платить телефоном за уже
            // покрытое. Продуктовых зависимостей это не добавляет.
            implementation(libs.jgit)
            implementation(libs.jgit.ssh.apache)
        }
        getByName("androidDeviceTest").dependencies {
            // kotlin("test") на Android-таргете разворачивается в kotlin-test-junit,
            // JUnit 4 приезжает с ним; runner добавляет AndroidJUnitRunner и
            // InstrumentationRegistry (через транзитивный monitor).
            implementation(kotlin("test"))
            implementation(libs.androidx.test.runner)

            // JGit сюда подключать нельзя: конвейер дексации device-тестов
            // KMP-библиотеки не потребляет глобальные синтетики D8, и джарник
            // с Java records (JGit ≥ 7.x) валит mergeExtDexAndroidDeviceTest.
            // Факт установлен спайком E0 фичи 007-git-sync; спайк живёт в
            // androidApp/src/androidTest, где конвейер приложения дексует
            // records штатно.
        }
    }
}

// Корпус сверки уезжает в ассеты инструментального APK из своего родного
// каталога, а не копией в src: вторая копия разъехалась бы с первой молча.
// Статический каталог, не задача: содержимое корпуса меняется только руками.
androidComponents {
    onVariants { variant ->
        variant.deviceTests.values.forEach { deviceTest ->
            deviceTest.sources.assets?.addStaticSourceDirectory("../testdata/render-corpus")
        }
    }
}
