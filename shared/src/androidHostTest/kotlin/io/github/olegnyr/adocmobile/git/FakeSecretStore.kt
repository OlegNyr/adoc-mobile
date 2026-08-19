package io.github.olegnyr.adocmobile.git

import java.io.IOException

/**
 * Подделка хранилища секретов: настоящее опирается на Android Keystore и
 * проверяется device-кейсами. Здесь важен *контракт* — слоты независимы,
 * открытая часть читается без расшифровки, отданный массив потребляется
 * немедленно (вызывающий затирает его сразу после возврата).
 *
 * Вынесена из `SshKeyStoreHostTest` слайсом `SL-21`: тем же контрактом
 * пользуется `SshKeyBackendHostTest`, а две копии подделки разошлись бы —
 * и тогда один из наборов проверял бы уже не то хранилище, что другой.
 */
internal class FakeSecretStore : GitSecretStore {

    private val plain = mutableMapOf<String, ByteArray>()
    private val secret = mutableMapOf<String, ByteArray>()
    private val tampered = mutableSetOf<String>()

    var writes = 0
        private set
    val touchedSlots = mutableSetOf<String>()

    var secretReads = 0
    var failNextStore = false
    var lastStoredSecret: ByteArray? = null
        private set
    var lastReadSecret: ByteArray? = null
        private set

    override fun store(slot: String, secret: ByteArray, plain: ByteArray) {
        if (failNextStore) {
            failNextStore = false
            throw IOException("запись не удалась")
        }
        lastStoredSecret = secret
        this.secret[slot] = secret.copyOf()
        this.plain[slot] = plain.copyOf()
        tampered -= slot
        touchedSlots += slot
        writes += 1
    }

    override fun readSecret(slot: String): ByteArray? {
        // Подменённая открытая часть ломает тег GCM — секрет не читается.
        if (slot in tampered) return null
        val stored = secret[slot] ?: return null
        secretReads += 1
        return stored.copyOf().also { lastReadSecret = it }
    }

    override fun readPlain(slot: String): ByteArray? = plain[slot]?.copyOf()

    override fun readVerified(slot: String): Pair<ByteArray, ByteArray>? {
        if (slot in tampered) return null
        val open = plain[slot]?.copyOf() ?: return null
        val stored = readSecret(slot) ?: return null
        return open to stored
    }

    override fun stamp(slot: String): String? = if (slot in secret) "запись-$writes" else null

    override fun clear(slot: String) {
        secret.remove(slot)
        plain.remove(slot)
    }

    fun corruptPlain(slot: String, replacement: String? = null) {
        plain[slot] = replacement?.encodeToByteArray() ?: byteArrayOf(0x00, 0x01, 0x02)
        tampered += slot
    }
}
