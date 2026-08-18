package io.github.olegnyr.adocmobile.git

import java.io.File
import java.security.PublicKey
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder

/**
 * SSH-транспорт JGit на Apache MINA sshd (`FR-27`, `FR-29`, `SL-18`).
 *
 * Транспорт выбран xref:../../adr/adr-007-ssh-transport.adoc[ADR-007], а
 * место хранения приватного ключа — xref:../../adr/adr-015-ssh-key-storage.adoc[ADR-015]:
 * ключ приходит из хранилища секретов, а не с диска в `~/.ssh`.
 *
 * Политика host key (`FR-29`): неизвестный или сменившийся ключ сервера
 * *останавливает* соединение и спрашивает пользователя.
 * Предзагруженного списка доверенных серверов нет — это было бы молчаливым
 * доверием, которого спека прямо запрещает.
 */
class SshTransport(
    private val keys: AndroidSshKeyStore,
    private val knownHosts: File,
    private val trust: HostKeyTrust,
) {

    /**
     * Фабрика сессий для JGit; `null` — ключа на устройстве нет, и по SSH
     * идти не с чем (экран обязан предложить создать ключ).
     *
     * Ключ читается на каждое построение фабрики: он мог быть заменён или
     * удалён с экрана ключа между операциями.
     */
    suspend fun sessionFactory(): SshSessionFactory? {
        val keyPair = keys.keyPair() ?: return null
        knownHosts.parentFile?.mkdirs()
        if (!knownHosts.exists()) knownHosts.createNewFile()

        return SshdSessionFactoryBuilder()
            // Домашний каталог и `.ssh` — приватные каталоги приложения:
            // системного `~/.ssh` на Android нет, а известные хосты обязаны
            // жить там, куда не дотянется другое приложение (ADR-007).
            .setHomeDirectory(knownHosts.parentFile)
            .setSshDirectory(knownHosts.parentFile)
            .setPreferredAuthentications("publickey")
            .setDefaultKeysProvider { listOf(keyPair) }
            .setServerKeyDatabase { _, _ -> AskingServerKeyDatabase(knownHosts, trust) }
            .build(null)
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
     * Доверять ли ключу сервера. Вызывается из потока операции и блокирует
     * её до ответа.
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
 * Неизвестный ключ уходит в [HostKeyTrust]; принятый записывается, чтобы в
 * следующий раз вопроса не было. Сменившийся ключ спрашивается отдельно и
 * заменяется только по явному согласию.
 */
private class AskingServerKeyDatabase(
    private val knownHosts: File,
    private val trust: HostKeyTrust,
) : ServerKeyDatabase {

    override fun lookup(
        connectAddress: String,
        remoteAddress: java.net.InetSocketAddress,
        config: ServerKeyDatabase.Configuration,
    ): List<PublicKey> = emptyList()

    override fun accept(
        connectAddress: String,
        remoteAddress: java.net.InetSocketAddress,
        serverKey: PublicKey,
        config: ServerKeyDatabase.Configuration,
        provider: org.eclipse.jgit.transport.CredentialsProvider?,
    ): Boolean {
        val fingerprint = sshKeyFingerprint(rawEd25519(serverKey.encoded))
        val known = readKnownHosts()
        val recorded = known[connectAddress]

        return when {
            recorded == fingerprint -> true

            recorded != null -> {
                // Ключ сервера сменился: молча принимать нельзя никогда.
                trust.shouldTrust(connectAddress, fingerprint, changed = true)
                    .also { if (it) writeKnownHost(connectAddress, fingerprint) }
            }

            else -> trust.shouldTrust(connectAddress, fingerprint, changed = false)
                .also { if (it) writeKnownHost(connectAddress, fingerprint) }
        }
    }

    private fun readKnownHosts(): Map<String, String> =
        if (!knownHosts.isFile) {
            emptyMap()
        } else {
            knownHosts.readLines()
                .mapNotNull { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size == 2) parts[0] to parts[1] else null
                }
                .toMap()
        }

    private fun writeKnownHost(host: String, fingerprint: String) {
        val updated = readKnownHosts() + (host to fingerprint)
        knownHosts.writeText(updated.entries.joinToString("\n") { "${it.key} ${it.value}" } + "\n")
    }

    /** Хвост X.509-обёртки: для ed25519 это ровно 32 байта ключа. */
    private fun rawEd25519(encoded: ByteArray): ByteArray =
        encoded.copyOfRange(maxOf(0, encoded.size - 32), encoded.size)
}
