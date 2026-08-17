package io.github.olegnyr.adocmobile.preview

/**
 * Что политика превью велит сделать вызывающему прямо сейчас.
 *
 * Политика ничего не делает сама: ни таймеров, ни корутин, ни вызовов рендера.
 * Она отвечает решением, а исполняет его вызывающий — иначе пайплайн живого
 * превью нельзя было бы проверить без устройства и без реального времени
 * (NFR тестируемости; образец приёма — `AutosavePolicy` фичи 004).
 */
sealed interface PreviewAction {

    /** Делать нечего. */
    data object Idle : PreviewAction

    /**
     * Дождаться момента [dueAt] и позвать [PreviewPolicy.pauseElapsed].
     *
     * Более ранний вызов не ошибка: политика сама продлит ожидание, если пауза
     * ввода к этому моменту сдвинулась новой правкой.
     *
     * @property dueAt момент на той же монотонной шкале миллисекунд, на которой
     * вызывающий передаёт `now` в события.
     */
    data class WaitUntil(val dueAt: Long) : PreviewAction

    /**
     * Прервать незавершённый рендер, если он есть, и запустить новый: вызвать
     * `AdocRenderer.render(source)` и сообщить результат через
     * [PreviewPolicy.renderCompleted] с этим же [generation].
     *
     * Прерывание — часть действия, а не отдельное: правка отменяет устаревший
     * рендер, не дожидаясь его (`FR-10`), и очередь рендеров не растёт (`UC-3`).
     *
     * @property generation номер поколения — по нему политика отличает свежий
     * результат от опоздавшего (`FR-15`).
     */
    data class Render(val generation: Int, val source: String) : PreviewAction

    /**
     * Прервать незавершённый рендер и ничего не запускать взамен.
     *
     * Выдаётся при уходе с превью (`OQ-4`: движок работает только при видимом
     * превью) и когда текст вернулся к уже показанному — рендерить нечего, а
     * результат прерванного рендера перетёр бы актуальное содержимое.
     */
    data object CancelRender : PreviewAction
}

/**
 * Судьба завершившегося рендера.
 *
 * Отдельный тип, а не [PreviewAction]: здесь политика не просит, а разрешает
 * или запрещает — публикация результата и есть та единственная работа на
 * главном потоке, которую оставляет `FR-16`.
 */
sealed interface PreviewUpdate {

    /** Результат свежий: передать [html] в превью. */
    data class Publish(val html: String) : PreviewUpdate

    /**
     * Результат устарел: после старта этого рендера был запущен более новый или
     * рендер был прерван. В превью не попадает (`FR-15`, `TC-12`).
     */
    data object Stale : PreviewUpdate
}

/**
 * Состояние превью для показа пользователю — решение `OQ-1`.
 *
 * Индикатор положен только первому рендеру документа, когда показывать ещё
 * нечего; дальше на время рендера держится предыдущий HTML, молча.
 */
enum class PreviewStatus {
    /** Вкладка превью не видна; движок молчит (`OQ-4`). */
    Hidden,

    /** Первый рендер идёт, показывать нечего — место индикатора. */
    FirstRender,

    /** Есть что показать; идущий рендер этого не меняет. */
    Content,
}

/**
 * Политика живого превью: когда рендерить, когда ждать, чей результат показать.
 *
 * Закрывает `FR-13`…`FR-17` и решения `OQ-1`, `OQ-4`. Чистый автомат: часов
 * внутри нет, время приходит параметром события; рендерер автомату не передаётся
 * вовсе — он у вызывающего, которому политика лишь велит [PreviewAction.Render].
 *
 * Правила, ради которых автомат существует:
 *
 * * правка запускает рендер по паузе ~300 мс, а не на каждый символ (`FR-13`);
 * * при скрытом превью не рендерится ничего (`OQ-4`);
 * * показ превью рендерит сразу, без дебаунса — задержку покрывает анимация
 *   переключения (`FR-17`, `OQ-4`);
 * * выполняется и публикуется только последний рендер: устаревший прерывается
 *   действием, опоздавший отбрасывается поколением (`FR-10`, `FR-15`);
 * * ввод не ждёт рендера никогда: в типе действий нет ответа «подождите»
 *   (`FR-16`).
 *
 * Экземпляр рассчитан на один открытый документ, как `AutosavePolicy`: для
 * нового документа заводится новый экземпляр — и его первый рендер снова
 * получает индикатор.
 *
 * @param debounceMillis пауза ввода перед рендером. Умолчание — `FR-13`:
 * 300 мс, вдвое больше медианы рендера предельного документа (142 мс, `T-013`).
 */
class PreviewPolicy(private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS) {

    init {
        require(debounceMillis > 0) { "Пауза ввода должна быть положительной, а не $debounceMillis" }
    }

    /** Последний опубликованный HTML; `null` — публиковать было нечего. */
    var html: String? = null
        private set

    /** Исходник, которому соответствует [html]. */
    private var publishedSource: String? = null

    /** Видна ли вкладка превью (`OQ-4`). */
    private var visible = false

    /** Момент, когда истекает пауза ввода; `null` — рендер не запланирован. */
    private var dueAt: Long? = null

    /** Поколение незавершённого рендера; `null` — рендера в полёте нет. */
    private var inFlight: Int? = null

    /** Исходник незавершённого рендера — станет [publishedSource] при публикации. */
    private var inFlightSource: String? = null

    /** Счётчик поколений; растёт на каждом старте, никогда не переиспользуется. */
    private var generation = 0

    /** Состояние для показа; выводится из полей, отдельно не хранится. */
    val status: PreviewStatus
        get() = when {
            !visible -> PreviewStatus.Hidden
            html == null -> PreviewStatus.FirstRender
            else -> PreviewStatus.Content
        }

    /**
     * Вкладка превью стала видимой.
     *
     * Рендер — сразу и без дебаунса (`FR-17`, `OQ-4`): пауза ввода защищает от
     * рендера на каждый символ, а здесь событие одно и явное. Если текст не
     * менялся с последней публикации, не тратится и рендер.
     *
     * @param source текущий текст документа
     */
    fun previewShown(source: String, now: Long): PreviewAction {
        visible = true
        dueAt = null
        if (source == publishedSource) return PreviewAction.Idle
        return startRender(source)
    }

    /**
     * Вкладка превью скрыта: движок останавливается (`OQ-4`).
     *
     * Незавершённый рендер прерывается, его поздний результат — устаревший.
     * Опубликованный HTML при этом не забывается: возврат на неизменённый текст
     * не стоит ничего.
     */
    fun previewHidden(): PreviewAction {
        visible = false
        dueAt = null
        if (inFlight == null) return PreviewAction.Idle
        inFlight = null
        inFlightSource = null
        return PreviewAction.CancelRender
    }

    /**
     * Пользователь изменил текст: пауза ввода начинается заново (`FR-13`).
     *
     * Текст сюда не передаётся намеренно: до истечения паузы он политике не
     * нужен, а сериализовать снимок на каждый символ — работа на главном потоке,
     * которую запрещает `FR-16`. Снимок придёт с [pauseElapsed].
     *
     * Идущий рендер срок не сдвигает и правкой не прерывается: прерывание
     * случится на [pauseElapsed], когда станет известен новый текст (`TC-13`).
     */
    fun textEdited(now: Long): PreviewAction {
        if (!visible) return PreviewAction.Idle
        val due = now + debounceMillis
        dueAt = due
        return PreviewAction.WaitUntil(due)
    }

    /**
     * Пауза ввода истекла — вызывается по [PreviewAction.WaitUntil].
     *
     * Вызов раньше срока не ошибка: пауза могла продлиться правкой после того,
     * как вызывающий завёл таймер, — тогда возвращается новый срок.
     *
     * @param source текущий текст документа — то, что уйдёт в рендер
     */
    fun pauseElapsed(source: String, now: Long): PreviewAction {
        if (!visible) return PreviewAction.Idle
        val due = dueAt ?: return PreviewAction.Idle
        if (now < due) return PreviewAction.WaitUntil(due)
        dueAt = null
        if (source == publishedSource) {
            // Текст вернулся к показанному: рендерить нечего, а начатый рендер
            // устаревшего текста обязан быть прерван — его результат перетёр бы
            // актуальное содержимое (FR-15).
            if (inFlight == null) return PreviewAction.Idle
            inFlight = null
            inFlightSource = null
            return PreviewAction.CancelRender
        }
        return startRender(source)
    }

    /**
     * Рендер поколения [generation] завершился строкой [renderedHtml].
     *
     * Публикуется только рендер, который всё ещё последний: опоздавший результат
     * более раннего поколения отбрасывается — порядок гарантируется здесь, а не
     * получается из удачного расписания корутин (`FR-15`, `TC-12`).
     */
    fun renderCompleted(generation: Int, renderedHtml: String): PreviewUpdate {
        if (generation != inFlight) return PreviewUpdate.Stale
        inFlight = null
        publishedSource = inFlightSource
        inFlightSource = null
        html = renderedHtml
        return PreviewUpdate.Publish(renderedHtml)
    }

    /** Начать рендер нового поколения; предыдущий этим же действием прерывается. */
    private fun startRender(source: String): PreviewAction {
        generation += 1
        inFlight = generation
        inFlightSource = source
        return PreviewAction.Render(generation, source)
    }

    companion object {
        /** Пауза ввода по умолчанию — `FR-13`. */
        const val DEFAULT_DEBOUNCE_MILLIS: Long = 300
    }
}
