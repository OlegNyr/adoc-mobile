package io.github.olegnyr.adocmobile.git

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранение SSH-ключа — слайс `SL-19` фичи 007-git-sync, `TC-55`, `TC-56`.
 *
 * Гоняется на JVM без устройства: после `SL-19` `AndroidSshKeyStore` не
 * трогает ни одного `android.*`-API — платформенное в нём только хранилище
 * секретов, которое здесь подделано. Настоящий Android Keystore проверяется
 * device-кейсами того же ряда (`TC-52`, `TC-55`).
 *
 * Ключевое свойство слайса: обе половины ключа лежат в *одном* слоте и
 * пишутся одной записью — пары «новая приватная часть со старой публичной»
 * не существует по построению, а не по удачному порядку шагов.
 */
class SshKeyStoreHostTest {

    private val secrets = FakeSecretStore()

    private fun store(): AndroidSshKeyStore = AndroidSshKeyStore(secrets = secrets)

    @Test
    fun TC_56_bothHalvesComeFromOneWriteAndMatchEachOther() {
        val created = runBlocking { store().generateKey("устройство") }
        assertEquals(1, secrets.writes, "обе половины ушли одной записью, а не двумя шагами")
        assertEquals(setOf(SSH_KEY_SLOT), secrets.touchedSlots, "ключ живёт в своём слоте и ничей больше не трогает")

        // «Перезапуск процесса»: новый экземпляр без памяти прежнего.
        val present = assertIs<SshKeyPresence.Present>(runBlocking { store().currentKey() })
        assertEquals(created.publicKeyLine, present.key.publicKeyLine)
        assertEquals(created.fingerprint, present.key.fingerprint)

        assertPairMatchesLine(present.key.publicKeyLine)
    }

    @Test
    fun TC_56_failedWriteLeavesThePreviousKeyIntact() {
        val first = runBlocking { store().generateKey("прежнее устройство") }

        secrets.failNextStore = true
        assertFails { runBlocking { store().generateKey("новое устройство") } }

        val present = assertIs<SshKeyPresence.Present>(runBlocking { store().currentKey() })
        assertEquals(first.publicKeyLine, present.key.publicKeyLine, "отказ записи не оставил половинчатой пары")
        assertPairMatchesLine(present.key.publicKeyLine)
    }

    @Test
    fun TC_56_presenceIsCheckedWithoutDecryptingThePrivatePart() {
        assertIs<SshKeyPresence.None>(runBlocking { store().currentKey() })
        assertEquals(0, secrets.secretReads, "пустое хранилище не расшифровывают вовсе")

        runBlocking { store().generateKey("устройство") }
        secrets.secretReads = 0

        assertIs<SshKeyPresence.Present>(runBlocking { store().currentKey() })
        assertEquals(0, secrets.secretReads, "показ ключа не поднимает приватную часть в память")
    }

    @Test
    fun TC_56_decodedPrivateBytesAreWipedAfterUse() {
        runBlocking { store().generateKey("устройство") }
        val stored = assertNotNull(secrets.lastStoredSecret, "приватная часть уходила в хранилище")
        assertTrue(stored.all { it == 0.toByte() }, "массив, отданный хранилищу, затёрт после записи")

        assertNotNull(runBlocking { store().keyPair() })
        val read = assertNotNull(secrets.lastReadSecret, "приватная часть поднималась из хранилища")
        assertTrue(read.all { it == 0.toByte() }, "расшифрованный массив затёрт после сборки пары")
    }

    @Test
    fun TC_56_unreadableKeyIsNotReportedAsAbsent() {
        runBlocking { store().generateKey("устройство") }
        secrets.corruptPlain(SSH_KEY_SLOT)

        assertIs<SshKeyPresence.Unreadable>(
            runBlocking { store().currentKey() },
            "ключ на устройстве есть — «ключа нет» здесь означало бы уничтожение без подтверждения",
        )
    }

    @Test
    fun TC_56_copyPathVerifiesTheKeyWhileTheDisplayPathDoesNot() {
        val created = runBlocking { store().generateKey("устройство") }

        // Показ открытую часть не заверяет — это цена дешёвого входа на экран.
        secrets.corruptPlain(SSH_KEY_SLOT, "ssh-ed25519 AAAAчужой ключ\n1")
        val shown = assertIs<SshKeyPresence.Present>(runBlocking { store().currentKey() })
        assertTrue(shown.key.publicKeyLine != created.publicKeyLine, "подмена на экране не видна")

        // А вот путь «скопировать» её ловит: тег GCM заверяет обе половины.
        assertNull(
            runBlocking { store().verifiedKey() },
            "подменённая строка не доезжает до буфера обмена",
        )
    }

    @Test
    fun TC_55_keyLivesInItsOwnSlotAndDoesNotTouchTheToken() {
        secrets.storeToken("gitlab.com", Secret("токен-для-проверки"))

        runBlocking { store().generateKey("устройство") }
        assertEquals("токен-для-проверки", secrets.tokenFor("gitlab.com")?.value, "ключ не затёр токен")

        runBlocking { store().deleteKey() }
        assertEquals("токен-для-проверки", secrets.tokenFor("gitlab.com")?.value, "удаление ключа не тронуло токен")
        assertIs<SshKeyPresence.None>(runBlocking { store().currentKey() })
    }

    /**
     * Публичная часть, показанная экраном, обязана соответствовать приватной:
     * подпись парой проверяется ключом, собранным *из строки на экране*.
     *
     * Провайдер — библиотечный (`ADR-016`), как и в продуктовом коде: системный
     * `Ed25519` чужую пару не принимает вовсе, а на `minSdk 26` его и нет.
     * Независимость проверки от продуктового кода держится не на провайдере, а
     * на том, что публичный ключ пересобирается здесь *из строки экрана*.
     */
    private fun assertPairMatchesLine(publicKeyLine: String) {
        val pair = assertNotNull(runBlocking { store().keyPair() }, "пара ключей собирается")
        val raw = assertNotNull(decodeSshBase64(publicKeyLine.split(" ")[1])).takeLast(32).toByteArray()
        val spki = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + raw
        val eddsa = EdDSASecurityProvider()
        val shown = KeyFactory.getInstance(EdDSASecurityProvider.PROVIDER_NAME, eddsa)
            .generatePublic(X509EncodedKeySpec(spki))

        val signature = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, eddsa)
        signature.initSign(pair.private)
        signature.update("рукопожатие".encodeToByteArray())
        val signed = signature.sign()

        val verifier = Signature.getInstance(EdDSAEngine.SIGNATURE_ALGORITHM, eddsa)
        verifier.initVerify(shown)
        verifier.update("рукопожатие".encodeToByteArray())
        assertTrue(verifier.verify(signed), "строка на экране — от того же ключа, которым подписывает транспорт")
    }

}
