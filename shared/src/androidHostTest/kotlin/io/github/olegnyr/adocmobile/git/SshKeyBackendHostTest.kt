package io.github.olegnyr.adocmobile.git

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.signature.BuiltinSignatures
import org.apache.sshd.common.util.security.SecurityUtils

/**
 * Бэкенд EdDSA — слайс `SL-21` фичи 007-git-sync, `TC-60`, `TC-61`.
 *
 * До слайса путь SSH был разомкнут в самом конце: ключ генерировался,
 * показывался и хранился, но предъявить его серверу было нечем — MINA sshd
 * без криптографического бэкенда EdDSA ed25519 не знает
 * (`SecurityUtils.isEDDSACurveSupported()` возвращал `false`, а
 * `PublicKeyEntry.toString` падал с `Cannot retrieve decoder for key=EdDSA`).
 * Решение — xref:../../adr/adr-016-eddsa-backend.adoc[ADR-016].
 *
 * Здесь проверяется *стык*, а не библиотека: пара, собранная нашим
 * хранилищем, обязана быть той, которую sshd умеет закодировать в проводной
 * формат и которой умеет подписать рукопожатие. Оба свойства красные, если
 * зависимость убрать из каталога либо если генерация вернётся к системному
 * алгоритму `Ed25519`: ключ Conscrypt/SunEC для бэкенда sshd чужой.
 */
class SshKeyBackendHostTest {

    private val secrets = FakeSecretStore()

    private fun store(): AndroidSshKeyStore = AndroidSshKeyStore(secrets = secrets)

    /**
     * Бэкенд подключён и sshd действительно умеет наш ключ: кодирует его в
     * проводной формат, считает тот же отпечаток и подписывает им.
     */
    @Test
    fun TC_60_sshdEncodesAndSignsWithTheGeneratedKey() {
        assertTrue(
            SecurityUtils.isEDDSACurveSupported(),
            "без бэкенда EdDSA sshd не знает ed25519 — предъявить ключ серверу нечем",
        )
        assertTrue(BuiltinSignatures.ed25519.isSupported, "подпись ssh-ed25519 доступна транспорту")

        val info = runBlocking { store().generateKey("устройство") }
        val pair = assertNotNull(runBlocking { store().keyPair() }, "пара для транспорта собирается")

        // То, что уйдёт серверу в рукопожатии, — ровно то, что человек вставил
        // в настройки GitLab: тип и base64 без комментария.
        val shown = info.publicKeyLine.split(" ").take(2).joinToString(" ")
        assertEquals(shown, PublicKeyEntry.toString(pair.public), "sshd кодирует ключ той же строкой, что показал экран")

        assertEquals(
            info.fingerprint,
            KeyUtils.getFingerPrint(BuiltinDigests.sha256, pair.public),
            "наш отпечаток совпадает с тем, что считает sshd, — и значит с публикацией сервера",
        )

        val payload = "рукопожатие".encodeToByteArray()
        val signer = BuiltinSignatures.ed25519.create()
        signer.initSigner(null, pair.private)
        signer.update(null, payload)
        val signed = signer.sign(null)

        val verifier = BuiltinSignatures.ed25519.create()
        verifier.initVerifier(null, pair.public)
        verifier.update(null, payload)
        assertTrue(verifier.verify(null, signed), "sshd подписывает и проверяет нашей парой — рукопожатие состоится")
    }

    /**
     * Генерация не опирается на версию платформы (`FR-27`, `minSdk 26`).
     *
     * Прямой проверки «работает на API 26» нет и быть не может: прогон идёт на
     * JVM, device-прогон — на Android 16. Проверяется наблюдаемое следствие
     * решения: ключ производит *библиотечный* провайдер, приезжающий в APK,
     * а не системный алгоритм `Ed25519`, которого на `minSdk 26` нет.
     * Вернуть `KeyPairGenerator.getInstance("Ed25519")` — и кейс краснеет:
     * на JDK 17 пара приедет из `sun.security.ec`.
     */
    @Test
    fun TC_61_keyComesFromTheBundledBackendNotFromThePlatform() {
        runBlocking { store().generateKey("устройство") }
        val pair = assertNotNull(runBlocking { store().keyPair() })

        assertEquals(
            EDDSA_BACKEND_PACKAGE,
            pair.public.javaClass.packageName,
            "публичная часть собрана библиотечным провайдером, а не системным",
        )
        assertEquals(
            EDDSA_BACKEND_PACKAGE,
            pair.private.javaClass.packageName,
            "и приватная тоже: KeyFactory восстановления пришпилен к тому же провайдеру",
        )
    }

    private companion object {
        /** Пакет бэкенда из xref:../../adr/adr-016-eddsa-backend.adoc[ADR-016]. */
        const val EDDSA_BACKEND_PACKAGE = "net.i2p.crypto.eddsa"
    }
}
