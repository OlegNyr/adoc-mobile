package io.github.olegnyr.adocmobile.render

import android.content.Context
import android.content.res.AssetManager
import com.dokar.quickjs.QuickJs
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
 * Три вещи, каждая из которых уже стоила отладки:
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
 * Конвертация в режиме фрагмента и с `showtitle` (`FR-7`).
 *
 * Без `showtitle` при `standalone: false` заголовок документа в вывод не
 * попадает — проверено прогоном обеих реализаций 2026-08-17, — а дизайн 02a его
 * показывает. Режим безопасности не задаётся намеренно: он развилка владельца
 * (`OQ-6`), и до ответа берётся умолчание движка.
 */
private const val CONVERT_SCRIPT = """
globalThis.__adocHtml = await globalThis.Asciidoctor.convert(
  globalThis.__adocSource,
  { standalone: false, attributes: { showtitle: '' } }
);
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

    override suspend fun render(source: String): String = turn.withLock {
        val literal = jsonStringLiteral(source)
        withContext(engineDispatcher) {
            val engine = engine()
            engine.evaluate<Any?>("globalThis.__adocSource = $literal;", filename = "source.js")
            engine.evaluate<Any?>(CONVERT_SCRIPT, filename = "convert.mjs", asModule = true)
            engine.evaluate<String>("globalThis.__adocHtml", filename = "fetch.js")
        }
    }

    /** Поднимает движок при первом обращении и больше к этому не возвращается. */
    private suspend fun engine(): QuickJs {
        quickJs?.let { return it }

        val assets = checkNotNull(installedAssets) {
            "Рендерер не установлен: вызовите installAdocRenderer(context) при старте приложения"
        }
        val bundle = assets.open(BUNDLE_ASSET).use { it.readBytes().decodeToString() }

        val created = QuickJs.create(engineDispatcher)
        created.maxStackSize = (ENGINE_STACK_KB - ENGINE_STACK_HEADROOM_KB).toLong() * 1024
        // Скрипт, а не модуль: финальный `export` в бандле заменён на присваивание
        // globalThis.Asciidoctor — см. androidApp/src/debug/tools/patch-asciidoctor.py.
        created.evaluate<Any?>(bundle, filename = "asciidoctor.js", asModule = false)

        quickJs = created
        initCount++
        return created
    }
}
