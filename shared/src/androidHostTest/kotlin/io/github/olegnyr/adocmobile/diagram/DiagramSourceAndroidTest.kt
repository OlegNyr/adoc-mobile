package io.github.olegnyr.adocmobile.diagram

import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Фича 008-diagrams, слайс `SL-2`: настоящая распаковка (`ADR-014`).
 *
 * Дом — host-тест, а не устройство: `java.util.zip` входит в JDK, и гонять ради
 * него телефон значит платить устройством за уже покрытое (правило проекта о
 * тестах). Проверяется та же реализация, что стоит в приложении.
 *
 * Оракул сильный намеренно: адреса не выдуманные, а снятые прогоном настоящего
 * расширения `asciidoctor-kroki` (разведка фичи, Q2). Разъедутся распаковка,
 * алфавит или порядок шагов — тест назовёт ожидаемый текст, а не «что-то пошло
 * не так».
 */
class DiagramSourceAndroidTest {

    @Test
    fun TC_40_realAddressFromTheExtensionDecodesToItsDiagramSource() {
        val payload = "eNpLVNC1U0jiAgAGdQF5"

        val source = decodeKrokiSource(payload, AndroidInflate)

        assertEquals("a -> b\n", source)
    }

    @Test
    fun TC_40_cyrillicDiagramSurvivesTheRoundTrip() {
        // Тот же прогон расширения: alice -> bob с кириллическим сообщением.
        val payload = "eNpLzMlMTlXQtVNIyk-yUriw_2LDhR0XNl3YerGJCwCb7A0D"

        val source = decodeKrokiSource(payload, AndroidInflate)

        // Обёртки @startuml/@enduml в адресе нет: их снимает предобработка
        // расширения. Значит, деградация показывает тело диаграммы, а не блок
        // ровно в том виде, в каком его набрал пользователь, — факт, который
        // лучше знать из теста, чем обнаружить на экране.
        assertEquals("alice -> bob: привет\n", source)
    }

    @Test
    fun TC_10_payloadThatIsNotAZlibStreamGivesNoSource() {
        // Валидный base64url, но за ним не поток: расширение такого не порождает,
        // а руками в документе написать можно.
        assertNull(decodeKrokiSource("bm90LWEtemxpYi1zdHJlYW0", AndroidInflate))
    }

    @Test
    fun TC_10_truncatedStreamGivesNoSourceInsteadOfHanging() {
        val full = "eNpLzMlMTlXQtVNIyk-yUriw_2LDhR0XNl3YerGJCwCb7A0D"

        assertNull(
            decodeKrokiSource(full.substring(0, 12), AndroidInflate),
            "оборванный поток должен давать отказ, а не крутиться в цикле",
        )
    }

    /**
     * `TC-41`: настоящая бомба распаковки отвергается, и память под неё не
     * выделяется.
     *
     * Поток здесь честный — два мегабайта нулей, сжатые штатным `Deflater`, —
     * то есть ровно то, что можно написать руками в документе:
     * `+<img src="https://kroki.io/plantuml/svg/…">+`. Проверка на подделке
     * доказала бы только передачу параметра; здесь проверяется, что реализация
     * действительно останавливается.
     */
    @Test
    fun TC_41_realDecompressionBombIsRefused() {
        val bomb = deflate(ByteArray(2 shl 20))

        assertNull(
            AndroidInflate.inflate(bomb, maxBytes = 1 shl 20),
            "распакованный гигант принят: документ — недоверенный ввод, предел обязателен",
        )
    }

    @Test
    fun TC_41_streamJustUnderTheLimitStillDecodes() {
        // Граница проверяется с обеих сторон: предел, который отвергает всё, —
        // не защита, а поломка.
        val payload = ByteArray(1024) { 'a'.code.toByte() }

        val inflated = AndroidInflate.inflate(deflate(payload), maxBytes = 1 shl 20)

        assertEquals(1024, inflated?.size)
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArray(bytes.size + 1024)
            val written = deflater.deflate(out)
            out.copyOf(written)
        } finally {
            deflater.end()
        }
    }
}
