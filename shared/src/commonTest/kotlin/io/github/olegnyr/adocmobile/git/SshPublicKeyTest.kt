package io.github.olegnyr.adocmobile.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Формат публичного ключа и отпечаток — слайс `SL-16` фичи 007-git-sync,
 * `TC-50` (дозаявлен): пользователь копирует строку в настройки сервера, а
 * отпечаток сверяет с тем, что показывает сервер.
 * Санитайзер комментария и границы SHA-256 добавлены слайсом `SL-19`
 * (`TC-57`).
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
        // Декодер здесь *свой*: сверять продуктовый кодировщик продуктовым же
        // декодером значит проверять их согласие, а не соответствие формату
        // (находка ревью `SL-20`).
        val body = referenceBase64Decode(parts[1])
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
    fun TC_57_commentIsSanitisedByUnicodeClassesNotAscii() {
        // Имя устройства приходит от системы, а строку человек вставляет в
        // веб-форму сервера. ASCII-класс `\s` пропускал бы всё, что ниже: они
        // и проверяются поимённо — записаны escape-последовательностями, иначе
        // в исходнике стояли бы невидимые символы.
        val hostile = "Pixel\u00A09\u2028Pro\u2029XL\u0085RU\u200E\u0007\u3000Nexus\uFEFF"
        val comment = openSshPublicKeyLine(raw, comment = hostile).split(" ")[2]

        val forbidden = listOf('\u00A0', '\u2028', '\u2029', '\u0085', '\u200E', '\u0007', '\u3000', '\uFEFF')
        forbidden.forEach { char ->
            assertTrue(char !in comment, "символ U+${char.code.toString(16)} не смеет попасть в строку ключа")
        }
        assertEquals("Pixel-9-Pro-XL-RU-Nexus", comment, "разделители схлопнуты в один дефис")

        // Невидимые «буквы»: категория буквенная, ширина нулевая — два ключа
        // выглядели бы в списке сервера подписанными одинаково.
        val invisible = openSshPublicKeyLine(raw, comment = "Pixel\u3164\u115F9").split(" ")[2]
        assertEquals("Pixel-9", invisible, "невидимые буквы схлопнуты, а не пропущены")

        // Буквы национальных алфавитов — не мусор: их сохраняем.
        val cyrillic = openSshPublicKeyLine(raw, comment = "Телефон Олега").split(" ")[2]
        assertEquals("Телефон-Олега", cyrillic)
    }

    @Test
    fun TC_57_commentLengthIsBounded() {
        val comment = openSshPublicKeyLine(raw, comment = "и".repeat(500)).split(" ")[2]
        assertTrue(comment.length <= 64, "длина комментария ограничена, а не «сколько прислали»: ${comment.length}")

        // Обрезка не оставляет висящего дефиса на конце.
        val trimmed = openSshPublicKeyLine(raw, comment = "a".repeat(63) + " хвост").split(" ")[2]
        assertTrue(!trimmed.endsWith("-"), "обрезанный комментарий не кончается разделителем: $trimmed")
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

    @Test
    fun TC_50_sha256HoldsAtBlockBoundaries() {
        // Границы набивки: 55 байт — последний размер, где длина влезает в тот
        // же блок; 56 и 63 — набивка вытесняет длину в следующий; 64 и 120 —
        // ровные блоки; 119 — вторая такая же граница. Прежний набор векторов
        // этих длин не трогал, а заявление «границы блоков сверены» их
        // подразумевало (находка security-ревью E4).
        val expected = mapOf(
            55 to "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318",
            56 to "b35439a4ac6f0948b6d6f9e3c6af0f5f590ce20f1bde7090ef7970686ec6738a",
            63 to "7d3e74a05d7db15bce4ad9ec0658ea98e3f06eeecf16b4c6fff2da457ddc2f34",
            64 to "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            119 to "31eba51c313a5c08226adf18d4a359cfdfd8d2e816b13f4af952f7ea6584dcfb",
            120 to "2f3d335432c70b580af0e8e1b3674a7c020d683aa5f73aaaedfdc55af904c21c",
        )
        expected.keys.sorted().forEach { size ->
            val digest = hex(sha256(ByteArray(size) { 'a'.code.toByte() }))
            assertEquals(expected.getValue(size), digest, "SHA-256 от $size байт «a»")
        }
    }

    @Test
    fun TC_50_base64DecoderRoundTripsAndRejectsGarbage() {
        // Декодер продуктовый: по показанной строке транспорт собирает саму
        // публичную часть пары, и мусор туда попадать не должен.
        val encoded = openSshPublicKeyLine(raw, comment = "").split(" ")[1]
        assertEquals(
            referenceBase64Decode(encoded).toList(),
            decodeSshBase64(encoded)?.toList(),
            "продуктовый декодер согласен с эталонным",
        )
        assertEquals(null, decodeSshBase64("не base64!"), "мусор не разбирается")
        assertEquals(null, decodeSshBase64(""), "пустая строка — не ключ")
    }

    /** Разбор base64 эталоном — только для сверки; продуктовый код проверяется им, а не собой. */
    private fun referenceBase64Decode(text: String): ByteArray {
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

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            "0123456789abcdef"[value shr 4].toString() + "0123456789abcdef"[value and 0x0F]
        }
}
