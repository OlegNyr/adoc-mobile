package io.github.olegnyr.adocmobile.screen.app

import android.content.Context

/**
 * Признак активного источника в `SharedPreferences` — платформенная половина
 * `FR-11`.
 *
 * Приём и мотив те же, что у удержанных ссылок `AndroidDocumentTreeAccess`:
 * значение переживает выгрузку процесса, а общая половина видит только
 * интерфейс [ActiveSourceStore] и о платформе не знает.
 *
 * Хранится имя элемента перечисления, а не его порядковый номер: номера
 * поедут при добавлении третьего источника, а имена нет.
 * Незнакомое имя читается как «выбора не было» — так же, как незнакомая
 * ссылка на дерево читается как «дерева нет»: откатить приложение на версию
 * без источника лучше, чем упасть на старте.
 */
class AndroidActiveSourceStore(context: Context) : ActiveSourceStore {

    private val store = context.applicationContext
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun loadActiveSource(): FileSourceKind? {
        val saved = store.getString(KEY_ACTIVE_SOURCE, null) ?: return null
        return FileSourceKind.entries.firstOrNull { it.name == saved }
    }

    override fun saveActiveSource(kind: FileSourceKind) {
        store.edit().putString(KEY_ACTIVE_SOURCE, kind.name).apply()
    }

    private companion object {
        /**
         * Свой файл настроек, а не общий с tree-доступом: тот чистится целиком
         * при взятии нового дерева (`clear()` в `takePersistableTreeAccess`), и
         * признак источника уезжал бы вместе с ним ровно в тот момент, когда
         * пользователь его и выбирает.
         */
        const val PREFERENCES = "adoc.active-source"
        const val KEY_ACTIVE_SOURCE = "active-source"
    }
}
