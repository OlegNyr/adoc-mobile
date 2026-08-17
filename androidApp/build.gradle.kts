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

dependencies {
    // Compose приходит транзитивно из :shared через api — версии живут только в каталоге.
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
}
