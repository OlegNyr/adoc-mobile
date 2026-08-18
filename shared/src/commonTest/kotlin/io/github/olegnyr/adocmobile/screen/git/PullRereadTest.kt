package io.github.olegnyr.adocmobile.screen.git

import io.github.olegnyr.adocmobile.document.DocumentSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Перечитка открытого документа после pull — слайс `SL-10`, `TC-13`.
 *
 * Половина «правки не теряются» закрыта решением `OQ-4` и проверена на
 * модели экрана репозитория (`TC_13_pullIsCancelledWhenUnsavedChangesCannotBeStored`):
 * без успешного сохранения pull не запускается. Здесь — вторая половина:
 * какой документ вообще требует перечитки.
 */
class PullRereadTest {

    private val open = DocumentSource(id = "docs/guide.adoc", displayName = "guide.adoc")

    @Test
    fun TC_13_openDocumentTouchedByPullIsReread() {
        assertTrue(
            PullReread.shouldReread(open, listOf("readme.adoc", "docs/guide.adoc")),
            "pull изменил открытый файл — его надо перечитать (FR-15)",
        )
    }

    @Test
    fun TC_13_untouchedOrAbsentDocumentIsNotReread() {
        assertFalse(
            PullReread.shouldReread(open, listOf("readme.adoc")),
            "чужие файлы перечитку открытого документа не вызывают",
        )
        assertFalse(PullReread.shouldReread(open, emptyList()), "pull ничего не менял")
        assertFalse(PullReread.shouldReread(null, listOf("docs/guide.adoc")), "редактор пуст — перечитывать нечего")
        assertFalse(
            PullReread.shouldReread(
                DocumentSource(id = "content://tree/docs/guide.adoc", displayName = "guide.adoc"),
                listOf("docs/guide.adoc"),
            ),
            "документ не из репозитория pull задеть не может — совпадения нет",
        )
    }
}
