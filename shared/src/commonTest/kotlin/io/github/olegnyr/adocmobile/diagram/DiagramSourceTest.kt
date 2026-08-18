package io.github.olegnyr.adocmobile.diagram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Фича 008-diagrams, слайс `SL-2`: восстановление исходника из адреса.
 *
 * Здесь проверяется *оркестровка* — порядок шагов и поведение на каждом «не
 * вышло»; сама распаковка подделана. Настоящая проверяется рядом, host-тестом
 * Android (`DiagramSourceAndroidTest`): он не требует устройства, потому что
 * `java.util.zip` — часть JDK.
 */
class DiagramSourceTest {

    /** Распаковка, которая отдаёт заранее заданный текст, соблюдая предел. */
    private fun inflateTo(text: String) = Inflate { _, maxBytes ->
        text.encodeToByteArray().takeIf { it.size <= maxBytes }
    }

    @Test
    fun TC_40_sourceIsRestoredFromPayload() {
        val source = decodeKrokiSource("eNpLVNC1U0jiAgAGdQF5", inflateTo("@startuml\na -> b\n@enduml"))

        assertEquals("@startuml\na -> b\n@enduml", source)
    }

    @Test
    fun TC_40_payloadWithPaddingIsAccepted() {
        // Расширение выравнивание не ставит, но адрес мог быть переписан руками
        // или перенесён прокси; «YQ==» — корректный base64url с выравниванием.
        assertEquals("x", decodeKrokiSource("YQ==", inflateTo("x")))
    }

    @Test
    fun TC_10_nothingIsRestoredWhenThePlatformHalfIsNotInstalled() {
        assertNull(decodeKrokiSource("eNpLVNC1U0jiAgAGdQF5", inflate = null))
    }

    @Test
    fun TC_10_brokenPayloadGivesNoSource() {
        // Не base64url вовсе.
        assertNull(decodeKrokiSource("!!!!", inflateTo("неважно")))
        // Распаковка не признала поток.
        assertNull(decodeKrokiSource("eNpLVNC1U0jiAgAGdQF5", Inflate { _, _ -> null }))
        // Распаковка бросила исключение — наружу оно выйти не имеет права.
        assertNull(decodeKrokiSource("eNpLVNC1U0jiAgAGdQF5", Inflate { _, _ -> error("сломалось") }))
    }

    @Test
    fun TC_10_bytesThatAreNotValidUtf8GiveNoSource() {
        val notText = Inflate { _, _ -> byteArrayOf(0xC3.toByte(), 0x28, 0xA0.toByte(), 0xA1.toByte()) }

        assertNull(
            decodeKrokiSource("eNpLVNC1U0jiAgAGdQF5", notText),
            "испорченные байты показаны как исходник — лучше пометка без текста",
        )
    }

    /**
     * `TC-41`: предел на распакованный размер доходит до реализации.
     *
     * Проверяется именно *передача* предела, а не отбрасывание уже собранного
     * гиганта: подделка, аллоцирующая мегабайты и возвращающая их, доказала бы
     * ровно то поведение, от которого мы уходим, — память уже выделена.
     */
    @Test
    fun TC_41_decompressionLimitReachesTheImplementation() {
        var seenLimit = -1
        val probe = Inflate { _, maxBytes ->
            seenLimit = maxBytes
            null
        }

        assertNull(decodeKrokiSource("eNpLVNC1U0jiAgAGdQF5", probe))
        assertTrue(seenLimit in 1..(4 shl 20), "распаковке передан бессмысленный предел: $seenLimit")
    }

    @Test
    fun TC_41_implementationRefusalOnLimitGivesNoSource() {
        // Реализация, честно отказавшаяся на пределе, не должна превращаться в
        // «исходник восстановлен».
        assertNull(decodeKrokiSource("eNpLVNC1U0jiAgAGdQF5", Inflate { _, _ -> null }))
    }
}
