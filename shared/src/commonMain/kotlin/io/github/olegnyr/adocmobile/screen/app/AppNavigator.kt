package io.github.olegnyr.adocmobile.screen.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

/**
 * Навигатор приложения: где пользователь сейчас и куда его ведёт «назад».
 *
 * Свой стек, а не навигационная библиотека, — решение владельца `ADR-012`:
 * экранов пять, глубина два, анимаций переходов дизайн не задаёт, а логика
 * переходов обязана проверяться в `commonTest` без композиции (`NFR-10`).
 * Несоставной класс с наблюдаемым состоянием — образец `EditorScreenModel`.
 *
 * *Инвариант стека:* внизу всегда корень, сверху — не больше одного экрана
 * второго уровня. Он и есть `FR-4`: возврат ведёт ровно на корневой список, а
 * не на предыдущий экран второго уровня, — поэтому переход с одного такого
 * экрана на другой *замещает* вершину, а не громоздится поверх.
 * Очередь конфликтных файлов (`FR-8`) пользуется этим же свойством: сменой
 * аргумента, а не ростом стека.
 *
 * Навигатор ничего не знает о содержимом экранов и ни у кого не спрашивает
 * разрешения уйти. Экран, которому перед уходом нужно доделать работу —
 * редактор с несохранёнными правками (`FR-5`, `FR-21` фичи 005), — сначала
 * доводит её (`EditorScreenModel.closeRequested`) и зовёт [back] по её итогу.
 * Так «назад» остаётся одним понятием, а условие ухода живёт там, где живут
 * данные.
 *
 * События приходят из композиции, то есть с главного потока; класс не
 * потокобезопасен намеренно — как и остальные holder-ы проекта.
 *
 * @param stack начальный стек; всё, что нарушает инвариант, приводится к нему
 */
class AppNavigator(stack: List<AppScreen> = listOf(AppScreen.Root)) {

    /** Стек снизу вверх: первый элемент — корень, последний — то, что видно. */
    var screens: List<AppScreen> by mutableStateOf(normalized(stack))
        private set

    /** Экран, который сейчас видит пользователь. */
    val current: AppScreen
        get() = screens.last()

    /**
     * Принадлежит ли навигатору системная кнопка «назад» (`FR-5`).
     *
     * На корне — `false`: обработчик выключается, и приложение сворачивается
     * платформенным поведением, а не собственной пустотой.
     */
    val canGoBack: Boolean
        get() = screens.size > 1

    /**
     * Перейти на экран (`FR-3`).
     *
     * Переход на корень равносилен возврату: другого способа оказаться внизу
     * стека нет, и заводить для него отдельное имя значило бы плодить два
     * пути к одному состоянию.
     */
    fun go(screen: AppScreen) {
        screens = if (screen == AppScreen.Root) {
            listOf(AppScreen.Root)
        } else {
            listOf(AppScreen.Root, screen)
        }
    }

    /**
     * Вернуться на корневой список (`FR-4`).
     *
     * @return `false` — возвращаться некуда, пользователь уже на корне;
     * состояние при этом не трогается. Хостинг по этому же признаку решает,
     * перехватывать ли системный «назад» ([canGoBack]).
     */
    fun back(): Boolean {
        if (!canGoBack) return false
        screens = listOf(AppScreen.Root)
        return true
    }

    companion object {

        /**
         * Сохранение стека для `rememberSaveable` (`FR-6`, `TC-4`).
         *
         * Список строк, а не `Parcelable`: форма общая для платформ, и
         * `commonMain` не знает ни о `Bundle`, ни о `NSCoder`.
         *
         * Любое непрочитанное сохранение деградирует до корня, а не роняет
         * запуск: значение приходит из системного хранилища и могло быть
         * написано другой версией приложения.
         * Хуже корня в этой ситуации только диалог «приложение остановлено».
         */
        val Saver: Saver<AppNavigator, Any> = listSaver(
            save = { navigator -> navigator.screens.map { screen -> screen.encode() } },
            restore = { saved ->
                val screens = saved.mapNotNull { value -> decodeAppScreen(value) }
                // Хоть одна нечитаемая строка — сохранение целиком не наше:
                // восстанавливать половину стека хуже, чем начать с корня.
                AppNavigator(if (screens.size == saved.size) screens else emptyList())
            },
        )

        /**
         * Стек, приведённый к инварианту: корень внизу, не больше одного
         * экрана второго уровня сверху.
         *
         * Всё, что инварианту не отвечает, сводится к корню — восстановление
         * из чужого сохранения обязано дать рабочее приложение, а не половину
         * состояния.
         */
        private fun normalized(stack: List<AppScreen>): List<AppScreen> {
            if (stack.firstOrNull() != AppScreen.Root) return listOf(AppScreen.Root)
            val top = stack.last()
            return if (top == AppScreen.Root) listOf(AppScreen.Root) else listOf(AppScreen.Root, top)
        }
    }
}
