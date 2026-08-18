package io.github.olegnyr.adocmobile.git

import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Жизненный цикл фабрики сессий — слайс `SL-20` фичи 007-git-sync, `TC-59` (дозаявлен).
 *
 * Прежде фабрика строилась на *каждую* операцию и не закрывалась никогда: за
 * сессию приложения это утечка потоков sshd (находка security-ревью E4).
 * Держать её вечно тоже нельзя — ключ мог быть заменён или удалён с экрана, и
 * пережившая замену фабрика подписывала бы рукопожатие прежним ключом.
 * Отсюда правило: одна фабрика на ключ, смена ключа видна по дешёвой метке.
 */
class SshTransportHostTest {

    private val dir: File = Files.createTempDirectory("ssh-transport").toFile()
    private val keys = FakeKeyPairs()

    private val transport = SshTransport(
        keys = keys,
        knownHosts = File(dir, "known_hosts"),
        trust = DenyingTrust,
    )

    @AfterTest
    fun cleanUp() {
        transport.close()
        dir.deleteRecursively()
    }

    @Test
    fun TC_59_factoryIsReusedWhileTheKeyIsTheSame() {
        val first = assertNotNull(runBlocking { transport.sessionFactory() })
        val second = assertNotNull(runBlocking { transport.sessionFactory() })

        assertSame(first, second, "фабрика одна на ключ — иначе её потоки копятся за сессию приложения")
        assertEquals(1, keys.pairReads, "приватная часть поднимается в память один раз, а не на операцию")
    }

    @Test
    fun TC_59_replacedKeyGivesANewFactory() {
        val first = assertNotNull(runBlocking { transport.sessionFactory() })

        keys.stamp = "второй-ключ"
        val second = assertNotNull(runBlocking { transport.sessionFactory() })

        assertTrue(first !== second, "после замены ключа прежняя фабрика не переиспользуется")
        assertEquals(2, keys.pairReads)
    }

    @Test
    fun TC_59_withoutKeyThereIsNoFactoryAndTheCachedOneIsDropped() {
        assertNotNull(runBlocking { transport.sessionFactory() })

        keys.stamp = null
        assertNull(runBlocking { transport.sessionFactory() }, "ключа нет — идти по SSH не с чем")

        keys.stamp = "новый-ключ"
        assertNotNull(runBlocking { transport.sessionFactory() }, "созданный заново ключ снова даёт фабрику")
        assertEquals(2, keys.pairReads, "во время «ключа нет» приватная часть не читалась")
    }

    @Test
    fun TC_59_closeReleasesTheFactory() {
        val first = assertNotNull(runBlocking { transport.sessionFactory() })
        transport.close()

        val second = assertNotNull(runBlocking { transport.sessionFactory() })
        assertTrue(first !== second, "после закрытия транспорт строит фабрику заново")
    }

    @Test
    fun TC_59_closeDuringBuildIsNotLost() {
        // Закрытие, случившееся пока строится фабрика, обязано победить: иначе
        // пара нового ключа осела бы в кэше вопреки `close()` (второй раунд
        // ревью). Гонка воспроизводится точно: закрываем из самого чтения пары.
        keys.beforeKeyPair = { transport.close() }

        assertNull(
            runBlocking { transport.sessionFactory() },
            "фабрика, построенная после закрытия, наружу не отдаётся и не кэшируется",
        )

        keys.beforeKeyPair = null
        val after = assertNotNull(runBlocking { transport.sessionFactory() }, "транспорт остался годным")
        assertSame(after, assertNotNull(runBlocking { transport.sessionFactory() }), "и снова кэширует")
    }

    /** Решение человека в этих кейсах не участвует: до сети дело не доходит. */
    private object DenyingTrust : HostKeyTrust {
        override fun shouldTrust(host: String, fingerprint: String, changed: Boolean): Boolean = false
    }

    /** Подделка хранилища ключа: настоящее опирается на Android Keystore. */
    private class FakeKeyPairs : SshKeyPairs {
        // RSA, а не ed25519: содержимое пары для этих кейсов безразлично, а
        // ed25519 MINA sshd без бэкенда EdDSA не кодирует вовсе (см. отчёт
        // слайса `SL-20`).
        private val pair: KeyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

        var stamp: String? = "первый-ключ"
        var beforeKeyPair: (() -> Unit)? = null
        var pairReads = 0
            private set

        override suspend fun keyStamp(): String? = stamp

        override suspend fun keyPair(): KeyPair? {
            beforeKeyPair?.invoke()
            if (stamp == null) return null
            pairReads += 1
            return pair
        }
    }
}
