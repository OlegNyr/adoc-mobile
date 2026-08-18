package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.git.SshKeyInfo
import io.github.olegnyr.adocmobile.git.SshKeyPresence
import io.github.olegnyr.adocmobile.git.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    private var keyChanges = 0

    private fun model() = SshKeyScreenModel(
        keys = store,
        scope = scope,
        keyChanged = { keyChanges += 1 },
    ).also { it.start() }

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
    fun TC_51_keyChangeIsAnnouncedSoTheTransportReleasesThePair() {
        // Транспорт держит расшифрованную пару в памяти, пока жива его
        // фабрика: после создания и после удаления ключа хостингу говорят
        // отпустить её сразу (находка security-ревью `SL-20`).
        val model = model()
        model.generateRequested("устройство")
        assertEquals(1, keyChanges, "создание ключа объявлено")

        model.deleteRequested()
        model.deleteConfirmed()
        assertEquals(2, keyChanges, "удаление ключа объявлено")
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

    @Test
    fun TC_51_failureOverAnExistingKeyStillDemandsConfirmation() {
        // Отказ генерации поверх существующего ключа не смеет превращать
        // экран в «ключа нет»: следующее нажатие затёрло бы целую пару без
        // подтверждения (находка security-ревью `SL-19`).
        store.key = SshKeyInfo("ssh-ed25519 AAAAold прежний", "SHA256:old", 1_000)
        store.failNext = true
        val model = model()

        model.generateRequested("новое устройство")
        model.replaceConfirmed()
        assertIs<SshKeyScreenState.Unreadable>(model.state, "после отказа состояние не «ключа нет»")

        model.generateRequested("новое устройство")
        assertIs<SshKeyScreenState.ConfirmingReplace>(model.state, "перезапись снова через подтверждение")
    }

    @Test
    fun TC_51_readFailureDoesNotLookLikeAnAbsentKey() {
        store.failRead = true
        val model = model()

        assertIs<SshKeyScreenState.Unreadable>(model.state, "не смогли прочитать — не значит «нет»")
        model.generateRequested("устройство")
        assertIs<SshKeyScreenState.ConfirmingReplace>(model.state)
        assertEquals(0, store.generateCalls)
    }

    @Test
    fun TC_51_transientFailureCanBeRetriedInsteadOfDestroyingTheKey() {
        // Отказ чтения мог быть временным: единственным выходом из состояния
        // не должно быть уничтожение целой пары (второй раунд ревью `SL-19`).
        store.key = SshKeyInfo("ssh-ed25519 AAAAold прежний", "SHA256:old", 1_000)
        store.failRead = true
        val model = model()
        assertIs<SshKeyScreenState.Unreadable>(model.state)

        store.failRead = false
        model.retryRequested()

        val ready = assertIs<SshKeyScreenState.Ready>(model.state, "повтор вернул рабочий ключ")
        assertEquals("ssh-ed25519 AAAAold прежний", ready.key.publicKeyLine)
        assertEquals(0, store.generateCalls, "ключ при этом не пересоздавался")
    }

    @Test
    fun TC_51_copyVerifiesTheKeyBeforeHandingItOut() {
        // Строку человек вставит в настройки сервера и выдаст доступ: подмена
        // открытой части не смеет доехать до буфера обмена (находка
        // security-ревью `SL-20`).
        store.key = SshKeyInfo("ssh-ed25519 AAAAkey Pixel", "SHA256:abc", 1_000)
        store.verificationFails = true
        val model = model()

        var copied: String? = null
        model.copyRequested { copied = it }

        assertNull(copied, "непроверенный ключ в буфер не уходит")
        assertIs<SshKeyScreenState.Unreadable>(model.state, "и человеку сказано, почему")
    }

    @Test
    fun TC_51_unreadableKeyIsNotOverwrittenWithoutConfirmation() {
        // «Ключ есть, но не читается» — не «ключа нет»: создание поверх него
        // спрашивает то же подтверждение, что замена (`TC-56`, ревью E4).
        store.unreadable = true
        val model = model()

        assertIs<SshKeyScreenState.Unreadable>(model.state)

        model.generateRequested("устройство")
        assertIs<SshKeyScreenState.ConfirmingReplace>(model.state, "перезапись вслепую запрещена")
        assertEquals(0, store.generateCalls, "без подтверждения ключ не трогается")

        model.replaceDismissed()
        assertIs<SshKeyScreenState.Unreadable>(model.state, "передумал — состояние прежнее")

        model.generateRequested("устройство")
        model.replaceConfirmed()
        assertEquals(1, store.generateCalls, "подтверждение создаёт новый ключ")
    }

    /** Подделка шва: настоящая генерация — платформенная и проверяется device-кейсом. */
    private class FakeSshKeyStore : SshKeyStore {
        var key: SshKeyInfo? = null
        var unreadable = false
        var generateCalls = 0
            private set
        var deleteCalls = 0
            private set
        var lastComment: String? = null
        var failNext = false
        var failRead = false
        var verificationFails = false
        var beforeGenerate: (suspend () -> Unit)? = null

        override suspend fun currentKey(): SshKeyPresence = when {
            failRead -> error("хранилище недоступно")
            unreadable -> SshKeyPresence.Unreadable
            else -> key?.let { SshKeyPresence.Present(it) } ?: SshKeyPresence.None
        }

        override suspend fun verifiedKey(): SshKeyInfo? = if (verificationFails) null else key

        override suspend fun generateKey(comment: String): SshKeyInfo {
            beforeGenerate?.invoke()
            if (failNext) {
                failNext = false
                error("генерация не удалась")
            }
            generateCalls += 1
            lastComment = comment
            unreadable = false
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
