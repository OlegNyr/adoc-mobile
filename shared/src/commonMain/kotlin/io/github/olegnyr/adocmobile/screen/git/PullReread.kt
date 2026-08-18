package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.document.DocumentSource

/**
 * Перечитка открытого документа после pull (`FR-15`, `TC-13`) — слайс
 * `SL-10` фичи 007-git-sync.
 *
 * Решение владельца `OQ-4`: несохранённые правки сохраняются *перед* pull
 * тем же механизмом немедленной записи, что «Поделиться» и «Назад»
 * (`EditorScreenModel.shareRequested`/`closeRequested`), поэтому здесь
 * остаётся чистый вопрос — задел ли pull открытый файл. Правка, попавшая в
 * pull, может стать конфликтом; тогда пользователь идёт на экран слияния —
 * цена решения записана в аналитике.
 *
 * Логика отдельной функцией, а не внутри модели редактора: `screen/git` —
 * зона Git-фичи, а перечитку исполняет хостинг вызовом существующего
 * `EditorScreenModel.open(source)`; врезка согласуется на этапе UI вместе с
 * остальными (журнал `SL-10`).
 */
object PullReread {

    /**
     * Нужно ли перечитывать открытый документ.
     *
     * Идентификатор документа клона — путь от корня рабочей копии
     * (`RepoDocumentAccess`), и pull называет пути в том же виде, поэтому
     * сравнение прямое. Документ не из репозитория (SAF-источник) pull
     * задеть не может — сравнение просто не совпадёт.
     *
     * @param open открытый документ; `null` — редактор пуст, перечитывать нечего
     * @param changedPaths пути, изменённые pull-ом (`PullResult.Updated.files`)
     */
    fun shouldReread(open: DocumentSource?, changedPaths: List<String>): Boolean {
        val id = open?.id ?: return false
        return changedPaths.any { it == id }
    }
}
