package io.github.olegnyr.adocmobile.git

import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeListResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Host-половина рубежей моста (`TC-36`) — слайс `SL-4` фичи 007-git-sync.
 *
 * Гоняется на JVM без устройства: именные проверки `resolve` (пустые
 * сегменты, `..`, `.git` в любом регистре и сегменте) и симлинк-рубеж —
 * чтобы CI без телефона не оставался слеп к security-рубежу, который иначе
 * держится на одном device-тесте (находка повторного ревью `SL-4`).
 * Симлинк-кейсы пропускаются молча, если файловая система их не даёт
 * (Windows без прав разработчика) — device-тест их всё равно закрывает.
 */
class RepoDocumentAccessHostTest {

    private val root: File = Files.createTempDirectory("repo-access-host").toFile()

    private fun freshAccess(): RepoDocumentAccess {
        File(root, ".git").mkdirs()
        File(root, ".git/config").writeText("[core]\n")
        File(root, "docs").mkdirs()
        File(root, "docs/guide.adoc").writeText("= Руководство\n")
        File(root, "readme.adoc").writeText("= Репозиторий\n")
        return RepoDocumentAccess(workTree = root)
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun TC_36_nameRulesRejectEscapesAndGitInAnySegmentAndCase() {
        val access = freshAccess()
        val rejected = listOf(
            "../outside.adoc",
            "..",
            "",
            "docs//guide.adoc",
            ".git/config",
            ".GIT/config",
            "docs/.git/config",
            "docs\\guide.adoc",
        )
        rejected.forEach { path ->
            assertIs<DocumentOpenResult.Failed>(
                runBlocking { access.open(DocumentSource(id = path, displayName = path)) },
                "открытие «$path» обязано быть отвергнуто",
            )
            assertIs<DocumentWriteResult.Failed>(
                runBlocking { access.write(DocumentSource(id = path, displayName = path), "текст") },
                "запись «$path» обязана быть отвергнута",
            )
        }

        // Легальные пути каноническим рубежом не ломаются.
        assertIs<DocumentOpenResult.Opened>(
            runBlocking { access.open(DocumentSource(id = "docs/guide.adoc", displayName = "guide.adoc")) },
        )
    }

    @Test
    fun TC_36_symlinksAreRejectedOnHostWhenFileSystemAllowsThem() {
        val access = freshAccess()
        val created = runCatching {
            Files.createSymbolicLink(
                File(root, "notes.adoc").toPath(),
                File(root, ".git/config").toPath(),
            )
        }.isSuccess
        if (!created) return // ФС без симлинков: кейс закрывает device-тест.

        assertIs<DocumentOpenResult.Failed>(
            runBlocking { access.open(DocumentSource(id = "notes.adoc", displayName = "notes.adoc")) },
            "ссылка на .git внутри копии отвергается NOFOLLOW-проходом",
        )
        assertIs<DocumentWriteResult.Failed>(
            runBlocking { access.write(DocumentSource(id = "notes.adoc", displayName = "notes.adoc"), "мусор") },
        )
        val documents = assertIs<TreeListResult.Listed>(runBlocking { access.listDocuments() }).documents
        assertEquals(
            listOf("readme.adoc"),
            documents.map { it.id },
            "перечень не предлагает документов-призраков",
        )
    }
}
