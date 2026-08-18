package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.git.SshKeyInfo
import io.github.olegnyr.adocmobile.git.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Модель экрана SSH-ключа — слайс `SL-17` фичи 007-git-sync, `TC-51`
 * (дозаявлен): создание ключа, копирование строки, замена и удаление с
 * подтверждением.
 *
 * Шов подделан: генерация опирается на криптопровайдер платформы, а логика
 * экрана обязана проверяться без устройства (`NFR-10`).
 */
class SshKeyScreenModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val store = FakeSshKeyStore()

    private fun model() = SshKeyScreenModel(keys = store, scope = scope).also { it.start() }

    @Test
    fun TC_51_withoutKeyScreenOffersToCreateOne() {
        val model = model()

        assertIs<SshKeyScreenState.NoKey>(model.state, "ключа нет — экран предлагает создать")
        assertEquals(0, store.generateCalls)
    }

    @Test
    fun TC_51_generatedKeyIsShownWithLineAndFingerprint() {
        val model = model()

        model.generateRequested(comment = "  Pixel 9  ")

        val ready = assertIs<SshKeyScreenState.Ready>(model.state)
        assertEquals("ssh-ed25519 AAAAkey Pixel-9", ready.key.publicKeyLine)
        assertTrue(ready.key.fingerprint.startsWith("SHA256:"), "отпечаток виден для сверки с сервером")
        assertEquals("Pixel 9", store.lastComment?.trim(), "комментарий уходит в шов как ввёл пользователь")
    }

    @Test
    fun TC_51_secondTapDuringGenerationCreatesOneKey() {
        val gate = Channel<Unit>()
        store.beforeGenerate = { gate.receive() }
        val model = model()

        model.generateRequested("устройство")
        assertIs<SshKeyScreenState.Working>(model.state, "фаза занята синхронно, до точки приостановки")
        model.generateRequested("устройство")

        assertTrue(gate.trySend(Unit).isSuccess)
        assertEquals(1, store.generateCalls, "двойное нажатие создаёт один ключ (приём TC-40)")
    }

    @Test
    fun TC_51_replacingExistingKeyAsksForConfirmationFirst() {
        store.key = SshKeyInfo("ssh-ed25519 AAAAold прежний", "SHA256:old", 1_000)
        val model = model()

        model.generateRequested("новое устройство")

        assertIs<SshKeyScreenState.ConfirmingReplace>(
            model.state,
            "замена ключа рвёт доступ к серверу — сначала подтверждение",
        )
        assertEquals(0, store.generateCalls, "без подтверждения ключ не трогается")

        model.replaceDismissed()
        assertIs<SshKeyScreenState.Ready>(model.state, "передумал — прежний ключ на месте")

        model.generateRequested("новое устройство")
        model.replaceConfirmed()
        assertEquals(1, store.generateCalls, "подтверждение создаёт новый ключ")
    }

    @Test
    fun TC_51_deleteAsksForConfirmationAndClearsBothHalves() {
        store.key = SshKeyInfo("ssh-ed25519 AAAAold прежний", "SHA256:old", 1_000)
        val model = model()

        model.deleteRequested()
        assertIs<SshKeyScreenState.ConfirmingDelete>(model.state)
        assertEquals(0, store.deleteCalls)

        model.deleteConfirmed()
        assertEquals(1, store.deleteCalls)
        assertIs<SshKeyScreenState.NoKey>(model.state, "после удаления экран снова предлагает создать")
    }

    @Test
    fun TC_51_copyHandsOutExactlyThePublicLine() {
        store.key = SshKeyInfo("ssh-ed25519 AAAAkey Pixel", "SHA256:abc", 1_000)
        val model = model()

        var copied: String? = null
        model.copyRequested { copied = it }

        assertEquals("ssh-ed25519 AAAAkey Pixel", copied, "в буфер уходит строка ключа целиком")
        val ready = assertIs<SshKeyScreenState.Ready>(model.state)
        assertNotNull(ready.notice, "пользователю сказано, что ключ скопирован")
    }

    @Test
    fun TC_51_generationFailureIsShownAndRetryIsPossible() {
        store.failNext = true
        val model = model()

        model.generateRequested("устройство")
        val failed = assertIs<SshKeyScreenState.NoKey>(model.state, "отказ возвращает экран в исходное состояние")
        assertNotNull(failed.failure, "отказ объясняется текстом, а не тишиной")

        model.generateRequested("устройство")
        assertIs<SshKeyScreenState.Ready>(model.state, "повтор после отказа возможен")
    }

    /** Подделка шва: настоящая генерация — платформенная и проверяется device-кейсом. */
    private class FakeSshKeyStore : SshKeyStore {
        var key: SshKeyInfo? = null
        var generateCalls = 0
            private set
        var deleteCalls = 0
            private set
        var lastComment: String? = null
        var failNext = false
        var beforeGenerate: (suspend () -> Unit)? = null

        override suspend fun currentKey(): SshKeyInfo? = key

        override suspend fun generateKey(comment: String): SshKeyInfo {
            beforeGenerate?.invoke()
            if (failNext) {
                failNext = false
                error("генерация не удалась")
            }
            generateCalls += 1
            lastComment = comment
            val cleaned = comment.trim().replace(Regex("\\s+"), "-")
            val info = SshKeyInfo("ssh-ed25519 AAAAkey $cleaned", "SHA256:fake", 2_000)
            key = info
            return info
        }

        override suspend fun deleteKey() {
            deleteCalls += 1
            key = null
        }
    }
}
