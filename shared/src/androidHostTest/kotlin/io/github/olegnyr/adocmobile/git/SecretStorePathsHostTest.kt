package io.github.olegnyr.adocmobile.git

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Пути Git-слоя и имена слотов — слайс `SL-19` фичи 007-git-sync, `TC-55`,
 * `TC-58`.
 *
 * Правила резервной копии исключают ровно `filesDir/git`, поэтому путь обязан
 * собираться кодом, а не строкой у хостинга: врезка мимо каталога тихо
 * вернула бы `known_hosts` в облачную копию, и сверка XML с константой этого
 * не поймала бы (находка ревью `SL-19`).
 *
 * Android Keystore здесь не участвует: проверка имени слота идёт до первого
 * обращения к платформе, а пути — чистая арифметика файлов.
 */
class SecretStorePathsHostTest {

    private val filesDir = File("/data/data/io.github.olegnyr.adocmobile/files")

    @Test
    fun TC_58_privateFilesLiveInTheDirectoryTheBackupRulesExclude() {
        assertEquals(File(filesDir, GIT_PRIVATE_DIR_NAME), gitPrivateDir(filesDir))
        assertTrue(
            gitKnownHostsFile(filesDir).path.replace('\\', '/').endsWith("/files/$GIT_PRIVATE_DIR_NAME/known_hosts"),
            gitKnownHostsFile(filesDir).path,
        )
    }

    @Test
    fun TC_55_slotNameCannotLeaveTheStoreDirectory() {
        val store = AndroidSecretStore(dir = File("/data/data/app/files/git"))

        // Имя слота становится именем файла и псевдонимом ключа: путь наружу
        // и разделители в нём отвергаются до первого касания платформы.
        listOf("../evil", "a/b", "", "СЛОТ", "slot.bin", "x".repeat(41)).forEach { slot ->
            assertFailsWith<IllegalArgumentException>("имя слота «$slot» обязано отвергаться") {
                store.stamp(slot)
            }
        }
    }
}
