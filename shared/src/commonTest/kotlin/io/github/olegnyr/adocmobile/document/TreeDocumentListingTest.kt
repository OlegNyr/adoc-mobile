package io.github.olegnyr.adocmobile.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Фича 004-document-and-files, слайс `SL-7` — общая половина tree-доступа:
 * какие записи каталога считаются документами и что видит пользователь при
 * отказе.
 *
 * Слайс пересматривает `FR-1`…`FR-3` (решение владельца от 2026-08-17: сценарий
 * открытия — «выбрать папку, затем файл в ней»), но пересмотренных формулировок
 * и своих `TC-*` в спеке пока нет. Поэтому имена тестов названы по требованию,
 * как в [DocumentOpenTest], — метка, навешанная не на тот кейс, хуже
 * отсутствующей (урок `SL-1`).
 */
class TreeDocumentListingTest {

    @Test
    fun FR_1_onlyAdocFilesAreListed() {
        // Каталог настоящего репозитория: документы вперемешку с картинками,
        // сборкой и подкаталогами. Каталог с именем `notes.adoc` — не документ:
        // фильтр обязан смотреть на признак каталога, а не только на имя.
        val entries = listOf(
            TreeEntry(id = "1", name = "readme.adoc", isDirectory = false),
            TreeEntry(id = "2", name = "pic.png", isDirectory = false),
            TreeEntry(id = "3", name = "img", isDirectory = true),
            TreeEntry(id = "4", name = "notes.adoc", isDirectory = true),
            TreeEntry(id = "5", name = "build.gradle.kts", isDirectory = false),
            TreeEntry(id = "6", name = "guide.adoc", isDirectory = false),
        )

        val documents = adocDocumentsOf(entries)

        assertEquals(listOf("guide.adoc", "readme.adoc"), documents.map { it.name })
    }

    @Test
    fun FR_1_extensionIsCaseInsensitive() {
        // Файл, переименованный на Windows или пришедший из чужого архива,
        // может нести расширение в любом регистре — это тот же документ.
        val entries = listOf(
            TreeEntry(id = "1", name = "README.ADOC", isDirectory = false),
            TreeEntry(id = "2", name = "Notes.Adoc", isDirectory = false),
        )

        assertEquals(2, adocDocumentsOf(entries).size)
    }

    @Test
    fun FR_1_adocAloneIsNotAName() {
        // Файл, который называется только расширением, — не документ с пустым
        // именем, а скрытый файл в духе `.gitignore`: в списке ему не место.
        val entries = listOf(TreeEntry(id = "1", name = ".adoc", isDirectory = false))

        assertTrue(adocDocumentsOf(entries).isEmpty())
    }

    @Test
    fun FR_1_listingIsSortedByNameIgnoringCase() {
        // Провайдер не обещает порядка выдачи; пользователь ищет файл глазами,
        // и «Zzz» между «api» и «guide» — это потерянный файл, а не мелочь.
        val entries = listOf(
            TreeEntry(id = "1", name = "guide.adoc", isDirectory = false),
            TreeEntry(id = "2", name = "API.adoc", isDirectory = false),
            TreeEntry(id = "3", name = "zzz.adoc", isDirectory = false),
        )

        assertEquals(listOf("API.adoc", "guide.adoc", "zzz.adoc"), adocDocumentsOf(entries).map { it.name })
    }

    @Test
    fun FR_3_everyTreeErrorHasItsOwnUserMessage() {
        // FR-3 действует и для дерева: отзыв права на папку — штатный сценарий
        // с понятным сообщением, а не пустой экран. Тексты не делят один на
        // всех — иначе пользователь не отличит отозванный доступ от исчезнувшей
        // папки.
        val messages = TreeAccessError.entries.map { it.userMessage("Documents") }

        assertEquals(TreeAccessError.entries.size, messages.toSet().size, "сообщения не должны повторяться")
        messages.forEach { message ->
            assertTrue(message.isNotBlank(), "у каждого отказа обязано быть сообщение")
            assertTrue(message.length > 20, "«Ошибка» — не понятное сообщение: $message")
        }
    }

    @Test
    fun FR_3_treeMessageNamesTheFolder() {
        val message = TreeAccessError.PermissionLost.userMessage("Documents")

        assertTrue(message.contains("Documents"), "сообщение обязано называть папку: $message")
    }
}
