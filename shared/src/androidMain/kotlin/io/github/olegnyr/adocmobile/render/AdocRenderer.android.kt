package io.github.olegnyr.adocmobile.render

import android.content.Context
import android.content.res.AssetManager
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Android-реализация шва рендера: Asciidoctor.js в QuickJS через `quickjs-kt`
 * (`FR-2`).
 *
 * Приёмы взяты из стенда `T-013` (`androidApp/src/debug/…/bench/RenderBench.kt`)
 * и из xref разведки `doc/research/render-stack.adoc`: там они получены прогоном
 * на устройстве, а не рассуждением. Стенд при этом остаётся стендом — он меряет,
 * а этот код рендерит по требованию.
 *
 * Четыре вещи, каждая из которых уже стоила отладки:
 *
 * . *Свой поток с известным стеком* (`FR-4`). Разбор AsciiDoc рекурсивен, а
 *   `maxStackSize` в QuickJS — проверка против настоящего стека потока: лимит
 *   выше него меняет понятное исключение на `SIGSEGV`.
 * . *Промис разворачивается вручную* (`FR-6`). `convert` в Asciidoctor.js 4
 *   асинхронный; обычный скрипт вернул бы сам объект `Promise`, чьё строковое
 *   представление — 32 символа, неотличимые от пустого документа. Модуль с
 *   `await` на верхнем уровне результата не возвращает вовсе (модуль — не
 *   выражение), поэтому он кладёт HTML в `globalThis`, а забирается тот вторым
 *   дешёвым вызовом.
 * . *Движок и бандл — один экземпляр на процесс* (`FR-3`). Дорого не создание
 *   рантайма, а разбор и выполнение ~865 КБ скрипта: 46–49 мс на всё про всё.
 * . *Бандла два, и порядок важен* (`ADR-008`, фича 008). Расширение Kroki
 *   исполняется следом за ядром и опирается на его пролог с полифилами; своё
 *   тело оно держит внутри IIFE, иначе объявления двух бандлов сталкиваются
 *   именами. Реестр расширений собирается тут же, один раз (`FR-6` фичи 008).
 */

/**
 * Путь к пропатченному бандлу внутри ассетов.
 *
 * Бандл лежит в `shared/src/androidMain/assets` — рядом с кодом, которому он
 * нужен, а не в приложении: иначе каждое приложение, подключившее модуль, должно
 * помнить, что обязано положить файл. Путь совпадает с тем, из которого читает
 * стенд `T-013`, поэтому слияние ассетов оставляет стенд рабочим.
 */
private const val BUNDLE_ASSET = "asciidoctor/asciidoctor.js"

/**
 * Ассет расширения Kroki — второй самодостаточный скрипт рядом с ядром
 * (`ADR-008`). Собирается тем же `patch-asciidoctor.py`, сверяется задачей
 * сборки `verifyKrokiAsset` (`TC-4` фичи 008).
 */
private const val KROKI_ASSET = "asciidoctor/asciidoctor-kroki.js"

/**
 * Размер стека потока движка, КБ. Значение — из замеров `T-013`: на нём документ
 * предельного размера (~1000 строк, `ADR-004`) разбирается без переполнения.
 */
private const val ENGINE_STACK_KB = 8192

/**
 * Запас между лимитом движка и краем настоящего стека, КБ: движок должен упереться
 * в свой лимит и бросить исключение раньше, чем поток снесёт стек.
 */
private const val ENGINE_STACK_HEADROOM_KB = 1024

/**
 * Предел одной конвертации, мс (`FR-11`, `TC-9`).
 *
 * Выведен из записанных замеров `T-013`, как требует `FR-11`, а не назначен:
 * медиана на предельном документе (~1000 строк, `ADR-004`) — 142 мс, худший
 * известный прогон (3000 строк, за пределом) — 414 мс. 5000 мс — это ~35 медиан
 * и ~12 худших прогонов: запас на бюджетное устройство, чья скорость не
 * измерялась (названная гипотеза спеки). Требование фиксирует, что зависание
 * невозможно, а не конкретное число: после замера на бюджетном устройстве
 * значение положено ужесточить.
 */
private const val RENDER_TIMEOUT_MILLIS = 5_000L

/**
 * Переживает ли движок исход рендера [failure] (`FR-9`, постусловие `UC-4`).
 *
 * Отмена — штатная работа пайплайна (`FR-10`): при быстром наборе она случается
 * на каждую правку, и платить за неё 46–49 мс подъёма движка значило бы
 * похоронить смысл синглтона (`FR-3`). Всё остальное — отказ движка: таймаут
 * (`QuickJsInterruptedException` — по имени «прерывание», по смыслу `FR-11`),
 * переполнение стека, исчерпание памяти, исключение — после него неизвестно,
 * на чём остановился Opal-рантайм, и следующий вызов не имеет права это
 * наследовать. Умолчание поэтому «пересоздать», а не «поверить».
 *
 * Тип-предок, а не точный класс: kotlinx кидает наследников
 * (`JobCancellationException`). `Throwable`, а не `Exception`: переполнение
 * стека — `Error`.
 */
internal fun engineSurvives(failure: Throwable): Boolean = failure is CancellationException

/**
 * Конвертация в режиме фрагмента и с `showtitle` (`FR-7`).
 *
 * Без `showtitle` при `standalone: false` заголовок документа в вывод не
 * попадает — проверено прогоном обеих реализаций 2026-08-17, — а дизайн 02a его
 * показывает. Режим безопасности не задаётся намеренно: он развилка владельца
 * (`OQ-6`), и до ответа берётся умолчание движка.
 *
 * Реестр расширений подставляется *условно* (`FR-3` фичи 008): выключенные
 * диаграммы — это конвертация вообще без расширения, а не расширение с пустым
 * адресом. Тогда в выводе не появляется ни одного адреса Kroki и отправлять
 * наружу нечего — решение владельца `OQ-2`.
 */
private const val CONVERT_SCRIPT = """
const options = { standalone: false, attributes: { showtitle: '' } };
if (globalThis.__adocKrokiEnabled) {
  options.extension_registry = globalThis.__adocKrokiRegistry;
  options.attributes['kroki-server-url'] = globalThis.__adocKrokiServerUrl;
}
globalThis.__adocHtml = await globalThis.Asciidoctor.convert(globalThis.__adocSource, options);
"""

/**
 * Подъём расширения Kroki: реестр создаётся один раз вместе с движком (`FR-6`
 * фичи 008).
 *
 * Реестр — состояние, и переиспользование одного экземпляра на нескольких
 * документах могло бы молча перестать работать со второй конвертации; `TC-3`
 * проверяет, что не перестаёт. Оно работает потому, что сброс реестра между
 * документами сохраняет расширения, зарегистрированные вне активации.
 *
 * Плата за синглтон названа здесь, чтобы её не искали потом: реестр и общий
 * контекст расширения удерживают ссылку на *последний* обработанный документ,
 * пока не придёт следующий. Один документ за раз и предельный размер ~1000
 * строк (`ADR-004`) делают это приемлемым; если предел вырастет, удержание
 * придётся пересматривать вместе с ним.
 *
 * Регистрируются все тридцать типов диаграмм разом: выборочной регистрации у
 * расширения нет, а перечень типов — знание эталона, а не наше.
 */
private const val KROKI_REGISTER_SCRIPT = """
globalThis.__adocKrokiRegistry = globalThis.Asciidoctor.Extensions.create();
globalThis.AsciidoctorKroki.default.register(globalThis.__adocKrokiRegistry);
"""

/**
 * Ассеты приложения — единственное, что реализации нужно от платформы.
 *
 * Хранится статически, потому что и движок статический: он живёт столько же,
 * сколько процесс (`FR-3`), и переживает пересоздание экрана.
 */
@Volatile
private var installedAssets: AssetManager? = null

/**
 * Отдаёт реализации доступ к бандлу. Вызывается один раз при старте приложения.
 *
 * Явный вызов вместо автоматического захвата контекста выбран сознательно:
 * альтернативы — `ContentProvider` или `androidx.startup` — прячут инициализацию
 * в манифест, а цена здесь одна строка в `MainActivity`. Забыть про неё нельзя:
 * без установки первый же рендер падает с внятным сообщением, а не молча.
 */
fun installAdocRenderer(context: Context) {
    installedAssets = context.applicationContext.assets
}

/**
 * Поднимает расширение Kroki поверх уже живого ядра; `false` — расширение
 * недоступно (`TC-37`).
 *
 * Отдельная функция, а не строки внутри `engine()`, по двум причинам. Первая —
 * проверяемость: сломанный или отсутствующий ассет иначе никак не
 * воспроизвести, а именно этот путь и опасен. Вторая — граница отказа: всё, что
 * пойдёт не так здесь, обязано остаться здесь. Диаграммы — дополнение к
 * рендеру; документ без них показывается, документ без движка — нет.
 *
 * Отмена сквозь эту границу проходит: она не отказ расширения, а решение
 * вызывающего (тот же принцип, что в [engineSurvives]).
 */
internal suspend fun installKrokiExtension(engine: QuickJs, bundle: String): Boolean =
    try {
        // Скрипт, а не модуль, и строго после ядра (ADR-008): пролог с полифилами
        // приезжает с ядром, а тело расширения завёрнуто в IIFE — иначе
        // объявления двух бандлов сталкиваются именами.
        engine.evaluate<Any?>(bundle, filename = "asciidoctor-kroki.js", asModule = false)
        engine.evaluate<Any?>(KROKI_REGISTER_SCRIPT, filename = "kroki-register.js")
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        false
    }

/** @see AdocRenderer */
actual fun adocRenderer(): AdocRenderer = QuickJsAdocRenderer

/**
 * Движок-синглтон. `object`, а не класс с временем жизни экрана: пересоздавать
 * его на каждый поворот значило бы платить 46–49 мс за поворот и терять смысл
 * `FR-3`.
 */
internal object QuickJsAdocRenderer : AdocRenderer {

    /**
     * Однопоточный исполнитель с явно заданным стеком. Он же гарантирует, что все
     * обращения к движку идут из одного потока: `quickjs-kt` этого требует.
     */
    private val engineDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(null, runnable, "adoc-render", ENGINE_STACK_KB.toLong() * 1024)
        }.asCoroutineDispatcher()
    }

    /**
     * Одна очередь на инициализацию и на рендеры.
     *
     * Однопоточного диспетчера мало: рендер — это три отдельных вызова через
     * `globalThis`, и два одновременных рендера переплели бы их, отдав чужой HTML.
     * Отмена устаревших рендеров вместо ожидания — `FR-10`, слайс `SL-4`.
     */
    private val turn = Mutex()

    private var quickJs: QuickJs? = null

    /**
     * Сколько раз поднимался движок. Оракул `TC-2`: два вызова `render` не должны
     * поднимать его дважды.
     */
    internal var initCount: Int = 0
        private set

    /**
     * Удалось ли поднять расширение Kroki. Оракул `TC-37`.
     *
     * Пока это знание никому, кроме тестов, не видно: сообщить пользователю
     * «диаграммы недоступны» некому — плашка и пометка появляются в `SL-2`.
     * Наблюдаемое поведение при `false` совпадает с режимом `ВЫКЛ`: блок
     * диаграммы остаётся исходным текстом.
     */
    internal var krokiAvailable: Boolean = false
        private set

    override suspend fun render(source: String): String = render(source, DiagramOptions.Disabled)

    override suspend fun render(source: String, diagrams: DiagramOptions): String = turn.withLock {
        val literal = jsonStringLiteral(source)
        // Адрес сервера пересекает границу с JS тем же способом, что документ
        // (FR-5 фичи 003): подстановка сырой строки в текст скрипта — инъекция,
        // а не «кривой адрес».
        val serverLiteral = jsonStringLiteral(diagrams.serverUrl)
        withContext(engineDispatcher) {
            val engine = engine()
            try {
                engine.evaluate<Any?>("globalThis.__adocSource = $literal;", filename = "source.js")
                engine.evaluate<Any?>(
                    "globalThis.__adocKrokiEnabled = ${diagrams.krokiEnabled && krokiAvailable};" +
                        "globalThis.__adocKrokiServerUrl = $serverLiteral;",
                    filename = "diagrams.js",
                )
                engine.evaluate<Any?>(CONVERT_SCRIPT, filename = "convert.mjs", asModule = true)
                engine.evaluate<String>("globalThis.__adocHtml", filename = "fetch.js")
            } catch (failure: Throwable) {
                // Отказ движка (TC-8, TC-9): экземпляр закрывается здесь же, на
                // его потоке, и следующий render поднимет свежий — постусловие
                // UC-4. Отмена (FR-10) движок не трогает: см. engineSurvives.
                if (!engineSurvives(failure)) {
                    quickJs = null
                    runCatching { engine.close() }
                }
                // Сам отказ — не место для трактовки: что показать и что
                // записать, решает PreviewPolicy.renderFailed, и не по
                // сообщению исключения (TC-29), а по его типу.
                throw failure
            }
        }
    }

    /** Поднимает движок при первом обращении и больше к этому не возвращается. */
    private suspend fun engine(): QuickJs {
        quickJs?.let { return it }

        val assets = checkNotNull(installedAssets) {
            "Рендерер не установлен: вызовите installAdocRenderer(context) при старте приложения"
        }
        val bundle = assets.open(BUNDLE_ASSET).use { it.readBytes().decodeToString() }
        // Ассет расширения читается «мягко»: диаграммы — дополнение к рендеру, а
        // не его условие (FR-19). Умолчание режима — ВЫКЛ (решение владельца
        // OQ-2), то есть большинству запусков расширение вообще не понадобится,
        // и уронить из-за него показ документа было бы худшим из возможных
        // поведений.
        val krokiBundle = runCatching {
            assets.open(KROKI_ASSET).use { it.readBytes().decodeToString() }
        }.getOrNull()

        val created = QuickJs.create(engineDispatcher)
        try {
            created.maxStackSize = (ENGINE_STACK_KB - ENGINE_STACK_HEADROOM_KB).toLong() * 1024
            // Лимит на конвертацию (FR-11): движок сам прерывает вычисление,
            // ушедшее за предел, — QuickJsInterruptedException, отказ по FR-9.
            created.evaluationTimeoutMillis = RENDER_TIMEOUT_MILLIS
            // Скрипт, а не модуль: финальный `export` в бандле заменён на присваивание
            // globalThis.Asciidoctor — см. androidApp/src/debug/tools/patch-asciidoctor.py.
            created.evaluate<Any?>(bundle, filename = "asciidoctor.js", asModule = false)
        } catch (failure: Throwable) {
            // Полусобранный движок не оставляется ни в поле, ни в памяти:
            // иначе каждый повторный подъём после отказа течёт нативным рантаймом.
            runCatching { created.close() }
            throw failure
        }

        // Расширение поднимается уже поверх живого ядра и на отказ ядра не влияет
        // (TC-37): недоступное расширение означает документ без диаграмм, а не
        // приложение без превью.
        krokiAvailable = krokiBundle != null && installKrokiExtension(created, krokiBundle)

        quickJs = created
        initCount++
        return created
    }
}
