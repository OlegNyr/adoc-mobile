package io.github.olegnyr.adocmobile.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Пауза ввода в тестах: круглая и короткая, чтобы арифметика в проверках читалась. */
private const val PAUSE = 1_000L

private const val ORIGINAL = "= Заметки\n\nТекст.\n"

/**
 * Стенд для политики автосохранения: виртуальные часы, виртуальный таймер и
 * виртуальная запись в файл.
 *
 * Часы и таймер здесь, а не в политике, — в этом и смысл: политика получает
 * время параметром, поэтому весь `SL-4` проверяется без устройства и без
 * `delay`. Запись «идёт» ровно столько, сколько тест не вызывает [completeWrite]
 * или [failWrite] — так проверяется поведение при правке во время записи
 * (`TC-19`), которое иначе воспроизводится только гонкой.
 */
private class AutosaveHarness(pauseMillis: Long = PAUSE) {

    val policy = AutosavePolicy(pauseMillis)

    var document: DocumentState = DocumentState.opened(
        source = DocumentSource(id = "content://doc/1", displayName = "notes.adoc"),
        fileText = ORIGINAL,
    )
        private set

    var now: Long = 0
        private set

    /** Тексты, с которыми была начата запись, по порядку. */
    val writes = mutableListOf<String>()

    private var timer: Long? = null
    private var writing: String? = null

    val isWriting: Boolean get() = writing != null

    fun type(text: String) {
        document = document.edited(text)
        apply(policy.textEdited(document, now))
    }

    fun goToBackground() = apply(policy.movedToBackground(document, now))

    fun requestRetry() = apply(policy.retryRequested(document, now))

    /** Провернуть часы до [target], срабатывая запланированные таймеры по дороге. */
    fun advanceTo(target: Long) {
        while (true) {
            val fires = timer ?: break
            if (fires > target) break
            now = maxOf(now, fires)
            timer = null
            apply(policy.pauseElapsed(document, now))
        }
        now = maxOf(now, target)
    }

    fun advanceBy(millis: Long) = advanceTo(now + millis)

    fun completeWrite() {
        checkNotNull(writing) { "записи не идёт — завершать нечего" }
        writing = null
        val outcome = policy.writeSucceeded(document, now)
        document = outcome.document
        apply(outcome.action)
    }

    fun failWrite() {
        checkNotNull(writing) { "записи не идёт — проваливать нечего" }
        writing = null
        val outcome = policy.writeFailed(document)
        document = outcome.document
        apply(outcome.action)
    }

    private fun apply(action: AutosaveAction) {
        when (action) {
            AutosaveAction.Idle -> Unit
            is AutosaveAction.WaitUntil -> timer = action.dueAt
            is AutosaveAction.Write -> {
                writing = action.text
                writes += action.text
            }
        }
    }
}

/**
 * Критерии приёмки фичи 004-document-and-files, слайс SL-4: `TC-15`…`TC-19`.
 *
 * Проверяется политика автосохранения: дебаунс паузы ввода, немедленная запись
 * при уходе в фон, видимый отказ и приостановка после него (`FR-16`…`FR-19`).
 */
class AutosavePolicyTest {

    @Test
    fun TC_15_pauseTriggersExactlyOneWrite() {
        // Смысл кейса — «ровно один раз, а не на каждый символ»: каждая правка
        // сдвигает паузу, и запись уходит одна, с последним текстом.
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL а")
        harness.advanceBy(100)
        harness.type("$ORIGINAL аб")
        harness.advanceBy(100)
        harness.type("$ORIGINAL абв")

        assertTrue(harness.writes.isEmpty(), "пока идёт набор, писать нечего")

        harness.advanceBy(PAUSE * 3)

        assertEquals(listOf("$ORIGINAL абв"), harness.writes, "одна запись с последним текстом, а не три")
    }

    @Test
    fun TC_15_writeIsNotRepeatedAfterItSucceeded() {
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL правка")
        harness.advanceBy(PAUSE)
        harness.completeWrite()

        harness.advanceBy(PAUSE * 10)

        assertEquals(1, harness.writes.size, "текст записан — новых попыток быть не должно")
        assertFalse(harness.document.isModified, "после успешной записи признак обязан погаснуть")
        assertEquals(PAUSE, harness.document.savedAt, "отметка времени проставлена по факту записи")
        assertEquals(AutosaveStatus.Idle, harness.policy.status)
    }

    @Test
    fun TC_16_movingToBackgroundWritesImmediately() {
        // Уход в фон не ждёт паузы: система вправе выгрузить процесс сразу после.
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL правка")
        harness.advanceBy(10)

        harness.goToBackground()

        assertEquals(listOf("$ORIGINAL правка"), harness.writes)
        assertTrue(harness.now < PAUSE, "запись ушла до истечения паузы ввода")
    }

    @Test
    fun TC_16_backgroundWithoutChangesWritesNothing() {
        val harness = AutosaveHarness()

        harness.goToBackground()

        assertTrue(harness.writes.isEmpty(), "расхождения с диском нет — переписывать файл незачем")
    }

    @Test
    fun TC_17_failedWriteStaysVisibleAndTextSurvives() {
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL несохранённое")
        harness.advanceBy(PAUSE)
        assertTrue(harness.isWriting)

        harness.failWrite()

        assertTrue(harness.document.lastSaveFailed, "на этом признаке держится сообщение пользователю (FR-17)")
        assertEquals(AutosaveStatus.Suspended, harness.policy.status)
        assertTrue(harness.document.isModified, "признак изменений отказ не гасит")
        assertEquals("$ORIGINAL несохранённое", harness.document.text, "текст остаётся в памяти")
        assertNull(harness.document.savedAt, "отметка времени не сдвигается: записи не было")
    }

    @Test
    fun TC_18_failureSuspendsAutosaveUntilNextEdit() {
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL первая")
        harness.advanceBy(PAUSE)
        harness.failWrite()

        // Ни время, ни уход в фон попытку не возобновляют: приложение не должно
        // биться о закрытый файл каждую паузу ввода (FR-19).
        harness.advanceBy(PAUSE * 100)
        harness.goToBackground()

        assertEquals(1, harness.writes.size, "после отказа автосохранение приостановлено")

        harness.type("$ORIGINAL вторая")
        harness.advanceBy(PAUSE)

        assertEquals(
            listOf("$ORIGINAL первая", "$ORIGINAL вторая"),
            harness.writes,
            "явная правка снимает приостановку",
        )
    }

    @Test
    fun TC_18_manualRetryResumesAfterFailure() {
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL правка")
        harness.advanceBy(PAUSE)
        harness.failWrite()

        harness.requestRetry()

        assertEquals(listOf("$ORIGINAL правка", "$ORIGINAL правка"), harness.writes)
        assertEquals(AutosaveStatus.Writing, harness.policy.status)
    }

    @Test
    fun TC_19_editDuringWriteIsNotLost() {
        // Главный кейс слайса. Запись не блокирует ввод, значит текст на диске и
        // текст в редакторе на момент завершения записи — разные вещи, и путать
        // их нельзя: иначе правка, сделанная во время записи, молча считается
        // сохранённой и пропадает при выгрузке приложения.
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL первая версия")
        harness.advanceBy(PAUSE)
        assertTrue(harness.isWriting)

        harness.type("$ORIGINAL первая версия и хвост")
        harness.completeWrite()

        assertEquals("$ORIGINAL первая версия", harness.document.savedText, "на диск ушёл снимок, а не текущий текст")
        assertEquals("$ORIGINAL первая версия и хвост", harness.document.text, "правка во время записи цела")
        assertTrue(harness.document.isModified, "хвост ещё не на диске — признак обязан гореть")

        harness.advanceBy(PAUSE * 2)
        harness.completeWrite()

        assertEquals(
            listOf("$ORIGINAL первая версия", "$ORIGINAL первая версия и хвост"),
            harness.writes,
            "хвост дописывается следующей записью",
        )
        assertFalse(harness.document.isModified)
    }

    @Test
    fun TC_19_secondWriteDoesNotStartWhileFirstIsInFlight() {
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL а")
        harness.advanceBy(PAUSE)

        harness.type("$ORIGINAL аб")
        harness.advanceBy(PAUSE * 5)
        harness.goToBackground()

        assertEquals(listOf("$ORIGINAL а"), harness.writes, "двух записей в один файл одновременно быть не может")

        harness.completeWrite()

        assertEquals(
            listOf("$ORIGINAL а", "$ORIGINAL аб"),
            harness.writes,
            "просроченный хвост уходит сразу после текущей записи, а не ждёт новой паузы",
        )
    }

    @Test
    fun FR_8_revertingBeforeTheWriteCancelsIt() {
        // Расширение UC-2: правка отменена до записи и текст совпал с сохранённым —
        // признак гаснет *без записи*. Отдельного TC в спеке нет, требование есть.
        val harness = AutosaveHarness()
        harness.type("$ORIGINAL правка")
        harness.type(ORIGINAL)

        harness.advanceBy(PAUSE * 3)

        assertTrue(harness.writes.isEmpty(), "текст вернулся к сохранённому — переписывать файл незачем")
        assertEquals(AutosaveStatus.Idle, harness.policy.status)
    }

    @Test
    fun FR_18_statusFollowsTheSaveCycle() {
        val harness = AutosaveHarness()
        assertEquals(AutosaveStatus.Idle, harness.policy.status)

        harness.type("$ORIGINAL правка")
        assertEquals(AutosaveStatus.Pending, harness.policy.status, "пауза ввода идёт")

        harness.advanceBy(PAUSE)
        assertEquals(AutosaveStatus.Writing, harness.policy.status)

        harness.completeWrite()
        assertEquals(AutosaveStatus.Idle, harness.policy.status)
    }

    @Test
    fun TC_15_lateTimerDoesNotWriteWhilePauseIsStillRunning() {
        // Вызывающий вправе позвать pauseElapsed раньше времени — политика не
        // должна на это писать, а обязана вернуть новый срок ожидания.
        val harness = AutosaveHarness(pauseMillis = PAUSE)
        harness.type("$ORIGINAL правка")

        val action = harness.policy.pauseElapsed(harness.document, now = 1)

        assertEquals(AutosaveAction.WaitUntil(PAUSE), action)
        assertTrue(harness.writes.isEmpty())
    }
}
