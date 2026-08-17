package io.github.olegnyr.adocmobile.document

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.github.olegnyr.adocmobile.preview.PreviewImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Tree-доступ через Storage Access Framework — платформенная половина `SL-7`.
 *
 * Экран запускает `Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)`; выданный URI
 * дерева приходит в [takePersistableTreeAccess]. Дальше всё идёт через
 * контракты общей половины: перечень документов ([listDocuments]), открытие и
 * запись выбранного файла ([open], [write] — те же сигнатуры, что у
 * [AndroidDocumentFileAccess], экран не переписывается), чтение соседних
 * картинок для превью ([imageSource]).
 *
 * Правило «ровно одно право» из `SL-2` действует и здесь — теперь это право на
 * *дерево*: при взятии нового отдаются все прочие постоянные разрешения,
 * включая прежнее право на одиночный файл. Предел системы — 128 разрешений на
 * приложение, и копить их нельзя (урок `SL-2`, проверенный на устройстве).
 *
 * Файлы пользователя не копируются никуда: правится ровно тот документ, который
 * лежит в выбранной папке (`NFR` «файлы пользователя»).
 */
class AndroidDocumentTreeAccess(context: Context) : DocumentTreeAccess {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val store = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Взять постоянное разрешение на выбранное дерево и запомнить ссылку.
     *
     * Метод платформенный намеренно — по той же причине, что
     * [AndroidDocumentFileAccess.takePersistableAccess]: `Uri` не имеет права
     * попасть в общую модель (`FR-9`), и превращение «выбор пользователя →
     * [TreeSource]» заканчивается здесь.
     *
     * Разрешение берётся на чтение и запись сразу; выдал провайдер только
     * чтение — берём чтение, отказ записи покажет `FR-17`. Провайдер, не дающий
     * постоянного разрешения вовсе, не роняет приложение: ссылка запоминается,
     * а [listDocuments] назовёт исход [TreeAccessError.PermissionLost] с
     * понятным сообщением (`FR-3`).
     *
     * Смена дерева забывает последний открытый документ прежнего дерева:
     * ссылка на файл вне удерживаемого дерева всё равно не открылась бы, а
     * молча показать документ из чужой папки — хуже, чем показать пустой список.
     */
    fun takePersistableTreeAccess(treeUri: Uri): TreeSource {
        runCatching { resolver.takePersistableUriPermission(treeUri, READ_WRITE) }
            .onFailure {
                runCatching {
                    resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

        // «Ровно одно право» — теперь право на дерево. Отдаются *все* прочие
        // постоянные разрешения, а не только прежнее дерево: право на одиночный
        // файл, оставшееся от пути SL-2, тоже считается — предел в 128 у
        // системы общий, по приложению, а не по способу открытия.
        resolver.persistedUriPermissions
            .filter { it.uri != treeUri }
            .forEach { releasePermission(it.uri) }

        val source = TreeSource(id = treeUri.toString(), displayName = displayNameOfTree(treeUri))
        store.edit()
            .clear()
            .putString(KEY_TREE_ID, source.id)
            .putString(KEY_TREE_NAME, source.displayName)
            .apply()
        return source
    }

    /**
     * Запомненное дерево — независимо от того, действует ли ещё разрешение.
     *
     * Право намеренно не проверяется — по той же причине, что у
     * [AndroidDocumentFileAccess.heldSource]: отфильтрованное дерево дало бы
     * после отзыва пустой экран вместо сообщения, которого требует `FR-3`.
     * Потерю доступа обязан назвать [listDocuments], и он её назовёт.
     */
    override fun heldTree(): TreeSource? {
        val id = store.getString(KEY_TREE_ID, null) ?: return null
        val name = store.getString(KEY_TREE_NAME, null) ?: return null
        return TreeSource(id, name)
    }

    override suspend fun listDocuments(): TreeListResult = withContext(Dispatchers.IO) {
        val tree = heldTree()
            ?: return@withContext TreeListResult.Failed(
                TreeSource(id = "", displayName = FALLBACK_TREE_NAME),
                TreeAccessError.PermissionLost,
            )
        val treeUri = Uri.parse(tree.id)

        // Предпроверка права — как в [AndroidDocumentFileAccess.open]: потеря
        // доступа — штатный исход с точным именем, а не разбор исключения.
        if (!holdsTreePermission(treeUri)) {
            return@withContext TreeListResult.Failed(tree, TreeAccessError.PermissionLost)
        }

        try {
            val entries = queryChildren(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                ?: return@withContext TreeListResult.Failed(tree, TreeAccessError.NotFound)
            val documents = adocDocumentsOf(entries).map { entry ->
                DocumentSource(
                    id = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id).toString(),
                    displayName = entry.name,
                )
            }
            TreeListResult.Listed(documents)
        } catch (failure: Exception) {
            TreeListResult.Failed(tree, failure.toTreeAccessError())
        }
    }

    override suspend fun open(source: DocumentSource): DocumentOpenResult = withContext(Dispatchers.IO) {
        val treeUri = heldTreeUriFor(source.id)
            ?: return@withContext DocumentOpenResult.Failed(source, DocumentAccessError.PermissionLost)
        if (!holdsTreePermission(treeUri)) {
            return@withContext DocumentOpenResult.Failed(source, DocumentAccessError.PermissionLost)
        }

        try {
            val bytes = resolver.openInputStream(Uri.parse(source.id))?.use { it.readBytes() }
                ?: return@withContext DocumentOpenResult.Failed(source, DocumentAccessError.NotFound)
            val result = openDocument(source, bytes)
            if (result is DocumentOpenResult.Opened) {
                rememberDocument(source)
            }
            result
        } catch (failure: Exception) {
            DocumentOpenResult.Failed(source, failure.toDocumentAccessError())
        }
    }

    override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult =
        withContext(Dispatchers.IO) {
            val treeUri = heldTreeUriFor(source.id)
                ?: return@withContext DocumentWriteResult.Failed(source, DocumentWriteError.PermissionLost)

            // Проверяется именно запись: провайдер мог выдать только чтение
            // (см. [takePersistableTreeAccess]) — тогда документы открываются,
            // но сохранить их нельзя, и это случай `FR-17`, а не сбой.
            if (!holdsTreeWritePermission(treeUri)) {
                return@withContext DocumentWriteResult.Failed(source, DocumentWriteError.PermissionLost)
            }

            try {
                // Режим "wt" — запись с усечением, урок `SL-6` (`FR-20a`): "w" у
                // части провайдеров не усекает, и короткий текст оставил бы в
                // файле хвост старого содержимого. Запись напрямую — `FR-20`.
                val stream = resolver.openOutputStream(Uri.parse(source.id), "wt")
                    ?: return@withContext DocumentWriteResult.Failed(source, DocumentWriteError.NotFound)
                stream.use { it.write(fileText.encodeToByteArray()) }
                DocumentWriteResult.Written
            } catch (failure: Exception) {
                DocumentWriteResult.Failed(source, failure.toDocumentWriteError())
            }
        }

    /**
     * Последний открытый документ дерева — им экран восстанавливает документ
     * после перезапуска, тем же контрактом, что при одиночном файле.
     *
     * Право не проверяется — см. [heldTree].
     */
    override fun heldSource(): DocumentSource? {
        val id = store.getString(KEY_DOCUMENT_ID, null) ?: return null
        val name = store.getString(KEY_DOCUMENT_NAME, null) ?: return null
        return DocumentSource(id, name)
    }

    /** Отдать право на дерево и забыть и его, и последний открытый документ. */
    override fun release() {
        releaseHeldPermission()
        store.edit().clear().apply()
    }

    /**
     * Источник байтов для перехвата картинок превью (`TC-34` фичи 003).
     *
     * Путь уже провалидирован резолвером превью, но чтение не выходит за дерево
     * и при его ошибке: [resolveTreePath] — второй рубеж, и путь ни разу не
     * склеивается со строкой — каждый сегмент сопоставляется с именами реальных
     * детей каталога. Любой отказ по дороге — `null`: превью показывает документ
     * без картинки, а не падает.
     *
     * Чтение блокирующее по контракту [PreviewImageSource]: перехват ресурсов —
     * синхронный вызов на рабочем потоке WebView, не на главном.
     */
    fun imageSource(): PreviewImageSource = PreviewImageSource { relativePath ->
        val tree = heldTree() ?: return@PreviewImageSource null
        val treeUri = Uri.parse(tree.id)
        if (!holdsTreePermission(treeUri)) return@PreviewImageSource null

        runCatching {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentId = resolveTreePath(rootId, relativePath) { directoryId ->
                runCatching { queryChildren(treeUri, directoryId) }.getOrNull()
            } ?: return@runCatching null

            resolver
                .openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
                ?.use { it.readBytes() }
        }.getOrNull()
    }

    /**
     * Отдать право, но оставить ссылки — воспроизведение потери доступа для
     * проверки `FR-3` на устройстве, тем же приёмом, что
     * [AndroidDocumentFileAccess.loseAccessForCheck]: отзыв руками стирает
     * ссылку заодно и проверяет не тот сценарий.
     */
    fun loseAccessForCheck() {
        releaseHeldPermission()
    }

    /**
     * URI удерживаемого дерева, если [sourceId] — документ *этого* дерева.
     *
     * Принадлежность проверяется до обращения к провайдеру: ссылка из чужого
     * дерева или из пути одиночного файла — это потерянный доступ
     * (штатный исход `FR-3`), а не повод спрашивать провайдер о чужом URI.
     * `getTreeDocumentId` на URI без сегмента `tree/` бросает — для такой
     * ссылки ответ «не принадлежит», не падение.
     */
    private fun heldTreeUriFor(sourceId: String): Uri? {
        val tree = heldTree() ?: return null
        val treeUri = Uri.parse(tree.id)
        val sourceUri = Uri.parse(sourceId)
        val belongs = runCatching {
            sourceUri.authority == treeUri.authority &&
                DocumentsContract.getTreeDocumentId(sourceUri) == DocumentsContract.getTreeDocumentId(treeUri)
        }.getOrDefault(false)
        return if (belongs) treeUri else null
    }

    /**
     * Дети каталога дерева; `null` — провайдер не ответил.
     *
     * Строки без идентификатора или имени пропускаются: такая запись
     * бесполезна и для списка, и для прохода по пути, а ронять из-за неё весь
     * перечень — значит отдать работоспособность списку на откуп худшей записи.
     */
    private fun queryChildren(treeUri: Uri, parentDocumentId: String): List<TreeEntry>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = if (cursor.isNull(0)) null else cursor.getString(0)
                    val name = if (cursor.isNull(1)) null else cursor.getString(1)
                    if (id == null || name == null) continue
                    val mime = if (cursor.isNull(2)) null else cursor.getString(2)
                    add(TreeEntry(id, name, mime == DocumentsContract.Document.MIME_TYPE_DIR))
                }
            }
        }
    }

    private fun rememberDocument(source: DocumentSource) {
        store.edit()
            .putString(KEY_DOCUMENT_ID, source.id)
            .putString(KEY_DOCUMENT_NAME, source.displayName)
            .apply()
    }

    private fun releaseHeldPermission() {
        val uri = store.getString(KEY_TREE_ID, null)?.let { Uri.parse(it) } ?: return
        releasePermission(uri)
    }

    /**
     * Отдать ровно те флаги, которые удерживаются, — тем же приёмом, что в
     * [AndroidDocumentFileAccess]: провайдер вправе выдать только чтение, и
     * попытка отпустить незанятую запись — исключение.
     */
    private fun releasePermission(uri: Uri) {
        resolver.persistedUriPermissions
            .filter { it.uri == uri }
            .forEach { permission ->
                val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if (flags != 0) {
                    runCatching { resolver.releasePersistableUriPermission(uri, flags) }
                }
            }
    }

    private fun holdsTreePermission(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }

    private fun holdsTreeWritePermission(treeUri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /**
     * Имя папки: запрос имени документа корня дерева; не ответил — хвост
     * идентификатора дерева (`primary:Docs` → `Docs`); совсем ничего — «папка».
     */
    private fun displayNameOfTree(treeUri: Uri): String {
        val queried = runCatching {
            val rootDocumentUri =
                DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            resolver.query(
                rootDocumentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        if (queried != null) return queried

        val fromId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?.substringAfterLast(':')
            ?.substringAfterLast('/')
        return fromId?.takeIf { it.isNotEmpty() } ?: FALLBACK_TREE_NAME
    }

    private companion object {
        const val PREFERENCES = "document-tree-access"
        const val KEY_TREE_ID = "held-tree-id"
        const val KEY_TREE_NAME = "held-tree-name"
        const val KEY_DOCUMENT_ID = "held-document-id"
        const val KEY_DOCUMENT_NAME = "held-document-name"
        const val FALLBACK_TREE_NAME = "папка"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/**
 * Исключение перечисления — в исход, который умеет разобрать интерфейс.
 *
 * Порядок ветвей содержательный, как у [toDocumentAccessError]:
 * [FileNotFoundException] — наследник [IOException], и перепутанные местами
 * ветви молча превратят «папка удалена» в «ошибка чтения».
 */
internal fun Throwable.toTreeAccessError(): TreeAccessError = when (this) {
    is SecurityException -> TreeAccessError.PermissionLost
    is FileNotFoundException -> TreeAccessError.NotFound
    is IOException -> TreeAccessError.ListFailed
    else -> TreeAccessError.ListFailed
}
