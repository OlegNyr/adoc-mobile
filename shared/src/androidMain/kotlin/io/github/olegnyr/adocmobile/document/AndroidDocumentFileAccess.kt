package io.github.olegnyr.adocmobile.document

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Файловый доступ через Storage Access Framework.
 *
 * Платформенная половина шва: умеет достать байты по `content://` URI и
 * удержать выданное разрешение. Всё, что делается с байтами дальше, живёт в
 * [openDocument] в общем коде.
 *
 * Файл пользователя не копируется никуда: правится ровно тот документ, который
 * выбран в системном диалоге (`NFR` «файлы пользователя»).
 */
class AndroidDocumentFileAccess(context: Context) : DocumentFileAccess {

    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver
    private val store = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Взять постоянное разрешение на выбранный документ и запомнить ссылку.
     *
     * Метод платформенный намеренно: `Uri` не имеет права попасть в общую модель
     * (`FR-9`), поэтому превращение «выбор пользователя → [DocumentSource]»
     * заканчивается здесь.
     *
     * Разрешение берётся на чтение и запись сразу. Запись нужна `SL-4`, а
     * попросить её позже нельзя — придётся заново гнать пользователя в диалог.
     * Если провайдер выдал только чтение, берём чтение: документ откроется, а
     * отказ записи `SL-4` обязан показывать и без того (`FR-17`).
     *
     * Провайдер, не дающий постоянного разрешения вовсе, не роняет приложение:
     * ссылка запоминается, а [open] назовёт исход [DocumentAccessError.PermissionLost]
     * с понятным сообщением. Исключение отсюда было бы падением на выборе
     * файла — то есть ровно тем, что `FR-3` запрещает.
     */
    fun takePersistableAccess(uri: Uri): DocumentSource {
        runCatching { resolver.takePersistableUriPermission(uri, READ_WRITE) }
            .onFailure {
                runCatching {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }

        // Прежнее разрешение отдаётся, иначе они копятся: система держит не
        // больше 128 постоянных разрешений на приложение, и, набрав предел,
        // редактор перестанет открывать файлы вовсе. Проверено на устройстве:
        // после четырёх открытий в списке лежали все четыре.
        val previous = store.getString(KEY_ID, null)
        if (previous != null && previous != uri.toString()) {
            releasePermission(Uri.parse(previous))
        }

        val source = DocumentSource(id = uri.toString(), displayName = displayNameOf(uri))
        store.edit()
            .putString(KEY_ID, source.id)
            .putString(KEY_NAME, source.displayName)
            .apply()
        return source
    }

    override suspend fun open(source: DocumentSource): DocumentOpenResult = withContext(Dispatchers.IO) {
        // Uri.parse не проверяет строку и не бросает: разбор непонятной ссылки
        // отложен до обращения к провайдеру, и там он станет обычным отказом.
        val uri = Uri.parse(source.id)

        // Разрешение проверяется до чтения, а не по исключению из него: `FR-3`
        // называет потерю доступа штатным сценарием, и отличить её от ошибки
        // ввода-вывода нужно точно, а не по типу исключения провайдера.
        // Проверено на устройстве: без разрешения openInputStream бросает
        // SecurityException («Permission Denial: opening provider …»). Разбор
        // исключения дал бы тот же ответ — но предпроверка отвечает и на вопрос
        // «показывать ли имя файла на экране», не трогая провайдер.
        if (!holdsPermission(uri)) {
            return@withContext DocumentOpenResult.Failed(source, DocumentAccessError.PermissionLost)
        }

        try {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext DocumentOpenResult.Failed(source, DocumentAccessError.NotFound)
            openDocument(source, bytes)
        } catch (failure: Exception) {
            DocumentOpenResult.Failed(source, failure.toDocumentAccessError())
        }
    }

    override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult =
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(source.id)

            // Предпроверка права — по той же причине, что в [open]: потеря
            // доступа — штатный исход с точным именем, а не разбор исключения.
            // Проверяется именно запись: провайдер мог выдать только чтение
            // (см. [takePersistableAccess]), и тогда документ открывается, но
            // сохранить его нельзя — ровно случай `FR-17`.
            if (!holdsWritePermission(uri)) {
                return@withContext DocumentWriteResult.Failed(source, DocumentWriteError.PermissionLost)
            }

            try {
                // Режим "wt" — запись с усечением. Именно "wt", а не "w": для
                // части провайдеров "w" не усекает файл, и текст короче
                // прежнего оставил бы в файле хвост старого содержимого —
                // молчаливая порча, которую не увидит ни один наш тест.
                // Запись идёт напрямую в файл пользователя: без временного
                // файла и замены — решение `FR-20`, цена записана в спеке.
                val stream = resolver.openOutputStream(uri, "wt")
                    ?: return@withContext DocumentWriteResult.Failed(source, DocumentWriteError.NotFound)
                stream.use { it.write(fileText.encodeToByteArray()) }
                DocumentWriteResult.Written
            } catch (failure: Exception) {
                DocumentWriteResult.Failed(source, failure.toDocumentWriteError())
            }
        }

    /**
     * Запомненный источник — независимо от того, действует ли ещё разрешение.
     *
     * Право здесь намеренно *не* проверяется. Отфильтровать источник с
     * потерянным доступом соблазнительно — но тогда пользователь после отзыва
     * увидит пустой экран вместо объяснения, а `FR-3` требует ровно обратного:
     * понятное сообщение и предложение выбрать файл заново. Потерю доступа
     * обязан назвать [open], и он её назовёт.
     */
    override fun heldSource(): DocumentSource? {
        val id = store.getString(KEY_ID, null) ?: return null
        val name = store.getString(KEY_NAME, null) ?: return null
        return DocumentSource(id, name)
    }

    override fun release() {
        releaseHeldPermission()
        store.edit().clear().apply()
    }

    /**
     * Отдать разрешение, но оставить ссылку — воспроизведение потери доступа.
     *
     * Нужен для проверки `TC-21` на устройстве: отзыв доступа руками требует
     * либо переустановки приложения, либо очистки данных, и то и другое стирает
     * ссылку заодно, то есть проверяет не тот сценарий. Здесь остаётся ровно
     * то состояние, которое `FR-3` называет штатным: ссылка есть, права нет.
     */
    fun loseAccessForCheck() {
        releaseHeldPermission()
    }

    private fun releaseHeldPermission() {
        val uri = store.getString(KEY_ID, null)?.let { Uri.parse(it) } ?: return
        releasePermission(uri)
    }

    /**
     * Отдать ровно те флаги, которые удерживаются.
     *
     * Флаги считаются по списку, а не задаются константой: провайдер вправе
     * выдать только чтение, и попытка отпустить незанятую запись — исключение.
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

    private fun holdsPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun holdsWritePermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    private fun displayNameOf(uri: Uri): String {
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()

        return queried ?: uri.lastPathSegment?.substringAfterLast('/') ?: FALLBACK_NAME
    }

    private companion object {
        const val PREFERENCES = "document-access"
        const val KEY_ID = "held-source-id"
        const val KEY_NAME = "held-source-name"
        const val FALLBACK_NAME = "документ"
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/**
 * Исключение файлового доступа — в исход, который умеет разобрать интерфейс.
 *
 * Отдельная функция, а не `when` внутри `catch`: порядок ветвей здесь
 * содержательный ([FileNotFoundException] — наследник [IOException], и
 * перепутанные местами ветви молча превратят «файл удалён» в «ошибка чтения»),
 * а такое место должно быть видно и проверяемо отдельно.
 */
internal fun Throwable.toDocumentAccessError(): DocumentAccessError = when (this) {
    is SecurityException -> DocumentAccessError.PermissionLost
    is FileNotFoundException -> DocumentAccessError.NotFound
    is IOException -> DocumentAccessError.ReadFailed
    else -> DocumentAccessError.ReadFailed
}

/**
 * Исключение записи — в классифицированный отказ (`FR-17`).
 *
 * Порядок ветвей содержательный, как у [toDocumentAccessError]:
 * [FileNotFoundException] — наследник [IOException].
 *
 * «Нет места» распознаётся по `errno` в цепочке причин: провайдер заворачивает
 * `ErrnoException(ENOSPC)` в [IOException], и по типу верхнего исключения
 * исход не отличим от любой другой ошибки ввода-вывода. Распознавание — по
 * лучшей доступной примете, а не гарантия: провайдер, потерявший `errno`,
 * честно даст общий [DocumentWriteError.WriteFailed].
 */
internal fun Throwable.toDocumentWriteError(): DocumentWriteError = when {
    this is SecurityException -> DocumentWriteError.PermissionLost
    this is FileNotFoundException -> DocumentWriteError.NotFound
    isNoSpace() -> DocumentWriteError.NoSpace
    else -> DocumentWriteError.WriteFailed
}

private fun Throwable.isNoSpace(): Boolean {
    var failure: Throwable? = this
    var hops = 0
    while (failure != null && hops < CAUSE_CHAIN_LIMIT) {
        if (failure is android.system.ErrnoException && failure.errno == android.system.OsConstants.ENOSPC) {
            return true
        }
        failure = failure.cause
        hops++
    }
    return false
}

/** Страховка от цикла в цепочке причин: исключения умеют ссылаться друг на друга. */
private const val CAUSE_CHAIN_LIMIT = 8
