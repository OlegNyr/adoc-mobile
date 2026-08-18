package io.github.olegnyr.adocmobile.screen.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.TreeListResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Что показывает корневой список (`FR-1`).
 *
 * Отдельный тип, а не набор флагов, — тем же приёмом, что `EditorDocument` и
 * `RepositoryScreenState`: состояния различаются действием пользователя, и
 * смешивать их нельзя.
 */
sealed interface RootListState {

    /** Источник ещё читается. */
    data object Loading : RootListState

    /**
     * Источника нет: первый запуск или право отдано (`FR-2`).
     *
     * Действия этого состояния — дело экрана, а не модели: пока источник один,
     * это «Открыть папку», с приходом второго добавится «Клонировать»
     * (`SL-3`).
     */
    data object NoSource : RootListState

    /**
     * Источник есть, но не читается: право отозвано, папка удалена (`FR-15`).
     * [message] — готовый текст шва (`TreeAccessError.userMessage`), экран его
     * не сочиняет.
     */
    data class Failed(val message: String) : RootListState

    /**
     * Файлы источника (`FR-14`).
     *
     * @property title имя источника для заголовка списка
     * @property files содержимое; пустой список — источник без документов, это
     * состояние, а не отказ (`FR-1a` фичи 004)
     * @property notice отказ открытия документа, с которым редактор вернул
     * пользователя к списку (`FR-9` фичи 005); `null` — отказа не было
     */
    data class Listed(
        val title: String,
        val files: List<DocumentSource>,
        val notice: String? = null,
    ) : RootListState
}

/**
 * Holder корневого списка — единственного списка файлов в приложении
 * (`FR-14`, xref ADR-013).
 *
 * Источник приходит интерфейсом [DocumentTreeAccess], а не конкретной
 * реализацией: у папки SAF и у рабочей копии клона он один и тот же, поэтому
 * список не знает, чьи файлы показывает, и знать не должен. Выбор активного
 * источника — забота хостинга (`SL-3`), Git-части списка — забота `SL-4`;
 * здесь нет ни того, ни другого, и приложение остаётся полезным без Git.
 *
 * Несоставной класс с наблюдаемым состоянием — образец `EditorScreenModel` и
 * `RepositoryScreenModel`; события приходят с главного потока, класс не
 * потокобезопасен намеренно.
 *
 * @param scope область корутин экрана
 */
class RootListModel(
    private val access: DocumentTreeAccess,
    private val scope: CoroutineScope,
) {

    /** Что показывать: наблюдаемое состояние для композиции. */
    var state: RootListState by mutableStateOf(RootListState.Loading)
        private set

    private var reading = false

    /**
     * Прочитать источник (`FR-7`).
     *
     * Не идемпотентен намеренно — тем же решением, что `RepositoryScreenModel.start`:
     * возврат из документа обязан увидеть папку такой, какой она стала, а не
     * такой, какой была на входе. Защита только от параллельного чтения:
     * второй запрос поверх идущего до шва не доходит.
     *
     * Отказ — состояние ([RootListState.Failed]), а не пустой экран: без
     * текста и действия пользователь остался бы перед вечной загрузкой.
     */
    fun start() {
        if (reading) return
        reading = true
        scope.launch {
            try {
                state = read()
            } finally {
                reading = false
            }
        }
    }

    /**
     * Платформа взяла право на новый источник — перечитать его.
     *
     * Отдельный вход, а не [start], потому что снимает [RootListState.Listed.notice]:
     * отказ открытия относился к документу прежнего источника, и переносить
     * его на новый нельзя.
     */
    fun sourceChosen() {
        start()
    }

    /**
     * Редактор не смог открыть выбранный документ и вернул пользователя к
     * списку (`FR-9` фичи 005).
     *
     * Текст показывается плашкой над списком и гаснет следующим чтением: он
     * про конкретную попытку, а не про источник. Список при этом остаётся —
     * неудачная попытка открыть один файл не повод прятать остальные.
     *
     * Вне состояния [RootListState.Listed] — тихий no-op: показывать отказ
     * поверх пустоты или поверх отказа источника значило бы спорить с более
     * важным сообщением.
     */
    fun documentOpenFailed(message: String) {
        val listed = state as? RootListState.Listed ?: return
        state = listed.copy(notice = message)
    }

    private suspend fun read(): RootListState {
        access.heldTree() ?: return RootListState.NoSource
        return when (val result = access.listDocuments()) {
            is TreeListResult.Listed -> RootListState.Listed(
                // Имя берётся у только что прочитанного дерева, а не у того,
                // что было до чтения: смена источника меняет и заголовок.
                title = access.heldTree()?.displayName.orEmpty(),
                files = result.documents,
            )

            is TreeListResult.Failed ->
                RootListState.Failed(result.error.userMessage(result.tree.displayName))
        }
    }
}
