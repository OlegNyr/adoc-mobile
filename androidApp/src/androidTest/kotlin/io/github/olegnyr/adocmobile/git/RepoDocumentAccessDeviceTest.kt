package io.github.olegnyr.adocmobile.git

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeListResult
import java.io.File
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Мост document-шва над клоном (`SL-4` фичи 007-git-sync): `TC-36` —
 * файл клона открывается через `RepoDocumentAccess`, правится и записывается,
 * и Git видит правку. Это исполняемая половина `FR-12` до врезки клона в
 * экран редактора (врезка — этап UI).
 *
 * Фикстура та же, что в `JGitSyncDeviceTest`: `file://`-remote, настоящий
 * клон через `JGitSync` — мост проверяется над честной рабочей копией.
 */
class RepoDocumentAccessDeviceTest {

    private val filesDir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
    private val root = File(filesDir, "devicetest-bridge-root")
    private val remote = File(filesDir, "devicetest-bridge-remote")
    private val seed = File(filesDir, "devicetest-bridge-seed")

    private lateinit var workTree: File

    @BeforeTest
    fun cloneFixture() {
        listOf(root, remote, seed).forEach { deleteTreeNoFollow(it) }
        root.mkdirs()
        val remoteUri = "file://" + remote.absolutePath

        Git.init().setBare(true).setInitialBranch("main").setDirectory(remote).call().close()
        Git.init().setInitialBranch("main").setDirectory(seed).call().use { git ->
            File(seed, "readme.adoc").writeText("= Репозиторий\n")
            File(seed, "docs").mkdirs()
            File(seed, "docs/guide.adoc").writeText("= Руководство\n\nТекст.\n")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("база").setAuthor("Тест", "test@example.com")
                .setCommitter("Тест", "test@example.com").setSign(false).call()
            git.push().setRemote(remoteUri).add("refs/heads/main").call()
        }

        runBlocking {
            JGitSync(reposRoot = root)
                .clone(CloneRequest(url = remoteUri, directoryName = "repo", branch = "main"))
                .toList()
        }
        workTree = File(root, "repo")
    }

    @AfterTest
    fun cleanUp() {
        listOf(root, remote, seed).forEach { deleteTreeNoFollow(it) }
    }

    private fun access() = RepoDocumentAccess(workTree = workTree)

    @Test
    fun TC_36_cloneFileOpensEditsAndGitSeesTheChange() {
        val access = access()
        val source = DocumentSource(id = "docs/guide.adoc", displayName = "guide.adoc")

        val opened = assertIs<DocumentOpenResult.Opened>(runBlocking { access.open(source) })
        assertEquals("= Руководство\n\nТекст.\n", opened.document.text, "текст файла клона прочитан мостом")

        val edited = opened.document.edited("= Руководство\n\nПравка с телефона.\n")
        val written = runBlocking { access.write(source, edited.fileText()) }
        assertIs<DocumentWriteResult.Written>(written)

        assertEquals(
            "= Руководство\n\nПравка с телефона.\n",
            File(workTree, "docs/guide.adoc").readText(),
            "правка легла в тот же файл рабочей копии (FR-12)",
        )

        Git.open(workTree).use { git ->
            val status = git.status().call()
            assertTrue(
                "docs/guide.adoc" in status.modified,
                "git видит файл изменённым — правка пришла в рабочую копию, а не в копию файла",
            )
        }
    }

    @Test
    fun TC_36_rootDocumentsAreListedByTheSameRulesAsFolders() {
        val listed = assertIs<TreeListResult.Listed>(runBlocking { access().listDocuments() })
        assertEquals(
            listOf("readme.adoc"),
            listed.documents.map { it.id },
            "перечень корня — только .adoc, как у SAF-дерева (FR-1a фичи 004)",
        )
    }

    @Test
    fun TC_36_escapePathsAndGitDirAreUnreachable() {
        val access = access()
        assertIs<DocumentOpenResult.Failed>(
            runBlocking { access.open(DocumentSource(id = "../escape.adoc", displayName = "x")) },
            "выход за рабочую копию невозможен по построению",
        )
        assertIs<DocumentOpenResult.Failed>(
            runBlocking { access.open(DocumentSource(id = ".git/config", displayName = "config")) },
            "служебный каталог Git закрыт от моста",
        )
        assertIs<DocumentOpenResult.Failed>(
            runBlocking { access.open(DocumentSource(id = "docs/.git/config", displayName = "config")) },
            ".git закрыт в любом сегменте, не только первом",
        )

        // Опасная половина — запись — закрыта теми же рубежами.
        assertIs<DocumentWriteResult.Failed>(
            runBlocking { access.write(DocumentSource(id = "../escape.adoc", displayName = "x"), "текст") },
        )
        assertIs<DocumentWriteResult.Failed>(
            runBlocking { access.write(DocumentSource(id = ".git/config", displayName = "config"), "текст") },
        )
        assertIs<DocumentWriteResult.Failed>(
            runBlocking { access.write(DocumentSource(id = "нет-такого.adoc", displayName = "x"), "текст") },
            "мост не создаёт новых файлов: запись только в существующие",
        )
    }

    @Test
    fun TC_36_symlinkInsideCloneCannotEscapeWorkTree() {
        // Симлинк создаёт «автор remote»: содержимое клона — чужой вход.
        val secret = File(filesDir, "devicetest-bridge-secret.txt")
        secret.writeText("секрет вне рабочей копии")
        val innocentLink = File(workTree, "innocent.adoc")
        val outsideLink = File(workTree, "outside")
        val gitLink = File(workTree, "notes.adoc")
        try {
            Os.symlink(secret.absolutePath, innocentLink.absolutePath)
            Os.symlink(filesDir.absolutePath, outsideLink.absolutePath)
            // Ссылка ВНУТРЬ копии — на .git/config: канонический рубеж её
            // пропустил бы, отбивает NOFOLLOW-проход по сегментам.
            Os.symlink(File(workTree, ".git/config").absolutePath, gitLink.absolutePath)

            val access = access()
            assertIs<DocumentOpenResult.Failed>(
                runBlocking { access.open(DocumentSource(id = "innocent.adoc", displayName = "innocent.adoc")) },
                "чтение по симлинку за пределы копии отвергается каноническим рубежом",
            )
            assertIs<DocumentWriteResult.Failed>(
                runBlocking {
                    access.write(DocumentSource(id = "innocent.adoc", displayName = "innocent.adoc"), "перезапись")
                },
                "запись по симлинку за пределы копии отвергается",
            )
            assertEquals("секрет вне рабочей копии", secret.readText(), "файл за копией не тронут")
            assertIs<DocumentOpenResult.Failed>(
                runBlocking {
                    access.open(DocumentSource(id = "outside/devicetest-bridge-secret.txt", displayName = "x"))
                },
                "путь через симлинк-каталог тоже отвергается",
            )
            assertIs<DocumentOpenResult.Failed>(
                runBlocking { access.open(DocumentSource(id = "notes.adoc", displayName = "notes.adoc")) },
                "симлинк на .git внутри копии отвергается: запрет .git не обходится ссылкой",
            )
            assertIs<DocumentWriteResult.Failed>(
                runBlocking { access.write(DocumentSource(id = "notes.adoc", displayName = "notes.adoc"), "мусор") },
                "запись в .git через ссылку отвергается — целостность репозитория",
            )

            // Перечень корня не предлагает ссылок вовсе — ни наружу, ни внутрь.
            val documents = assertIs<TreeListResult.Listed>(runBlocking { access.listDocuments() }).documents
            assertEquals(
                listOf("readme.adoc"),
                documents.map { it.id },
                "listDocuments не показывает документов-призраков из симлинков",
            )

            val listed = runBlocking { JGitSync(reposRoot = root).listFiles() }
            assertTrue(
                listed.none { "innocent" in it.path || "outside" in it.path },
                "симлинки не попадают в список файлов экрана: $listed",
            )
        } finally {
            // Симлинки убираются самим тестом, обычным delete (удаляется
            // ссылка, не цель): уборка фикстуры не смеет встретить ссылку на
            // filesDir — File.deleteRecursively пошёл бы по ней и снёс данные
            // приложения. Это уже случилось одним прогоном (см. журнал SL-4).
            outsideLink.delete()
            innocentLink.delete()
            gitLink.delete()
            secret.delete()
        }
    }

    /**
     * Уборка фикстур без следования за симлинками: `File.deleteRecursively`
     * идёт по симлинк-каталогам, и ссылка на `filesDir`, оставшаяся от
     * упавшего теста, привела бы к удалению данных приложения. Path-API
     * удаляет саму ссылку.
     */
    @OptIn(ExperimentalPathApi::class)
    private fun deleteTreeNoFollow(root: File) {
        runCatching { root.toPath().deleteRecursively() }
    }

    @Test
    fun TC_10_listFilesEnumeratesWorkTreeWithoutGitDir() {
        val files = runBlocking { JGitSync(reposRoot = root).listFiles() }

        assertEquals(
            listOf("docs/guide.adoc", "readme.adoc"),
            files.map { it.path },
            "пути от корня копии с /, порядок по пути без регистра, .git исключён (FR-10)",
        )
        assertTrue(files.all { it.lastModifiedEpochMs > 0 }, "время правки заполнено")
    }
}
