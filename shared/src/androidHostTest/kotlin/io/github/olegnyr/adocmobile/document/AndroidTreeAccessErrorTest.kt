package io.github.olegnyr.adocmobile.document

import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Фича 004-document-and-files, слайс `SL-7`: разбор отказов перечисления дерева.
 *
 * Тест на хосте, без устройства, — по тем же соображениям, что
 * [AndroidDocumentAccessErrorTest]: функция чистая, и главная ловушка — порядок
 * ветвей ([FileNotFoundException] наследует [IOException]; перепутанные местами
 * ветви молча превратят «папка удалена» в «ошибка чтения»).
 */
class AndroidTreeAccessErrorTest {

    @Test
    fun FR_3_securityExceptionMeansTreeAccessLost() {
        assertEquals(
            TreeAccessError.PermissionLost,
            SecurityException("Permission Denial").toTreeAccessError(),
        )
    }

    @Test
    fun FR_3_missingTreeIsNotAListFailure() {
        assertEquals(
            TreeAccessError.NotFound,
            FileNotFoundException("No such directory").toTreeAccessError(),
        )
    }

    @Test
    fun FR_3_ioFailureStaysAListFailure() {
        assertEquals(TreeAccessError.ListFailed, IOException("broken pipe").toTreeAccessError())
    }

    @Test
    fun FR_3_unexpectedFailureIsStillHandled() {
        // Провайдер вправе бросить что угодно — штатный сценарий не должен
        // зависеть от его фантазии.
        assertEquals(TreeAccessError.ListFailed, IllegalArgumentException("bad uri").toTreeAccessError())
    }
}
