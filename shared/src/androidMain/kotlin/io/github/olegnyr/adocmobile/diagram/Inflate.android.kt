package io.github.olegnyr.adocmobile.diagram

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Android-половина распаковки (`ADR-014`): штатный zlib платформы.
 *
 * Своего декодера здесь нет намеренно. Ошибка в чужом коде даёт отказ, ошибка в
 * своём декодере Хаффмана — молча испорченный текст, и поймать его можно только
 * корпусом, которого у нас на сжатие нет.
 *
 * Контекст платформы этой реализации не нужен: `java.util.zip` — часть JDK, и
 * потому она же проверяется host-тестом без устройства.
 */
internal object AndroidInflate : Inflate {

    /**
     * Распаковывает zlib-поток, останавливаясь на пределе.
     *
     * Три условия выхода, и все три обязательны, потому что вход недоверенный:
     *
     * . *поток кончился* — обычный успех;
     * . *распакованного стало больше предела* — отказ до того, как выделена
     *   память сверх него; накопитель заводится под предел, а не под «сколько
     *   придёт»;
     * . *распаковщику нечего делать* — вход исчерпан или нужен словарь. Без
     *   этой ветки оборванный поток крутил бы цикл вечно, а оборвать поток
     *   может кто угодно: адрес приходит из документа.
     *
     * `Inflater` держит нативную память, и `end()` в `finally` — не
     * аккуратность, а обязанность: без него каждая недоступная диаграмма
     * оставляла бы за собой нативный буфер до сборки мусора.
     */
    override fun inflate(bytes: ByteArray, maxBytes: Int): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(minOf(bytes.size * 4, maxBytes))
            val buffer = ByteArray(BUFFER_BYTES)
            while (!inflater.finished()) {
                val written = inflater.inflate(buffer)
                if (written == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                    continue
                }
                if (out.size() + written > maxBytes) return null
                out.write(buffer, 0, written)
            }
            out.toByteArray()
        } catch (_: DataFormatException) {
            // Не zlib-поток. Штатный исход: payload мог быть написан руками.
            null
        } finally {
            inflater.end()
        }
    }

    private const val BUFFER_BYTES = 8 * 1024
}

/**
 * Ставит платформенную половину фичи диаграмм.
 *
 * Зовётся из `installAdocRenderer`, а не из своей строки в `MainActivity`:
 * точка входа у стека рендера и превью одна, приложение уже её вызывает, а
 * композиционный корень сейчас принадлежит фиче навигации. Когда `SL-3`
 * доберётся до пайплайна, установку можно будет поднять туда же, где ставятся
 * остальные швы.
 */
fun installDiagramSupport() {
    DiagramSupport.install(AndroidInflate)
}
