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
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeListResult
import io.github.olegnyr.adocmobile.document.TreeSource
import io.github.olegnyr.adocmobile.preview.previewPage
import io.github.olegnyr.adocmobile.render.AdocRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Что показывает экран редактора: папки нет, папка недоступна, список
 * документов папки, документ открыт.
 *
 * Отдельный тип, а не набор флагов, — тем же приёмом, что `AccessScreenState`
 * временной поверхности: состояния различаются действием пользователя, и
 * смешивать их нельзя. Сценарий — решение владельца `OQ-1`: сначала папка,
 * затем файл в ней (tree-доступ, журнал 004 `SL-7`).
 */
sealed interface EditorDocument {

    /** Папка не выбрана: первый запуск или право на дерево отдано. */
    data object NoFolder : EditorDocument

    /**
     * Папку не удалось перечислить; [message] — готовый текст для пользователя
     * (`TreeAccessError.userMessage`, `FR-3` фичи 004). Действие пользователя —
     * выбрать папку заново.
     */
    data class FolderFailed(val message: String) : EditorDocument

    /**
     * Папка открыта, документ не выбран: `.adoc`-документы её корня по имени
     * (`FR-1a` фичи 004; пустой список — папка без документов, не ошибка).
     *
     * @property notice отказ открытия выбранного документа
     * (`DocumentAccessError.userMessage`, `FR-9`); `null` — отказа не было
     */
    data class Browsing(
        val tree: TreeSource,
        val documents: List<DocumentSource>,
        val notice: String? = null,
    ) : EditorDocument

    /**
     * Документ открыт; [runner] несёт модель документа и автосохранение,
     * [preview] — живой пайплайн превью этого документа (`FR-13`).
     */
    data class Open(val runner: AutosaveRunner, val preview: PreviewPipeline) : EditorDocument
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
 * * файлы и перечень папки — только через шов [DocumentTreeAccess] (`NFR-6`).
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
    private val access: DocumentTreeAccess,
    private val renderer: AdocRenderer,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val page: suspend (fragment: String) -> String = { fragment -> previewPage(fragment) },
    private val delayUntil: suspend (dueAt: Long) -> Unit = { dueAt ->
        delay((dueAt - clock()).coerceAtLeast(0))
    },
) {

    /** Что показывать: наблюдаемое состояние для композиции. */
    var document: EditorDocument by mutableStateOf(EditorDocument.NoFolder)
        private set

    /**
     * Отказ последней записи — текст `DocumentWriteError.userMessage` для
     * плашки под app bar (`FR-12`, решение `OQ-3`); `null` — плашки нет.
     *
     * Гаснет только успешной записью или сменой документа, а не первой же
     * правкой: пока текст не лёг в файл, отказ остаётся правдой, и прятать его
     * по нажатию клавиши значило бы мигать плашкой на каждой попытке.
     */
    var writeFailure: String? by mutableStateOf(null)
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
     * Поднять последний удержанный источник (`FR-8`, `UC-1`); без него —
     * показать документы удержанного дерева, без дерева — пустоту `OQ-1`.
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
        val held = access.heldSource()
        if (held != null) {
            open(held, keepField = held.id == fieldSourceId)
        } else {
            scope.launch { browseHeldTree() }
        }
    }

    /**
     * Платформа взяла право на новое дерево — перечислить его документы.
     *
     * Параметров нет намеренно: право и ссылка уже удержаны платформенной
     * половиной шва (`takePersistableTreeAccess`), holder лишь спрашивает шов.
     * Прежний документ, если был открыт, закрывается без записи: вместе с
     * правом на новое дерево платформа отдала право на старое, и хвост записи
     * туда уже не пройдёт (правило «ровно одно право», журнал 004 `SL-7`).
     */
    fun folderChosen() {
        documentScope?.cancel()
        documentScope = null
        writeFailure = null
        scope.launch { browseHeldTree() }
    }

    /**
     * Открыть документ по источнику — из списка папки или удержанный.
     *
     * Отказ открытия — штатный сценарий (`FR-9`): экран возвращается к списку
     * документов папки с текстом `userMessage`; если и папка не перечислилась,
     * её отказ главнее — пользователю предлагается выбрать папку заново.
     * Если при этом уже открыт другой документ, он остаётся на экране
     * нетронутым — терять рабочий документ из-за неудачной попытки нельзя.
     */
    fun open(source: DocumentSource, keepField: Boolean = false) {
        scope.launch {
            when (val result = access.open(source)) {
                is DocumentOpenResult.Opened -> attach(result.document, keepField)
                is DocumentOpenResult.Failed -> {
                    if (document !is EditorDocument.Open) {
                        browseHeldTree(notice = result.error.userMessage(source.displayName))
                    }
                }
            }
        }
    }

    /**
     * Документы удержанного дерева — наблюдаемым состоянием.
     *
     * Отказ перечисления показывается сообщением о *папке*, даже когда сюда
     * привёл отказ открытия файла ([notice] при этом уступает): не читается
     * папка целиком, и чинить надо её.
     */
    private suspend fun browseHeldTree(notice: String? = null) {
        val tree = access.heldTree()
        if (tree == null) {
            document = EditorDocument.NoFolder
            return
        }
        document = when (val result = access.listDocuments()) {
            is TreeListResult.Listed -> EditorDocument.Browsing(tree, result.documents, notice)
            is TreeListResult.Failed ->
                EditorDocument.FolderFailed(result.error.userMessage(result.tree.displayName))
        }
    }

    /**
     * Видна ли пользователю вкладка превью — с учётом фона приложения.
     *
     * Хранится в holder-е, а не только в композиции, потому что переживает
     * смену документа: новый пайплайн обязан узнать о видимости сразу.
     */
    private var previewVisible = false

    /**
     * Каждое изменение текста поля — в модель (`FR-10`) и в пайплайн превью
     * (`FR-13`; текст пайплайну не передаётся — политика возьмёт снимок,
     * когда истечёт пауза, решение `FR-16` фичи 003).
     *
     * Зовётся подпиской экрана на `TextFieldState` (`snapshotFlow`); без
     * открытого документа изменений быть не может — пустое поле не редактируется.
     */
    fun textEdited(text: String) {
        val open = document as? EditorDocument.Open ?: return
        open.runner.textEdited(text)
        open.preview.textEdited()
    }

    /**
     * Честный сигнал видимости превью (`FR-13`): вкладка `ПРЕВЬЮ` на переднем
     * плане — и только она. Уход приложения в фон — тоже «скрыто» (`OQ-5`
     * фичи 003): рендерить страницу, которую никто не видит, незачем, а
     * начатый рендер прерывается.
     *
     * Сигнал идемпотентен: повторное значение (рекомпозиция, поворот) не
     * перезапускает рендер.
     */
    fun previewVisibilityChanged(visible: Boolean) {
        if (visible == previewVisible) return
        previewVisible = visible
        val open = document as? EditorDocument.Open ?: return
        if (visible) open.preview.previewShown() else open.preview.previewHidden()
    }

    /** Кнопка «Повторить» на плашке отказа рендера — язык плашки решён `OQ-3`. */
    fun previewRetryRequested() {
        (document as? EditorDocument.Open)?.preview?.retryRequested()
    }

    /** Кнопка «Повторить» на плашке отказа записи — второй способ снять паузу `FR-19` фичи 004. */
    fun retryWriteRequested() {
        (document as? EditorDocument.Open)?.runner?.retryRequested()
    }

    /**
     * «Отменить» — из меню документа (`FR-16`) и с аппаратной клавиатуры
     * (`FR-17`; поверхность одна, метод один — `FR-13` фичи 004 закрывается
     * здесь же, не дважды). При пустой истории — тихий no-op, не ошибка:
     * гарантия [DocumentEditor.undo].
     *
     * Правка поля доедет до модели и превью обычным путём — подпиской экрана
     * на `TextFieldState` (`snapshotFlow` → [textEdited]): у отмены нет своего
     * канала в модель, и потому расхождение поля с превью невозможно (`FR-6`).
     */
    fun undoRequested() {
        editor.undo()
    }

    /** «Повторить» — симметрично [undoRequested]. */
    fun redoRequested() {
        editor.redo()
    }

    /** Приложение на переднем плане — для записи при уходе в фон. */
    private var foreground = true

    /**
     * Приложение ушло в фон или вернулось (`FR-11`, `FR-16` фичи 004).
     *
     * Уход в фон — немедленная запись: система вправе выгрузить процесс сразу
     * после этого события, и дожидаться паузы ввода нельзя. Решает политика:
     * без расхождения с диском записи не будет. Сигнал идемпотентен — хостинг
     * шлёт значение, а не событие, и рекомпозиция не должна рождать записей.
     * Видимость превью в фоне гасит отдельный сигнал [previewVisibilityChanged]:
     * им управляет экран, у которого есть и вкладка, и передний план.
     */
    fun foregroundChanged(foreground: Boolean) {
        if (foreground == this.foreground) return
        this.foreground = foreground
        if (!foreground) {
            (document as? EditorDocument.Open)?.runner?.movedToBackground()
        }
    }

    private fun attach(opened: DocumentState, keepField: Boolean) {
        documentScope?.cancel()
        val docScope = CoroutineScope(scope.coroutineContext + Job(scope.coroutineContext[Job]))
        documentScope = docScope

        // Плашка отказа — про документ, а не про экран: новый документ
        // начинается без чужого отказа.
        writeFailure = null

        // Новый исполнитель — новая политика автосохранения (параметр по
        // умолчанию конструктора): экземпляр политики рассчитан ровно на один
        // документ (FR-8). Шов записи обёрнут наблюдателем: исполнитель отдаёт
        // политике только факт отказа, а плашке `OQ-3` нужен его текст —
        // перехват на границе шва даёт текст, не трогая пакет document/.
        val runner = AutosaveRunner(
            initialDocument = opened,
            access = WriteObservingAccess(access) { result ->
                writeFailure = when (result) {
                    DocumentWriteResult.Written -> null
                    is DocumentWriteResult.Failed -> result.error.userMessage(result.source.displayName)
                }
            },
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

        // Пайплайн превью — тоже по экземпляру на документ (политика фичи 003
        // рассчитана на один документ, и первый рендер нового документа снова
        // получает индикатор). Снимок текста берётся из поля: единственный
        // источник истины — TextFieldState (FR-6).
        val preview = PreviewPipeline(
            renderer = renderer,
            scope = docScope,
            clock = clock,
            sourceText = { editor.textFieldState.text.toString() },
            page = page,
            delayUntil = delayUntil,
        )

        document = EditorDocument.Open(runner, preview)

        // Документ сменился при видимом превью: новый пайплайн узнаёт о
        // видимости сразу и рендерит без дебаунса, как при показе вкладки.
        if (previewVisible) preview.previewShown()
    }
}

/**
 * Шов записи с наблюдателем исхода — для плашки отказа записи (`OQ-3`).
 *
 * [AutosaveRunner] сообщает политике только *факт* отказа; текст
 * `DocumentWriteError.userMessage` при этом оставался бы внутри шва. Обёртка
 * перехватывает исход на границе — единственной точке, принадлежащей экрану, —
 * и не меняет ни исполнителя, ни политику, ни сам шов.
 *
 * [onWriteResult] зовётся в корутине исполнителя, то есть в области экрана на
 * главном потоке — там же, где живёт наблюдаемое состояние holder-а.
 */
private class WriteObservingAccess(
    private val delegate: DocumentFileAccess,
    private val onWriteResult: (DocumentWriteResult) -> Unit,
) : DocumentFileAccess by delegate {

    override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult =
        delegate.write(source, fileText).also(onWriteResult)
}
