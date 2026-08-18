package io.github.olegnyr.adocmobile.diagram

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Распаковка zlib — единственное, что фиче нужно от платформы для восстановления
 * исходника диаграммы (`ADR-014`).
 *
 * Интерфейс, а не `expect`/`actual`: распаковка — платформенная возможность,
 * доступная на обеих целевых платформах, и оформляется тем же приёмом, что
 * `PreviewImageSource` и `Secret`. Интерфейс к тому же подделывается в
 * `commonTest`, а `expect object` — нечем.
 *
 * Реализация обязана быть чистой функцией от байтов: ни состояния между
 * вызовами, ни исключений наружу. `null` означает «поток не разбирается» — и это
 * штатный исход, а не отказ: payload приходит из документа пользователя.
 */
fun interface Inflate {

    /**
     * Распаковывает zlib-поток; `null` — данные не поток либо распакованное не
     * умещается в [maxBytes].
     *
     * Предел передаётся *внутрь*, а не проверяется по возвращённому массиву:
     * проверять размер после распаковки значит сначала выделить ту самую
     * память, от которой защищаешься. Реализация обязана прекратить работу,
     * как только распакованного стало больше предела.
     */
    fun inflate(bytes: ByteArray, maxBytes: Int): ByteArray?
}

/**
 * Платформенная половина фичи, установленная на старте приложения.
 *
 * Тот же приём, что у `installAdocRenderer`: общий код объявляет потребность,
 * платформа закрывает её при запуске. Пока не установлена — исходник диаграммы
 * не восстанавливается, и деградация показывает одну пометку без текста
 * (`FR-22`). Это честный исход, а не тихая поломка: пометка на месте.
 */
object DiagramSupport {

    @Volatile
    var inflate: Inflate? = null
        private set

    /** Вызывается один раз при старте приложения. */
    fun install(inflate: Inflate) {
        this.inflate = inflate
    }
}

/**
 * Предел распакованного исходника, байт.
 *
 * Payload приходит из разметки, а документ — недоверенный ввод: ничто не мешает
 * написать в нём `+<img src="https://kroki.io/plantuml/svg/…">+` с payload,
 * раздувающимся при распаковке в гигабайты. Предел заведомо больше любой
 * рукописной диаграммы (документ ограничен ~1000 строк, `ADR-004`) и заведомо
 * меньше того, что успеет заметить пользователь. Соблюдает его реализация
 * [Inflate], которой предел передаётся параметром: только она знает, сколько
 * уже записано, и только она может остановиться до выделения памяти.
 */
private const val MAX_DIAGRAM_SOURCE_BYTES = 1 shl 20

/**
 * Восстанавливает исходный текст диаграммы из payload адреса; `null` — не вышло.
 *
 * Три шага, и на каждом «не вышло» — обычный исход, а не ошибка: base64url →
 * распаковка zlib → строгий UTF-8. Строгий намеренно (`ADR-014`, записанное
 * последствие): распаковка чужого потока может дать байты, которые сойдут за
 * текст лишь частично, и показать такой «исходник» пользователю хуже, чем не
 * показать ничего. Не сложилось — деградация покажет пометку без текста
 * (`TC-10`).
 */
@OptIn(ExperimentalEncodingApi::class)
fun decodeKrokiSource(payload: String, inflate: Inflate?): String? {
    if (inflate == null) return null
    val compressed = runCatching { KROKI_BASE64.decode(payload) }.getOrNull() ?: return null
    val bytes = runCatching { inflate.inflate(compressed, MAX_DIAGRAM_SOURCE_BYTES) }.getOrNull() ?: return null
    return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
}

/**
 * Алфавит адресов Kroki: base64url, выравнивание необязательно.
 *
 * Расширение выравнивание не ставит, но принимать его надо: адрес мог быть
 * переписан руками или переносом через прокси.
 */
@OptIn(ExperimentalEncodingApi::class)
private val KROKI_BASE64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
