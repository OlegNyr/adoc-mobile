pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
// Резолвер JDK: позволяет Gradle скачать нужный toolchain, если его нет в системе.
// На этой машине java на PATH — JRE 1.8, поэтому страховка не лишняя.
//
// Версия задана здесь, а не в каталоге, вынужденно: блок plugins в settings
// выполняется раньше, чем создаётся version catalog, и libs в нём недоступен.
// Это единственное исключение из правила «версии живут только в libs.versions.toml».
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "adoc-mobile"

include(":shared")
include(":androidApp")
