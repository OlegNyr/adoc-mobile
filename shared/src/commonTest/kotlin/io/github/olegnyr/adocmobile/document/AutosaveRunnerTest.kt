package io.github.olegnyr.adocmobile.document

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Слайс `SL-6` фичи 004: хвост `TC-15`…`TC-19` — от политики до диска.
 *
 * [AutosavePolicy] проверена своими тестами как чистый автомат; здесь
 * проверяется *исполнитель* — что действия политики доезжают до шва записи,
 * а результаты записи возвращаются в политику и в модель документа.
 *
 * Инструментов времени нет намеренно, по образцу `PreviewPolicyTest`:
 * часы — обычная переменная, ожидание паузы — подставная функция с воротами,
 * шов — подделка интерфейса (тот же приём, что сделал `DocumentFileAccess`
 * интерфейсом, а не `expect`/`actual`). Диспетчер `Unconfined` выполняет
 * корутины синхронно до первой точки приостановки, и тест управляет каждой.
 */
class AutosaveRunnerTest {

    private var now = 0L
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val access = FakeAccess()
    private val waits = FakeWaits()

    private val source = DocumentSource(id = "content://doc/1", displayName = "заметка.adoc")

    private fun runner(fileText: String): AutosaveRunner = AutosaveRunner(
        initialDocument = DocumentState.opened(source, fileText),
        access = access,
        scope = scope,
        clock = { now },
        delayUntil = waits::wait,
    )

    @Test
    fun TC_15_pauseWritesExactlyOnceThroughSeam() {
        val runner = runner("строка")

        runner.textEdited("строка!")
        now = 400
        runner.textEdited("строка!!")

        // Две правки — два запланированных срока, но ни одной записи до паузы.
        assertEquals(2, waits.requested.size)
        assertEquals(0, access.writeCalls, "запись до истечения паузы")

        now = 400 + AutosavePolicy.DEFAULT_PAUSE_MILLIS
        waits.releaseLast()

        assertEquals(1, access.writeCalls, "пауза даёт ровно одну запись")
        assertEquals("строка!!", access.written.single())
        assertFalse(runner.document.isModified, "успешная запись гасит признак изменений")
        assertEquals(now, runner.document.savedAt)
    }

    @Test
    fun TC_16_movingToBackgroundWritesImmediately() {
        val runner = runner("текст")

        runner.textEdited("текст-правка")
        runner.movedToBackground()

        assertEquals(1, access.writeCalls, "фон пишет сразу, не дожидаясь паузы")
        assertEquals("текст-правка", access.written.single())
        assertFalse(runner.document.isModified)
    }

    @Test
    fun TC_17_failedWriteKeepsTextAndIsVisible() {
        access.result = DocumentWriteResult.Failed(source, DocumentWriteError.WriteFailed)
        val runner = runner("текст")

        runner.textEdited("несохранённое")
        runner.movedToBackground()

        assertEquals(1, access.writeCalls)
        assertTrue(runner.document.isModified, "текст остался несохранённым")
        assertEquals("несохранённое", runner.document.text, "текст не потерян")
        assertTrue(runner.document.lastSaveFailed, "отказ виден модели (FR-17)")
        assertEquals(AutosaveStatus.Suspended, runner.status, "отказ виден статусом (FR-19)")
    }

    @Test
    fun TC_18_afterFailureNoRetryUntilEditOrManualRetry() {
        access.result = DocumentWriteResult.Failed(source, DocumentWriteError.WriteFailed)
        val runner = runner("текст")
        runner.textEdited("правка")
        runner.movedToBackground()
        assertEquals(1, access.writeCalls)

        // Повторный уход в фон попытку не возобновляет — способов ровно два.
        runner.movedToBackground()
        assertEquals(1, access.writeCalls, "фон после отказа не бьётся о закрытый файл")

        // Ручной повтор — возобновляет, и запись теперь удаётся.
        access.result = null
        now = 5_000
        runner.retryRequested()
        assertEquals(2, access.writeCalls)
        assertFalse(runner.document.isModified)
        assertEquals(AutosaveStatus.Idle, runner.status)
    }

    @Test
    fun TC_19_editDuringWriteIsNotMarkedSaved() {
        val gate = CompletableDeferred<DocumentWriteResult>()
        access.gate = gate
        val runner = runner("v0")

        runner.textEdited("v1")
        runner.movedToBackground()
        assertEquals(1, access.writeCalls, "запись «v1» пошла и висит на вводе-выводе")

        // Пока запись идёт, пользователь продолжает печатать.
        now = 100
        runner.textEdited("v2")

        access.gate = null
        gate.complete(DocumentWriteResult.Written)

        // Сохранённым стал снимок «v1», а не текущий текст: правка «v2» не
        // имеет права считаться лежащей на диске.
        assertEquals("v1", runner.document.savedText)
        assertEquals("v2", runner.document.text)
        assertTrue(runner.document.isModified, "правка во время записи не потеряна и не «сохранена»")

        // Хвост уходит своей записью после паузы.
        now = 100 + AutosavePolicy.DEFAULT_PAUSE_MILLIS
        waits.releaseLast()
        assertEquals(2, access.writeCalls)
        assertEquals("v2", access.written.last())
        assertFalse(runner.document.isModified)
    }

    @Test
    fun TC_6_crlfDocumentIsWrittenWithCrlf() {
        val runner = runner("первая\r\nвторая")

        runner.textEdited("первая\nвторая правленая")
        runner.movedToBackground()

        // Шов получает текст файла, а не текст редактора: переводы строк
        // восстановила модель (`fileText()`), шву достались готовые байты.
        assertEquals("первая\r\nвторая правленая", access.written.single())
        assertEquals(runner.document.fileText(), access.written.single())
    }

    @Test
    fun TC_15_returningToSavedTextCancelsPendingWrite() {
        val runner = runner("исходный")

        runner.textEdited("исходный+")
        runner.textEdited("исходный")

        now = AutosavePolicy.DEFAULT_PAUSE_MILLIS + 1
        waits.releaseAll()

        assertEquals(0, access.writeCalls, "текст совпал с диском — переписывать файл незачем (FR-8)")
        assertEquals(AutosaveStatus.Idle, runner.status)
    }

    /** Шов-подделка: записи копятся, исход задаётся тестом, ввод-вывод — воротами. */
    private class FakeAccess : DocumentFileAccess {
        val written = mutableListOf<String>()
        var writeCalls = 0

        /** Исход ближайшей записи; `null` — успех. */
        var result: DocumentWriteResult? = null

        /** Ворота ввода-вывода: пока стоят, запись «идёт». */
        var gate: CompletableDeferred<DocumentWriteResult>? = null

        override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult {
            writeCalls++
            written += fileText
            gate?.let { return it.await() }
            return result ?: DocumentWriteResult.Written
        }

        override suspend fun open(source: DocumentSource): DocumentOpenResult =
            error("чтение в этих тестах не используется")

        override fun heldSource(): DocumentSource? = null

        override fun release() = Unit
    }

    /** Подставное ожидание паузы: сроки записываются, продолжение — за тестом. */
    private class FakeWaits {
        val requested = mutableListOf<Long>()
        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        suspend fun wait(dueAt: Long) {
            requested += dueAt
            val gate = CompletableDeferred<Unit>()
            gates += gate
            gate.await()
        }

        fun releaseLast() {
            gates.last().complete(Unit)
        }

        fun releaseAll() {
            gates.forEach { it.complete(Unit) }
        }
    }
}
