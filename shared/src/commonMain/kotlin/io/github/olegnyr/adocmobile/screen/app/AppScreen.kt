package io.github.olegnyr.adocmobile.screen.app

/**
 * Экраны приложения — раскладка 1a (`ADR-003`): корневой список файлов и
 * четыре экрана второго уровня.
 *
 * Запечатанный тип, а не строковые маршруты: аргументы экрана (идентификатор
 * документа, путь конфликтного файла) обязаны пережить поворот и выгрузку
 * процесса (`FR-6`), и терять их в разборе строки при каждом переходе незачем.
 * Строковая форма нужна ровно в одном месте — сохранении ([AppNavigator.Saver]).
 *
 * Нижней навигации здесь нет и не будет: это раскладка 1b, отвергнутая
 * `ADR-003`.
 */
sealed interface AppScreen {

    /** Корень приложения — список файлов активного источника (`FR-1`). */
    data object Root : AppScreen

    /**
     * Открытый документ (`FR-3`).
     *
     * @property sourceId идентификатор источника документа — `DocumentSource.id`
     * того шва, который дал файл: SAF-URI у папки, путь от корня рабочей копии
     * у клона. Хостинг превращает его в `DocumentSource`, потому что имя для
     * показа знает список, а не навигатор.
     */
    data class Editor(val sourceId: String) : AppScreen

    /** Форма клонирования — макет «05» (`FR-3`). */
    data object Clone : AppScreen

    /** Экран коммита и push — макет «03» (`FR-3`). */
    data object Commit : AppScreen

    /**
     * Экран слияния — макет «04» (`FR-3`).
     *
     * @property path конфликтный файл; очередь из нескольких файлов (`FR-8`)
     * хостинг ведёт сменой этого аргумента, а не ростом стека
     */
    data class Conflict(val path: String) : AppScreen
}

/**
 * Строковая форма экрана для сохранения.
 *
 * Тег и аргумент разделены первым двоеточием: идентификаторы SAF содержат и
 * двоеточия, и проценты (`content://…/primary%3ADocuments%2F…`), поэтому
 * делить можно только по первому разделителю, а собирать — конкатенацией.
 *
 * Пара [encode]/[decodeAppScreen] обратима для *любого* аргумента, включая
 * пустую строку: кодек не судит о том, бывает ли такой идентификатор, — он
 * обязан вернуть то же, что получил. Различает пустой аргумент и его
 * отсутствие наличие самого разделителя, а не длина хвоста.
 */
internal fun AppScreen.encode(): String = when (this) {
    AppScreen.Root -> ROOT_TAG
    AppScreen.Clone -> CLONE_TAG
    AppScreen.Commit -> COMMIT_TAG
    is AppScreen.Editor -> EDITOR_TAG + SEPARATOR + sourceId
    is AppScreen.Conflict -> CONFLICT_TAG + SEPARATOR + path
}

/**
 * Экран из строковой формы; `null` — строка не читается.
 *
 * `null` вместо исключения намеренно: сохранение приходит из системного
 * `Bundle` и может быть написано другой версией приложения.
 * Уронить запуск на неузнанной строке нельзя — восстановление обязано
 * деградировать до корня (`FR-6`), и решение об этом принимает
 * [AppNavigator.Saver], а не разбор.
 */
internal fun decodeAppScreen(value: String): AppScreen? {
    val separator = value.indexOf(SEPARATOR)
    val tag = if (separator < 0) value else value.substring(0, separator)
    // `null` — разделителя не было вовсе; пустая строка — был, а хвост пуст.
    // Разница существенна: `"editor:"` — это документ с пустым
    // идентификатором, а `"editor"` — строка, которую мы никогда не писали.
    val argument = if (separator < 0) null else value.substring(separator + 1)
    return when (tag) {
        ROOT_TAG -> if (argument == null) AppScreen.Root else null
        CLONE_TAG -> if (argument == null) AppScreen.Clone else null
        COMMIT_TAG -> if (argument == null) AppScreen.Commit else null
        EDITOR_TAG -> argument?.let(AppScreen::Editor)
        CONFLICT_TAG -> argument?.let(AppScreen::Conflict)
        else -> null
    }
}

private const val SEPARATOR = ':'
private const val ROOT_TAG = "root"
private const val EDITOR_TAG = "editor"
private const val CLONE_TAG = "clone"
private const val COMMIT_TAG = "commit"
private const val CONFLICT_TAG = "conflict"
