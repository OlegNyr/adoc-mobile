package io.github.olegnyr.adocmobile

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Дымовой тест каркаса, не привязанный к тест-кейсу спеки: он проверяет, что
 * тестовый набор commonTest вообще собирается и запускается, и что шов
 * expect/actual разрешается в платформенную реализацию.
 */
class PlatformTest {

    @Test
    fun platformNameIsResolvedToActualImplementation() {
        val name = platformName()
        assertTrue(name.isNotBlank(), "имя платформы не должно быть пустым")
    }
}
