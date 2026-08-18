package io.github.olegnyr.adocmobile.git

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилище секретов на Android Keystore (`SL-8` фичи 007-git-sync): `TC-30` —
 * токен переживает новый экземпляр хранилища (перезапуск процесса читает тот
 * же файл тем же Keystore-ключом), а побайтовый поиск по каталогу данных
 * приложения его не находит (`FR-28`).
 *
 * Настоящий `AndroidKeyStore` — потому тест device: на JVM его нет.
 */
class AndroidSecretStoreDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.filesDir, "devicetest-git-secrets.bin")

    private val plaintext = "ghp_devicetest-secret-token-2026"

    private fun store() = AndroidSecretStore(file = file, keyAlias = "devicetest-git-secrets")

    @AfterTest
    fun cleanUp() {
        store().clearToken()
    }

    @Test
    fun TC_30_tokenSurvivesRestartAndNeverTouchesDiskInPlaintext() {
        store().storeToken(HOST, Secret(plaintext))

        // «Перезапуск процесса»: новый экземпляр без памяти прежнего.
        val reloaded = store().tokenFor(HOST)
        assertEquals(plaintext, reloaded?.value, "токен восстановлен из шифртекста (FR-28, TC-30)")

        // Побайтовый поиск по каталогу данных: plaintext не лежит нигде —
        // ни в нашем файле, ни в настройках, ни в чужих кэшах.
        val needle = plaintext.encodeToByteArray()
        val dataDir = File(context.applicationInfo.dataDir)
        val offenders = dataDir.walkTopDown()
            .filter { it.isFile }
            .filter { it.length() in 1..MAX_SCANNED_BYTES }
            .filter { candidate -> candidate.readBytes().containsSubArray(needle) }
            .map { it.relativeTo(dataDir).path }
            .toList()
        assertTrue(offenders.isEmpty(), "секрет найден открытым текстом: $offenders (FR-28)")

        assertTrue(file.isFile && file.length() > 0, "шифртекст при этом существует — хранится не «нигде»")
    }

    @Test
    fun TC_30_clearForgetsTokenAndBrokenFileReadsAsAbsent() {
        store().storeToken(HOST, Secret(plaintext))
        store().clearToken()
        assertNull(store().tokenFor(HOST), "забытый токен не восстанавливается")

        file.writeBytes(byteArrayOf(12, 1, 2, 3))
        assertNull(store().tokenFor(HOST), "битый файл — «токена нет», не падение: пользователь введёт заново")
    }

    private fun ByteArray.containsSubArray(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }

    @Test
    fun TC_30_tokenIsBoundToItsHostAndSurvivesNoTagForgery() {
        store().storeToken(HOST, Secret(plaintext))

        assertNull(
            store().tokenFor("evil.example.org"),
            "токен выдан github.com — чужой хост его не получает (FR-26, security-ревью E2)",
        )

        // Подделка шифртекста: порча байта ломает тег GCM → «токена нет».
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(store().tokenFor(HOST), "битый тег GCM — токена нет, не падение и не мусор")
    }

    private companion object {
        const val HOST = "github.com"
        // Крупные файлы (дексы профилировщика) не сканируются: секрет туда
        // попасть не мог, а чтение сотен мегабайт сделало бы тест минутным.
        const val MAX_SCANNED_BYTES = 4L * 1024 * 1024
    }
}
