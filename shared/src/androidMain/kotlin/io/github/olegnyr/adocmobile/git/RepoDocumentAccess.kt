package io.github.olegnyr.adocmobile.git

import io.github.olegnyr.adocmobile.document.DocumentAccessError
import io.github.olegnyr.adocmobile.document.DocumentFileAccess
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.DocumentWriteError
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeAccessError
import io.github.olegnyr.adocmobile.document.TreeEntry
import io.github.olegnyr.adocmobile.document.TreeListResult
import io.github.olegnyr.adocmobile.document.TreeSource
import io.github.olegnyr.adocmobile.document.adocDocumentsOf
import io.github.olegnyr.adocmobile.document.openDocument
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Document-шов поверх рабочей копии клона (`FR-12`, мост `SL-4`).
 *
 * Реализация `DocumentTreeAccess` на `java.io.File`: файлы клона — обычные
 * файлы приватного хранилища (решение `OQ-1`), SAF для них не нужен — ровно
 * та File-реализация, которую разведка назвала недостающей (Q7). Существующий
 * редактор работает через этот же интерфейс, поэтому файл клона открывается
 * и правится без правок экрана редактора; врезка в его хостинг — этап UI.
 *
 * Идентификатор [DocumentSource.id] — путь от корня рабочей копии с `/`,
 * тот же, что в `RepoFile.path`: экран репозитория отдаёт его мосту как есть.
 *
 * «Постоянное разрешение» здесь — сам факт клона: приватному хранилищу
 * не нужны URI-права. [heldSource] намеренно живёт только в памяти процесса:
 * восстановление документа после перезапуска — обязательство хостинга
 * (этап UI), потому что относительный путь однозначен только в паре с
 * репозиторием, и хранить их порознь значило бы открыть чужой файл после
 * смены клона. Решение записано в журнале `SL-4`.
 */
class RepoDocumentAccess(
    private val workTree: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DocumentTreeAccess {

    // Пишется в корутинах IO, читается с главного потока — @Volatile
    // обеспечивает видимость; согласованность даёт последовательность
    // операций экрана (открыл — держит), как и у SAF-реализации.
    @Volatile
    private var held: DocumentSource? = null

    override fun heldTree(): TreeSource =
        TreeSource(id = workTree.absolutePath, displayName = workTree.name)

    /**
     * Документы корня копии — тем же фильтром и порядком, что у SAF-дерева
     * (`FR-1a` фичи 004). Отказы различаются: исчезнувшая копия — [TreeAccessError.NotFound],
     * каталог, который есть, но не перечислился, — [TreeAccessError.ListFailed].
     */
    override suspend fun listDocuments(): TreeListResult = withContext(io) {
        val children = workTree.listFiles()
            ?: return@withContext TreeListResult.Failed(
                heldTree(),
                if (workTree.isDirectory) TreeAccessError.ListFailed else TreeAccessError.NotFound,
            )
        val entries = children
            // Симлинки не перечисляются — тем же правилом, что в
            // JGitSync.listFiles: перечень не смеет предлагать путь, который
            // open обязан отвергнуть, а ссылка внутрь копии (.git) — это
            // входная дверь для обхода запрета по именам.
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .map { TreeEntry(id = it.name, name = it.name, isDirectory = it.isDirectory) }
        TreeListResult.Listed(
            adocDocumentsOf(entries).map { DocumentSource(id = it.id, displayName = it.name) },
        )
    }

    override suspend fun open(source: DocumentSource): DocumentOpenResult = withContext(io) {
        val file = resolve(source.id)
            ?: return@withContext DocumentOpenResult.Failed(source, DocumentAccessError.NotFound)
        try {
            val result = openDocument(source, file.readBytes())
            if (result is DocumentOpenResult.Opened) held = source
            result
        } catch (_: IOException) {
            DocumentOpenResult.Failed(source, DocumentAccessError.ReadFailed)
        }
    }

    /**
     * Запись только в *существующий* файл копии: мост правит то, что показал
     * список (`FR-12`), и не создаёт файлов — создание пришло бы отдельным
     * требованием со своей формой. Несуществующий или недопустимый путь —
     * [DocumentWriteError.NotFound], как у SAF-близнеца.
     */
    override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult =
        withContext(io) {
            val file = resolve(source.id)
                ?: return@withContext DocumentWriteResult.Failed(source, DocumentWriteError.NotFound)
            try {
                // Прямая запись, как решено FR-20 фичи 004: без временного
                // файла и замены. writeBytes сам усекает файл (FR-20a).
                file.writeBytes(fileText.encodeToByteArray())
                DocumentWriteResult.Written
            } catch (_: IOException) {
                DocumentWriteResult.Failed(source, DocumentWriteError.WriteFailed)
            }
        }

    override fun heldSource(): DocumentSource? = held

    override fun release() {
        held = null
    }

    /**
     * Файл по пути от корня копии; `null` — файла нет или путь недопустим.
     *
     * Рубежа три, и падают они независимо — содержимое клона контролирует
     * автор remote, JGit честно раскладывает симлинки из репозитория:
     *
     * 1. *По именам*: пустые сегменты, `.`/`..`, разделители и управляющие не
     *    проходят; служебный `.git` закрыт в любом сегменте и без учёта
     *    регистра — на регистронезависимом томе именной рубеж иначе открылся бы.
     * 2. *NOFOLLOW по сегментам*: ни один элемент пути не смеет быть
     *    символьной ссылкой. Ссылку *внутрь* копии (`notes.adoc → .git/config`)
     *    канонический рубеж пропустил бы — путь-то остаётся в копии, — а по
     *    ссылке, которой ходить нельзя, мы не ходим вовсе; заодно исчезает
     *    окно «проверили путь — прочитали уже ссылку».
     * 3. *Канонический*: путь после разрешения ссылок обязан остаться внутри
     *    рабочей копии — второй страж на случай обмана первых двух.
     *
     * Порядок проверок — часть защиты: NOFOLLOW-проход идёт до чтения, и
     * появись когда-нибудь «создать файл при записи», рубежи пересобираются
     * заново, а не ослабляются по месту.
     */
    private fun resolve(relativePath: String): File? {
        val segments = relativePath.split('/')
        if (segments.any { !isSafeDirectoryName(it) }) return null
        if (segments.any { it.equals(GIT_DIR, ignoreCase = true) }) return null

        var current = workTree
        var lastAttributes: BasicFileAttributes? = null
        for (segment in segments) {
            current = File(current, segment)
            lastAttributes = try {
                Files.readAttributes(
                    current.toPath(),
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
            } catch (_: IOException) {
                return null
            }
            if (lastAttributes.isSymbolicLink) return null
        }
        if (lastAttributes?.isRegularFile != true) return null

        return try {
            val root = workTree.canonicalFile.toPath()
            current.takeIf { it.canonicalFile.toPath().startsWith(root) }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val GIT_DIR = ".git"
    }
}
