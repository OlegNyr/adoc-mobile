package io.github.olegnyr.adocmobile.diagram

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Фича 008-diagrams, слайс `SL-2`: разбор адреса диаграммы.
 *
 * Адрес — единственное, что остаётся от блока диаграммы в выводе конвертера, и
 * из него же восстанавливается исходник при деградации (`FR-22`). Формат снят
 * прогоном расширения, а не вспомнен: `{сервер}/{тип}/{формат}/{payload}`, где
 * payload — deflate + base64url.
 *
 * Признаком «это диаграмма» служит *происхождение*, а не форма пути (решение
 * владельца 2026-08-18): под форму «три сегмента, последний base64url» попадает
 * любая внешняя картинка без расширения в имени, и превращать её в пометку
 * «диаграмма не загружена» значит портить документ.
 */
class KrokiAddressTest {

    private val server = "https://kroki.example"

    @Test
    fun TC_39_addressOfADiagramIsParsedIntoParts() {
        val url = "$server/plantuml/svg/eNpLVNC1U0jiAgAGdQF5"

        val address = parseKrokiAddress(url, server)

        assertEquals(
            KrokiAddress(
                url = url,
                serverUrl = server,
                type = "plantuml",
                format = "svg",
                payload = "eNpLVNC1U0jiAgAGdQF5",
            ),
            address,
        )
    }

    @Test
    fun TC_39_serverWithPortAndPathPrefixSurvivesParsing() {
        val proxied = "https://kroki.acme.io:8443/render"

        val address = parseKrokiAddress("$proxied/mermaid/png/eNpLL0osyFAIcbFWcNTVtXOyBgArZAR8", proxied)

        assertEquals(proxied, address?.serverUrl)
        assertEquals("mermaid", address?.type)
        assertEquals("png", address?.format)
    }

    @Test
    fun TC_39_trailingSlashInSettingsDoesNotBreakParsing() {
        val url = "$server/plantuml/svg/AAAA"

        assertNotNull(parseKrokiAddress(url, "$server/"), "хвостовой слэш в настройке съел разбор")
    }

    @Test
    fun TC_39_schemeAndHostAreCaseInsensitive() {
        val url = "HTTPS://Kroki.Example/plantuml/svg/AAAA"

        val address = parseKrokiAddress(url, server)

        assertNotNull(address, "регистр схемы и хоста сделал адрес чужим, хотя по RFC 3986 он тот же")
        assertEquals(url, address.url, "адрес обязан сохраняться как есть: он же ключ кэша")
    }

    @Test
    fun TC_39_pictureFromAnotherServerIsNotADiagram() {
        // Главное, ради чего сверяется происхождение: чужая картинка без
        // расширения в имени по форме пути неотличима от диаграммы.
        val foreign = listOf(
            "https://wiki.acme.io/download/attachments/12345/QUJDRA",
            "https://api.github.com/avatars/u/1234567",
            "https://ci.acme.io/job/build/badge/icon",
        )
        for (url in foreign) {
            assertNull(parseKrokiAddress(url, server), "чужая картинка «$url» принята за диаграмму")
        }
    }

    @Test
    fun TC_39_anythingThatIsNotADiagramAddressIsRefused() {
        val foreign = listOf(
            // Локальная картинка документа — её показывает другой шов.
            "https://adoc-preview.invalid/pic.png",
            // Сегментов не три.
            "$server/plantuml/svg",
            "$server/plantuml/svg/AAAA/BBBB",
            // Пустые сегменты.
            "$server/plantuml//AAAA",
            "$server/plantuml/svg/",
            // Не тот протокол и пустая строка.
            "javascript:alert(1)",
            "",
            // Тип и формат — из перечня разрешённых символов, а не что угодно.
            "$server/pla ntuml/svg/AAAA",
            "$server/plantuml/sv%67/AAAA",
            // Похожий, но чужой хост: префикс совпадает, а сервер другой.
            "https://kroki.example.evil.com/plantuml/svg/AAAA",
        )
        for (url in foreign) {
            assertNull(parseKrokiAddress(url, server), "адрес «$url» разобран как диаграмма, а не должен")
        }
    }

    @Test
    fun TC_39_payloadKeepsBase64UrlAlphabetOnly() {
        // «+» и «/» в base64url заменены на «-» и «_»: их появление означает,
        // что это не наш payload и восстанавливать из него исходник нельзя.
        assertNull(parseKrokiAddress("$server/plantuml/svg/eNpL+VNC1", server))
        assertEquals(
            "eNpL-VNC1_A=",
            parseKrokiAddress("$server/plantuml/svg/eNpL-VNC1_A=", server)?.payload,
        )
    }

    @Test
    fun TC_39_emptyServerSettingRecognisesNothing() {
        // Пустая настройка не имеет права превратиться в «подходит любой адрес».
        assertNull(parseKrokiAddress("$server/plantuml/svg/AAAA", ""))
        assertNull(parseKrokiAddress("$server/plantuml/svg/AAAA", "/"))
    }
}
