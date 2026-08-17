package io.github.olegnyr.adocmobile.render

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CancellationException as JavaCancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Фича 003-render-preview, слайс `SL-5`: какой исход рендера переживает движок.
 *
 * Тест на хосте, без устройства: решение — чистая функция, а движок ей не
 * нужен. Что прерванная конвертация действительно прервана и что процесс жив
 * после отказа — половина `TC-8`/`TC-9`, требующая прогона движка на
 * устройстве; она в журнале слайса, не здесь.
 *
 * Главная ловушка — имена: `QuickJsInterruptedException` звучит как отмена, но
 * это *таймаут* (`FR-11`) — отказ движка, после которого движок пересоздаётся
 * (постусловие `UC-4`). Отменой является только `CancellationException` —
 * штатная работа пайплайна (`FR-10`), за которую нельзя платить пересозданием:
 * при быстром наборе она случается на каждую правку, и 46–49 мс подъёма движка
 * на каждую отмену похоронили бы смысл синглтона (`FR-3`).
 */
class EngineRecoveryTest {

    // ---- TC-8: отказ движка — движок пересоздаётся, следующий рендер штатный ----

    @Test
    fun TC_8_engineExceptionDiscardsEngine() {
        assertFalse(engineSurvives(QuickJsException("SyntaxError: …")))
    }

    @Test
    fun TC_8_stackOverflowDiscardsEngine() {
        // Переполнение стека потока движка — StackOverflowError, не Exception:
        // решение обязано принимать Throwable, а не сужать до Exception.
        assertFalse(engineSurvives(StackOverflowError()))
    }

    @Test
    fun TC_8_memoryExhaustionDiscardsEngine() {
        assertFalse(engineSurvives(OutOfMemoryError()))
    }

    @Test
    fun TC_8_unknownFailureDiscardsEngineByDefault() {
        // Неизвестный отказ — не повод доверять движку: умолчание — пересоздать.
        assertFalse(engineSurvives(IllegalStateException("движку плохо")))
    }

    // ---- TC-9: таймаут — отказ, а не отмена ----

    @Test
    fun TC_9_timeoutDiscardsEngineDespiteInterruptedName() {
        // evaluationTimeoutMillis кидает QuickJsInterruptedException — по имени
        // «прерывание», по смыслу отказ FR-11: неизвестно, на чём остановился
        // Opal-рантайм, и следующий вызов не имеет права это наследовать.
        assertFalse(engineSurvives(QuickJsInterruptedException("Interrupted")))
    }

    // ---- FR-10: отмена — штатная работа пайплайна, движок остаётся ----

    @Test
    fun FR_10_cancellationKeepsEngine() {
        assertTrue(engineSurvives(CancellationException("superseded")))
    }

    @Test
    fun FR_10_cancellationSubclassKeepsEngine() {
        // kotlinx кидает наследников (JobCancellationException) — решение
        // обязано узнавать их по типу-предку, а не по точному классу.
        class ChildCancellation : JavaCancellationException("child")
        assertTrue(engineSurvives(ChildCancellation()))
    }
}
