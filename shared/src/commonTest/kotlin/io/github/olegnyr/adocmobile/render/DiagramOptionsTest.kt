package io.github.olegnyr.adocmobile.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Фича 008-diagrams: умолчания режима диаграмм (`TC-36`) и безопасность адреса
 * сервера на границе с разметкой (`TC-38`).
 *
 * Тест на одно поле выглядит избыточным ровно до того момента, когда умолчание
 * поменяют «за компанию» с чем-нибудь ещё. Здесь оно не деталь реализации, а
 * решение владельца (`OQ-2`): текст диаграммы — фрагмент документа
 * пользователя, и уходить на чужой сервер он должен только по явному
 * включению. Смена `false` на `true` в одной строке — это молчаливая отправка
 * документов третьей стороне у всех, кто ничего не настраивал.
 *
 * Живёт в `commonTest`, потому что проверять тут нечего платформенного, а
 * правило проекта требует держать ядро проверяемым без устройства.
 */
class DiagramOptionsTest {

    @Test
    fun TC_36_diagramsAreDisabledByDefault() {
        assertFalse(
            DiagramOptions.Disabled.krokiEnabled,
            "умолчание режима диаграмм перестало быть ВЫКЛ (решение владельца OQ-2, FR-23)",
        )
    }

    @Test
    fun TC_36_defaultServerAddressIsThePublicKroki() {
        assertEquals(
            "https://kroki.io",
            DiagramOptions(krokiEnabled = true).serverUrl,
            "умолчание адреса сервера разошлось с FR-24",
        )
        assertEquals(
            "https://kroki.io",
            DEFAULT_KROKI_SERVER_URL,
            "константа умолчания разошлась с FR-24",
        )
    }

    /**
     * `TC-38` — покрывает `NFR-8`, `FR-13`: адрес, способный сломать разметку,
     * до страницы превью не доходит.
     *
     * Адрес пересекает две границы, а не одну. Подстановку в текст скрипта
     * закрывает `jsonStringLiteral`; вторая граница — `+<img src="…">+`, куда
     * конвертер подставляет адрес *без* экранирования (`alt` он экранирует,
     * `src` — нет). Кавычка в адресе означала бы произвольные атрибуты в
     * странице превью.
     */
    @Test
    fun TC_38_serverAddressThatCouldBreakMarkupIsRefused() {
        val hostile = listOf(
            "https://kroki.io\" data-x=\"",
            "https://kroki.io/<script>",
            "https://kroki.io ",
            "https://kroki.io\u0000",
            "https://kroki.io\n",
            "https://kroki.io\\evil",
        )
        for (address in hostile) {
            assertFailsWith<IllegalArgumentException>("адрес «$address» принят, а не должен") {
                DiagramOptions(krokiEnabled = true, serverUrl = address)
            }
        }
    }

    @Test
    fun TC_38_ordinaryAddressesAreAccepted() {
        for (address in listOf("https://kroki.io", "https://kroki.acme.io:8443", "https://192.168.1.10")) {
            DiagramOptions(krokiEnabled = true, serverUrl = address)
        }
    }
}
