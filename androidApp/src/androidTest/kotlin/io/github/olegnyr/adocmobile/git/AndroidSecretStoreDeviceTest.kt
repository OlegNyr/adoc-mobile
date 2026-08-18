package io.github.olegnyr.adocmobile.git

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Хранилище секретов на Android Keystore (`SL-8` и `SL-19` фичи 007-git-sync):
 * `TC-30` — токен переживает новый экземпляр хранилища (перезапуск процесса
 * читает тот же файл тем же Keystore-ключом), а побайтовый поиск по каталогу
 * данных приложения его не находит (`FR-28`); `TC-55` — слоты независимы.
 *
 * Настоящий `AndroidKeyStore` — потому тест device: на JVM его нет.
 */
class AndroidSecretStoreDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.filesDir, "devicetest-git-secrets")

    private val plaintext = "ghp_devicetest-secret-token-2026"

    private fun store() = AndroidSecretStore(dir = dir, keyAliasPrefix = "devicetest-git-secrets")

    @AfterTest
    fun cleanUp() {
        store().clearToken()
        store().clear(SSH_KEY_SLOT)
        dir.deleteRecursively()
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
            .filter { it.isFile && it.length() in 1..MAX_SCANNED_BYTES }
            .filter { candidate ->
                val bytes = runCatching { candidate.readBytes() }.getOrNull() ?: return@filter false
                bytes.containsSubArray(needle)
            }
            .map { it.relativeTo(dataDir).path }
            .toList()
        assertTrue(offenders.isEmpty(), "секрет найден открытым текстом: $offenders (FR-28)")

        val file = File(dir, "$TOKEN_SLOT.bin")
        assertTrue(file.isFile && file.length() > 0, "шифртекст при этом существует — хранится не «нигде»")
    }

    @Test
    fun TC_30_clearForgetsTokenAndBrokenFileReadsAsAbsent() {
        store().storeToken(HOST, Secret(plaintext))
        store().clearToken()
        assertNull(store().tokenFor(HOST), "забытый токен не восстанавливается")

        File(dir, "$TOKEN_SLOT.bin").writeBytes(byteArrayOf(0, 0, 0, 0, 12, 1, 2, 3))
        assertNull(store().tokenFor(HOST), "битый файл — «токена нет», не падение: пользователь введёт заново")
    }

    @Test
    fun TC_30_tokenIsBoundToItsHostAndSurvivesNoTagForgery() {
        store().storeToken(HOST, Secret(plaintext))

        assertNull(
            store().tokenFor("evil.example.org"),
            "токен выдан github.com — чужой хост его не получает (FR-26, security-ревью E2)",
        )

        // Подделка шифртекста: порча байта ломает тег GCM → «токена нет».
        val file = File(dir, "$TOKEN_SLOT.bin")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(store().tokenFor(HOST), "битый тег GCM — токена нет, не падение и не мусор")
    }

    @Test
    fun TC_55_slotsAreIndependentOfEachOther() {
        // Прежнее однослотовое хранилище держало токен и приватный SSH-ключ в
        // одном файле под одним псевдонимом Keystore: запись одного затирала
        // другой, а очистка делала второй нечитаемым (блокер ревью E4).
        val secret = ByteArray(48) { (it + 1).toByte() }
        store().storeToken(HOST, Secret(plaintext))
        store().store(SSH_KEY_SLOT, secret = secret.copyOf(), plain = "ssh-ed25519 AAAA тест".encodeToByteArray())

        assertEquals(plaintext, store().tokenFor(HOST)?.value, "запись ключа не тронула токен")
        assertEquals(
            secret.toList(),
            store().readSecret(SSH_KEY_SLOT)?.toList(),
            "запись токена не тронула ключ",
        )

        store().storeToken(HOST, Secret("второй-токен"))
        assertEquals(
            secret.toList(),
            store().readSecret(SSH_KEY_SLOT)?.toList(),
            "повторный ввод токена не разрушает приватный ключ",
        )

        store().clearToken()
        assertNull(store().tokenFor(HOST))
        assertEquals(
            secret.toList(),
            store().readSecret(SSH_KEY_SLOT)?.toList(),
            "очистка токена оставляет ключ читаемым — Keystore-ключ у каждого слота свой",
        )

        store().clear(SSH_KEY_SLOT)
        assertNull(store().readSecret(SSH_KEY_SLOT))
        assertNull(store().stamp(SSH_KEY_SLOT), "слота больше нет")
    }

    @Test
    fun TC_56_stampChangesOnEveryWrite() {
        // На метке держится кэш фабрики сессий транспорта: одинаковая метка
        // после перезаписи означала бы работу прежним ключом.
        store().storeToken(HOST, Secret(plaintext))
        val first = store().stamp(TOKEN_SLOT)
        store().storeToken(HOST, Secret(plaintext))
        val second = store().stamp(TOKEN_SLOT)

        assertTrue(first != null && second != null && first != second, "$first / $second")
        store().clearToken()
        assertNull(store().stamp(TOKEN_SLOT), "у очищенного слота метки нет")
    }

    @Test
    fun TC_55_plainPartIsReadableWithoutTheSecretAndDiesWithTheKeystoreEntry() {
        val plain = "ssh-ed25519 AAAAtест устройство".encodeToByteArray()
        store().store(SSH_KEY_SLOT, secret = ByteArray(32) { 7 }, plain = plain)

        assertEquals(plain.toList(), store().readPlain(SSH_KEY_SLOT)?.toList(), "открытая часть читается как есть")

        // Без своего ключа в Keystore слот мёртв целиком: отдавать открытую
        // половину значило бы показать экрану ключ, которым нечего подписать
        // (находка ревью SL-19).
        java.security.KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
            .deleteEntry("devicetest-git-secrets.$SSH_KEY_SLOT")
        assertNull(store().readPlain(SSH_KEY_SLOT), "слот без ключа платформы не отдаёт и открытую часть")

        store().store(SSH_KEY_SLOT, secret = ByteArray(32) { 7 }, plain = plain)

        // Открытая часть заверена тем же тегом: подмена ломает расшифровку
        // секретной — пара половин связана криптографически.
        val file = File(dir, "$SSH_KEY_SLOT.bin")
        val bytes = file.readBytes()
        bytes[6] = (bytes[6] + 1).toByte()
        file.writeBytes(bytes)
        assertNull(store().readSecret(SSH_KEY_SLOT), "подменённая открытая часть не даёт прочитать секретную")
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

    private companion object {
        const val HOST = "github.com"

        // Порог поднят, а не снят: `readBytes` целиком на дексах
        // профилировщика давал бы OOM, и падение выглядело бы как провал
        // security-кейса (находка ревью SL-19). Любой наш файл с секретом
        // меньше на несколько порядков.
        const val MAX_SCANNED_BYTES = 64L * 1024 * 1024
    }
}
