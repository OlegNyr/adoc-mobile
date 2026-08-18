package io.github.olegnyr.adocmobile.git

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище секретов Git-слоя (`FR-28`, решение OQ-2): файл в приватном
 * каталоге, шифрованный AES-ключом из Android Keystore.
 *
 * Схема — вариант (а) владельца: Tink не тянем, свой код мал и проходит
 * security-ревью. Ключ генерируется в Keystore неизвлекаемым (AES-256-GCM),
 * наружу выходят только IV и шифртекст; файл без ключа — шум.
 * Токен хранится *в паре с хостом* и выдаётся только ему: единственный
 * токен «для всех origin» утёк бы чужому серверу по его 401
 * (находка security-ревью E2).
 * Plaintext-секрет не попадает ни в настройки, ни в логи, ни в сам файл
 * (`TC-30`: побайтовый поиск по каталогу данных обязан его не найти).
 *
 * Названные отклонения (правило «отступление названо», ревью E2):
 * plaintext живёт в памяти `String`-ом и не затирается — JVM гарантий
 * затирания не даёт, `CharArray`-гимнастика дала бы ложное чувство защиты;
 * ключ без `setUserAuthenticationRequired` — push не должен просить
 * биометрию, решение удобства, пересматривается по боли; без
 * `setUnlockedDeviceRequired` — операции Git запускает пользователь на
 * разблокированном устройстве.
 *
 * Блокирующие вызовы намеренно: единственный потребитель — `JGitSync`,
 * который уже работает на IO-диспетчере.
 */
interface GitSecretStore {

    /** Запомнить токен для [host], перезаписав прежний (`FR-26`: вводится один раз). */
    fun storeToken(host: String, token: Secret)

    /** Токен, сохранённый для [host]; `null` — не вводился, чужой хост или хранилище недоступно. */
    fun tokenFor(host: String): Secret?

    /** Забыть токен: файл и Keystore-ключ удаляются вместе. */
    fun clearToken()
}

/**
 * Реализация на Android Keystore.
 *
 * @param file файл шифртекста в приватном каталоге приложения
 * @param keyAlias псевдоним AES-ключа в `AndroidKeyStore`
 */
class AndroidSecretStore(
    private val file: File,
    private val keyAlias: String = DEFAULT_ALIAS,
) : GitSecretStore {

    override fun storeToken(host: String, token: Secret) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = host.lowercase() + PAYLOAD_SEPARATOR + token.value
        val ciphertext = cipher.doFinal(payload.encodeToByteArray())

        // Атомарная запись: обрыв посреди writeBytes оставил бы обрезанный
        // файл, и прежний токен молча пропал бы (находка ревью E2).
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + ciphertext)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "не удалось заменить файл секретов" }
        }
    }

    override fun tokenFor(host: String): Secret? {
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            val ivSize = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val ciphertext = bytes.copyOfRange(1 + ivSize, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val payload = cipher.doFinal(ciphertext).decodeToString()
            val storedHost = payload.substringBefore(PAYLOAD_SEPARATOR)
            val token = payload.substringAfter(PAYLOAD_SEPARATOR, missingDelimiterValue = "")
            if (storedHost.equals(host, ignoreCase = true) && token.isNotEmpty()) Secret(token) else null
        } catch (_: Exception) {
            // Битый файл, сменившийся ключ (сброс Keystore) или повреждённый
            // тег GCM: токена нет, пользователь введёт заново — штатный путь.
            null
        }
    }

    override fun clearToken() {
        file.delete()
        // Ключ уходит вместе с данными: File.delete на flash не затирает
        // байты, и переживший ключ расшифровал бы восстановленный шифртекст
        // (находка ревью E2).
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
        } catch (_: Exception) {
            // Keystore без записи — уже чисто.
        }
    }

    // synchronized: check-then-generate без замка из двух потоков породил бы
    // два ключа, и шифртекст первого стал бы нечитаем (находка ревью E2).
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "git-secrets"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val PAYLOAD_SEPARATOR = "\n"
    }
}
