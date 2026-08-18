package io.github.olegnyr.adocmobile.screen.git

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.git.SshKeyInfo
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

    /** Спрошено подтверждение замены: старый ключ перестанет подходить серверу. */
    data class ConfirmingReplace(val key: SshKeyInfo, val comment: String) : SshKeyScreenState

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
 */
class SshKeyScreenModel(
    private val keys: SshKeyStore,
    private val scope: CoroutineScope,
) {

    var state: SshKeyScreenState by mutableStateOf(SshKeyScreenState.Loading)
        private set

    /** Вход на экран: прочитать ключ устройства. */
    fun start() {
        scope.launch {
            state = try {
                keys.currentKey()?.let { SshKeyScreenState.Ready(it) } ?: SshKeyScreenState.NoKey()
            } catch (_: Exception) {
                SshKeyScreenState.NoKey(failure = READ_FAILED_MESSAGE)
            }
        }
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
        state = SshKeyScreenState.Ready(confirming.key)
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
                SshKeyScreenState.NoKey()
            } catch (_: Exception) {
                SshKeyScreenState.NoKey(failure = DELETE_FAILED_MESSAGE)
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
        val ready = state as? SshKeyScreenState.Ready ?: return
        copy(ready.key.publicKeyLine)
        state = ready.copy(notice = COPIED_MESSAGE)
    }

    private fun generate(comment: String) {
        // Фаза занимается синхронно, до первой точки приостановки: двойное
        // нажатие иначе создало бы два ключа, и второй затёр бы первый.
        state = SshKeyScreenState.Working
        scope.launch {
            state = try {
                SshKeyScreenState.Ready(keys.generateKey(comment))
            } catch (_: Exception) {
                SshKeyScreenState.NoKey(failure = GENERATE_FAILED_MESSAGE)
            }
        }
    }

    private companion object {
        const val READ_FAILED_MESSAGE = "Не удалось прочитать ключ устройства. Попробуйте ещё раз."
        const val GENERATE_FAILED_MESSAGE = "Создать ключ не удалось. Попробуйте ещё раз."
        const val DELETE_FAILED_MESSAGE = "Удалить ключ не удалось. Попробуйте ещё раз."
        const val COPIED_MESSAGE = "Публичный ключ скопирован — вставьте его в настройки сервера."
    }
}
