package io.github.olegnyr.adocmobile.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Формат публичного ключа и отпечаток — слайс `SL-16` фичи 007-git-sync,
 * `TC-50` (дозаявлен): пользователь копирует строку в настройки сервера, а
 * отпечаток сверяет с тем, что показывает сервер.
 *
 * Логика в `commonMain` и проверяется без устройства: кодирование ключа —
 * чистые байты, и держать его в платформенной половине значило бы писать
 * заново для iOS (`NFR-2`, `NFR-10`).
 *
 * Эталон взят из спецификации: `ssh-ed25519` (RFC 8709), формат строки —
 * `тип пробел base64 пробел комментарий` (`sshd(8)`, `AUTHORIZED_KEYS`);
 * отпечаток — `SHA256:` плюс base64 без набивки (`ssh-keygen -l`).
 */
class SshPublicKeyTest {

    // 32 байта «сырого» ed25519-ключа: 0x00, 0x01, … 0x1F.
    private val raw = ByteArray(32) { it.toByte() }

    @Test
    fun TC_50_publicKeyIsSerialisedInOpenSshWireFormat() {
        val line = openSshPublicKeyLine(raw, comment = "adoc-mobile@Pixel")

        val parts = line.split(" ")
        assertEquals(3, parts.size, "строка ключа — три поля через пробел")
        assertEquals("ssh-ed25519", parts[0], "тип ключа по RFC 8709")
        assertEquals("adoc-mobile@Pixel", parts[2], "комментарий виден в списке ключей сервера")

        // Тело: length-prefixed «ssh-ed25519» + length-prefixed 32 байта.
        val body = base64Decode(parts[1])
        assertEquals(4 + 11 + 4 + 32, body.size, "длина тела фиксирована для ed25519")
        assertEquals(listOf(0, 0, 0, 11), body.take(4).map { it.toInt() and 0xFF })
        assertEquals("ssh-ed25519", body.copyOfRange(4, 15).decodeToString())
        assertEquals(listOf(0, 0, 0, 32), body.copyOfRange(15, 19).map { it.toInt() and 0xFF })
        assertEquals(raw.toList(), body.copyOfRange(19, body.size).toList(), "сырой ключ передан дословно")
    }

    @Test
    fun TC_50_commentIsSanitisedAndNeverBreaksTheLine() {
        // Имя устройства приходит от системы: пробелы и переводы строк в нём
        // сломали бы формат authorized_keys, а пустой комментарий допустим.
        val line = openSshPublicKeyLine(raw, comment = "Pixel 9 Pro\nXL")
        assertEquals(3, line.split(" ").size, "комментарий не рвёт строку на лишние поля")
        assertTrue("\n" !in line, "перевода строки в ключе нет")
        assertEquals("Pixel-9-Pro-XL", line.split(" ")[2])

        val noComment = openSshPublicKeyLine(raw, comment = "   ")
        assertEquals(2, noComment.split(" ").size, "пустой комментарий просто опускается")
    }

    @Test
    fun TC_50_fingerprintMatchesSshKeygenFormat() {
        val fingerprint = sshKeyFingerprint(raw)

        assertTrue(fingerprint.startsWith("SHA256:"), "формат `ssh-keygen -l`")
        val body = fingerprint.removePrefix("SHA256:")
        assertTrue("=" !in body, "base64 отпечатка идёт без набивки")
        assertEquals(43, body.length, "SHA-256 в base64 без набивки — 43 символа")
        assertEquals(fingerprint, sshKeyFingerprint(raw), "функция чистая")
    }

    @Test
    fun TC_50_differentKeysGiveDifferentFingerprints() {
        val other = ByteArray(32) { (it + 1).toByte() }
        assertTrue(
            sshKeyFingerprint(raw) != sshKeyFingerprint(other),
            "отпечаток различает ключи — иначе сверять его с сервером бессмысленно",
        )
    }

    @Test
    fun TC_50_sha256MatchesReferenceVectors() {
        // Своя реализация хэша обязана проверяться эталонными векторами
        // (FIPS 180-4), иначе отпечаток будет красивым и неверным: тест на
        // длину строки такую ошибку не заметит.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(sha256(ByteArray(0))),
            "SHA-256 пустого сообщения",
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(sha256("abc".encodeToByteArray())),
            "SHA-256(\"abc\") — классический вектор",
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
            "вектор длиной 56 байт — проверяет набивку на границе блока",
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            hex(sha256(ByteArray(1_000_000) { 'a'.code.toByte() })),
            "миллион «a» — многоблочный проход",
        )
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0x0F]
        }

    /** Разбор base64 — только для проверки: продуктовый код кодирует, а не декодирует. */
    private fun base64Decode(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean = text.trimEnd('=')
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (ch in clean) {
            buffer = (buffer shl 6) or alphabet.indexOf(ch)
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }
}
