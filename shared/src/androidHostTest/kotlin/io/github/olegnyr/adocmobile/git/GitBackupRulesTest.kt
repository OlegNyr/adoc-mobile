package io.github.olegnyr.adocmobile.git

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Правила резервной копии — слайс `SL-19` фичи 007-git-sync, `TC-58`.
 *
 * Открытая половина Git-слоя (`known_hosts`, публичная строка ключа, шифртекст
 * секретов) уезжала бы в облачную копию и в перенос на новое устройство: там
 * доверенные отпечатки серверов оказались бы у человека, который их *не
 * подтверждал*, — прямое нарушение `FR-29`.
 *
 * Проверяется исходником манифеста и XML-правил, а не рантаймом: узнать на
 * устройстве, что бэкап действительно исключил каталог, нельзя без облачного
 * прогона. Файлы читаются от корня репозитория — рабочий каталог JVM-прогона
 * может оказаться модулем, поэтому корень ищется вверх по дереву.
 */
class GitBackupRulesTest {

    @Test
    fun TC_58_gitPrivateDirectoryIsExcludedFromBothBackupRuleSets() {
        val manifest = repoFile("androidApp/src/main/AndroidManifest.xml").readText()
        assertTrue(
            "android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest,
            "манифест обязан ссылаться на правила Android 12+",
        )
        assertTrue(
            "android:fullBackupContent=\"@xml/backup_rules\"" in manifest,
            "манифест обязан ссылаться на правила старых версий — иначе там копируется всё",
        )

        val exclusion = "domain=\"file\" path=\"$GIT_PRIVATE_DIR_NAME/\""
        val extraction = repoFile("androidApp/src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("<cloud-backup>" in extraction && "<device-transfer>" in extraction, extraction)
        assertTrue(
            extraction.split(exclusion).size == 3,
            "каталог Git-слоя исключён и из облачной копии, и из переноса на устройство",
        )

        val fullBackup = repoFile("androidApp/src/main/res/xml/backup_rules.xml").readText()
        assertTrue(exclusion in fullBackup, "тот же каталог исключён и в правилах до Android 12")
    }

    private fun repoFile(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("не найден файл $relative от каталога прогона ${File(".").absolutePath}")
    }
}
