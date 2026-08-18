package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.git.CloneEvent
import io.github.olegnyr.adocmobile.git.CloneProgress
import io.github.olegnyr.adocmobile.git.CloneRequest
import io.github.olegnyr.adocmobile.git.GitAuth
import io.github.olegnyr.adocmobile.git.GitSync
import io.github.olegnyr.adocmobile.git.RepositorySnapshot
import io.github.olegnyr.adocmobile.git.GitSyncError
import io.github.olegnyr.adocmobile.git.Secret
import io.github.olegnyr.adocmobile.git.hasCredentialsInUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Что происходит на экране клонирования (`FR-3`, `FR-5`, `FR-7`).
 *
 * Отдельный тип, а не набор флагов, — приёмом `EditorDocument`: состояния
 * различаются действиями пользователя, и смешивать их нельзя.
 */
sealed interface CloneScreenPhase {

    /**
     * Форма доступна для ввода; [failure] — готовый текст последнего отказа
     * (`GitSyncError.userMessage`, `FR-7`); `null` — отказа не было.
     */
    data class Editing(val failure: String? = null) : CloneScreenPhase

    /**
     * Операция идёт; [progress] — последний срез для чертёжного блока
     * (`FR-5`); `null` — событий от транспорта ещё не было.
     */
    data class Cloning(val progress: CloneProgress?) : CloneScreenPhase

    /** Клон готов; экрану остаётся отдать [repository] дальше — на экран репозитория. */
    data class Done(val repository: RepositorySnapshot) : CloneScreenPhase
}

/**
 * Holder экрана клонирования — владелец полей формы и фазы операции.
 *
 * Образец — `EditorScreenModel`: несоставной класс, наблюдаемое состояние
 * `mutableStateOf`, события приходят из композиции с главного потока, шов
 * приходит интерфейсом и в тестах подменяется подделкой (`NFR-10`).
 *
 * Одна операция за раз: повторный [cloneRequested] во время клонирования
 * блокируется состоянием, а не гонкой (`TC-34`, раздел «Состояние и корутины»
 * аналитики). Способа авторизации в модели нет — E1 ограничен анонимным
 * HTTPS, развилки секретов (`OQ-2`/`OQ-3`) не решаются умолчанием.
 *
 * @param sync платформенная реализация шва; экран строится только при её
 * наличии — платформа без Git не показывает Git-разделов вовсе (`FR-2`)
 * @param scope область корутин экрана: её отмена снимает подписку на клон
 */
class CloneScreenModel(
    private val sync: GitSync,
    private val scope: CoroutineScope,
) {

    /** Поля формы по макету «05». Ветка и папка пустыми означают умолчания. */
    var url: String by mutableStateOf("")
    var branch: String by mutableStateOf("")
    var directoryName: String by mutableStateOf("")

    /** `depth 1` (`FR-4`); включён по умолчанию — главная митигация памяти. */
    var shallow: Boolean by mutableStateOf(true)

    /** Способ авторизации сегментами формы (`FR-3`): `БЕЗ АВТ.` или `ТОКЕН`; SSH — E4. */
    var authMode: CloneAuthMode by mutableStateOf(CloneAuthMode.None)

    /**
     * Токен из поля формы (`FR-26`). Живёт в модели только до терминального
     * события: успех очищает поле — секрет держится не дольше необходимого,
     * запомненную копию несёт платформенное хранилище (`FR-28`).
     */
    var token: String by mutableStateOf("")

    /** Фаза экрана: форма, операция с прогрессом, готово. */
    var phase: CloneScreenPhase by mutableStateOf(CloneScreenPhase.Editing())
        private set

    /**
     * Имя папки, которое уйдёт в запрос, если поле оставить пустым, — для
     * подсказки в форме. До первого ввода адреса подсказки нет: показывать
     * запасное `repo` при пустом URL значило бы врать (находка ревью `SL-3`).
     */
    val suggestedDirectoryName: String
        get() = url.trim().takeIf { it.isNotEmpty() }?.let(::defaultDirectoryName).orEmpty()

    /**
     * Кнопка `КЛОНИРОВАТЬ`.
     *
     * Запуск возможен только из формы: во время операции блокирует состояние
     * (`TC-34`), после `Done` клонировать больше нечего — экран уходит по
     * `onCloned`, и повторное нажатие не должно рождать второй запрос.
     *
     * Пустой URL — отказ сразу и без шва: сети у операции ещё нет, а форма
     * обязана объяснить, чего не хватает (`FR-7`). Отказ шва возвращает форму
     * с текстом ошибки; повторный запуск — новая подписка (`TC-4`).
     */
    fun cloneRequested() {
        if (phase !is CloneScreenPhase.Editing) return

        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            phase = CloneScreenPhase.Editing(failure = EMPTY_URL_MESSAGE)
            return
        }
        // FR-30: секрет в адресе лёг бы открытым текстом в конфиг репозитория —
        // отвергается с подсказкой. Детектор общий с платформенным швом
        // (hasCredentialsInUrl) и ловит и бессхемный user:secret@host.
        if (hasCredentialsInUrl(trimmedUrl)) {
            phase = CloneScreenPhase.Editing(
                failure = GitSyncError.CredentialsInUrl.userMessage(trimmedUrl),
            )
            return
        }
        if (authMode == CloneAuthMode.Token && token.isBlank()) {
            phase = CloneScreenPhase.Editing(failure = EMPTY_TOKEN_MESSAGE)
            return
        }
        // Токен передаётся только по https: на голом http он ушёл бы открытым
        // текстом (NFR-8, находка security-ревью E2); file:// и прочим токен
        // не нужен вовсе.
        if (authMode == CloneAuthMode.Token && !trimmedUrl.startsWith("https://", ignoreCase = true)) {
            phase = CloneScreenPhase.Editing(failure = TOKEN_NEEDS_HTTPS_MESSAGE)
            return
        }

        val request = CloneRequest(
            url = trimmedUrl,
            directoryName = directoryName.trim().ifEmpty { defaultDirectoryName(trimmedUrl) },
            branch = branch.trim().ifEmpty { null },
            shallow = shallow,
        )
        val auth = when (authMode) {
            CloneAuthMode.None -> GitAuth.None
            CloneAuthMode.Token -> GitAuth.Token(Secret(token.trim()))
        }

        phase = CloneScreenPhase.Cloning(progress = null)
        scope.launch {
            sync.clone(request, auth).collect { event ->
                phase = when (event) {
                    is CloneEvent.Progress -> CloneScreenPhase.Cloning(event.progress)

                    is CloneEvent.Finished -> {
                        // Секрет в модели больше не нужен: запомненную копию
                        // несёт платформенное хранилище (FR-28).
                        token = ""
                        CloneScreenPhase.Done(event.repository)
                    }

                    is CloneEvent.Failed ->
                        CloneScreenPhase.Editing(failure = event.error.userMessage(request.url))
                }
            }
        }
    }

    private companion object {
        const val EMPTY_URL_MESSAGE = "Введите адрес репозитория."
        const val EMPTY_TOKEN_MESSAGE = "Введите токен или выберите «БЕЗ АВТ.»."
        const val TOKEN_NEEDS_HTTPS_MESSAGE =
            "Токен передаётся только по HTTPS. Укажите https-адрес или выберите «БЕЗ АВТ.»."
    }
}

/** Способы авторизации, доступные форме в IT2: анонимно или токеном; SSH — этап E4 (OQ-3). */
enum class CloneAuthMode { None, Token }

/**
 * Имя папки клона из адреса remote: последний сегмент пути без `.git`.
 *
 * Понимает и URL с путём (`…/platform-docs.git`), и scp-форму
 * (`git@host:acme/platform-docs.git`). Вырожденный адрес даёт `repo`, а не
 * пустую строку: пустое имя папки — это клон в корень хранилища (`TC-35`).
 */
fun defaultDirectoryName(url: String): String =
    url.trimEnd('/')
        .substringAfterLast('/')
        .substringAfterLast(':')
        .removeSuffix(".git")
        .ifEmpty { "repo" }
