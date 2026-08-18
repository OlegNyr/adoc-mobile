package io.github.olegnyr.adocmobile.git

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.RemoteRefUpdate

/**
 * СПАЙК E0 фичи 007-git-sync (SL-0) — ВЫБРАСЫВАЕТСЯ ЦЕЛИКОМ.
 *
 * Не тест-кейсы спеки, а проверка гипотез разведки фактами на устройстве:
 *  - H1 — стоковый JGit 7.x работает на ART: shallow clone `depth 1` по HTTPS.
 *  - H2 — push из shallow-клона проходит (file://-remote, без сети).
 *  - H3 — первая git-операция процесса заметно дороже последующих
 *         (FileStoreAttributes меряет разрешение таймстампов).
 *  - H5 — убийство процесса с занятым index.lock оставляет stale-lock;
 *         следующая операция падает; лечится удалением lock-файла.
 *
 * Результаты пишутся в logcat тегом GitSpike и снимаются хостом.
 * Идентификаторов TC-* нет намеренно: это спайк, разрешённый до одобрения
 * спеки, его результат — правка research.adoc, а не покрытие требований.
 *
 * `@Ignore` на классе — по границе работ («никаких сетевых обращений из
 * автоматических тестов»): H1 клонирует настоящий GitHub, и в автоматический
 * device-прогон спайку хода нет. Запускается только руками, снятием
 * аннотации; файл оставлен как образец замеров и оркестровки `force-stop`
 * до появления настоящих тестов E1 — после чего удаляется.
 */
@Ignore
class JGitSpikeTest {

    // targetContext, а не context: filesDir тестового пакета не существует
    // (инструментация живёт в процессе приложения), а filesDir приложения —
    // ровно приватное хранилище из варианта (а) вопроса OQ-1.
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun freshDir(name: String): File =
        File(ctx.filesDir, name).apply {
            deleteRecursively()
            check(!exists()) { "не удалось очистить $this" }
        }

    private fun usedHeap(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** Аккумулятор прогресса клона: какие задачи шли и сколько единиц работы. */
    private class SpikeProgress : ProgressMonitor {
        val tasks = mutableListOf<String>()
        private var current = ""
        private var units = 0

        override fun start(totalTasks: Int) {}
        override fun beginTask(title: String?, totalWork: Int) {
            flush()
            current = title ?: "?"
            units = 0
        }
        override fun update(completed: Int) { units += completed }
        override fun endTask() = flush()
        override fun isCancelled() = false
        override fun showDuration(enabled: Boolean) {}
        private fun flush() {
            if (current.isNotEmpty()) tasks += "$current: $units"
            current = ""
        }
    }

    // ------------------------------------------------------------------ H1

    /**
     * H1: shallow clone публичного репозитория этого же проекта по HTTPS в
     * приватный filesDir. Меряем время, кучу (дельта и пик) и нативную память.
     * Если стоковый JGit 7.x не работает на ART — падение здесь, и точная
     * ошибка в logcat и есть главный результат спайка.
     */
    @Test
    fun h1_shallowCloneOverHttps() {
        val dir = freshDir("spike-h1-clone")
        System.gc()
        val heapBefore = usedHeap()
        val nativeBefore = Debug.getNativeHeapAllocatedSize()

        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var heapPeak = heapBefore
        val sampler = thread(name = "spike-heap-sampler") {
            while (!done.get()) {
                heapPeak = maxOf(heapPeak, usedHeap())
                Thread.sleep(50)
            }
        }

        val progress = SpikeProgress()
        val t0 = System.nanoTime()
        try {
            Git.cloneRepository()
                .setURI("https://github.com/OlegNyr/adoc-mobile.git")
                .setDirectory(dir)
                .setDepth(1)
                .setProgressMonitor(progress)
                .call()
                .use { git ->
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    done.set(true)
                    sampler.join()
                    val heapAfter = usedHeap()
                    val nativeAfter = Debug.getNativeHeapAllocatedSize()

                    val head = git.repository.resolve("HEAD")
                    val commits = git.log().call().count()
                    val files = dir.walkTopDown().count { it.isFile }
                    val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    val shallowFile = File(dir, ".git/shallow")

                    Log.i(TAG, "H1 OK: clone depth=1 https за ${elapsedMs} мс")
                    Log.i(TAG, "H1 HEAD=$head, коммитов в log=$commits, shallow-файл=${shallowFile.exists()}")
                    Log.i(TAG, "H1 файлов=$files, байт=$bytes")
                    Log.i(
                        TAG,
                        "H1 heap: до=${heapBefore / 1024} KiB, после=${heapAfter / 1024} KiB, " +
                            "пик=${heapPeak / 1024} KiB, дельта-пик=${(heapPeak - heapBefore) / 1024} KiB",
                    )
                    Log.i(
                        TAG,
                        "H1 native: до=${nativeBefore / 1024} KiB, после=${nativeAfter / 1024} KiB",
                    )
                    Log.i(TAG, "H1 прогресс: ${progress.tasks.joinToString(" | ")}")

                    assertTrue(head != null, "HEAD не разрешился после клона")
                    assertTrue(commits == 1, "depth=1, но log насчитал $commits коммитов")
                    assertTrue(files > 10, "подозрительно мало файлов после клона: $files")
                }
        } catch (e: Throwable) {
            done.set(true)
            Log.e(TAG, "H1 FAIL: ${e::class.java.name}: ${e.message}", e)
            var cause = e.cause
            while (cause != null) {
                Log.e(TAG, "H1 cause: ${cause::class.java.name}: ${cause.message}")
                cause = cause.cause
            }
            throw e
        }
    }

    // ------------------------------------------------------------------ H2

    /**
     * H2: bare-remote в приватном каталоге (file://), shallow clone depth=1,
     * правка, commit, push обратно. Вопрос гипотезы — доходит ли push из
     * shallow-клона; заодно фиксируем, поддерживает ли локальный транспорт
     * JGit shallow вообще (в log должен остаться один коммит).
     */
    @Test
    fun h2_pushFromShallowClone() {
        val bare = freshDir("spike-h2-bare")
        val seed = freshDir("spike-h2-seed")
        val shallow = freshDir("spike-h2-shallow")
        val bareUri = "file://" + bare.absolutePath

        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()

        // Наполняем remote историей из двух коммитов, чтобы depth=1 её реально обрезал.
        Git.init().setInitialBranch("main").setDirectory(seed).call().use { git ->
            File(seed, "a.adoc").writeText("= Документ A\n\nПервая версия.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("первый").setAuthor("Spike", "spike@example.com")
                .setCommitter("Spike", "spike@example.com").setSign(false).call()
            File(seed, "b.adoc").writeText("= Документ B\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("второй").setAuthor("Spike", "spike@example.com")
                .setCommitter("Spike", "spike@example.com").setSign(false).call()
            val results = git.push().setRemote(bareUri).add("refs/heads/main").call()
            results.forEach { r ->
                r.remoteUpdates.forEach { u ->
                    check(u.status == RemoteRefUpdate.Status.OK) { "seed push: ${u.status} ${u.message}" }
                }
            }
        }

        val t0 = System.nanoTime()
        val cloneResult = runCatching {
            Git.cloneRepository()
                .setURI(bareUri)
                .setDirectory(shallow)
                .setBranch("main")
                .setDepth(1)
                .call()
        }
        val git = cloneResult.getOrElse { e ->
            Log.e(TAG, "H2 FAIL на shallow clone по file://: ${e::class.java.name}: ${e.message}", e)
            throw e
        }
        val cloneMs = (System.nanoTime() - t0) / 1_000_000

        git.use {
            val commitsInShallow = it.log().call().count()
            val shallowMarker = File(shallow, ".git/shallow").exists()
            Log.i(TAG, "H2 shallow clone file:// за $cloneMs мс; log=$commitsInShallow, shallow-файл=$shallowMarker")

            File(shallow, "a.adoc").appendText("\nПравка с устройства.\n")
            it.add().addFilepattern("a.adoc").call()
            it.commit().setMessage("третий, из shallow").setAuthor("Spike", "spike@example.com")
                .setCommitter("Spike", "spike@example.com").setSign(false).call()

            val pushT0 = System.nanoTime()
            val results = try {
                it.push().setRemote("origin").add("refs/heads/main").call()
            } catch (e: Throwable) {
                Log.e(TAG, "H2 FAIL на push из shallow: ${e::class.java.name}: ${e.message}", e)
                throw e
            }
            val pushMs = (System.nanoTime() - pushT0) / 1_000_000

            val statuses = results.flatMap { r -> r.remoteUpdates.map { u -> "${u.remoteName}=${u.status}" } }
            Log.i(TAG, "H2 push из shallow за $pushMs мс: ${statuses.joinToString()}")
            results.forEach { r ->
                r.remoteUpdates.forEach { u ->
                    assertTrue(
                        u.status == RemoteRefUpdate.Status.OK,
                        "push из shallow не прошёл: ${u.status} ${u.message}",
                    )
                }
            }
        }

        // Контроль на стороне remote: новый коммит действительно доехал.
        Git.open(bare).use { bareGit ->
            val tip = bareGit.repository.resolve("refs/heads/main")
            val messages = bareGit.log().add(tip).setMaxCount(1).call().map { c -> c.shortMessage }
            Log.i(TAG, "H2 вершина remote после push: $tip «${messages.firstOrNull()}»")
            assertTrue(
                messages.firstOrNull() == "третий, из shallow",
                "вершина remote не совпала: $messages",
            )
        }
        Log.i(TAG, "H2 OK: push из shallow-клона прошёл")
    }

    // ------------------------------------------------------------------ H3

    /**
     * H3: цена первой git-операции процесса против последующих.
     * Запускать отдельным вызовом am instrument (свежий процесс!) — иначе
     * другой тест уже оплатил замер FileStoreAttributes, и «холода» не видно.
     */
    @Test
    fun h3_firstOperationColdVsWarm() {
        fun cycleMs(name: String): Long {
            val dir = freshDir(name)
            val t0 = System.nanoTime()
            Git.init().setInitialBranch("main").setDirectory(dir).call().use { git ->
                File(dir, "a.adoc").writeText("= A\n")
                git.add().addFilepattern(".").call()
                git.commit().setMessage("c").setAuthor("Spike", "spike@example.com")
                    .setCommitter("Spike", "spike@example.com").setSign(false).call()
            }
            return (System.nanoTime() - t0) / 1_000_000
        }

        val cold = cycleMs("spike-h3-one")
        val warm1 = cycleMs("spike-h3-two")
        val warm2 = cycleMs("spike-h3-three")
        Log.i(TAG, "H3 init+add+commit: холодный=$cold мс, тёплый1=$warm1 мс, тёплый2=$warm2 мс")
        // Вердикт по числам выносим в research.adoc, жёсткого порога нет:
        // спайк фиксирует факт, а не проверяет требование.
    }

    // ------------------------------------------------------------------ H5

    /**
     * H5, жертва: честно берём index.lock через JGit (lockDirCache) и виснем.
     * Хост в это время делает am force-stop — процесс умирает с занятым lock.
     * Разблокировки в конце нет намеренно: если force-stop не пришёл,
     * тест сам оставит lock по таймауту — состояние то же.
     */
    @Test
    fun h5_killVictimHoldsIndexLock() {
        val dir = freshDir("spike-h5-kill")
        Git.init().setInitialBranch("main").setDirectory(dir).call().use { git ->
            File(dir, "a.adoc").writeText("= A\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("база").setAuthor("Spike", "spike@example.com")
                .setCommitter("Spike", "spike@example.com").setSign(false).call()

            git.repository.lockDirCache()
            Log.i(TAG, "H5 victim: index.lock взят=${File(dir, ".git/index.lock").exists()}, жду force-stop")
            Thread.sleep(60_000)
        }
        Log.i(TAG, "H5 victim: force-stop не пришёл, lock оставлен по таймауту")
    }

    /**
     * H5, разбор после убийства: что осталось на диске, как падает следующая
     * операция и лечится ли удалением lock-файла. Запускается отдельным
     * вызовом после force-stop.
     */
    @Test
    fun h5_inspectAfterKill() {
        val dir = File(ctx.filesDir, "spike-h5-kill")
        val lock = File(dir, ".git/index.lock")
        Log.i(TAG, "H5 inspect: каталог=${dir.exists()}, index.lock=${lock.exists()}")
        assertTrue(dir.exists(), "жертва не создала репозиторий — сначала запусти victim")
        assertTrue(lock.exists(), "stale index.lock не остался — гипотеза H5 в этой части не подтверждена")

        Git.open(dir).use { git ->
            File(dir, "a.adoc").appendText("правка после смерти процесса\n")
            try {
                git.add().addFilepattern(".").call()
                fail("add прошёл при живом index.lock — JGit его молча перезаписал?")
            } catch (e: Throwable) {
                Log.i(TAG, "H5 операция при stale-lock: ${e::class.java.name}: ${e.message}")
                var cause = e.cause
                while (cause != null) {
                    Log.i(TAG, "H5 cause: ${cause::class.java.name}: ${cause.message}")
                    cause = cause.cause
                }
            }

            // Протокол лечения: удалить lock и повторить.
            assertTrue(lock.delete(), "index.lock не удалился")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("после лечения").setAuthor("Spike", "spike@example.com")
                .setCommitter("Spike", "spike@example.com").setSign(false).call()
            val commits = git.log().call().count()
            Log.i(TAG, "H5 OK: после удаления lock операция прошла, коммитов=$commits")
            assertTrue(commits == 2, "ожидались 2 коммита, есть $commits")
        }
    }

    /**
     * H5, запасной путь без force-stop: lock-файл создаётся руками — ровно то,
     * что остаётся от убитого процесса. Даёт точную ошибку и протокол лечения
     * даже если оркестровка с force-stop не удалась.
     */
    @Test
    fun h5_staleLockArtificial() {
        val dir = freshDir("spike-h5-artificial")
        Git.init().setInitialBranch("main").setDirectory(dir).call().use { git ->
            File(dir, "a.adoc").writeText("= A\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("база").setAuthor("Spike", "spike@example.com")
                .setCommitter("Spike", "spike@example.com").setSign(false).call()

            val lock = File(dir, ".git/index.lock")
            check(lock.createNewFile()) { "не удалось создать $lock" }
            File(dir, "a.adoc").appendText("правка\n")
            try {
                git.add().addFilepattern(".").call()
                fail("add прошёл при живом index.lock")
            } catch (e: Throwable) {
                Log.i(TAG, "H5a операция при stale-lock: ${e::class.java.name}: ${e.message}")
                var cause = e.cause
                while (cause != null) {
                    Log.i(TAG, "H5a cause: ${cause::class.java.name}: ${cause.message}")
                    cause = cause.cause
                }
            }
            assertTrue(lock.delete(), "index.lock не удалился")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("после лечения").setAuthor("Spike", "spike@example.com")
                .setCommitter("Spike", "spike@example.com").setSign(false).call()
            Log.i(TAG, "H5a OK: удаление lock лечит, коммитов=${git.log().call().count()}")
        }
    }

    private companion object {
        const val TAG = "GitSpike"
    }
}
