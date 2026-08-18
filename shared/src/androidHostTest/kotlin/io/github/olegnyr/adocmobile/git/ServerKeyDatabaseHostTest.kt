package io.github.olegnyr.adocmobile.git

import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * База ключей сервера — слайс `SL-20` фичи 007-git-sync, `TC-53`, `TC-54`.
 *
 * До этого слайса транспорт не был покрыт ни одним автотестом, а спека при
 * этом утверждала «политика реализована». Проверять его на устройстве незачем:
 * `ServerKeyDatabase` — обычный JVM-код JGit и sshd, и host-прогон ловит
 * ошибку без телефона (`NFR-10`).
 *
 * Главное свойство: отпечаток считается от *проводного* представления ключа
 * его собственного типа. Прежний код брал последние 32 байта X.509-обёртки
 * любого ключа и хэшировал их под меткой `ssh-ed25519`: для RSA это младшие
 * байты модуля и показателя, то есть значение, которого нет ни в одной
 * публикации сервера. Сверить его было нельзя никогда, и единственным
 * работающим поведением оставалось «доверять вслепую».
 *
 * Типы в наборе — `ssh-rsa` и `ecdsa-sha2-nistp256`: именно их предъявляют
 * самостоятельно развёрнутые GitLab и Gitea, и именно на них прежний расчёт
 * давал мусор. Ключа `ssh-ed25519` в наборе нет намеренно — MINA sshd без
 * бэкенда EdDSA (`net.i2p.crypto:eddsa` либо BouncyCastle) такой ключ не
 * кодирует вовсе (`SecurityUtils.isEDDSACurveSupported() == false`), и это
 * отдельная находка, вынесенная вопросом координатору: она касается не
 * сверки ключа сервера, а самой возможности предъявить *свой* ed25519-ключ.
 * Здесь закреплено лишь то, что несериализуемый ключ получает отказ, а не
 * молчаливое доверие.
 */
class ServerKeyDatabaseHostTest {

    private val dir: File = Files.createTempDirectory("known-hosts").toFile()
    private val knownHosts = File(dir, "known_hosts")
    private val trust = RecordingTrust()

    private fun database(timeoutMs: Long = 5_000) =
        AskingServerKeyDatabase(knownHosts = knownHosts, trust = trust, askTimeoutMs = timeoutMs)

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun TC_53_fingerprintIsComputedFromTheWireFormatOfItsOwnKeyType() {
        // Ожидание считается независимо — по RFC 4253 и RFC 5656, — а не тем
        // же кодом: иначе тест подтверждал бы сам себя.
        listOf(rsaKey(), ecdsaKey()).forEach { key ->
            trust.reset()
            database().accept(HOST, ADDRESS, key, null, null)

            assertEquals(
                expectedFingerprint(key),
                trust.lastFingerprint,
                "отпечаток ключа типа ${key.algorithm} обязан совпадать с тем, что показывает сервер",
            )
        }
    }

    @Test
    fun TC_53_fingerprintsOfDifferentKeyTypesDoNotCollide() {
        val fingerprints = listOf(rsaKey(), rsaKey(), ecdsaKey()).map { key ->
            trust.reset()
            database().accept(HOST, ADDRESS, key, null, null)
            trust.lastFingerprint
        }
        assertEquals(fingerprints.toSet().size, fingerprints.size, "разные ключи — разные отпечатки")
        fingerprints.forEach { assertTrue(it.orEmpty().startsWith("SHA256:"), "формат `ssh-keygen -l`: $it") }
    }

    @Test
    fun TC_54_recordedKeyPassesWhileAnyOtherKeyIsAskedAsChanged() {
        val server = rsaKey()
        trust.answer = true
        assertTrue(database().accept(HOST, ADDRESS, server, null, null), "подтверждённый ключ принят")
        assertEquals(1, trust.calls)
        assertFalse(trust.lastChanged, "первый раз — «сервер незнаком», не «ключ сменился»")

        trust.reset()
        assertTrue(database().accept(HOST, ADDRESS, server, null, null), "тот же ключ вопроса не задаёт")
        assertEquals(0, trust.calls)

        // Другой ключ того же хоста — «ключ сменился», а не «незнакомый».
        trust.reset()
        trust.answer = false
        assertFalse(database().accept(HOST, ADDRESS, ecdsaKey(), null, null), "отказ пользователя рвёт соединение")
        assertEquals(1, trust.calls)
        assertTrue(trust.lastChanged, "второй случай означает смену ключа или перехват — текст обязан быть другим")

        // Отказ ничего не записал: прежний ключ по-прежнему единственный.
        trust.reset()
        assertTrue(database().accept(HOST, ADDRESS, server, null, null), "запись не пострадала от отказа")
        assertEquals(0, trust.calls)
    }

    @Test
    fun TC_54_lookupReturnsRecordedKeysSoTheAlgorithmIsNotChosenByTheOtherSide() {
        assertTrue(database().lookup(HOST, ADDRESS, null).isEmpty(), "неизвестный хост — пустой список")

        val server = rsaKey()
        trust.answer = true
        database().accept(HOST, ADDRESS, server, null, null)
        database().accept("other.example.org:22", ADDRESS, ecdsaKey(), null, null)

        val found = database().lookup(HOST, ADDRESS, null)
        assertEquals(1, found.size, "у хоста ровно его ключ")
        assertEquals(server.encoded.toList(), found.single().encoded.toList(), "ключ вернулся тот же, побайтово")
        assertEquals(1, database().lookup("other.example.org:22", ADDRESS, null).size, "чужие записи не смешаны")
    }

    @Test
    fun TC_54_storedEntryKeepsTheWholeKeyAndSurvivesGarbageLines() {
        trust.answer = true
        val server = rsaKey()
        database().accept(HOST, ADDRESS, server, null, null)

        val line = knownHosts.readLines().single { it.startsWith("$HOST ") }
        val blob = Base64.getDecoder().decode(line.split(" ")[2])
        assertEquals(
            expectedWireFormat(server).toList(),
            blob.toList(),
            "в файле лежит ключ целиком, а не отпечаток: сверять обрезок значит принимать подделку",
        )

        // Обрезанная строка (обрыв записи в чужой реализации, ручная правка)
        // не разбирается как валидная запись — иначе ложная тревога «ключ
        // сервера сменился» приучала бы жать «доверять».
        // Обрезанный блоб *поддерживаемого* типа: возьми здесь ed25519 —
        // строка отбрасывалась бы из-за отсутствия бэкенда EdDSA, а не из-за
        // обрезки, и тест был бы зелёным по неверной причине (ревью SL-20).
        knownHosts.appendText("$HOST ssh-rsa AAAAB3NzaC1yc2E\n")
        trust.reset()
        assertTrue(database().accept(HOST, ADDRESS, server, null, null), "целая запись всё ещё узнаётся")
        assertEquals(0, trust.calls)
        assertEquals(1, database().lookup(HOST, ADDRESS, null).size, "битая строка в перечень не попадает")
    }

    @Test
    fun TC_54_keyThatCannotBeSerialisedIsDeniedNotTrusted() {
        // Что нельзя записать целиком, то нельзя и сверить в следующий раз:
        // доверие такому ключу было бы одноразовым и необоснованным.
        trust.answer = true

        assertFalse(
            database().accept(HOST, ADDRESS, unsupportedKey(), null, null),
            "ключ неизвестного типа не получает доверия",
        )
        assertEquals(0, trust.calls, "и вопрос человеку не задаётся: сверять нечего")
        assertFalse(knownHosts.isFile && knownHosts.readText().isNotEmpty(), "в файл ничего не попало")
    }

    @Test
    fun TC_54_writeIsAtomicAndKeepsOtherHosts() {
        trust.answer = true
        val first = rsaKey()
        database().accept(HOST, ADDRESS, first, null, null)
        database().accept("second.example.org:22", ADDRESS, ecdsaKey(), null, null)

        // Смена ключа заменяет записи *своего* хоста и не трогает чужие.
        val replacement = rsaKey()
        database().accept(HOST, ADDRESS, replacement, null, null)

        val hosts = knownHosts.readLines().filter { it.isNotBlank() }.map { it.substringBefore(' ') }
        assertEquals(listOf(HOST, "second.example.org:22").sorted(), hosts.sorted())
        assertEquals(1, database().lookup(HOST, ADDRESS, null).size, "у хоста один действующий ключ")
        assertEquals(
            replacement.encoded.toList(),
            database().lookup(HOST, ADDRESS, null).single().encoded.toList(),
        )
        assertTrue(dir.listFiles()?.none { it.name.endsWith(".tmp") } ?: false, "временный файл не остался")
    }

    @Test
    fun TC_54_trustQuestionDoesNotBlockTheOperationForever() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        trust.beforeAnswer = {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        trust.answer = true

        val start = System.currentTimeMillis()
        assertFalse(
            database(timeoutMs = 200).accept(HOST, ADDRESS, rsaKey(), null, null),
            "нет ответа — нет доверия: молчание не считается согласием",
        )
        assertTrue(System.currentTimeMillis() - start < 5_000, "вопрос не держит операцию бесконечно")
        assertTrue(started.await(1, TimeUnit.SECONDS), "вопрос всё-таки задавался")
        release.countDown()
        assertFalse(knownHosts.isFile && knownHosts.readText().isNotEmpty(), "по таймауту ничего не записано")
    }

    @Test
    fun TC_54_interruptedOperationDoesNotWaitForTheAnswer() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        trust.beforeAnswer = {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        trust.answer = true

        var accepted = true
        var interruptedFlag = false
        val worker = Thread {
            accepted = database(timeoutMs = 60_000).accept(HOST, ADDRESS, rsaKey(), null, null)
            interruptedFlag = Thread.currentThread().isInterrupted
        }
        worker.start()
        // Латч, а не sleep: прерывание до входа в ожидание ответа проверяло бы
        // не то (ревью SL-20).
        assertTrue(started.await(10, TimeUnit.SECONDS), "вопрос задан")
        worker.interrupt()
        worker.join(5_000)
        release.countDown()

        assertFalse(worker.isAlive, "отмена операции не оставляет поток висеть на вопросе")
        assertFalse(accepted, "прерванный вопрос означает отказ, а не доверие")
        assertTrue(interruptedFlag, "признак прерывания доведён до JGit — операция отменяется, а не идёт дальше")
    }

    // --- эталон: проводной формат и отпечаток считаются здесь независимо ---

    /** `SHA256:` + base64 без набивки от SHA-256 проводного представления. */
    private fun expectedFingerprint(key: PublicKey): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(expectedWireFormat(key))
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun expectedWireFormat(key: PublicKey): ByteArray = when (key) {
        is RSAPublicKey -> field("ssh-rsa".encodeToByteArray()) +
            field(key.publicExponent.toByteArray()) +
            field(key.modulus.toByteArray())

        is ECPublicKey -> {
            val size = (key.params.curve.field.fieldSize + 7) / 8
            val point = byteArrayOf(0x04) + unsigned(key.w.affineX, size) + unsigned(key.w.affineY, size)
            field("ecdsa-sha2-nistp256".encodeToByteArray()) +
                field("nistp256".encodeToByteArray()) +
                field(point)
        }

        else -> error("в наборе только те типы, чей проводной формат тест считает сам")
    }

    /** Длина 4 байтами big-endian, затем данные (RFC 4251, `string`). */
    private fun field(data: ByteArray): ByteArray = byteArrayOf(
        (data.size ushr 24).toByte(),
        (data.size ushr 16).toByte(),
        (data.size ushr 8).toByte(),
        data.size.toByte(),
    ) + data

    private fun unsigned(value: BigInteger, size: Int): ByteArray {
        val bytes = value.toByteArray()
        val trimmed = if (bytes.size > size) bytes.copyOfRange(bytes.size - size, bytes.size) else bytes
        return ByteArray(size - trimmed.size) + trimmed
    }

    /** Ключ типа, который транспорт сериализовать не умеет. */
    private fun unsupportedKey(): PublicKey = object : PublicKey {
        override fun getAlgorithm(): String = "НЕИЗВЕСТНЫЙ"
        override fun getFormat(): String = "X.509"
        override fun getEncoded(): ByteArray = ByteArray(32) { it.toByte() }
    }

    private fun rsaKey(): PublicKey = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair().public

    private fun ecdsaKey(): PublicKey = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().public

    /** Подделка решения человека: реальный ответ приходит с экрана. */
    private class RecordingTrust : HostKeyTrust {
        var answer = false
        var calls = 0
            private set
        var lastFingerprint: String? = null
            private set
        var lastChanged = false
            private set
        var beforeAnswer: (() -> Unit)? = null

        override fun shouldTrust(host: String, fingerprint: String, changed: Boolean): Boolean {
            calls += 1
            lastFingerprint = fingerprint
            lastChanged = changed
            beforeAnswer?.invoke()
            return answer
        }

        fun reset() {
            calls = 0
            beforeAnswer = null
            // Иначе ассерт сверил бы значение прошлого вызова и прошёл бы,
            // даже если вопрос не задавался вовсе (ревью SL-20).
            lastFingerprint = null
            lastChanged = false
        }
    }

    private companion object {
        const val HOST = "gitlab.example.org:22"
        val ADDRESS: InetSocketAddress = InetSocketAddress.createUnresolved("gitlab.example.org", 22)
    }
}
