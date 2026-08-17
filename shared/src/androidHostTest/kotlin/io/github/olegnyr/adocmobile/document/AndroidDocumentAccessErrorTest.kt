package io.github.olegnyr.adocmobile.document

import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Фича 004-document-and-files, слайс `SL-2`: разбор отказов файлового доступа.
 *
 * Тест на хосте, без устройства: функция чистая, а проверять её на устройстве
 * значит проверять один путь из четырёх. Что именно прилетает от системы в
 * каждом случае — вопрос устройства, и он закрыт `TC-21`; здесь проверяется,
 * что прилетевшее не потеряется по дороге.
 *
 * Главная ловушка — порядок ветвей: [FileNotFoundException] наследует
 * [IOException], и достаточно поменять две строки местами, чтобы «файл удалён»
 * навсегда стал «ошибкой чтения». Тест ради этого и написан.
 */
class AndroidDocumentAccessErrorTest {

    @Test
    fun FR_3_securityExceptionMeansAccessLost() {
        assertEquals(
            DocumentAccessError.PermissionLost,
            SecurityException("Permission Denial").toDocumentAccessError(),
        )
    }

    @Test
    fun FR_3_missingFileIsNotAReadFailure() {
        assertEquals(
            DocumentAccessError.NotFound,
            FileNotFoundException("No such file").toDocumentAccessError(),
        )
    }

    @Test
    fun FR_3_ioFailureStaysAReadFailure() {
        assertEquals(DocumentAccessError.ReadFailed, IOException("broken pipe").toDocumentAccessError())
    }

    @Test
    fun FR_3_unexpectedFailureIsStillHandled() {
        // Провайдер вправе бросить что угодно, включая IllegalArgumentException
        // на разобранный URI. Штатный сценарий не должен зависеть от их фантазии.
        assertEquals(DocumentAccessError.ReadFailed, IllegalArgumentException("bad uri").toDocumentAccessError())
    }
}
