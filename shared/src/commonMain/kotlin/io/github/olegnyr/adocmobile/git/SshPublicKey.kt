package io.github.olegnyr.adocmobile.git

/**
 * Публичный ключ SSH в том виде, в каком его ждёт сервер, и его отпечаток
 * (`FR-27`, слайс `SL-16`).
 *
 * Живёт в `commonMain`: кодирование — чистые байты, и держать его в
 * платформенной половине значило бы писать заново для iOS (`NFR-2`).
 * Платформе остаётся только сгенерировать пару и отдать сюда 32 сырых
 * байта публичной части.
 *
 * Приватный ключ здесь не появляется ни в каком виде: он существует в
 * памяти генерации и уходит в хранилище секретов
 * (xref:../../adr/adr-015-ssh-key-storage.adoc[ADR-015]).
 */

/** Тип ключа в проводном формате SSH (RFC 8709). */
private const val KEY_TYPE = "ssh-ed25519"

/**
 * Строка для `authorized_keys` и поля «SSH key» в настройках GitLab:
 * `ssh-ed25519 AAAA… комментарий`.
 *
 * Комментарий — обычно имя устройства: владелец увидит его в списке ключей
 * сервера и поймёт, какой телефон он добавлял. Санитайзер — [sanitizeKeyComment]:
 * формат `authorized_keys` строчный и разделяется пробелами, а имя устройства
 * приходит от системы и может содержать что угодно. Пустой комментарий просто
 * опускается.
 *
 * @param rawPublicKey 32 байта публичной части ed25519
 */
fun openSshPublicKeyLine(rawPublicKey: ByteArray, comment: String): String {
    val body = buildList {
        addAll(lengthPrefixed(KEY_TYPE.encodeToByteArray()))
        addAll(lengthPrefixed(rawPublicKey))
    }.toByteArray()

    val encoded = base64(body)
    val cleanComment = sanitizeKeyComment(comment)
    return if (cleanComment.isEmpty()) "$KEY_TYPE $encoded" else "$KEY_TYPE $encoded $cleanComment"
}

/**
 * Комментарий ключа, пригодный для строки `authorized_keys` (`TC-57`).
 *
 * Правило белого списка, а не чёрного: сохраняются буквы и цифры *любого*
 * алфавита плюс `- _ . @`, всё прочее становится дефисом, повторы дефисов
 * схлопываются, длина ограничена [MAX_COMMENT_LENGTH].
 *
 * Почему не регулярное выражение: ASCII-класс `\s` пропускал бы неразрывный
 * пробел, разделители строк и абзацев, управляющие символы и метки
 * направления письма — они прошли бы дословно в строку, которую человек
 * вставляет в веб-форму сервера (находка security-ревью E4). Флаг `(?U)`,
 * который сделал бы класс юникодным, на Android падает (`CLAUDE.md`), так
 * что класс задан явно — проверкой категории символа, без регулярок вовсе.
 */
internal fun sanitizeKeyComment(comment: String): String {
    val mapped = StringBuilder(comment.length)
    comment.forEach { char ->
        // Невидимые «буквы» (hangul filler и родня) по категории проходят как
        // буквы, а рисуются пустотой: два ключа выглядели бы в списке сервера
        // одинаково (находка security-ревью `SL-20`).
        val keep = (char.isLetterOrDigit() && char !in INVISIBLE_LETTERS) || char in KEPT_PUNCTUATION
        if (keep) {
            mapped.append(char)
        } else if (mapped.isNotEmpty() && mapped.last() != SEPARATOR) {
            mapped.append(SEPARATOR)
        }
    }
    return mapped.toString().take(MAX_COMMENT_LENGTH).trim(SEPARATOR)
}

/** Длина комментария: имя устройства, а не поле для рассказа. */
private const val MAX_COMMENT_LENGTH = 64

private const val SEPARATOR = '-'

private const val KEPT_PUNCTUATION = "-_.@"

/**
 * «Буквы», которых не видно: hangul-заполнители и их родня.
 * Категория у них буквенная, ширина — пустая, и в списке ключей сервера два
 * разных ключа выглядели бы одинаково подписанными.
 */
private const val INVISIBLE_LETTERS = "\u115F\u1160\u3164\uFFA0"

/**
 * Отпечаток в формате `ssh-keygen -l`: `SHA256:` плюс base64 хэша
 * проводного представления ключа, без набивки.
 *
 * Нужен, чтобы владелец сверил ключ на экране с тем, что показывает
 * GitLab, не сравнивая строку целиком.
 */
fun sshKeyFingerprint(rawPublicKey: ByteArray): String {
    val body = buildList {
        addAll(lengthPrefixed(KEY_TYPE.encodeToByteArray()))
        addAll(lengthPrefixed(rawPublicKey))
    }.toByteArray()
    return "SHA256:" + base64(sha256(body)).trimEnd('=')
}

/** Длина 4 байтами big-endian, затем данные — проводной формат SSH. */
private fun lengthPrefixed(data: ByteArray): List<Byte> = buildList {
    val size = data.size
    add((size ushr 24).toByte())
    add((size ushr 16).toByte())
    add((size ushr 8).toByte())
    add(size.toByte())
    addAll(data.toList())
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Разбор base64 из строки ключа; `null` — строка не base64.
 *
 * Нужен там, где по показанной строке надо собрать сам ключ: транспорт
 * поднимает публичную часть пары, а отпечаток считается от тех же байт.
 * Живёт рядом с кодировщиком и в `commonMain` по той же причине — на iOS
 * иначе пришлось бы писать заново (`NFR-2`).
 */
internal fun decodeSshBase64(text: String): ByteArray? {
    if (text.isEmpty()) return null
    val clean = text.trimEnd('=')
    var buffer = 0
    var bits = 0
    val out = ArrayList<Byte>(clean.length * 3 / 4 + 1)
    for (char in clean) {
        val index = BASE64_ALPHABET.indexOf(char)
        if (index < 0) return null
        buffer = (buffer shl 6) or index
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out += ((buffer shr bits) and 0xFF).toByte()
        }
    }
    return out.toByteArray()
}

/**
 * Base64 своей реализацией: `java.util.Base64` в `commonMain` недоступен, а
 * тянуть зависимость ради тридцати строк — плата больше выигрыша.
 */
private fun base64(data: ByteArray): String {
    val out = StringBuilder()
    var index = 0
    while (index + 2 < data.size) {
        val chunk = ((data[index].toInt() and 0xFF) shl 16) or
            ((data[index + 1].toInt() and 0xFF) shl 8) or
            (data[index + 2].toInt() and 0xFF)
        out.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
        out.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
        out.append(BASE64_ALPHABET[(chunk shr 6) and 0x3F])
        out.append(BASE64_ALPHABET[chunk and 0x3F])
        index += 3
    }
    when (data.size - index) {
        1 -> {
            val chunk = (data[index].toInt() and 0xFF) shl 16
            out.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
            out.append("==")
        }

        2 -> {
            val chunk = ((data[index].toInt() and 0xFF) shl 16) or
                ((data[index + 1].toInt() and 0xFF) shl 8)
            out.append(BASE64_ALPHABET[(chunk shr 18) and 0x3F])
            out.append(BASE64_ALPHABET[(chunk shr 12) and 0x3F])
            out.append(BASE64_ALPHABET[(chunk shr 6) and 0x3F])
            out.append('=')
        }
    }
    return out.toString()
}

/**
 * SHA-256 своей реализацией по FIPS 180-4.
 *
 * `commonMain` не имеет хэшей в стандартной библиотеке, а отпечаток обязан
 * считаться одинаково на обеих платформах и проверяться без устройства.
 *
 * Секретов реализация не касается, но безразличной к безопасности она *не
 * является*: на отпечатке держится решение человека доверять ключу сервера
 * (`FR-29`), и ошибка расчёта означала бы сверку не того значения. Поэтому
 * она сверена эталонными векторами FIPS 180-4, включая границы блоков
 * набивки — 55, 56, 63, 64, 119 и 120 байт (`TC-50`). Прежняя формулировка
 * этого KDoc («криптостойкость здесь не вопрос безопасности») была верна до
 * E4 и исправлена вместе с расширением набора векторов.
 */
internal fun sha256(message: ByteArray): ByteArray {
    val k = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )
    var h0 = 0x6a09e667
    var h1 = -0x4498517b
    var h2 = 0x3c6ef372
    var h3 = -0x5ab00ac6
    var h4 = 0x510e527f
    var h5 = -0x64fa9774
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19

    val bitLength = message.size.toLong() * 8
    val padded = buildList {
        addAll(message.toList())
        add(0x80.toByte())
        while ((size % 64) != 56) add(0)
        for (shift in 56 downTo 0 step 8) add((bitLength ushr shift).toByte())
    }.toByteArray()

    val w = IntArray(64)
    var chunk = 0
    while (chunk < padded.size) {
        for (i in 0 until 16) {
            val base = chunk + i * 4
            w[i] = ((padded[base].toInt() and 0xFF) shl 24) or
                ((padded[base + 1].toInt() and 0xFF) shl 16) or
                ((padded[base + 2].toInt() and 0xFF) shl 8) or
                (padded[base + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + k[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
        chunk += 64
    }

    return intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).flatMap { value ->
        listOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }.toByteArray()
}

private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
