package io.github.olegnyr.adocmobile.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.document.AutosaveRunner
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentState
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.preview.previewPage
import io.github.olegnyr.adocmobile.render.AdocRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Что показывает экран редактора: документ открывается или документ открыт.
 *
 * Состояний списка здесь больше нет — они переехали в корневой экран
 * приложения (`FR-14` фичи 009, ADR-013). Пока список жил внутри редактора,
 * списков в приложении было два, и это был записанный долг «два понятия
 * файлов»; теперь редактор — экран одного документа, и другого дела у него
 * нет.
 *
 * Отдельный тип, а не флаг, — по прежней причине: состояния различаются тем,
 * что видит и может пользователь.
 */
sealed interface EditorDocument {

    /**
     * Документ ещё читается: хостинг назвал источник, содержимое в пути.
     *
     * Не «пустой экран»: показывать здесь нечего, но выйти отсюда можно —
     * «назад» держит перехват уровня приложения по признаку стека (`FR-5`
     * фичи 009). Именно на этом состоянии трижды воспроизводился дефект
     * «экран без выхода».
     */
    data object Opening : EditorDocument

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
 * * файлы — только через шов [DocumentTreeAccess] (`NFR-6`); перечень папки
 *   этот экран не спрашивает, список живёт на корне (`FR-14` фичи 009).
 *
 * События приходят из композиции, то есть с главного потока; класс, как и
 * исполнитель автосохранения, не потокобезопасен намеренно.
 *
 * @param scope область корутин экрана: её отмена снимает и открытие, и
 * автосохранение всех документов
 * @param onDocumentClosed документ закрыт («назад», `FR-21`) или не открылся;
 * аргумент — текст отказа открытия либо `null`. Через него хостинг навигации
 * (фича 009) возвращает пользователя на корневой список и показывает там текст
 * отказа. Обязателен и умолчания не имеет: своих состояний «без документа» у
 * экрана не осталось, поэтому пустой обработчик означал бы экран без выхода —
 * пользователь застревал бы на пустом экране открытия. Ровно этот дефект
 * возвращался трижды (`FR-5` фичи 009), и цена умолчания здесь — его
 * четвёртое возвращение, а не удобство вызова.
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
    private val onDocumentClosed: (notice: String?) -> Unit,
) {

    /** Что показывать: наблюдаемое состояние для композиции. */
    var document: EditorDocument by mutableStateOf(EditorDocument.Opening)
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
     * Платформа взяла право на новое дерево.
     *
     * Для экрана документа это просто выход: выбирать файл пользователь будет
     * на корне, а прежний документ закрывается без записи — вместе с правом на
     * новое дерево платформа отдала право на старое, и хвост записи туда уже
     * не пройдёт (правило «ровно одно право», журнал 004 `SL-7`).
     */
    fun folderChosen() {
        leaveDocument()
    }

    /**
     * Открыть документ по источнику, названному хостингом навигации.
     *
     * Отказ открытия — штатный сценарий (`FR-9`): экран сообщает его хостингу
     * готовым текстом `userMessage`, тот возвращает пользователя на корневой
     * список и показывает текст там.
     * Если при этом уже открыт другой документ, он остаётся на экране
     * нетронутым — терять рабочий документ из-за неудачной попытки нельзя.
     *
     * @param keepField не перетирать текст поля загрузкой с диска:
     * восстановление `rememberSaveable` после поворота или выгрузки процесса
     * (`FR-7`). Поле, разошедшееся с диском, — это несохранённая правка; она
     * помечается изменённой и уходит в файл автосохранением, а не теряется.
     */
    fun open(source: DocumentSource, keepField: Boolean = false) {
        scope.launch {
            when (val result = access.open(source)) {
                is DocumentOpenResult.Opened -> attach(result.document, keepField)
                is DocumentOpenResult.Failed -> {
                    if (document !is EditorDocument.Open) {
                        leaveDocument(result.error.userMessage(source.displayName))
                    }
                }
            }
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

    /** Ожидание записи перед отправкой; ссылка нужна, чтобы повторный запрос не удвоил отправку. */
    private var shareJob: Job? = null

    /**
     * «Поделиться» из меню документа (`FR-20`): сначала немедленная запись
     * несохранённых правок — тем же механизмом, что уход в фон
     * ([AutosaveRunner.movedToBackground]), — затем сигнал отправки [send]
     * с источником документа.
     *
     * Отправляется файл с диска, совпадающий с полем, поэтому сигнал ждёт,
     * пока расхождение с диском не закроется записью. Отказ записи отменяет
     * отправку: устаревший файл не уходит, а отказ виден плашкой
     * [writeFailure] — как у любой записи. По той же причине запрос при уже
     * приостановленном автосохранении (`FR-19` фичи 004) молчит: плашка на
     * экране, и снять паузу обязана явная правка или «Повторить запись».
     *
     * Без открытого документа — тихий no-op: пункт меню в этом состоянии
     * недоступен, а сигнал без файла не имеет смысла.
     *
     * @param send сигнал платформе: показать системный диалог отправки файла;
     * исполнение — `ACTION_SEND` — остаётся за платформенной половиной
     */
    fun shareRequested(send: (DocumentSource) -> Unit) {
        val open = document as? EditorDocument.Open ?: return
        val docScope = documentScope ?: return
        shareJob?.cancel()
        // Корутина — дочерняя к области документа: смена документа отменяет
        // и повисшую отправку — файл прежнего документа не уходит из-под
        // нового (тот же мотив, что у TC-5).
        shareJob = docScope.launch {
            open.runner.movedToBackground()
            // Запись не блокирует ввод: правка во время записи оставляет
            // расхождение, и хвост доедет следующей записью — сигнал ждёт
            // состояние, где диск совпал с полем либо запись отказала.
            val settled = open.runner.documents.first { !it.isModified || it.lastSaveFailed }
            if (!settled.isModified) send(settled.source)
        }
    }

    /** Ожидание записи перед закрытием; ссылка — чтобы повторное «назад» не плодило закрытий. */
    private var closeJob: Job? = null

    /**
     * «Назад» из открытого документа — кнопка app bar и системная кнопка/жест
     * Android (`FR-21`, решение `OQ-8`): закрыть документ и сообщить об этом
     * хостингу — вернуть пользователя на корневой список его дело (`FR-4`
     * фичи 009). Право на дерево удержано, системный диалог не нужен.
     *
     * Перед закрытием — немедленная запись несохранённых правок тем же
     * механизмом, что уход в фон и «Поделиться»
     * ([AutosaveRunner.movedToBackground]): текст не теряется. Отказ записи
     * отменяет закрытие: документ остаётся на экране, а отказ виден плашкой
     * [writeFailure] — закрыть втихую с потерей правок нельзя. Повторное
     * «назад» при живом отказе так же молчит, как «Поделиться»: паузу
     * автосохранения снимает явная правка или «Повторить запись», и лишь
     * успешная запись открывает путь к списку.
     *
     * Без открытого документа — тихий no-op: пока документ читается, «назад»
     * держит перехват уровня приложения по признаку стека (`FR-5` фичи 009),
     * и доделывать этой модели нечего.
     */
    fun closeRequested() {
        val open = document as? EditorDocument.Open ?: return
        val docScope = documentScope ?: return
        closeJob?.cancel()
        // Корутина — дочерняя к области документа, как у отправки: смена
        // документа отменяет и повисшее закрытие.
        closeJob = docScope.launch {
            open.runner.movedToBackground()
            // Тот же критерий, что у «Поделиться»: закрывать можно то, что
            // легло на диск, — сигнал ждёт совпадения диска с полем либо
            // отказа записи.
            // Плашка отказа гаснет по правилу «смена документа»
            // ([writeFailure]): закрытие случается только после успешной
            // записи, и гореть отказу не над чем.
            val settled = open.runner.documents.first { !it.isModified || it.lastSaveFailed }
            if (!settled.isModified) leaveDocument()
        }
    }

    /**
     * Единственный выход из открытого документа (`FR-4`, `FR-5` фичи 009).
     *
     * Через него идут *все* пути: «назад» кнопкой и жестом, отказ открытия и
     * смена папки из меню. Так и задумано: пока веток было три, одна из них
     * (`folderChosen`) молчала — навигатор продолжал считать, что документ
     * открыт, тело экрана при хостинге не рисовалось, перехват «назад» был
     * выключен, и пользователь оставался на пустом экране без единого выхода
     * (находка ревью `SL-2`). Одна ветка — одно место, где об этом можно
     * забыть, и оно закрыто тестом.
     *
     * @param notice текст отказа открытия либо `null` — обычное закрытие
     */
    private fun leaveDocument(notice: String? = null) {
        documentScope?.cancel()
        documentScope = null
        writeFailure = null
        document = EditorDocument.Opening
        onDocumentClosed(notice)
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
        // документ (FR-8). Исход каждой записи исполнитель сообщает сам
        // (мини-слайс SL-8 фичи 004): плашке `OQ-3` нужен текст ошибки, и тип
        // с источником приходят штатно, без обёртки шва. Наблюдатель зовётся
        // в корутине области экрана — там же, где живёт состояние holder-а.
        val runner = AutosaveRunner(
            initialDocument = opened,
            access = access,
            scope = docScope,
            clock = clock,
            delayUntil = delayUntil,
            onWriteResult = { result ->
                writeFailure = when (result) {
                    DocumentWriteResult.Written -> null
                    is DocumentWriteResult.Failed -> result.error.userMessage(result.source.displayName)
                }
            },
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
