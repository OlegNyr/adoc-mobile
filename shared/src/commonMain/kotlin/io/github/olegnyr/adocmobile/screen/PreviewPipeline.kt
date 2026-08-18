package io.github.olegnyr.adocmobile.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.diagram.DiagramResolver
import io.github.olegnyr.adocmobile.preview.PreviewAction
import io.github.olegnyr.adocmobile.preview.PreviewFailure
import io.github.olegnyr.adocmobile.preview.PreviewPolicy
import io.github.olegnyr.adocmobile.preview.PreviewStatus
import io.github.olegnyr.adocmobile.preview.PreviewUpdate
import io.github.olegnyr.adocmobile.preview.previewPage
import io.github.olegnyr.adocmobile.render.AdocRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Исполнитель действий [PreviewPolicy]: политика решает, этот класс делает —
 * слайс `SL-2` фичи 005, живая вкладка превью (`FR-13`, `FR-14`).
 *
 * Тот же приём, что [io.github.olegnyr.adocmobile.document.AutosaveRunner] у
 * автосохранения: политика — чистый автомат фичи 003 со всеми решениями
 * владельца (дебаунс, поколения, «движок молчит при скрытом превью», индикатор
 * только первому рендеру), а здесь её действия получают исполнение:
 *
 * * [PreviewAction.WaitUntil] — отложенной корутиной;
 * * [PreviewAction.Render] — вызовом [AdocRenderer.render] с отменой прежнего
 *   рендера: отмена корутины доезжает до `interruptEvaluation` движка
 *   (журнал 003 `SL-5` — настоящая отмена — обязанность исполнителя);
 * * [PreviewAction.CancelRender] — отменой без замены;
 * * результат — обратно в политику ([PreviewPolicy.renderCompleted] /
 *   [PreviewPolicy.renderFailed]), и только её ответ публикуется.
 *
 * Фрагмент конвертера заворачивается в страницу с темой и гарнитурами дизайна
 * ([previewPage], `FR-14`) прямо в корутине рендера — на главном потоке
 * остаётся одна публикация строки (`FR-16` фичи 003).
 *
 * Экземпляр, как и политика, живёт один на открытый документ: у нового
 * документа свой пайплайн, и его первый рендер снова получает индикатор.
 * События приходят с главного потока; класс не потокобезопасен намеренно.
 *
 * @param sourceText снимок текущего текста поля; читается только в моменты,
 * когда политика его требует (показ, пауза, повтор), а не на каждый символ
 * @param page сборка страницы из фрагмента — параметр ради тестов: настоящая
 * [previewPage] грузит шрифты из ресурсов, которых в `commonTest` нет
 * @param delayUntil ожидание до срока — параметр ради тестов, умолчание — [delay]
 */
class PreviewPipeline(
    private val renderer: AdocRenderer,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val sourceText: () -> String,
    private val policy: PreviewPolicy = PreviewPolicy(),
    private val page: suspend (fragment: String) -> String = { fragment -> previewPage(fragment) },
    private val diagrams: DiagramResolver = DiagramResolver(),
    private val delayUntil: suspend (dueAt: Long) -> Unit = { dueAt ->
        delay((dueAt - clock()).coerceAtLeast(0))
    },
) {

    /** Последняя опубликованная страница; `null` — публиковать было нечего. */
    var html: String? by mutableStateOf(null)
        private set

    /** Состояние превью для показа: скрыто, первый рендер, содержимое. */
    var status: PreviewStatus by mutableStateOf(PreviewStatus.Hidden)
        private set

    /** Отказ актуального рендера — плашка с повтором; `null` — плашки нет. */
    var failure: PreviewFailure? by mutableStateOf(null)
        private set

    private var waitJob: Job? = null
    private var renderJob: Job? = null

    /** Вкладка превью стала видимой: рендер сразу, без дебаунса. */
    fun previewShown() {
        perform(policy.previewShown(sourceText(), clock()))
    }

    /** Вкладка превью скрыта (или приложение ушло в фон): движок останавливается. */
    fun previewHidden() {
        // Корутина отменяется здесь явно, а не только действием политики.
        // С фичи 008 она переживает публикацию страницы: после первой
        // публикации политика уже не считает рендер идущим (`inFlight` закрыт),
        // и на уход с вкладки отвечает `Idle` — а в корутине в этот момент ещё
        // идёт загрузка диаграмм. Без этой строки скрытое превью продолжало бы
        // ходить в сеть, и поймать это можно было бы только счётчиком запросов.
        renderJob?.cancel()
        renderJob = null
        perform(policy.previewHidden())
    }

    /** Пользователь изменил текст: пауза ввода начинается заново. */
    fun textEdited() {
        perform(policy.textEdited(clock()))
    }

    /** Кнопка «Повторить» на плашке отказа: рендер сразу. */
    fun retryRequested() {
        perform(policy.retryRequested(sourceText(), clock()))
    }

    private fun perform(action: PreviewAction) {
        when (action) {
            PreviewAction.Idle -> Unit

            is PreviewAction.WaitUntil -> scheduleWait(action.dueAt)

            is PreviewAction.Render -> launchRender(action)

            PreviewAction.CancelRender -> {
                renderJob?.cancel()
                renderJob = null
            }
        }
        syncState()
    }

    /**
     * Ждать срока и спросить политику снова — с тем снимком текста, который
     * актуален на момент истечения паузы, а не на момент правки (`FR-16`).
     *
     * Прежнее ожидание отменяется: правка сдвинула срок, и два таймера на один
     * дебаунс — лишние пробуждения с бессмысленными `Idle` в ответ.
     */
    private fun scheduleWait(dueAt: Long) {
        waitJob?.cancel()
        waitJob = scope.launch {
            delayUntil(dueAt)
            perform(policy.pauseElapsed(sourceText(), clock()))
        }
    }

    /**
     * Прервать прежний рендер и запустить новый — прерывание входит в само
     * действие [PreviewAction.Render], отдельного сигнала не бывает.
     *
     * Отмена корутины — не отказ: политике она не сообщается вовсе, её поздний
     * результат и так отброшен поколением. Любое другое исключение — отказ
     * движка, и его судьбу решает политика (`FR-9` фичи 003).
     */
    private fun launchRender(action: PreviewAction.Render) {
        renderJob?.cancel()
        renderJob = scope.launch {
            // Ответ политики ([PreviewUpdate]: Publish / Stale / Failed) уже
            // записан в её полях: публикация — это синхронизация наблюдаемого
            // состояния, различать исходы здесь не нужно — их различила
            // политика (FR-15 фичи 003).
            try {
                publishWithDiagrams(action)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                policy.renderFailed(action.generation, failure)
            }
            syncState()
        }
    }

    /**
     * Публикация в два приёма — решение владельца `OQ-4` фичи 008.
     *
     * Сначала страница с плейсхолдерами на местах ещё не приехавших диаграмм:
     * текст пользователю нужен сразу, а не после сети. Потом загрузка и вторая
     * публикация — *всегда*, а не только когда что-то приехало: плейсхолдер
     * обещает картинку, и если её не будет, обещание надо снять, иначе документ
     * без сети навсегда останется в «СБОРКА…» (`FR-19`, `TC-17`).
     *
     * Обе проверки поколения делает политика, и они разные. Первую закрывает
     * `renderCompleted` («этот рендер всё ещё актуален»), вторую —
     * `diagramsResolved` («показана всё ещё та страница, для которой грузили»).
     * Без второй поздние картинки легли бы поверх свежего текста, и выглядело бы
     * это как «превью иногда показывает старое» — раз в двадцать попыток.
     *
     * Отмена (правка текста, уход с превью) отменяет и загрузку: она идёт в этой
     * же корутине, а `CancelRender` её отменяет. Отдельного сигнала транспорту
     * не нужно.
     *
     * Названная цена: вторая публикация заменяет страницу целиком, хотя
     * изменились только адреса картинок. `WebView` перерисует её и вернёт
     * прокрутку (`FR-23` фичи 003), но перезагрузка страницы заметна. Чинить
     * это в `SL-3` не стали: точечная замена требует скрипта на странице,
     * которого там нет и быть не должно.
     */
    private suspend fun publishWithDiagrams(action: PreviewAction.Render) {
        // Параметры диаграмм берутся из одного места и уходят и в движок, и в
        // проход: разъедутся — адреса перестанут узнаваться (см. DiagramResolver).
        val fragment = renderer.render(action.source, diagrams.options())
        val first = diagrams.pending(fragment)
        policy.renderCompleted(action.generation, page(first.html))
        syncState()

        if (first.missing.isEmpty()) return
        diagrams.load(first.missing)

        // Вторая публикация делается всегда, а не только когда что-то приехало.
        // Иначе документ без сети навсегда оставался бы с плейсхолдерами: они
        // обещают картинку, которой не будет. Второй проход превращает
        // недостающее в исходник с пометкой (`FR-19`, `TC-17`).
        policy.diagramsResolved(action.generation, page(diagrams.resolved(fragment).html))
    }

    private fun syncState() {
        html = policy.html
        status = policy.status
        failure = policy.failure
    }
}
