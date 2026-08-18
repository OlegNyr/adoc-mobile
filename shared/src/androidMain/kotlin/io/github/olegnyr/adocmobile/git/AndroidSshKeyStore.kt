package io.github.olegnyr.adocmobile.git

import android.os.Build
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Генерация и хранение SSH-ключа на Android (`FR-27`, `SL-16`).
 *
 * Ключ ed25519 создаётся обычным провайдером JCA, приватная часть уходит в
 * хранилище секретов фичи (файл + AES-GCM-ключ из Keystore) — решение
 * xref:../../adr/adr-015-ssh-key-storage.adoc[ADR-015] по итогам спайка:
 * Keystore подписывает ed25519 только в DER и только с `DIGEST_NONE`, а SSH
 * нужны сырые 64 байта, и на `minSdk 26` опереться на Keystore нельзя.
 *
 * Приватный ключ не попадает ни в логи, ни в возвращаемые типы: наружу
 * выходит только [SshKeyInfo] с публичной частью. Публичная часть хранится
 * рядом отдельной записью — чтобы показать её на экране, не расшифровывая
 * приватную.
 *
 * @param secrets хранилище секретов; ключ лежит в нём под своим именем хоста
 * @param publicKeyFile файл публичной части в приватном каталоге приложения
 */
class AndroidSshKeyStore(
    private val secrets: GitSecretStore,
    private val publicKeyFile: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val deviceName: () -> String = { "${Build.MANUFACTURER} ${Build.MODEL}".trim() },
) : SshKeyStore {

    /** Ключ устройства или `null`, если он не создавался. */
    override suspend fun currentKey(): SshKeyInfo? = withContext(io) {
        if (!publicKeyFile.isFile) return@withContext null
        // Ключ считается существующим, только когда на месте обе половины:
        // публичная строка без приватной части бесполезна и вводила бы
        // пользователя в заблуждение («ключ есть», а подписать нечем).
        if (secrets.tokenFor(PRIVATE_KEY_SLOT) == null) return@withContext null
        val line = publicKeyFile.readText().trim()
        if (line.isEmpty()) return@withContext null
        SshKeyInfo(
            publicKeyLine = line,
            fingerprint = fingerprintOf(line),
            createdAtEpochMs = publicKeyFile.lastModified(),
        )
    }

    /**
     * Создать ключ ed25519, заменив прежний (`FR-27`).
     *
     * Порядок важен: сначала приватная часть в хранилище, затем публичная на
     * диск. Обрыв между шагами оставляет состояние «приватный есть, публичной
     * нет», которое [currentKey] честно читает как «ключа нет» — лучше, чем
     * обратное, где экран показывал бы ключ, которым нечего подписать.
     */
    override suspend fun generateKey(comment: String): SshKeyInfo = withContext(io) {
        val generator = KeyPairGenerator.getInstance(ALGORITHM)
        val pair = generator.run {
            initialize(ED25519_KEY_SIZE, SecureRandom())
            generateKeyPair()
        }

        // Приватная часть — в PKCS#8, как её отдаёт провайдер: расшифровывать
        // и разбирать её здесь незачем, транспорту она уходит целиком.
        val privateKey = Secret(pair.private.encoded.encodeBase64())
        secrets.storeToken(PRIVATE_KEY_SLOT, privateKey)

        val line = openSshPublicKeyLine(rawEd25519PublicKey(pair.public.encoded), comment)
        publicKeyFile.parentFile?.mkdirs()
        publicKeyFile.writeText(line)

        SshKeyInfo(
            publicKeyLine = line,
            fingerprint = fingerprintOf(line),
            createdAtEpochMs = clock(),
        )
    }

    /** Забыть ключ целиком: обе половины (`FR-28`). */
    override suspend fun deleteKey() = withContext(io) {
        publicKeyFile.delete()
        secrets.clearToken()
        Unit
    }

    /**
     * Пара ключей для транспорта; `null` — ключа нет или он не читается.
     *
     * Восстановление живёт здесь, а не в транспорте: приватный материал не
     * должен покидать хранилище даже внутри платформенного кода — наружу
     * уходит уже готовый `KeyPair`, и только ему (`ADR-015`).
     *
     * Публичная часть собирается из сырых 32 байт добавлением
     * фиксированного заголовка `SubjectPublicKeyInfo` для ed25519: он
     * неизменен, и разбирать ASN.1 ради него незачем.
     */
    internal suspend fun keyPair(): KeyPair? = withContext(io) {
        val stored = secrets.tokenFor(PRIVATE_KEY_SLOT)?.value ?: return@withContext null
        if (!publicKeyFile.isFile) return@withContext null
        try {
            val factory = KeyFactory.getInstance(ALGORITHM)
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(stored.decodeBase64()))

            val line = publicKeyFile.readText().trim()
            val body = line.split(" ").getOrNull(1)?.decodeBase64() ?: return@withContext null
            val raw = body.copyOfRange(body.size - ED25519_RAW_SIZE, body.size)
            val publicKey = factory.generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + raw))

            KeyPair(publicKey, privateKey)
        } catch (_: Exception) {
            null
        }
    }

    private fun fingerprintOf(line: String): String {
        val encoded = line.split(" ").getOrNull(1).orEmpty()
        val body = encoded.decodeBase64()
        // Сырые 32 байта лежат за двумя length-prefixed полями заголовка.
        val raw = body.copyOfRange(body.size - ED25519_RAW_SIZE, body.size)
        return sshKeyFingerprint(raw)
    }

    /**
     * Сырые 32 байта из X.509-обёртки публичного ключа.
     *
     * `SubjectPublicKeyInfo` для ed25519 всегда 44 байта: 12 байт заголовка
     * с идентификатором алгоритма и 32 байта ключа. Поэтому хвост берётся
     * по длине — разбирать ASN.1 ради фиксированной структуры незачем.
     */
    private fun rawEd25519PublicKey(encoded: ByteArray): ByteArray =
        encoded.copyOfRange(encoded.size - ED25519_RAW_SIZE, encoded.size)

    private companion object {
        const val ALGORITHM = "Ed25519"
        const val ED25519_KEY_SIZE = 255
        const val ED25519_RAW_SIZE = 32

        /** Имя записи приватного ключа в хранилище секретов — не хост, а слот. */
        const val PRIVATE_KEY_SLOT = "ssh-private-key"

        /** Неизменный заголовок `SubjectPublicKeyInfo` для ed25519 (RFC 8410). */
        val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
    }
}

/** Base64 без переводов строк — для хранения приватной части строкой. */
private fun ByteArray.encodeBase64(): String =
    android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

private fun String.decodeBase64(): ByteArray =
    android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
