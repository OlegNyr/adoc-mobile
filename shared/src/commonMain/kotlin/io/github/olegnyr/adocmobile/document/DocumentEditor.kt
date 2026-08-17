@file:OptIn(ExperimentalFoundationApi::class)

package io.github.olegnyr.adocmobile.document

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd

/**
 * Состояние редактирования документа: поле ввода вместе с историей отмены.
 *
 * Своей истории правок здесь нет и не будет (`FR-10`). Группировка непрерывного
 * ввода, ёмкость истории и правило «перевод строки — граница шага» уже написаны
 * в `TextFieldState`, и переписывать их значило бы держать вторую реализацию тех
 * же правил. Наша работа — подключить встроенную и проверить её тестами
 * (`FR-11`, `FR-12`, `FR-15`).
 *
 * Класс существует ради двух вещей, которых голый [TextFieldState] не даёт:
 *
 * . *очистка истории при загрузке документа* (`FR-14`) — отменить правку в файл,
 * который уже закрыт, нельзя;
 * . *локализация экспериментального API*. `TextFieldState.undoState` помечен
 * `@ExperimentalFoundationApi`, и согласие на него дано ровно в этом файле.
 * Остальной код — меню документа, горячие клавиши, автосохранение — работает с
 * [canUndo], [undo] и [redo], которые ничего экспериментального не обещают.
 * Если апстрим сломает поведение, менять придётся один файл, а не все места
 * вызова.
 *
 * Опора на встроенный механизм привязывает нас к поведению Compose 1.11.2:
 * KDoc `TextFieldEditUndoBehavior` в этой версии расходится с кодом, и мы
 * опираемся на код. Страховка — `DocumentEditorTest.TC_12_*`: он сломается,
 * если обновление Compose сменит поведение.
 *
 * @property textFieldState поле ввода документа. Отдаётся наружу целиком:
 * `BasicTextField` принимает именно [TextFieldState], а подсветка и рендер
 * предпросмотра подписываются на его текст. Принимается параметром, а не
 * создаётся внутри, потому что экран обязан брать его у `rememberSaveable`:
 * вместе с состоянием поля сохраняется и восстанавливается история отмены, а
 * без этого выгрузка приложения системой стирала бы её.
 */
class DocumentEditor(val textFieldState: TextFieldState = TextFieldState()) {

    /** Есть ли что отменять. Ложь — [undo] будет пустой операцией, а не ошибкой. */
    val canUndo: Boolean get() = textFieldState.undoState.canUndo

    /** Есть ли что повторять. */
    val canRedo: Boolean get() = textFieldState.undoState.canRedo

    /**
     * Загрузить документ в редактор.
     *
     * История отмены при этом очищается (`FR-14`): шаги, записанные для прежнего
     * файла, применять к новому нельзя — они описывают правки по индексам чужого
     * текста. Очистка идёт *после* подстановки текста, иначе сама подстановка
     * осталась бы в истории отменяемым шагом.
     *
     * На вход берётся документ, а не строка, намеренно: очистку истории
     * оправдывает смена документа, а не смена текста. Правка того же документа
     * обязана историю сохранять.
     */
    fun load(document: DocumentState) {
        textFieldState.setTextAndPlaceCursorAtEnd(document.text)
        textFieldState.undoState.clearHistory()
    }

    /** Отменить последний шаг. При пустой истории не делает ничего. */
    fun undo() {
        textFieldState.undoState.undo()
    }

    /** Повторить отменённый шаг. При пустой истории не делает ничего. */
    fun redo() {
        textFieldState.undoState.redo()
    }
}
