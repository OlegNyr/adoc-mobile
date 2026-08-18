package io.github.olegnyr.adocmobile.git

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
 * Генерация и хранение SSH-ключа на Android (`FR-27`, `SL-16`, `SL-19`).
 *
 * Ключ ed25519 создаётся обычным провайдером JCA, приватная часть уходит в
 * хранилище секретов фичи (файл + AES-GCM-ключ из Keystore) — решение
 * xref:../../adr/adr-015-ssh-key-storage.adoc[ADR-015] по итогам спайка:
 * Keystore подписывает ed25519 только в DER и только с `DIGEST_NONE`, а SSH
 * нужны сырые 64 байта, и на `minSdk 26` опереться на Keystore нельзя.
 *
 * *Обе половины пары лежат в одном слоте и пишутся одной записью* (`TC-56`,
 * слайс `SL-19`): публичная строка — открытой частью слота, приватная —
 * шифртекстом. Прежняя схема писала их в два места по очереди, и отказ на
 * втором шаге оставлял новую приватную часть со старой публичной строкой:
 * сервер получал один ключ, подпись шла другим, и отказ аутентификации ничем
 * не объяснялся. Порядок шагов такую пару не спасает — спасает одна запись.
 *
 * Приватный ключ не попадает ни в логи, ни в возвращаемые типы: наружу
 * выходит только [SshKeyInfo] с публичной частью. Показ ключа приватную часть
 * не расшифровывает вовсе — открытая часть слота читается без ключа Keystore;
 * там, где расшифровка нужна (сборка пары для транспорта), массив затирается
 * сразу после использования.
 *
 * `android.*`-API здесь нет намеренно: платформенное в классе — только
 * хранилище секретов, поэтому логика проверяется host-тестами без устройства
 * (`SshKeyStoreHostTest`), а устройство закрывает Keystore и криптопровайдер
 * (`TC-52`).
 *
 * @param secrets хранилище секретов; ключ лежит в нём слотом [SSH_KEY_SLOT]
 */
class AndroidSshKeyStore(
    private val secrets: GitSecretStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SshKeyStore, SshKeyPairs {

    /** Состояние ключа устройства — без расшифровки приватной части. */
    override suspend fun currentKey(): SshKeyPresence = withContext(io) {
        secrets.stamp(SSH_KEY_SLOT) ?: return@withContext SshKeyPresence.None
        val stored = secrets.readPlain(SSH_KEY_SLOT)?.let(::parsePublicPart)
            ?: return@withContext SshKeyPresence.Unreadable
        SshKeyPresence.Present(stored)
    }

    /**
     * Ключ с проверенной целостностью (`FR-27`).
     *
     * Открытая часть слота тегом GCM заверена, но её показ тег не проверяет —
     * для этого нужна расшифровка. Здесь она и делается: секретная часть
     * поднимается, тег проверяется вместе с открытой, массив затирается.
     * Строка, которую человек вставит в настройки сервера, обязана пройти
     * этот путь — подменённая иначе доехала бы до буфера обмена.
     */
    override suspend fun verifiedKey(): SshKeyInfo? = withContext(io) {
        // Одно чтение на обе половины: раздельные дали бы окно, в котором
        // подменённая открытая часть уезжает наружу «проверенной» — тег
        // сошёлся бы над другим содержимым файла (второй раунд ревью).
        val (plain, secret) = secrets.readVerified(SSH_KEY_SLOT) ?: return@withContext null
        secret.fill(0)
        parsePublicPart(plain)
    }

    /**
     * Создать ключ ed25519, заменив прежний (`FR-27`).
     *
     * Одна запись на обе половины: отказ оставляет прежний ключ целым, а не
     * пару из разных ключей.
     */
    override suspend fun generateKey(comment: String): SshKeyInfo = withContext(io) {
        val generator = KeyPairGenerator.getInstance(ALGORITHM)
        val pair = generator.run {
            initialize(ED25519_KEY_SIZE, SecureRandom())
            generateKeyPair()
        }

        val line = openSshPublicKeyLine(rawEd25519PublicKey(pair.public.encoded), comment)
        val createdAt = clock()
        // Приватная часть — в PKCS#8, как её отдаёт провайдер: разбирать её
        // здесь незачем, транспорту она уходит целиком.
        val privateKey = pair.private.encoded
        try {
            secrets.store(
                slot = SSH_KEY_SLOT,
                secret = privateKey,
                plain = publicPart(line, createdAt),
            )
        } finally {
            privateKey.fill(0)
        }

        SshKeyInfo(publicKeyLine = line, fingerprint = fingerprintOf(line), createdAtEpochMs = createdAt)
    }

    /** Забыть ключ целиком: обе половины (`FR-28`). Токен при этом цел (`TC-55`). */
    override suspend fun deleteKey() = withContext(io) {
        secrets.clear(SSH_KEY_SLOT)
    }

    /**
     * Метка текущего ключа для транспорта: меняется при каждой записи и
     * исчезает с удалением. Позволяет держать фабрику сессий, не поднимая
     * приватную часть в память на каждую операцию.
     */
    override suspend fun keyStamp(): String? = withContext(io) { secrets.stamp(SSH_KEY_SLOT) }

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
    override suspend fun keyPair(): KeyPair? = withContext(io) {
        // Тем же одним чтением: публичная половина пары обязана происходить из
        // того же содержимого файла, что и приватная.
        val (plain, stored) = secrets.readVerified(SSH_KEY_SLOT) ?: return@withContext null
        val line = parsePublicPart(plain)?.publicKeyLine ?: return@withContext null
        try {
            val factory = KeyFactory.getInstance(ALGORITHM)
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(stored))

            val body = decodeSshBase64(line.split(" ").getOrNull(1).orEmpty()) ?: return@withContext null
            val raw = body.copyOfRange(body.size - ED25519_RAW_SIZE, body.size)
            val publicKey = factory.generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + raw))

            KeyPair(publicKey, privateKey)
        } catch (_: Exception) {
            null
        } finally {
            // Расшифрованный приватный ключ не остаётся в куче ждать дампа.
            stored.fill(0)
        }
    }

    /** Открытая часть слота: строка ключа и время создания, по строке на поле. */
    private fun publicPart(line: String, createdAt: Long): ByteArray =
        "$line\n$createdAt".encodeToByteArray()

    private fun parsePublicPart(plain: ByteArray): SshKeyInfo? = try {
        val text = plain.decodeToString()
        val line = text.substringBefore('\n').trim()
        val createdAt = text.substringAfter('\n', missingDelimiterValue = "").trim().toLong()
        if (line.split(" ").size < 2) null else SshKeyInfo(line, fingerprintOf(line), createdAt)
    } catch (_: Exception) {
        null
    }

    private fun fingerprintOf(line: String): String {
        val body = decodeSshBase64(line.split(" ").getOrNull(1).orEmpty()) ?: return UNKNOWN_FINGERPRINT
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
        const val UNKNOWN_FINGERPRINT = "SHA256:—"

        /** Неизменный заголовок `SubjectPublicKeyInfo` для ed25519 (RFC 8410). */
        val ED25519_SPKI_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
    }
}

/**
 * Источник пары ключей для SSH-транспорта.
 *
 * Отдельный интерфейс, а не конкретный класс: транспорту нужны ровно две
 * вещи — метка текущего ключа и сама пара, — и на этом шве он проверяется
 * без Android Keystore.
 */
interface SshKeyPairs {

    /** Метка текущего ключа; `null` — ключа нет. Меняется при замене ключа. */
    suspend fun keyStamp(): String?

    /** Пара ключей или `null` — ключа нет либо он не читается. */
    suspend fun keyPair(): KeyPair?
}
