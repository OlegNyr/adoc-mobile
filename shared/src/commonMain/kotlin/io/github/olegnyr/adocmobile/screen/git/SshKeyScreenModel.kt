package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.git.SshKeyInfo
import io.github.olegnyr.adocmobile.git.SshKeyPresence
import io.github.olegnyr.adocmobile.git.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Что показывает экран SSH-ключа (`FR-27`, раздел SSH макета «06»). */
sealed interface SshKeyScreenState {

    /** Ключ читается с диска. */
    data object Loading : SshKeyScreenState

    /**
     * Ключа нет — экран предлагает создать; [failure] — текст последнего
     * отказа генерации, `null` — отказа не было.
     */
    data class NoKey(val failure: String? = null) : SshKeyScreenState

    /** Идёт генерация или удаление: действия заблокированы. */
    data object Working : SshKeyScreenState

    /**
     * Ключ есть: строка для сервера и отпечаток для сверки.
     * [notice] — короткое уведомление («скопировано»), гаснет само действием.
     */
    data class Ready(val key: SshKeyInfo, val notice: String? = null) : SshKeyScreenState

    /**
     * Ключ на устройстве есть, но прочитать его нельзя (`TC-56`).
     *
     * Отдельное состояние, а не [NoKey]: показать «ключа нет» значило бы
     * предложить создать ключ без предупреждения о замене — и стереть
     * рабочую пару одним нажатием.
     *
     * Сюда же ведёт любой отказ чтения, записи и удаления: он может быть
     * временным, поэтому из состояния всегда есть путь *назад* — повторить
     * чтение, — а не только вперёд, к уничтожению ключа (второй раунд ревью
     * `SL-19`).
     */
    data class Unreadable(val failure: String) : SshKeyScreenState

    /**
     * Спрошено подтверждение замены: старый ключ перестанет подходить серверу.
     * [key] — `null`, когда прежний ключ не читается: подтверждение всё равно
     * спрашивается, показывать в нём нечего.
     */
    data class ConfirmingReplace(val key: SshKeyInfo?, val comment: String) : SshKeyScreenState

    /** Спрошено подтверждение удаления. */
    data class ConfirmingDelete(val key: SshKeyInfo) : SshKeyScreenState
}

/**
 * Holder экрана SSH-ключа — приёмом `CommitScreenModel`: наблюдаемое
 * состояние, шов интерфейсом, одна операция за раз.
 *
 * Разрушающие действия (замена и удаление) спрашивают подтверждение: после
 * них сервер перестаёт пускать, а восстановить приватный ключ нельзя.
 * Приватная часть в модели не появляется вовсе — только [SshKeyInfo].
 *
 * @param keyChanged вызывается после создания и удаления ключа: транспорт
 * держит расшифрованную пару в памяти, пока живёт его фабрика сессий, и
 * обязан отпустить её сразу, а не при следующей операции — иначе «ключ стёрт
 * безвозвратно» на экране расходится с тем, что лежит в куче процесса
 * (находка security-ревью `SL-20`). Хостинг связывает этот колбэк с
 * `SshTransport.close()`.
 */
class SshKeyScreenModel(
    private val keys: SshKeyStore,
    private val scope: CoroutineScope,
    private val keyChanged: () -> Unit,
) {

    var state: SshKeyScreenState by mutableStateOf(SshKeyScreenState.Loading)
        private set

    /** Вход на экран: прочитать ключ устройства. */
    fun start() {
        scope.launch {
            state = try {
                when (val presence = keys.currentKey()) {
                    is SshKeyPresence.Present -> SshKeyScreenState.Ready(presence.key)
                    SshKeyPresence.None -> SshKeyScreenState.NoKey()
                    // Ключ есть, но не читается: предлагать «создать» без
                    // подтверждения нельзя — это уничтожение вслепую.
                    SshKeyPresence.Unreadable -> SshKeyScreenState.Unreadable(UNREADABLE_MESSAGE)
                }
            } catch (_: Exception) {
                // Отказ чтения — не «ключа нет»: на диске он мог остаться
                // целым, и кнопка создания без подтверждения стёрла бы его
                // (находка security-ревью `SL-19`). Fail-safe в сторону
                // подтверждения.
                SshKeyScreenState.Unreadable(READ_FAILED_MESSAGE)
            }
        }
    }

    /**
     * Прочитать ключ заново после отказа (`TC-56`).
     *
     * Отказ бывает временным — Keystore занят, файл не открылся, — и путь
     * вперёд из «ключ не читается» ведёт только к уничтожению пары.
     * Поэтому из этого состояния всегда есть путь назад (второй раунд ревью
     * `SL-19`).
     */
    fun retryRequested() {
        if (state is SshKeyScreenState.Unreadable) start()
    }

    /**
     * Кнопка создания ключа (`FR-27`).
     *
     * При существующем ключе сначала спрашивается подтверждение: замена
     * рвёт доступ, пока владелец не пропишет новый ключ на сервере.
     */
    fun generateRequested(comment: String) {
        when (val current = state) {
            is SshKeyScreenState.Ready -> {
                state = SshKeyScreenState.ConfirmingReplace(current.key, comment)
            }

            // Нечитаемый ключ тоже перезаписывается только по подтверждению:
            // «не читается» не означает «его нет» (`TC-56`).
            is SshKeyScreenState.Unreadable -> {
                state = SshKeyScreenState.ConfirmingReplace(key = null, comment = comment)
            }

            is SshKeyScreenState.NoKey -> generate(comment)

            else -> Unit
        }
    }

    /** Замена подтверждена — создать новый ключ поверх прежнего. */
    fun replaceConfirmed() {
        val confirming = state as? SshKeyScreenState.ConfirmingReplace ?: return
        generate(confirming.comment)
    }

    /** Замена отменена — вернуться к показу ключа. */
    fun replaceDismissed() {
        val confirming = state as? SshKeyScreenState.ConfirmingReplace ?: return
        state = confirming.key
            ?.let { SshKeyScreenState.Ready(it) }
            ?: SshKeyScreenState.Unreadable(UNREADABLE_MESSAGE)
    }

    /** Кнопка удаления — спрашивает подтверждение (`FR-28`). */
    fun deleteRequested() {
        val ready = state as? SshKeyScreenState.Ready ?: return
        state = SshKeyScreenState.ConfirmingDelete(ready.key)
    }

    /** Удаление отменено. */
    fun deleteDismissed() {
        val confirming = state as? SshKeyScreenState.ConfirmingDelete ?: return
        state = SshKeyScreenState.Ready(confirming.key)
    }

    /** Удаление подтверждено: обе половины ключа стираются. */
    fun deleteConfirmed() {
        if (state !is SshKeyScreenState.ConfirmingDelete) return
        state = SshKeyScreenState.Working
        scope.launch {
            state = try {
                keys.deleteKey()
                keyChanged()
                SshKeyScreenState.NoKey()
            } catch (_: Exception) {
                // Удалить не удалось — ключ, скорее всего, на месте: снова
                // fail-safe в сторону подтверждения перед перезаписью.
                SshKeyScreenState.Unreadable(DELETE_FAILED_MESSAGE)
            }
        }
    }

    /**
     * Копирование публичного ключа в буфер обмена (`FR-27`).
     *
     * Само копирование делает платформа: буфер обмена — системный сервис, и
     * его вызов приходит параметром, а не тянет платформенный тип в модель.
     */
    fun copyRequested(copy: (String) -> Unit) {
        if (state !is SshKeyScreenState.Ready) return
        scope.launch {
            // Перед буфером обмена ключ сверяется целиком: эту строку человек
            // вставит в настройки сервера и тем самым выдаст доступ, а показ
            // открытой части её целостности не подтверждает (находка
            // security-ревью `SL-20`).
            val verified = try {
                keys.verifiedKey()
            } catch (_: Exception) {
                null
            }
            state = if (verified == null) {
                SshKeyScreenState.Unreadable(UNVERIFIED_MESSAGE)
            } else {
                copy(verified.publicKeyLine)
                SshKeyScreenState.Ready(verified, notice = COPIED_MESSAGE)
            }
        }
    }

    private fun generate(comment: String) {
        // Фаза занимается синхронно, до первой точки приостановки: двойное
        // нажатие иначе создало бы два ключа, и второй затёр бы первый.
        val hadKey = state !is SshKeyScreenState.NoKey
        state = SshKeyScreenState.Working
        scope.launch {
            state = try {
                keys.generateKey(comment).also { keyChanged() }.let(SshKeyScreenState::Ready)
            } catch (_: Exception) {
                // Отказ *поверх существующего* ключа возвращает экран к
                // «ключ есть, но не читается», а не к «ключа нет»: иначе
                // следующее нажатие затрёт целую пару без подтверждения
                // (находка security-ревью `SL-19`).
                if (hadKey) {
                    SshKeyScreenState.Unreadable(GENERATE_FAILED_MESSAGE)
                } else {
                    SshKeyScreenState.NoKey(failure = GENERATE_FAILED_MESSAGE)
                }
            }
        }
    }

    private companion object {
        const val READ_FAILED_MESSAGE = "Не удалось прочитать ключ устройства. Попробуйте ещё раз."
        const val UNREADABLE_MESSAGE =
            "Ключ устройства повреждён и больше не подойдёт серверу. Создайте новый и пропишите его на сервере."
        const val GENERATE_FAILED_MESSAGE = "Создать ключ не удалось. Попробуйте ещё раз."
        const val DELETE_FAILED_MESSAGE = "Удалить ключ не удалось. Попробуйте ещё раз."
        const val COPIED_MESSAGE = "Публичный ключ скопирован — вставьте его в настройки сервера."
        const val UNVERIFIED_MESSAGE =
            "Ключ устройства не прошёл проверку целостности и скопирован не был. Создайте новый ключ."
    }
}
