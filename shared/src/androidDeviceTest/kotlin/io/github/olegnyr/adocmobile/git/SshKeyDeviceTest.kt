package io.github.olegnyr.adocmobile.git

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.Signature
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Генерация SSH-ключа на устройстве — слайсы `SL-16`, `SL-19` и `SL-21` фичи
 * 007-git-sync, `TC-52`, `TC-55`, `TC-61`.
 *
 * Device, потому что хранение опирается на Android Keystore, а генерация — на
 * криптопровайдер, который обязан приехать в APK и завестись на ART: именно
 * эта пара и решается решениями `ADR-015` и `ADR-016`. Логика хранения
 * проверяется без устройства (`SshKeyStoreHostTest`) — здесь настоящие
 * Keystore и настоящая дексованная библиотека.
 * Сети тест не касается — живой прогон против GitLab остаётся ручным.
 */
class SshKeyDeviceTest {

    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val secretsDir = File(filesDir, "devicetest-ssh-secrets")

    private fun secrets(): GitSecretStore =
        AndroidSecretStore(dir = secretsDir, keyAliasPrefix = "devicetest-ssh-key")

    private fun store(): AndroidSshKeyStore = AndroidSshKeyStore(secrets = secrets())

    @AfterTest
    fun cleanUp() {
        runBlocking { store().deleteKey() }
        secrets().clearToken()
        secretsDir.deleteRecursively()
    }

    @Test
    fun TC_52_generatedKeyIsUsableEd25519AndSurvivesRestart() {
        val info = runBlocking { store().generateKey("adoc-mobile тест") }

        assertTrue(info.publicKeyLine.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"), info.publicKeyLine)
        assertEquals("adoc-mobile-тест", info.publicKeyLine.split(" ")[2], "комментарий очищен от пробелов")
        assertTrue(info.fingerprint.startsWith("SHA256:"), "отпечаток для сверки с сервером")

        // «Перезапуск процесса»: новый экземпляр без памяти прежнего.
        val reloaded = assertIs<SshKeyPresence.Present>(runBlocking { store().currentKey() })
        assertEquals(info.publicKeyLine, reloaded.key.publicKeyLine, "ключ переживает перезапуск")
        assertEquals(info.fingerprint, reloaded.key.fingerprint, "отпечаток считается от того же ключа")

        // Пара восстанавливается и годится для подписи — то, ради чего
        // ADR-015 отказался от Keystore.
        val pair = assertNotNull(runBlocking { store().keyPair() }, "пара ключей восстановлена")
        val eddsa = EdDSASecurityProvider()
        val signature = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, eddsa)
        signature.initSign(pair.private)
        signature.update("рукопожатие".encodeToByteArray())
        val signed = signature.sign()
        assertEquals(64, signed.size, "подпись — сырые 64 байта, как требует SSH (не DER, как у Keystore)")

        val verifier = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, eddsa)
        verifier.initVerify(pair.public)
        verifier.update("рукопожатие".encodeToByteArray())
        assertTrue(verifier.verify(signed), "публичная часть соответствует приватной")
    }

    /**
     * Генерация на устройстве идёт библиотечным бэкендом, а не системным
     * алгоритмом (`TC-61`, слайс `SL-21`).
     *
     * Device-половина важна отдельно от host: прогон идёт на Android 16, где
     * системный `Ed25519` *есть*, и молчаливый откат на него был бы незаметен
     * здесь — и отказал бы на `minSdk 26`, где его нет. Кейс краснеет, если
     * вернуть `KeyPairGenerator.getInstance("Ed25519")`: пара приедет из
     * Conscrypt.
     */
    @Test
    fun TC_61_keyComesFromTheBundledBackendNotFromThePlatform() {
        runBlocking { store().generateKey("устройство") }
        val pair = assertNotNull(runBlocking { store().keyPair() })

        assertEquals(
            "net.i2p.crypto.eddsa",
            pair.public.javaClass.`package`?.name,
            "публичная часть — от бэкенда из APK, а не от системного провайдера",
        )
        assertEquals(
            "net.i2p.crypto.eddsa",
            pair.private.javaClass.`package`?.name,
            "и приватная тоже: восстановление пришпилено к тому же провайдеру",
        )
    }

    @Test
    fun TC_52_privateKeyNeverLandsOnDiskInPlaintext() {
        val info = runBlocking { store().generateKey("устройство") }
        val pair = assertNotNull(runBlocking { store().keyPair() })

        // Побайтовый поиск приватного материала по каталогу данных: он лежит
        // только шифртекстом (FR-28, тем же правилом, что токен в TC-30).
        // Игл две: сырой DER и его base64 — прежний тест искал только первую,
        // а на диске материал мог оказаться строкой (находка ревью E4).
        // Порог размера поднят вместо снятия: `readBytes` целиком на дексах
        // профилировщика давал бы OOM, и падение выглядело бы как провал
        // security-кейса (находка ревью SL-19).
        val needles = listOf(pair.private.encoded, base64(pair.private.encoded).encodeToByteArray())
        val dataDir = File(InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo.dataDir)
        val offenders = dataDir.walkTopDown()
            .filter { it.isFile && it.length() in 1..MAX_SCANNED_BYTES }
            .filter { candidate ->
                val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return@filter false
                needles.any { bytes.containsSubArray(it) }
            }
            .map { it.relativeTo(dataDir).path }
            .toList()
        assertTrue(offenders.isEmpty(), "приватный ключ найден открытым текстом: $offenders")

        // Публичная часть, наоборот, читается без расшифровки — её и надо
        // показывать; лежит она открытой частью того же слота.
        val plain = assertNotNull(secrets().readPlain(SSH_KEY_SLOT)).decodeToString()
        assertTrue(plain.startsWith(info.publicKeyLine), plain)
    }

    @Test
    fun TC_52_deleteRemovesTheKeyAndUnreadableKeyIsNotReportedAsAbsent() {
        runBlocking { store().generateKey("устройство") }
        runBlocking { store().deleteKey() }

        assertIs<SshKeyPresence.None>(runBlocking { store().currentKey() }, "после удаления ключа нет")
        assertNull(runBlocking { store().keyPair() }, "и пары тоже")

        // Испорченный слот — это «ключ есть, но не читается»: выдать его за
        // «ключа нет» значило бы стереть рабочую пару без подтверждения.
        runBlocking { store().generateKey("устройство") }
        val file = File(secretsDir, "$SSH_KEY_SLOT.bin")
        file.writeBytes(byteArrayOf(0, 0, 0, 4, 1, 2, 3, 4, 12, 9, 9, 9))
        assertIs<SshKeyPresence.Unreadable>(runBlocking { store().currentKey() })
    }

    @Test
    fun TC_56_slotStampChangesOnEveryWriteAndDiesWithTheKey() {
        // На метке держится кэш фабрики сессий: не меняйся она при замене
        // ключа, транспорт подписывал бы рукопожатие прежней парой.
        runBlocking { store().generateKey("устройство") }
        val first = assertNotNull(runBlocking { store().keyStamp() })

        runBlocking { store().generateKey("устройство") }
        val second = assertNotNull(runBlocking { store().keyStamp() })
        assertTrue(first != second, "перезапись ключа меняет метку слота")

        runBlocking { store().deleteKey() }
        assertNull(runBlocking { store().keyStamp() }, "удалённый ключ метки не имеет")
    }

    @Test
    fun TC_56_keyWithoutItsKeystoreEntryReadsAsUnreadable() {
        runBlocking { store().generateKey("устройство") }

        // Сброс Keystore (смена блокировки экрана, восстановление на другом
        // устройстве) уносит ключ платформы, а файл слота остаётся. Показать
        // это как «ключа нет» значило бы предложить стереть его без
        // подтверждения (находка ревью SL-19).
        java.security.KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
            .deleteEntry("devicetest-ssh-key.$SSH_KEY_SLOT")

        assertIs<SshKeyPresence.Unreadable>(runBlocking { store().currentKey() })
        assertNull(runBlocking { store().keyPair() }, "подписать таким ключом нечего")
    }

    @Test
    fun TC_55_keyAndTokenDoNotDestroyEachOther() {
        secrets().storeToken("gitlab.com", Secret("devicetest-token"))
        val info = runBlocking { store().generateKey("устройство") }

        assertEquals("devicetest-token", secrets().tokenFor("gitlab.com")?.value, "ключ не затёр токен")
        val present = assertIs<SshKeyPresence.Present>(runBlocking { store().currentKey() })
        assertEquals(info.publicKeyLine, present.key.publicKeyLine)

        secrets().storeToken("gitlab.com", Secret("devicetest-token-2"))
        assertNotNull(runBlocking { store().keyPair() }, "повторный ввод токена не уничтожил приватный ключ")

        runBlocking { store().deleteKey() }
        assertEquals("devicetest-token-2", secrets().tokenFor("gitlab.com")?.value, "удаление ключа не тронуло токен")
    }

    private fun base64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private companion object {
        // Крупные файлы (дексы профилировщика) читать целиком нельзя — но и
        // порог должен быть заведомо больше любого нашего файла с секретом.
        const val MAX_SCANNED_BYTES = 64L * 1024 * 1024
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
