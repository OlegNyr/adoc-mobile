package io.github.olegnyr.adocmobile.git

import java.io.File
import java.net.InetSocketAddress
import java.security.PublicKey
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.digest.BuiltinDigests
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder

/**
 * SSH-транспорт JGit на Apache MINA sshd (`FR-27`, `FR-29`, `SL-18`, `SL-20`).
 *
 * Транспорт выбран xref:../../adr/adr-007-ssh-transport.adoc[ADR-007], а
 * место хранения приватного ключа — xref:../../adr/adr-015-ssh-key-storage.adoc[ADR-015]:
 * ключ приходит из хранилища секретов, а не с диска в `~/.ssh`.
 *
 * Политика host key (`FR-29`): неизвестный или сменившийся ключ сервера
 * *останавливает* соединение и спрашивает пользователя.
 * Предзагруженного списка доверенных серверов нет — это было бы молчаливым
 * доверием, которого спека прямо запрещает.
 *
 * Транспорт закрывается вместе с хостингом ([close]): фабрика сессий держит
 * пул потоков sshd, и построение новой на каждую операцию за сессию
 * приложения означало утечку (находка security-ревью E4). Поэтому фабрика
 * живёт, пока не меняется ключ: смену видно по метке [SshKeyPairs.keyStamp]
 * — дешёвой, не поднимающей приватную часть в память.
 */
class SshTransport(
    private val keys: SshKeyPairs,
    private val knownHosts: File,
    private val trust: HostKeyTrust,
) : AutoCloseable {

    private val building = Mutex()

    /** Замок на сам кэш: [close] приходит не из корутины и мьютекс взять не может. */
    private val cacheLock = Any()

    private var cached: SshdSessionFactory? = null
    private var cachedStamp: String? = null

    /**
     * Номер поколения кэша: [close] его увеличивает.
     *
     * Без него закрытие, случившееся *пока* строится фабрика, терялось:
     * построенная фабрика присваивалась уже после `close()`, и её пул потоков
     * с приватным ключом внутри не закрывал больше никто (находка
     * security-ревью `SL-20`).
     */
    private var generation = 0

    /**
     * Фабрика сессий для JGit; `null` — ключа на устройстве нет, и по SSH
     * идти не с чем (экран обязан предложить создать ключ).
     *
     * Возвращается один и тот же экземпляр, пока ключ не менялся: замена или
     * удаление ключа с экрана меняет метку, и прежняя фабрика закрывается —
     * иначе она пережила бы удаление ключа и утекла бы в чужие операции.
     */
    suspend fun sessionFactory(): SshSessionFactory? = building.withLock {
        val stamp = keys.keyStamp()
        if (stamp == null) {
            dropCached()
            return@withLock null
        }
        synchronized(cacheLock) {
            cached?.let { factory -> if (stamp == cachedStamp) return@withLock factory }
        }
        // Поколение берётся из самого сброса, а не отдельным чтением: между
        // двумя критическими секциями close() потерялся бы, и фабрика нового
        // ключа осела бы в кэше вопреки ему (второй раунд ревью).
        val startedAt = dropCached()

        val keyPair = keys.keyPair() ?: return@withLock null
        knownHosts.parentFile?.mkdirs()

        val factory = SshdSessionFactoryBuilder()
            // Домашний каталог и `.ssh` — приватные каталоги приложения:
            // системного `~/.ssh` на Android нет, а известные хосты обязаны
            // жить там, куда не дотянется другое приложение (ADR-007).
            .setHomeDirectory(knownHosts.parentFile)
            .setSshDirectory(knownHosts.parentFile)
            .setPreferredAuthentications("publickey")
            .setDefaultKeysProvider { listOf(keyPair) }
            .setServerKeyDatabase { _, _ -> AskingServerKeyDatabase(knownHosts, trust) }
            .build(null)

        val keep = synchronized(cacheLock) {
            // Пока строили, транспорт могли закрыть: тогда фабрику не кэшируем
            // и закрываем сами — иначе её потоки и приватный ключ в замыкании
            // остались бы жить без владельца.
            if (generation != startedAt) {
                false
            } else {
                cached = factory
                cachedStamp = stamp
                true
            }
        }
        if (!keep) {
            factory.close()
            return@withLock null
        }
        factory
    }

    /**
     * Закрыть фабрику и её потоки.
     *
     * Вызывает хостинг: когда Git-слой ему больше не нужен и *сразу после
     * удаления или замены ключа* — приватный материал живёт в замыкании
     * фабрики, и ждать следующей операции, чтобы его отпустить, нельзя
     * (`FR-28`: удаление обещает, что ключа на устройстве больше нет).
     * Транспорт после закрытия остаётся годным: следующая операция построит
     * фабрику заново.
     */
    override fun close() {
        dropCached()
    }

    /** Сбросить кэш и вернуть новое поколение. */
    private fun dropCached(): Int {
        val (previous, now) = synchronized(cacheLock) {
            generation += 1
            val current = cached
            cached = null
            cachedStamp = null
            current to generation
        }
        previous?.close()
        return now
    }
}

/**
 * Решение пользователя о ключе сервера (`FR-29`).
 *
 * Отдельный интерфейс, а не колбэк из модели: спрашивает *транспорт* в
 * своём потоке, а отвечает экран — и ответ обязан быть явным действием
 * человека, а не умолчанием.
 */
interface HostKeyTrust {

    /**
     * Доверять ли ключу сервера. Вызывается из потока операции.
     *
     * Реализация может блокировать поток до ответа человека, но не навсегда:
     * транспорт ждёт ограниченное время и считает молчание отказом, а отмену
     * операции — тем более (`TC-54`). Возврат после этого ни на что не
     * влияет: соединение уже разорвано.
     *
     * @param host адрес сервера, как его видит транспорт
     * @param fingerprint отпечаток `SHA256:…` — его пользователь сверяет с
     * опубликованным сервером
     * @param changed ключ сервера *сменился* — не просто неизвестен: это
     * либо смена ключа сервером, либо перехват, и в тексте это должно
     * звучать иначе
     */
    fun shouldTrust(host: String, fingerprint: String, changed: Boolean): Boolean
}

/**
 * База ключей серверов поверх файла `known_hosts` в приватном каталоге.
 *
 * Записывается *ключ целиком* в проводном формате (`хост тип base64`), и
 * сверка идёт по нему, а не по отпечатку: сравнение хэш-обрезка позволяло бы
 * подобрать ключ, чьё значение совпало бы с записанным, — вопрос человеку не
 * задался бы вовсе, и ветка «ключ сменился» не сработала бы (блокер
 * security-ревью E4).
 *
 * Отпечаток, который видит человек, считается штатным `KeyUtils` sshd от
 * проводного представления *того типа ключа, который предъявил сервер*, —
 * то же значение, что публикуют GitLab и `ssh-keygen -l`. Прежний расчёт
 * (хвост X.509-обёртки под меткой `ssh-ed25519`) для RSA и ECDSA давал число,
 * которого нет ни в одной публикации: сверка не могла пройти никогда.
 *
 * Неизвестный ключ уходит в [HostKeyTrust]; принятый записывается, чтобы в
 * следующий раз вопроса не было. Сменившийся ключ спрашивается отдельно и
 * заменяется только по явному согласию.
 */
internal class AskingServerKeyDatabase(
    private val knownHosts: File,
    private val trust: HostKeyTrust,
    private val askTimeoutMs: Long = DEFAULT_ASK_TIMEOUT_MS,
) : ServerKeyDatabase {

    /**
     * Ключи, уже виденные у этого адреса.
     *
     * Пустой список означал бы «набор алгоритмов ключа хоста не ограничен», и
     * тип ключа при следующем соединении выбирала бы противоположная сторона
     * (находка security-ревью E4).
     */
    override fun lookup(
        connectAddress: String,
        remoteAddress: InetSocketAddress?,
        config: ServerKeyDatabase.Configuration?,
    ): List<PublicKey> = entriesFor(connectAddress).mapNotNull(::resolve)

    override fun accept(
        connectAddress: String,
        remoteAddress: InetSocketAddress?,
        serverKey: PublicKey,
        config: ServerKeyDatabase.Configuration?,
        provider: org.eclipse.jgit.transport.CredentialsProvider?,
    ): Boolean {
        // Ключ, который нечем записать, нечем и сверить: доверять ему нельзя.
        val presented = serialize(serverKey) ?: return false
        val recorded = entriesFor(connectAddress)

        return when {
            presented in recorded -> true

            // Ключ сервера сменился: молча принимать нельзя никогда.
            else -> ask(connectAddress, serverKey, changed = recorded.isNotEmpty())
                .also { if (it) record(connectAddress, presented) }
        }
    }

    /**
     * Спросить человека, ограничив ожидание.
     *
     * Вопрос уходит в отдельный поток, а поток операции ждёт ответа с
     * таймаутом и реагирует на прерывание: иначе отменённая пользователем
     * операция висела бы до ответа, которого может не быть вовсе.
     */
    private fun ask(host: String, serverKey: PublicKey, changed: Boolean): Boolean {
        val fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, serverKey)
        val answer = FutureTask { trust.shouldTrust(host, fingerprint, changed) }
        Thread(answer, "ssh-host-key-trust").apply { isDaemon = true }.start()

        return try {
            answer.get(askTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            answer.cancel(true)
            false
        } catch (_: InterruptedException) {
            answer.cancel(true)
            // Признак прерывания доводится до JGit: операция отменена, а не
            // «пользователь отказал» — дальше идти всё равно нельзя.
            Thread.currentThread().interrupt()
            false
        } catch (_: ExecutionException) {
            false
        }
    }

    /** Строка `тип base64` — проводной формат ключа, как в `known_hosts`. */
    private fun serialize(key: PublicKey): String? = try {
        PublicKeyEntry.toString(key)
    } catch (_: Exception) {
        null
    }

    private fun resolve(entry: String): PublicKey? = try {
        PublicKeyEntry.parsePublicKeyEntry(entry).resolvePublicKey(null, null, PublicKeyEntryResolver.IGNORING)
    } catch (_: Exception) {
        // Битая или неизвестная запись — не ключ; молча пропускаем, иначе
        // одна испорченная строка обесценила бы весь файл.
        null
    }

    private fun entriesFor(host: String): List<String> = readEntries()
        .filter { it.first == host }
        .map { it.second }

    /** Пары «хост — запись ключа»; строки, которые не разбираются, отброшены. */
    private fun readEntries(): List<Pair<String, String>> {
        if (!knownHosts.isFile) return emptyList()
        return knownHosts.readLines().mapNotNull { line ->
            val parts = line.trim().split(" ")
            if (parts.size != 3) return@mapNotNull null
            val entry = "${parts[1]} ${parts[2]}"
            if (resolve(entry) == null) null else parts[0] to entry
        }
    }

    /**
     * Записать ключ хоста, заменив прежние записи *этого* хоста.
     *
     * Запись атомарна (`tmp` + `renameTo`, приём хранилища секретов): обрыв
     * оставил бы обрезанную строку, а она разбирается как «ключ сервера
     * сменился» — ложная тревога, приучающая жать «доверять».
     */
    private fun record(host: String, entry: String) = synchronized(KNOWN_HOSTS_LOCK) {
        // Замок общий на процесс, а не на экземпляр: базу ключей JGit создаёт
        // на сессию, и два параллельных подключения к разным хостам иначе
        // затёрли бы записи друг друга (находка security-ревью SL-20) —
        // подтверждение доверия пришлось бы давать заново.
        val kept = readEntries().filterNot { it.first == host }
        val text = (kept + (host to entry)).joinToString("") { "${it.first} ${it.second}\n" }

        knownHosts.parentFile?.mkdirs()
        val tmp = File(knownHosts.parentFile, knownHosts.name + "." + System.nanoTime() + ".tmp")
        try {
            writeDurably(tmp, text.encodeToByteArray())
            atomicReplace(tmp, knownHosts)
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        /**
         * Сколько ждать решения человека: сверить отпечаток с публикацией
         * сервера — минутное дело, но не мгновенное. По истечении — отказ.
         */
        const val DEFAULT_ASK_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/** Замок файла известных хостов — общий на процесс (см. `record`). */
private val KNOWN_HOSTS_LOCK = Any()
