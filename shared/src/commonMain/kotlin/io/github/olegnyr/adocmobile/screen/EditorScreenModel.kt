package io.github.olegnyr.adocmobile.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.document.AutosaveRunner
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentFileAccess
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Что показывает экран редактора: документа нет, документ не открылся, документ открыт.
 *
 * Отдельный тип, а не пара флагов, — тем же приёмом, что `AccessScreenState`
 * временной поверхности: «нет документа» и «не открылся» различаются действием
 * пользователя (у второго есть сообщение `FR-9`), и смешивать их нельзя.
 */
sealed interface EditorDocument {

    /** Документа нет: первый запуск или источник не удерживается. */
    data object None : EditorDocument

    /**
     * Открытие не удалось; [message] — готовый текст для пользователя
     * (`DocumentAccessError.userMessage`, `FR-9`). Экран остаётся в состоянии
     * «нет документа» с возможностью выбрать файл заново.
     */
    data class OpenFailed(val message: String) : EditorDocument

    /** Документ открыт; [runner] несёт модель документа и автосохранение. */
    data class Open(val runner: AutosaveRunner) : EditorDocument
}

/**
 * Метка состояния документа для app bar (`FR-1`).
 *
 * Два состояния по макету «02» — решение владельца `OQ-7`: `Writing` отдельно
 * не показывается, заметность отказа записи понесёт плашка (`OQ-3`, слайс `SL-3`).
 * Состояние передаётся текстом, а не только цветом (`NFR-9`).
 */
fun editorStatusLabel(document: DocumentState): String? =
    if (document.isModified) EDITOR_MODIFIED_LABEL else null

/** Текст метки несохранённых изменений — дословно из макета «02». */
const val EDITOR_MODIFIED_LABEL: String = "ИЗМЕНЁН · НЕ СОХРАНЁН"

/**
 * Holder экрана редактора: владелец всего изменяемого состояния экрана (`FR-6`).
 *
 * Несоставной класс без композиции — по требованию `NFR-10`: логика экрана
 * проверяется в `commonTest` подставными швами, часы и ожидание приходят
 * параметрами (тот же приём, что у [AutosaveRunner]).
 *
 * Holder соединяет готовые кирпичи и не переписывает их:
 *
 * * текст живёт в одном `TextFieldState` внутри [editor] — его обязан создать
 *   экран через `rememberSaveable` (`FR-7`, KDoc [DocumentEditor]);
 * * модель документа и автосохранение — [AutosaveRunner], по экземпляру на
 *   документ: политика рассчитана на один документ (`FR-8`, журнал 004 `SL-4`);
 * * файлы — только через шов [DocumentFileAccess] (`NFR-6`).
 *
 * События приходят из композиции, то есть с главного потока; класс, как и
 * исполнитель автосохранения, не потокобезопасен намеренно.
 *
 * @param scope область корутин экрана: её отмена снимает и открытие, и
 * автосохранение всех документов
 * @param clock источник времени в миллисекундах эпохи Unix
 * @param delayUntil ожидание до срока — параметр ради тестов, умолчание — [delay]
 */
class EditorScreenModel(
    val editor: DocumentEditor,
    private val access: DocumentFileAccess,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val delayUntil: suspend (dueAt: Long) -> Unit = { dueAt ->
        delay((dueAt - clock()).coerceAtLeast(0))
    },
) {

    /** Что показывать: наблюдаемое состояние для композиции. */
    var document: EditorDocument by mutableStateOf(EditorDocument.None)
        private set

    /**
     * Область корутин текущего документа — дочерняя к [scope].
     *
     * Отменяется при смене документа: повисшее ожидание паузы или запись
     * прежнего документа не должны пережить его закрытие, иначе хвост записи
     * уйдёт в файл, который пользователь уже не редактирует (`TC-5`).
     */
    private var documentScope: CoroutineScope? = null

    /**
     * Поднять последний удержанный источник (`FR-8`, `UC-1`).
     *
     * @param fieldSourceId источник, чей текст уже лежит в поле ввода, —
     * восстановление `rememberSaveable` после поворота или выгрузки процесса.
     * Совпал с удержанным — текст поля не перетирается загрузкой с диска и
     * история отмены остаётся жива (`FR-7`); поле, разошедшееся с диском, —
     * это несохранённая правка, она помечается изменённой и уходит в файл
     * автосохранением, а не теряется.
     */
    fun start(fieldSourceId: String? = null) {
        if (document is EditorDocument.Open) return
        val held = access.heldSource() ?: return
        open(held, keepField = held.id == fieldSourceId)
    }

    /**
     * Открыть документ по источнику.
     *
     * Отказ открытия — штатный сценарий (`FR-9`): состояние становится
     * [EditorDocument.OpenFailed] с текстом `userMessage`. Если при этом уже
     * открыт другой документ, он остаётся на экране нетронутым — терять рабочий
     * документ из-за неудачной попытки открыть новый нельзя.
     */
    fun open(source: DocumentSource, keepField: Boolean = false) {
        scope.launch {
            when (val result = access.open(source)) {
                is DocumentOpenResult.Opened -> attach(result.document, keepField)
                is DocumentOpenResult.Failed -> {
                    if (document !is EditorDocument.Open) {
                        document = EditorDocument.OpenFailed(result.error.userMessage(source.displayName))
                    }
                }
            }
        }
    }

    /**
     * Каждое изменение текста поля — в модель (`FR-10`).
     *
     * Зовётся подпиской экрана на `TextFieldState` (`snapshotFlow`); без
     * открытого документа изменений быть не может — пустое поле не редактируется.
     */
    fun textEdited(text: String) {
        (document as? EditorDocument.Open)?.runner?.textEdited(text)
    }

    private fun attach(opened: DocumentState, keepField: Boolean) {
        documentScope?.cancel()
        val docScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        documentScope = docScope

        // Новый исполнитель — новая политика автосохранения (параметр по
        // умолчанию конструктора): экземпляр политики рассчитан ровно на один
        // документ (FR-8).
        val runner = AutosaveRunner(
            initialDocument = opened,
            access = access,
            scope = docScope,
            clock = clock,
            delayUntil = delayUntil,
        )

        val fieldText = editor.textFieldState.text.toString()
        if (!keepField) {
            // Загрузка очищает историю отмены (FR-8, 004 FR-14).
            editor.load(opened)
        } else if (fieldText != opened.text) {
            // Восстановленное поле разошлось с диском: это несохранённая правка,
            // модель узнаёт о ней сразу, и автосохранение допишет её в файл.
            runner.textEdited(fieldText)
        }

        document = EditorDocument.Open(runner)
    }
}
