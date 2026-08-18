package io.github.olegnyr.adocmobile.diagram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android-транспорт диаграмм: `HttpURLConnection` (`OQ-7`).
 *
 * Своей библиотеки нет и не будет: таймауты, повторные попытки и деградация
 * заданы требованиями фичи, а не библиотекой, и тянуть ради трёх десятков строк
 * зависимость с весом в APK незачем.
 *
 * Чего этот класс *не* делает, хотя соблазн есть:
 *
 * * *не следует за редиректом на другой хост* — адрес сервера пользователь
 *   задал сам, и увести запрос с него не должен никто;
 * * *не верит заголовку `Content-Type`* — тип изображения берётся из формата
 *   диаграммы (`DiagramImages`), сервер здесь недоверенный ровно так же, как
 *   документ;
 * * *не сообщает причину отказа* — показываем мы во всех случаях одно и то же.
 *
 * @param connectTimeoutMillis, readTimeoutMillis — базовые предохранители от
 * зависания. Требованием таймаут станет в `SL-4`, где ему найдут число; здесь
 * важно лишь, что бесконечного ожидания не бывает.
 */
class HttpDiagramTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 10_000,
    private val maxBytes: Int = 4 shl 20,
) : DiagramTransport {

    override suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                instanceFollowRedirects = false
                setRequestProperty("Accept", "image/svg+xml, image/png, image/jpeg")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (out.size() + read > maxBytes) return@withContext null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (cancellation: CancellationException) {
            // Отмена — решение вызывающего (правка текста, уход с превью), а не
            // отказ сети: она обязана пройти сквозь транспорт наружу.
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
