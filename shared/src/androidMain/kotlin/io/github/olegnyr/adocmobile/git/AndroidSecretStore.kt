package io.github.olegnyr.adocmobile.git

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище секретов Git-слоя (`FR-28`, решение OQ-2): файлы в приватном
 * каталоге, шифрованные AES-ключами из Android Keystore.
 *
 * Схема — вариант (а) владельца: Tink не тянем, свой код мал и проходит
 * security-ревью. Ключ генерируется в Keystore неизвлекаемым (AES-256-GCM),
 * наружу выходят только IV и шифртекст; файл без ключа — шум.
 *
 * *Слотов много, и они независимы* (`TC-55`, слайс `SL-19`): у каждого свой
 * файл и свой Keystore-ключ. Прежнее однослотовое хранилище держало токен и
 * приватный SSH-ключ в одном файле под одним псевдонимом — запись одного
 * затирала другой, а очистка делала второй нечитаемым. Соглашение об именах
 * поверх одного слота этой беды не лечит, поэтому слот — это отдельный файл,
 * а не префикс внутри общего.
 *
 * У слота две части. Секретная лежит шифртекстом; *открытая* — рядом в том же
 * файле открытым текстом и заверяется тем же тегом GCM (AAD), чтобы читать её
 * без расшифровки секретной (публичная строка SSH-ключа: показать её экрану
 * незачем поднимать приватную часть в память — находка security-ревью E4).
 * Подмена открытой части ломает расшифровку секретной, то есть пара «открытая
 * и секретная половины» связана криптографически, а не порядком записи.
 * Сам показ открытой части тега не проверяет — для этого нужна расшифровка;
 * подмена ловится на пути *использования* секрета, а не на пути показа, и это
 * названное ограничение: тот, кто пишет в приватный каталог приложения, уже
 * владеет приложением.
 *
 * Токен хранится *в паре с хостом* и выдаётся только ему: единственный
 * токен «для всех origin» утёк бы чужому серверу по его 401
 * (находка security-ревью E2).
 * Plaintext-секрет не попадает ни в настройки, ни в логи, ни в сам файл
 * (`TC-30`: побайтовый поиск по каталогу данных обязан его не найти).
 *
 * Названные отклонения (правило «отступление названо», ревью E2):
 * plaintext токена живёт в памяти `String`-ом и не затирается — JVM гарантий
 * затирания не даёт, `CharArray`-гимнастика дала бы ложное чувство защиты;
 * ключ без `setUserAuthenticationRequired` — push не должен просить
 * биометрию, решение удобства, пересматривается по боли; без
 * `setUnlockedDeviceRequired` — операции Git запускает пользователь на
 * разблокированном устройстве.
 * Двоичные секреты (приватный SSH-ключ) `String`-ом не становятся вовсе:
 * они ходят массивами байт, и вызывающий их затирает.
 *
 * Блокирующие вызовы намеренно: потребители — `JGitSync` и хранилище ключа,
 * оба работают на IO-диспетчере.
 */
interface GitSecretStore {

    /**
     * Записать слот целиком, заменив прежнее содержимое.
     *
     * Запись атомарна: обрыв оставляет прежнее содержимое, а не обрезанный
     * файл. [secret] потребляется немедленно — вызывающему разрешено затереть
     * массив сразу после возврата.
     *
     * @param plain открытая часть слота: читается без расшифровки, но
     * заверяется тем же тегом, что и [secret]
     */
    fun store(slot: String, secret: ByteArray, plain: ByteArray = ByteArray(0))

    /**
     * Секретная часть слота или `null` — слота нет, ключ Keystore сменился,
     * файл повреждён. Вызывающий обязан затереть массив после использования.
     */
    fun readSecret(slot: String): ByteArray?

    /**
     * Открытая часть слота без расшифровки секретной; `null` — слота нет либо
     * секретную часть уже нечем расшифровать (ключ платформы пропал).
     *
     * Тег GCM здесь не проверяется: подмена открытой части ловится в
     * [readSecret].
     */
    fun readPlain(slot: String): ByteArray?

    /**
     * Обе половины слота из *одного* чтения файла; `null` — слота нет или тег
     * не сошёлся.
     *
     * Раздельные [readPlain] и [readSecret] дали бы два независимых чтения, и
     * подменивший файл между ними получил бы «проверенную» чужую открытую
     * часть: проверка тега прошла бы над одним содержимым, а наружу ушло бы
     * другое (находка второго раунда security-ревью `SL-19`).
     * Секретную часть вызывающий обязан затереть.
     */
    fun readVerified(slot: String): Pair<ByteArray, ByteArray>?

    /**
     * Метка слота: `null` — слота нет; при каждой записи значение меняется.
     *
     * Это и признак наличия без расшифровки, и версия содержимого: по ней
     * транспорт понимает, что ключ заменили, и не держит фабрику сессий от
     * прежнего ключа.
     */
    fun stamp(slot: String): String?

    /** Забыть слот: его файл и его Keystore-ключ удаляются вместе. */
    fun clear(slot: String)
}

/** Слот токена HTTPS (`FR-26`). */
const val TOKEN_SLOT: String = "https-token"

/** Слот SSH-ключа: обе половины пары лежат в нём одной записью (`FR-27`). */
const val SSH_KEY_SLOT: String = "ssh-key"

/**
 * Каталог приватных файлов Git-слоя внутри `filesDir` приложения.
 *
 * Имя знают трое: хостинг (он собирает пути), правила резервной копии
 * (каталог `res/xml`, `TC-58`) и этот файл. Каталог исключён из копии целиком:
 * `known_hosts` с доверенными отпечатками серверов не смеет переезжать на
 * устройство, где их никто не подтверждал (`FR-29`).
 */
const val GIT_PRIVATE_DIR_NAME: String = "git"

/**
 * Каталог приватных файлов Git-слоя внутри [filesDir] приложения.
 *
 * Пути собираются *этой* функцией, а не строками у хостинга: правила
 * резервной копии исключают ровно `filesDir/git`, и врезка мимо каталога
 * тихо вернула бы `known_hosts` в облачную копию (`TC-58`).
 */
fun gitPrivateDir(filesDir: File): File = File(filesDir, GIT_PRIVATE_DIR_NAME)

/** Файл известных хостов SSH — там же, под теми же правилами копии (`FR-29`). */
fun gitKnownHostsFile(filesDir: File): File = File(gitPrivateDir(filesDir), "known_hosts")

/**
 * Хранилище секретов приложения — единственная форма, которой пользуется
 * хостинг: каталог берётся из [gitPrivateDir], а не строкой на месте.
 * Тесты задают каталог явно обычным конструктором.
 */
fun appGitSecretStore(filesDir: File): AndroidSecretStore = AndroidSecretStore(gitPrivateDir(filesDir))

/** Запомнить токен для [host], перезаписав прежний (`FR-26`: вводится один раз). */
fun GitSecretStore.storeToken(host: String, token: Secret) {
    val payload = (host.lowercase() + TOKEN_SEPARATOR + token.value).encodeToByteArray()
    store(TOKEN_SLOT, payload)
    payload.fill(0)
}

/** Токен, сохранённый для [host]; `null` — не вводился, чужой хост или хранилище недоступно. */
fun GitSecretStore.tokenFor(host: String): Secret? {
    val payload = readSecret(TOKEN_SLOT) ?: return null
    return try {
        val text = payload.decodeToString()
        val storedHost = text.substringBefore(TOKEN_SEPARATOR)
        val token = text.substringAfter(TOKEN_SEPARATOR, missingDelimiterValue = "")
        if (storedHost.equals(host, ignoreCase = true) && token.isNotEmpty()) Secret(token) else null
    } finally {
        payload.fill(0)
    }
}

/** Забыть токен: файл и Keystore-ключ удаляются вместе. */
fun GitSecretStore.clearToken(): Unit = clear(TOKEN_SLOT)

private const val TOKEN_SEPARATOR = "\n"

/**
 * Заменить [target] содержимым [tmp] одним шагом.
 *
 * `File.renameTo` поверх существующего файла на части платформ отказывает, и
 * прежняя развязка «удалить и переименовать» рвала атомарность ровно там, где
 * та обещана: отказ второго шага уничтожал прежнее содержимое (находка ревью
 * `SL-19`). `ATOMIC_MOVE` делает замену одним шагом; если файловая система его
 * не даёт, остаётся `REPLACE_EXISTING` — окно меньше, чем у пары
 * «delete + rename».
 *
 * Общая на хранилище секретов и файл известных хостов: приём один, и
 * расходиться этим двум местам незачем.
 */
/** Замок хранилища секретов — общий на процесс: экземпляров может быть больше одного. */
private val SECRET_STORE_LOCK = Any()

internal fun atomicReplace(tmp: File, target: File) {
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Записать [bytes] в [file] и довести их до носителя.
 *
 * Без `fsync` «атомарность» защищает только от убитого процесса: при потере
 * питания переименование может лечь на диск раньше данных, и файл окажется
 * пустым — ключ пропал бы под видом «повреждён» (находка security-ревью
 * `SL-19`). Синхронизируется файл; каталог не синхронизируется — Android
 * такого API не даёт, и это названное ограничение.
 */
internal fun writeDurably(file: File, bytes: ByteArray) {
    FileOutputStream(file).use { stream ->
        stream.write(bytes)
        stream.flush()
        stream.fd.sync()
    }
}

/**
 * Реализация на Android Keystore.
 *
 * @param dir каталог слотов в приватном хранилище приложения
 * @param keyAliasPrefix префикс псевдонимов AES-ключей в `AndroidKeyStore`;
 * полное имя — `префикс.слот`, чтобы очистка одного слота не делала
 * нечитаемыми остальные
 */
class AndroidSecretStore(
    private val dir: File,
    private val keyAliasPrefix: String = DEFAULT_ALIAS_PREFIX,
) : GitSecretStore {


    // Замок на запись, общий на процесс, а не на экземпляр: хранилище может
    // быть создано дважды, и тогда `getKey`-then-`generate` из двух экземпляров
    // породил бы два ключа под одним псевдонимом — шифртекст первого стал бы
    // нечитаем (находки обоих раундов ревью SL-19). Гейт в модели экрана эту
    // гонку прикрывает, но контракт хранилища не должен держаться на
    // дисциплине вызывающего.
    override fun store(slot: String, secret: ByteArray, plain: ByteArray) = synchronized(SECRET_STORE_LOCK) {
        val file = fileOf(slot)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(slot))
        if (plain.isNotEmpty()) cipher.updateAAD(plain)
        val ciphertext = cipher.doFinal(secret)

        // Атомарная запись: обрыв посреди записи оставил бы обрезанный файл,
        // и прежний секрет молча пропал бы (находка ревью E2).
        dir.mkdirs()
        val tmp = File(dir, file.name + "." + System.nanoTime() + ".tmp")
        try {
            val iv = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv
            writeDurably(tmp, header(plain.size) + plain + iv + ciphertext)
            atomicReplace(tmp, file)
        } finally {
            tmp.delete()
        }
    }

    override fun readSecret(slot: String): ByteArray? = readSlot(slot)?.let { parts -> decrypt(slot, parts) }

    override fun readVerified(slot: String): Pair<ByteArray, ByteArray>? {
        val parts = readSlot(slot) ?: return null
        val secret = decrypt(slot, parts) ?: return null
        return parts.plain to secret
    }

    private fun decrypt(slot: String, parts: SlotParts): ByteArray? = parts.let {
        try {
            // existingKey, а не key: чтение не смеет заводить новый ключ —
            // иначе исчезнувший из Keystore слот молча обрастал бы ключом,
            // которым его шифртекст всё равно не расшифровать.
            val secretKey = existingKey(slot) ?: return@let null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, parts.iv))
            if (parts.plain.isNotEmpty()) cipher.updateAAD(parts.plain)
            cipher.doFinal(parts.ciphertext)
        } catch (_: Exception) {
            // Битый файл, сменившийся ключ (сброс Keystore), повреждённый тег
            // GCM или подменённая открытая часть: секрета нет — штатный путь,
            // пользователь введёт заново или пересоздаст ключ.
            null
        }
    }

    /**
     * Открытая часть слота — без расшифровки секретной, но только если
     * секретную *в принципе* есть чем расшифровать: без своего ключа в
     * Keystore слот мёртв целиком, и отдавать половину значило бы показать
     * экрану ключ, которым уже нечего подписать (находка ревью `SL-19`).
     *
     * Тегом GCM открытая часть заверена, но здесь он не проверяется — для
     * этого нужен ключ и расшифровка. Подмена открытой части ловится в
     * [readSecret], то есть на пути *использования* ключа, а не показа.
     */
    override fun readPlain(slot: String): ByteArray? {
        if (!hasKey(slot)) return null
        return readSlot(slot)?.plain
    }

    override fun stamp(slot: String): String? {
        val file = fileOf(slot)
        if (!file.isFile) return null
        // IV случаен на каждую запись — метка меняется по построению, без
        // расчёта хэша и без расшифровки. Файл, который не разбирается, тоже
        // получает метку: «слот есть, но битый» и «слота нет» — разные вещи,
        // и второе привело бы к уничтожению ключа без подтверждения.
        val parts = readSlot(slot) ?: return "broken-${file.length()}-${file.lastModified()}"
        return parts.iv.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            HEX[value shr 4].toString() + HEX[value and 0x0F]
        }
    }

    override fun clear(slot: String) = synchronized(SECRET_STORE_LOCK) {
        val file = fileOf(slot)
        // Отказ удаления не выдаётся за успех: иначе экран сказал бы «ключа на
        // устройстве больше нет», а при следующем входе показал бы «ключ не
        // читается» (находка второго раунда ревью `SL-19`).
        check(!file.exists() || file.delete()) { "не удалось удалить файл секрета" }
        // Ключ уходит вместе с данными: File.delete на flash не затирает
        // байты, и переживший ключ расшифровал бы восстановленный шифртекст
        // (находка ревью E2). Псевдоним свой у каждого слота — соседние
        // слоты остаются читаемыми (`TC-55`).
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(aliasOf(slot))
        } catch (_: Exception) {
            // Keystore без записи — уже чисто.
        }
    }

    private fun fileOf(slot: String): File {
        require(slot.isNotEmpty() && slot.length <= MAX_SLOT_LENGTH && slot.all { it in SLOT_ALPHABET }) {
            // Имя слота становится именем файла и псевдонимом ключа: чужие
            // символы вывели бы запись из каталога.
            "недопустимое имя слота"
        }
        return File(dir, "$slot.bin")
    }

    private fun aliasOf(slot: String): String = "$keyAliasPrefix.$slot"

    /** Есть ли у слота ключ в Keystore — дёшево и *без* его генерации. */
    private fun hasKey(slot: String): Boolean = existingKey(slot) != null

    /** Ключ слота, если он в Keystore есть; `null` — нет. Не создаёт. */
    private fun existingKey(slot: String): SecretKey? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(aliasOf(slot), null) as? SecretKey
    } catch (_: Exception) {
        null
    }


    /** Разбор файла слота; `null` — слота нет либо заголовок не читается. */
    private fun readSlot(slot: String): SlotParts? {
        val file = fileOf(slot)
        if (!file.isFile) return null
        return try {
            val bytes = file.readBytes()
            // Заголовок файла — недоверенный вход (его мог подменить кто
            // угодно с доступом к каталогу): каждая длина проверяется до
            // того, как ею индексируют, а не после.
            require(bytes.size > HEADER_SIZE)
            val plainSize = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
            require(plainSize >= 0 && HEADER_SIZE + plainSize < bytes.size)
            val plainEnd = HEADER_SIZE + plainSize
            val ivSize = bytes[plainEnd].toInt() and 0xFF
            val ivEnd = plainEnd + 1 + ivSize
            require(ivSize > 0 && ivEnd <= bytes.size)
            SlotParts(
                plain = bytes.copyOfRange(HEADER_SIZE, plainEnd),
                iv = bytes.copyOfRange(plainEnd + 1, ivEnd),
                ciphertext = bytes.copyOfRange(ivEnd, bytes.size),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun header(plainSize: Int): ByteArray = byteArrayOf(
        (plainSize ushr 24).toByte(),
        (plainSize ushr 16).toByte(),
        (plainSize ushr 8).toByte(),
        plainSize.toByte(),
    )

    // Тот же процессный замок: check-then-generate без него из двух потоков
    // породил бы два ключа, и шифртекст первого стал бы нечитаем (ревью E2).
    private fun key(slot: String): SecretKey = synchronized(SECRET_STORE_LOCK) {
        val alias = aliasOf(slot)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return@synchronized it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        generator.generateKey()
    }

    private class SlotParts(val plain: ByteArray, val iv: ByteArray, val ciphertext: ByteArray)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS_PREFIX = "git-secrets"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val HEADER_SIZE = 4
        const val MAX_SLOT_LENGTH = 40
        const val SLOT_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789-"
        const val HEX = "0123456789abcdef"
    }
}
