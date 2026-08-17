package io.github.olegnyr.adocmobile.document

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Исполнитель действий [AutosavePolicy]: политика решает, этот класс делает.
 *
 * Слайс `SL-6` фичи 004 — последнее звено автосохранения. Политика — чистый
 * автомат и нарочно не умеет ни ждать, ни писать; здесь её действия получают
 * исполнение: [AutosaveAction.WaitUntil] — отложенной корутиной,
 * [AutosaveAction.Write] — вызовом шва [DocumentFileAccess.write], а результат
 * записи возвращается в политику через `writeSucceeded`/`writeFailed` и
 * обновляет документ.
 *
 * Разделение обязанностей на границе шва: политика выдаёт в `Write` *снимок
 * текста редактора*; исполнитель разворачивает его в текст файла
 * ([String.toFileText] по типу переводов строк документа); шов кодирует UTF-8
 * и пишет байты. Так переводы строк восстанавливает модель, а не платформа
 * (`FR-5`), и записанным считается ровно тот снимок, который ушёл в файл
 * (`TC-19` — правка во время записи не помечается сохранённой).
 *
 * Часы и ожидание — параметры, а не внутренности: тесты подставляют свои и
 * проверяют исполнителя без реального времени, тем же приёмом, что
 * `PreviewPolicyTest`. Экземпляр, как и политика, живёт один на открытый
 * документ.
 *
 * Класс не потокобезопасен намеренно: события приходят из композиции, то есть
 * с главного потока, и [scope] предполагается привязанным к нему же. Сам
 * ввод-вывод при этом главный поток не держит: шов обязан уходить с него
 * (`suspend` в контракте).
 *
 * @param scope область корутин экрана: её отмена снимает и ожидания, и записи
 * @param clock источник времени в миллисекундах эпохи Unix — та же шкала, что
 * у [DocumentState.savedAt]
 * @param delayUntil ожидание до момента `dueAt`; умолчание — [delay] по часам
 */
class AutosaveRunner(
    initialDocument: DocumentState,
    private val access: DocumentFileAccess,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val policy: AutosavePolicy = AutosavePolicy(),
    private val delayUntil: suspend (dueAt: Long) -> Unit = { dueAt ->
        delay((dueAt - clock()).coerceAtLeast(0))
    },
) {

    private val state = MutableStateFlow(initialDocument)

    /** Документ как поток — для подписки экрана (заголовок, метка состояния). */
    val documents: StateFlow<DocumentState> = state

    /** Текущее состояние документа. */
    val document: DocumentState get() = state.value

    /** Состояние автосохранения для показа пользователю — прокси к политике. */
    val status: AutosaveStatus get() = policy.status

    private var waitJob: Job? = null

    /**
     * Пользователь изменил текст.
     *
     * Принимает текст редактора; нормализацию и признак изменений делает
     * модель ([DocumentState.edited]), решение о записи — политика.
     */
    fun textEdited(newText: String) {
        state.value = document.edited(newText)
        perform(policy.textEdited(document, clock()))
    }

    /** Приложение уходит в фон: политика велит писать немедленно (`FR-16`). */
    fun movedToBackground() {
        perform(policy.movedToBackground(document, clock()))
    }

    /** Ручной повтор записи после отказа — второй способ снять паузу `FR-19`. */
    fun retryRequested() {
        perform(policy.retryRequested(document, clock()))
    }

    private fun perform(action: AutosaveAction) {
        when (action) {
            // Устаревшее ожидание не отменяется: сработав, оно позовёт
            // pauseElapsed, и политика ответит Idle — так задумано её
            // контрактом («более ранний вызов не ошибка»).
            AutosaveAction.Idle -> Unit
            is AutosaveAction.WaitUntil -> scheduleWait(action.dueAt)
            is AutosaveAction.Write -> launchWrite(action.text)
        }
    }

    /**
     * Ждать срока и спросить политику снова.
     *
     * Прежнее ожидание отменяется: правка сдвинула срок, и два таймера на один
     * дебаунс — это два лишних пробуждения с бессмысленными `Idle` в ответ.
     */
    private fun scheduleWait(dueAt: Long) {
        waitJob?.cancel()
        waitJob = scope.launch {
            delayUntil(dueAt)
            perform(policy.pauseElapsed(document, clock()))
        }
    }

    /**
     * Исполнить запись снимка и вернуть исход политике.
     *
     * В файл уходит текст файла, развёрнутый из снимка по типу переводов строк
     * документа. Тип берётся из текущего документа безопасно: он неизменен на
     * всю жизнь открытого документа (задаётся при чтении, `FR-4`).
     */
    private fun launchWrite(snapshot: String) {
        scope.launch {
            val result = access.write(document.source, snapshot.toFileText(document.lineEnding))
            val outcome = when (result) {
                DocumentWriteResult.Written -> policy.writeSucceeded(document, clock())
                is DocumentWriteResult.Failed -> policy.writeFailed(document)
            }
            state.value = outcome.document
            perform(outcome.action)
        }
    }
}
