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
val verifyNoColorLiterals by tasks.registering {
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

tasks.named("check") { dependsOn(verifyNoColorLiterals) }

kotlin {
    // Блок называется android, а не androidLibrary: последний уже помечен устаревшим.
    android {
        namespace = "io.github.olegnyr.adocmobile.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // Тесты в новом плагине по умолчанию выключены и включаются явно.
        // Без этого commonTest не во что компилировать на Android.
        withHostTestBuilder {}.configure {}

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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
    }
}
