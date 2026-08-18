import java.util.zip.ZipFile

plugins {
    // Плагин org.jetbrains.kotlin.android не применяется: начиная с AGP 9.0
    // поддержка Kotlin встроена в сам AGP. Плагин Compose при этом остаётся
    // отдельным. См. doc/adr/adr-001-toolchain-and-versions.adoc.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "io.github.olegnyr.adocmobile.android"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.olegnyr.adocmobile"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Спайк E0 фичи 007-git-sync: инструментальный прогон JGitSpikeTest.
        // Спайк живёт здесь, а не в androidDeviceTest KMP-модуля, потому что
        // конвейер дексации device-тестов KMP-библиотеки падает на Java records
        // из JGit 7.x (нет потребителя глобальных синтетик D8); конвейер
        // приложения дексует их штатно. Удаляется вместе со спайком.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Apache MINA sshd (ADR-007) тянет зависимости со своими META-INF, и
    // одинаковые пути из двух джарников валят упаковку APK
    // («2 files found with path 'META-INF/DEPENDENCIES'»). Это метаданные
    // сборки Maven, в рантайме бесполезные, — исключаем их из пакета.
    // Факт установлен спайком E4 (см. research 007 и CLAUDE.md).
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module",
                // JGit и его SSH-бандл несут одинаковые OSGi-метаданные.
                "OSGI-INF/l10n/plugin.properties",
            )
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        val java = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = java
        targetCompatibility = java
    }

}

/**
 * Страховка к TC-4: шрифты должны оказаться в APK, а не только в объявлениях.
 *
 * Заведена по факту: ресурсы Android в KMP-плагине AGP выключены по умолчанию,
 * из-за чего сборка была зелёной, тесты проходили, а приложение рисовало
 * системным шрифтом. Ни один тест этого не ловил — они проверяют объявления,
 * а не упаковку.
 *
 * Задача входит в `check` — иначе она защищает ровно до тех пор, пока о ней
 * помнят, то есть не защищает. Цена — сборка debug-APK на обычном прогоне
 * проверок, и она заплачена сознательно: этот регресс уже случался.
 */
val verifyFontsPackaged = tasks.register("verifyFontsPackaged") {
    group = "verification"
    description = "Шрифты действительно попали в APK"
    dependsOn("assembleDebug")

    val apk = layout.buildDirectory.file("outputs/apk/debug/androidApp-debug.apk")
    val declared = rootProject.layout.projectDirectory
        .dir("shared/src/commonMain/composeResources/font").asFile
    inputs.file(apk)

    doLast {
        val expected = declared.listFiles { f -> f.extension == "ttf" }?.map { it.name }.orEmpty()
        check(expected.isNotEmpty()) { "Не найдено объявленных шрифтов в $declared" }

        val packaged = ZipFile(apk.get().asFile).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.endsWith(".ttf") }.toList()
        }
        val missing = expected.filterNot { name -> packaged.any { it.endsWith("/$name") } }
        check(missing.isEmpty()) {
            "Шрифты не попали в APK (TC-4): ${missing.joinToString()}.\n" +
                "Проверьте androidResources { enable = true } в KMP-модуле: в плагине AGP они выключены по умолчанию."
        }
    }
}

/**
 * То же самое для стенда рендера (`T-013`): бандл Asciidoctor.js и нативная
 * библиотека QuickJS должны оказаться в debug-APK.
 *
 * Проверяется артефакт, а не объявление: ассет молча не упаковывается, а `.so`
 * теряется при неудачной фильтрации ABI — и то и другое даёт зелёную сборку и
 * падение на устройстве.
 */
val verifyRenderStandPackaged = tasks.register("verifyRenderStandPackaged") {
    group = "verification"
    description = "Бандл Asciidoctor.js и нативный QuickJS попали в debug-APK"
    dependsOn("assembleDebug")

    val apk = layout.buildDirectory.file("outputs/apk/debug/androidApp-debug.apk")
    inputs.file(apk)

    doLast {
        val entries = ZipFile(apk.get().asFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        check(entries.any { it == "assets/asciidoctor/asciidoctor.js" }) {
            "Бандл Asciidoctor.js не попал в APK: нет assets/asciidoctor/asciidoctor.js"
        }
        check(entries.any { it.startsWith("lib/") && it.endsWith("libquickjs.so") }) {
            "Нативная библиотека QuickJS не попала в APK: нет lib/*/libquickjs.so"
        }
    }
}

/**
 * То же для продуктовой сборки: бандл и нативная библиотека обязаны быть в
 * *release*-APK.
 *
 * Задача заведена вместе с переносом бандла из `androidApp/src/debug/assets` в
 * `shared/src/androidMain/assets` (фича 003, `SL-1`). Перенос сделан ради
 * release-сборки, и проверять его debug-APK бессмысленно: там ассет мог бы
 * приехать из отладочного набора исходников и скрыть ошибку.
 *
 * Цена — сборка release-APK на обычном прогоне проверок. Заплачена сознательно:
 * ровно этот класс отказов (зелёная сборка, пустой артефакт) в проекте уже
 * случался со шрифтами.
 */
val verifyBundlePackagedInRelease = tasks.register("verifyBundlePackagedInRelease") {
    group = "verification"
    description = "Бандл Asciidoctor.js и нативный QuickJS попали в release-APK"
    dependsOn("assembleRelease")

    val apkDir = layout.buildDirectory.dir("outputs/apk/release")
    inputs.dir(apkDir)

    doLast {
        val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }?.firstOrNull()
        checkNotNull(apk) { "Release-APK не найден в ${apkDir.get().asFile}" }

        val entries = ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
        check(entries.any { it == "assets/asciidoctor/asciidoctor.js" }) {
            "Бандл Asciidoctor.js не попал в release-APK: нет assets/asciidoctor/asciidoctor.js.\n" +
                "Проверьте androidResources { enable = true } в :shared — ассеты KMP-модуля " +
                "по умолчанию выключены, и сборка при этом остаётся зелёной."
        }
        check(entries.any { it.startsWith("lib/") && it.endsWith("libquickjs.so") }) {
            "Нативная библиотека QuickJS не попала в release-APK: нет lib/*/libquickjs.so"
        }
    }
}

/**
 * Страж JGit-шва (SL-2 фичи 007): классы JGit действительно попали в debug-APK.
 *
 * `:shared` объявляет JGit как `compileOnly` — вынужденно, см. комментарий в
 * его каталоге зависимостей. Цена приёма: убрать `implementation(libs.jgit)`
 * здесь можно молча, сборка останется зелёной, а `JGitSync` упадёт с
 * `NoClassDefFoundError` на устройстве. Ровно класс отказов «зелёная сборка,
 * пустой артефакт», который в проекте уже случался со шрифтами, — поэтому
 * страж, а не память разработчика.
 *
 * Ищется байтовая подпись дескриптора класса в `classes*.dex`: дескрипторы
 * лежат в dex открытым текстом, а распаковывать dex ради одного факта незачем.
 */
val verifyJGitPackaged = tasks.register("verifyJGitPackaged") {
    group = "verification"
    description = "Классы JGit попали в debug-APK"
    dependsOn("assembleDebug")

    val apk = layout.buildDirectory.file("outputs/apk/debug/androidApp-debug.apk")
    inputs.file(apk)

    doLast {
        val signature = "org/eclipse/jgit/api/Git".toByteArray(Charsets.US_ASCII)
        val found = ZipFile(apk.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .any { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    bytes.indexOfSubArray(signature) >= 0
                }
        }
        check(found) {
            "Классы JGit не попали в APK: в :shared он compileOnly, рантайм обязан " +
                "класть :androidApp через implementation(libs.jgit) — зависимость пропала."
        }
    }
}

/** Поиск подстроки байт — `String(bytes)` на 15-мегабайтном dex дороже и лжива для не-ASCII. */
fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

tasks.named("check") {
    dependsOn(verifyFontsPackaged, verifyRenderStandPackaged, verifyBundlePackagedInRelease, verifyJGitPackaged)
}

dependencies {
    // Compose приходит транзитивно из :shared через api — версии живут только в каталоге.
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)

    // Только debug: движок нужен стенду замеров T-013. В продуктовый код он
    // приедет отдельной задачей, когда шов рендерера будет спроектирован.
    debugImplementation(libs.quickjs.kt)

    // Рантайм-половина JGit-шва (SL-2 фичи 007): :shared объявляет JGit как
    // compileOnly — implementation там валит дексацию device-тестов KMP-модуля
    // (records, факт спайка E0). Классы в APK кладёт приложение; пропажу ловит
    // verifyJGitPackaged ниже.
    implementation(libs.jgit)
    // SSH-транспорт (E4): рантайм-половина, как у самого JGit.
    implementation(libs.jgit.ssh.apache)

    // Инструментальные тесты Git-слоя (SL-2): JGit в тестовом APK нужен и сам
    // по себе — тесты строят file://-remote руками.
    androidTestImplementation(libs.jgit)
    androidTestImplementation(libs.jgit.ssh.apache)
    // Корутины в тестах используются напрямую (runBlocking, сбор Flow) —
    // зависимость явная, а не наследство classpath приложения.
    androidTestImplementation(libs.kotlinx.coroutines.core)
    // test-junit, а не test: базовый kotlin-test не содержит аннотации @Test —
    // она приходит из привязки к конкретному фреймворку (здесь JUnit 4).
    androidTestImplementation(kotlin("test-junit"))
    androidTestImplementation(libs.androidx.test.runner)
}
