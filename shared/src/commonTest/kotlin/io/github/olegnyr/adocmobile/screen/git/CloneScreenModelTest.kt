package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.git.CloneEvent
import io.github.olegnyr.adocmobile.git.CloneProgress
import io.github.olegnyr.adocmobile.git.FakeGitSync
import io.github.olegnyr.adocmobile.git.GitAuth
import io.github.olegnyr.adocmobile.git.GitSyncError
import io.github.olegnyr.adocmobile.git.RepositorySnapshot
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
 * Модель экрана клонирования — слайс `SL-3` фичи 007-git-sync: экранная
 * половина `TC-3` и `TC-4` (модельная закрыта `SL-1`), дозаявленные `TC-34`
 * (повторный запуск блокируется состоянием) и `TC-35` (имя папки из URL).
 *
 * Приём тот же, что в `EditorScreenModelTest`: `Dispatchers.Unconfined`
 * исполняет корутины синхронно, шов — подделка `FakeGitSync`; промежуточные
 * состояния наблюдаются через ворота подделки (`beforeEach` + rendezvous-канал).
 */
class CloneScreenModelTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val sync = FakeGitSync()

    private val snapshot = RepositorySnapshot(
        directoryName = "platform-docs",
        branch = "main",
        remoteUrl = "https://github.com/acme/platform-docs.git",
    )

    private fun model(): CloneScreenModel = CloneScreenModel(sync = sync, scope = scope)

    private fun progress(completed: Int, total: Int?, percent: Int?) = CloneProgress(
        phase = "Приём объектов",
        completedObjects = completed,
        totalObjects = total,
        percent = percent,
        objectsPerSecond = null,
    )

    @Test
    fun TC_3_progressEventsReachModelInOrderAndEndInDone() {
        val first = CloneEvent.Progress(progress(10, 40, 25))
        val second = CloneEvent.Progress(progress(40, 40, 100))
        sync.scripts += listOf(first, second, CloneEvent.Finished(snapshot))

        // Rendezvous-ворота: подделка встаёт перед каждым событием, тест
        // снимает состояние модели и пропускает её дальше.
        val gate = Channel<Unit>()
        sync.beforeEach = { gate.receive() }

        val model = model()
        model.url = "https://github.com/acme/platform-docs.git"
        model.cloneRequested()

        val started = assertIs<CloneScreenPhase.Cloning>(model.phase, "операция началась до первого события")
        assertNull(started.progress, "срезов ещё не было")

        assertTrue(gate.trySend(Unit).isSuccess)
        assertEquals(first.progress, assertIs<CloneScreenPhase.Cloning>(model.phase).progress)

        assertTrue(gate.trySend(Unit).isSuccess)
        assertEquals(second.progress, assertIs<CloneScreenPhase.Cloning>(model.phase).progress, "события в порядке эмиссии")

        assertTrue(gate.trySend(Unit).isSuccess)
        assertEquals(snapshot, assertIs<CloneScreenPhase.Done>(model.phase).repository, "терминальное состояние — Done")

        val request = sync.requests.single()
        assertEquals("https://github.com/acme/platform-docs.git", request.url)
        assertEquals("platform-docs", request.directoryName, "имя папки выведено из URL (TC-35)")
        assertNull(request.branch, "пустая ветка — ветка remote по умолчанию")
        assertTrue(request.shallow, "depth 1 включён по умолчанию (FR-4)")
    }

    @Test
    fun TC_37_formFieldsReachRequestAsTyped() {
        sync.scripts += listOf(CloneEvent.Finished(snapshot))
        val model = model()
        model.url = "  https://github.com/acme/platform-docs.git  "
        model.branch = " release/1.0 "
        model.directoryName = " docs "
        model.shallow = false
        model.cloneRequested()

        val request = sync.requests.single()
        assertEquals("https://github.com/acme/platform-docs.git", request.url, "поля приходят в шов обрезанными")
        assertEquals("release/1.0", request.branch)
        assertEquals("docs", request.directoryName)
        assertEquals(false, request.shallow, "выключенный переключатель уходит в запрос (FR-4)")
    }

    @Test
    fun TC_4_failureShowsReadableMessageAndRetryReachesSeamAgain() {
        sync.scripts += listOf(CloneEvent.Failed(GitSyncError.Network))
        sync.scripts += listOf(CloneEvent.Finished(snapshot))

        val model = model()
        model.url = "https://github.com/acme/platform-docs.git"
        model.cloneRequested()

        val failed = assertIs<CloneScreenPhase.Editing>(model.phase, "отказ возвращает форму")
        val message = assertNotNull(failed.failure, "сообщение отказа заполнено (FR-7)")
        assertTrue("github.com" in message, "сообщение называет репозиторий")

        model.cloneRequested()
        assertIs<CloneScreenPhase.Done>(model.phase, "повторный запуск после отказа возможен (TC-4)")
        assertEquals(2, sync.requests.size)
    }

    @Test
    fun TC_4_blankUrlFailsFastWithoutTouchingSeam() {
        val model = model()
        model.url = "   "
        model.cloneRequested()

        val editing = assertIs<CloneScreenPhase.Editing>(model.phase)
        assertTrue(!editing.failure.isNullOrBlank(), "пустой URL — понятное сообщение, не тишина")
        assertTrue(sync.requests.isEmpty(), "шов не дёргается без адреса")
    }

    @Test
    fun TC_34_cloneRequestedDuringCloneIsBlockedByState() {
        sync.scripts += listOf(CloneEvent.Finished(snapshot))
        val gate = Channel<Unit>()
        sync.beforeEach = { gate.receive() }

        val model = model()
        model.url = "https://github.com/acme/platform-docs.git"
        model.cloneRequested()
        assertIs<CloneScreenPhase.Cloning>(model.phase)

        model.cloneRequested()
        assertEquals(1, sync.requests.size, "повторный запуск во время операции блокируется состоянием (TC-34)")

        assertTrue(gate.trySend(Unit).isSuccess)
        assertIs<CloneScreenPhase.Done>(model.phase)
    }

    @Test
    fun TC_34_cloneRequestedAfterDoneIsANoOp() {
        sync.scripts += listOf(CloneEvent.Finished(snapshot))
        val model = model()
        model.url = "https://github.com/acme/platform-docs.git"
        model.cloneRequested()
        assertIs<CloneScreenPhase.Done>(model.phase)

        // Клонировать после успеха нечего: экран уходит по onCloned, второе
        // нажатие не рождает запроса и не получает TargetExists.
        model.cloneRequested()
        assertEquals(1, sync.requests.size, "после Done запуск заблокирован состоянием")
        assertIs<CloneScreenPhase.Done>(model.phase)
    }

    @Test
    fun TC_35_suggestedDirectoryNameIsEmptyUntilUrlIsTyped() {
        val model = model()
        assertEquals("", model.suggestedDirectoryName, "до ввода адреса подсказка молчит, а не врёт «repo»")
        model.url = "https://github.com/acme/platform-docs.git"
        assertEquals("platform-docs", model.suggestedDirectoryName)
    }

    @Test
    fun TC_31_tokenReachesSeamMaskedEverywhereAndClearsOnSuccess() {
        sync.scripts += listOf(CloneEvent.Finished(snapshot))
        val model = model()
        model.url = "https://github.com/acme/platform-docs.git"
        model.authMode = CloneAuthMode.Token
        model.token = "  ghp_secret-token-value  "

        model.cloneRequested()

        assertIs<CloneScreenPhase.Done>(model.phase)
        val auth = assertIs<GitAuth.Token>(sync.auths.single(), "токен дошёл до шва (FR-26)")
        assertEquals("ghp_secret-token-value", auth.token.value, "значение обрезано и передано явно")
        assertTrue("ghp_secret" !in auth.token.toString(), "toString секрета маскирован (TC-31, NFR-8)")
        assertTrue("ghp_secret" !in auth.toString(), "toString способа авторизации не печатает секрета")
        assertEquals("", model.token, "успех очищает поле — секрет не живёт в модели дольше нужного")
    }

    @Test
    fun TC_31_blankTokenInTokenModeFailsFastWithoutSeam() {
        val model = model()
        model.url = "https://github.com/acme/platform-docs.git"
        model.authMode = CloneAuthMode.Token
        model.cloneRequested()

        val editing = assertIs<CloneScreenPhase.Editing>(model.phase)
        assertTrue(!editing.failure.isNullOrBlank(), "пустой токен — понятный отказ")
        assertTrue(sync.requests.isEmpty())
    }

    @Test
    fun TC_31_tokenModeRequiresHttpsScheme() {
        val model = model()
        model.url = "http://intranet.example.org/repo.git"
        model.authMode = CloneAuthMode.Token
        model.token = "ghp_secret"
        model.cloneRequested()

        val editing = assertIs<CloneScreenPhase.Editing>(model.phase)
        assertTrue("HTTPS" in editing.failure.orEmpty(), "токен по голому http не уходит (NFR-8, ревью E2)")
        assertTrue(sync.requests.isEmpty())
    }

    @Test
    fun TC_31_credentialsEmbeddedInUrlAreRefusedWithHint() {
        val model = model()
        model.url = "https://user:secret@github.com/acme/platform-docs.git"
        model.cloneRequested()

        val editing = assertIs<CloneScreenPhase.Editing>(model.phase)
        val failure = assertNotNull(editing.failure, "URL с кредами отвергается (FR-30)")
        assertTrue("ТОКЕН" in failure, "подсказка ведёт к полю авторизации")
        assertTrue("secret" !in failure, "сам секрет в сообщении не повторяется (NFR-8)")
        assertTrue(sync.requests.isEmpty(), "запрос с кредами в URL до шва не доходит")
    }

    @Test
    fun TC_35_defaultDirectoryNameIsDerivedFromUrlShapes() {
        assertEquals("platform-docs", defaultDirectoryName("https://github.com/acme/platform-docs.git"))
        assertEquals("platform-docs", defaultDirectoryName("https://github.com/acme/platform-docs/"))
        assertEquals("platform-docs", defaultDirectoryName("git@github.com:acme/platform-docs.git"))
        assertEquals("platform-docs", defaultDirectoryName("platform-docs.git///"), "хвостовые разделители не мешают")
        assertEquals("repo", defaultDirectoryName(".git"), "вырожденный адрес не даёт пустого имени")
        assertEquals("repo", defaultDirectoryName(""), "пустой адрес не даёт пустого имени")
    }
}
