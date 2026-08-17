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

tasks.named("check") {
    dependsOn(
        verifyNoColorLiterals,
        verifyFontLicenses,
        verifyFontDeclarations,
        verifyHighlightIsCommonOnly,
        verifyHighlightIsPlatformNeutral,
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
    }
}
