package io.github.olegnyr.adocmobile.git

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Генерация SSH-ключа на устройстве — слайс `SL-16` фичи 007-git-sync,
 * `TC-52` (дозаявлен).
 *
 * Device, потому что генерация опирается на криптопровайдер системы и на
 * Android Keystore (через хранилище секретов), а не на подделку: именно эта
 * пара и решается решением `ADR-015`.
 * Сети тест не касается — живой прогон против GitLab остаётся ручным.
 */
class SshKeyDeviceTest {

    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val secretsFile = File(filesDir, "devicetest-ssh-secrets.bin")
    private val publicKeyFile = File(filesDir, "devicetest-ssh-key.pub")

    private fun store(): AndroidSshKeyStore = AndroidSshKeyStore(
        secrets = AndroidSecretStore(file = secretsFile, keyAlias = "devicetest-ssh-key"),
        publicKeyFile = publicKeyFile,
    )

    @AfterTest
    fun cleanUp() {
        runBlocking { store().deleteKey() }
        secretsFile.delete()
        publicKeyFile.delete()
    }

    @Test
    fun TC_52_generatedKeyIsUsableEd25519AndSurvivesRestart() {
        val info = runBlocking { store().generateKey("adoc-mobile тест") }

        assertTrue(info.publicKeyLine.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"), info.publicKeyLine)
        assertEquals("adoc-mobile-тест", info.publicKeyLine.split(" ")[2], "комментарий очищен от пробелов")
        assertTrue(info.fingerprint.startsWith("SHA256:"), "отпечаток для сверки с сервером")

        // «Перезапуск процесса»: новый экземпляр без памяти прежнего.
        val reloaded = assertNotNull(runBlocking { store().currentKey() }, "ключ переживает перезапуск")
        assertEquals(info.publicKeyLine, reloaded.publicKeyLine)
        assertEquals(info.fingerprint, reloaded.fingerprint, "отпечаток считается от того же ключа")

        // Пара восстанавливается и годится для подписи — то, ради чего
        // ADR-015 отказался от Keystore.
        val pair = assertNotNull(runBlocking { store().keyPair() }, "пара ключей восстановлена")
        val signature = java.security.Signature.getInstance("Ed25519")
        signature.initSign(pair.private)
        signature.update("рукопожатие".encodeToByteArray())
        val signed = signature.sign()
        assertEquals(64, signed.size, "подпись — сырые 64 байта, как требует SSH (не DER, как у Keystore)")

        val verifier = java.security.Signature.getInstance("Ed25519")
        verifier.initVerify(pair.public)
        verifier.update("рукопожатие".encodeToByteArray())
        assertTrue(verifier.verify(signed), "публичная часть соответствует приватной")
    }

    @Test
    fun TC_52_privateKeyNeverLandsOnDiskInPlaintext() {
        val info = runBlocking { store().generateKey("устройство") }
        val pair = assertNotNull(runBlocking { store().keyPair() })

        // Побайтовый поиск приватного материала по каталогу данных: он лежит
        // только шифртекстом (FR-28, тем же правилом, что токен в TC-30).
        val needle = pair.private.encoded
        val dataDir = File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.dataDir)
        val offenders = dataDir.walkTopDown()
            .filter { it.isFile && it.length() in 1..(4L * 1024 * 1024) }
            .filter { candidate -> candidate.readBytes().containsSubArray(needle) }
            .map { it.relativeTo(dataDir).path }
            .toList()
        assertTrue(offenders.isEmpty(), "приватный ключ найден открытым текстом: $offenders")

        // Публичная часть, наоборот, лежит открыто — её и надо показывать.
        assertTrue(publicKeyFile.readText().trim() == info.publicKeyLine)
    }

    @Test
    fun TC_52_deleteRemovesBothHalvesAndHalfStateReadsAsNoKey() {
        runBlocking { store().generateKey("устройство") }
        runBlocking { store().deleteKey() }

        assertNull(runBlocking { store().currentKey() }, "после удаления ключа нет")
        assertNull(runBlocking { store().keyPair() }, "и пары тоже")

        // Половинчатое состояние (публичная часть без приватной) читается
        // как «ключа нет»: иначе экран показывал бы ключ, которым нечего
        // подписать.
        publicKeyFile.writeText("ssh-ed25519 AAAAfake устройство")
        assertNull(runBlocking { store().currentKey() }, "публичная часть без приватной — не ключ")
    }

    private fun ByteArray.containsSubArray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
