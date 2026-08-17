package io.github.olegnyr.adocmobile.document

/**
 * Что политика велит сделать вызывающему прямо сейчас.
 *
 * Политика ничего не делает сама: ни таймеров, ни корутин, ни обращений к файлу.
 * Она отвечает решением, а исполняет его вызывающий — иначе логику автосохранения
 * нельзя было бы проверить без устройства и без реального времени.
 */
sealed interface AutosaveAction {

    /** Делать нечего. */
    data object Idle : AutosaveAction

    /**
     * Дождаться момента [dueAt] и позвать [AutosavePolicy.pauseElapsed].
     *
     * Более ранний вызов не ошибка: политика сама сдвинет ожидание, если пауза
     * ввода к этому моменту продлилась новой правкой.
     *
     * @property dueAt момент в миллисекундах эпохи Unix — та же шкала, что у
     * [DocumentState.savedAt]
     */
    data class WaitUntil(val dueAt: Long) : AutosaveAction

    /**
     * Записать [text] в файл и сообщить результат через
     * [AutosavePolicy.writeSucceeded] или [AutosavePolicy.writeFailed].
     *
     * Текст передан снимком намеренно: пока идёт запись, пользователь продолжает
     * печатать, и на диск обязан уйти ровно тот текст, который политика считает
     * записанным (`TC-19`).
     */
    data class Write(val text: String) : AutosaveAction
}

/**
 * Состояние автосохранения для показа пользователю.
 *
 * Заголовок экрана строится по [DocumentState.isModified] (`FR-18`), но одного
 * признака мало: «сейчас пишем» и «после отказа ждём правки» — разные состояния
 * с разной подсказкой, а отличить их по признаку изменений нельзя.
 */
enum class AutosaveStatus {
    /** Расхождения с диском нет, ждать нечего. */
    Idle,

    /** Есть несохранённые правки, идёт отсчёт паузы ввода. */
    Pending,

    /** Запись идёт прямо сейчас. */
    Writing,

    /** После отказа записи автосохранение приостановлено — `FR-19`. */
    Suspended,
}

/**
 * Новое состояние документа и следующее действие.
 *
 * Возвращается только событиями, которые меняют документ: завершением записи.
 * Остальные события документ не трогают и отвечают одним [AutosaveAction] —
 * разные типы возврата здесь несут смысл, а не случайность.
 */
data class AutosaveOutcome(
    val document: DocumentState,
    val action: AutosaveAction,
)

/**
 * Политика автосохранения: когда писать, когда молчать, что делать после отказа.
 *
 * Закрывает `FR-16`…`FR-19`. Чистый автомат: часов внутри нет, время приходит
 * параметром каждого события. Это не стилистика, а требование тестируемости из
 * спеки — политика проверяется в `commonTest` без устройства, и тест, который
 * спит секунду, проверяет планировщик, а не правило.
 *
 * Экземпляр рассчитан на один открытый документ: он помнит снимок текста,
 * который сейчас пишется. Для нового документа заводится новый экземпляр.
 *
 * @param pauseMillis длина паузы ввода, после которой уходит запись. Спека
 * интервал не задаёт; значение по умолчанию заметно длиннее дебаунса рендера
 * (300 мс), чтобы файл не переписывался за каждой микропаузой в наборе.
 */
class AutosavePolicy(private val pauseMillis: Long = DEFAULT_PAUSE_MILLIS) {

    init {
        require(pauseMillis > 0) { "Пауза ввода должна быть положительной, а не $pauseMillis" }
    }

    /** Момент, когда истекает пауза ввода; `null` — запись не запланирована. */
    private var dueAt: Long? = null

    /** Снимок текста, который пишется прямо сейчас; `null` — записи нет. */
    private var writing: String? = null

    /** Автосохранение приостановлено после отказа записи — `FR-19`. */
    private var suspended = false

    /** Состояние для показа пользователю; выводится из полей, отдельно не хранится. */
    val status: AutosaveStatus
        get() = when {
            writing != null -> AutosaveStatus.Writing
            suspended -> AutosaveStatus.Suspended
            dueAt != null -> AutosaveStatus.Pending
            else -> AutosaveStatus.Idle
        }

    /**
     * Пользователь изменил текст: пауза ввода начинается заново.
     *
     * Отсчёт сдвигается на каждой правке — за этим и нужен дебаунс: запись
     * уходит одна, по паузе, а не на каждый символ (`TC-15`).
     *
     * Явная правка снимает приостановку из `FR-19`: раз пользователь продолжает
     * работать, попытку записи стоит повторить — но именно одну, а не цикл.
     *
     * @param document состояние *после* правки
     */
    fun textEdited(document: DocumentState, now: Long): AutosaveAction {
        suspended = false
        if (!document.isModified) {
            // Правку отменили, и текст совпал с записанным на диск: писать нечего
            // и переписывать файл незачем (`FR-8`, расширение `UC-2`).
            dueAt = null
            return AutosaveAction.Idle
        }
        val due = now + pauseMillis
        dueAt = due
        return AutosaveAction.WaitUntil(due)
    }

    /**
     * Пауза ввода истекла — вызывается по [AutosaveAction.WaitUntil].
     *
     * Вызов раньше срока не ошибка: пауза могла продлиться правкой уже после
     * того, как вызывающий завёл таймер, и тогда возвращается новый срок.
     */
    fun pauseElapsed(document: DocumentState, now: Long): AutosaveAction {
        val due = dueAt ?: return AutosaveAction.Idle
        if (now < due) return AutosaveAction.WaitUntil(due)
        if (writing != null) {
            // Запись уже идёт. Срок не сбрасываем: он просрочен, и хвост уйдёт
            // сразу после текущей записи — см. [resumeAfterWrite].
            return AutosaveAction.Idle
        }
        return startWrite(document)
    }

    /**
     * Приложение уходит в фон: писать немедленно, не дожидаясь паузы (`FR-16`).
     *
     * Система вправе выгрузить процесс сразу после этого события, поэтому
     * оставшаяся пауза здесь не отсчитывается.
     */
    fun movedToBackground(document: DocumentState, now: Long): AutosaveAction {
        if (writing != null) {
            // Двух записей в один файл одновременно быть не может. Помечаем
            // остаток просроченным, чтобы он ушёл сразу после текущей записи.
            if (document.isModified) dueAt = minOf(dueAt ?: now, now)
            return AutosaveAction.Idle
        }
        return startWrite(document)
    }

    /**
     * Пользователь попросил повторить запись вручную — снимает паузу из `FR-19`.
     *
     * Ручной повтор — второй из двух способов возобновить автосохранение после
     * отказа; первый — явная правка.
     */
    fun retryRequested(document: DocumentState, now: Long): AutosaveAction {
        suspended = false
        if (writing != null) {
            if (document.isModified) dueAt = minOf(dueAt ?: now, now)
            return AutosaveAction.Idle
        }
        return startWrite(document)
    }

    /**
     * Запись удалась.
     *
     * Сохранённым становится *снимок*, который писали, а не текущий текст:
     * запись не блокирует ввод, и пока она шла, пользователь мог печатать
     * дальше. Считать эту правку записанной значит потерять её при следующей
     * выгрузке приложения — ровно то, что запрещает `TC-19`.
     *
     * Поэтому здесь не [DocumentState.saved]: тот берёт текущий текст, и для
     * записи, во время которой ничего не менялось, он верен, а для `TC-19` нет.
     *
     * @param at время успешной записи в миллисекундах эпохи Unix
     */
    fun writeSucceeded(document: DocumentState, at: Long): AutosaveOutcome {
        val written = writing ?: return AutosaveOutcome(document, AutosaveAction.Idle)
        writing = null
        suspended = false
        val saved = document.saved(writtenText = written, at = at)
        return AutosaveOutcome(saved, resumeAfterWrite(saved, at))
    }

    /**
     * Запись не удалась.
     *
     * Текст остаётся в памяти, признак изменений продолжает гореть, отметка
     * времени не сдвигается (`FR-17`), а сам факт отказа виден вызывающему двумя
     * способами: через [DocumentState.lastSaveFailed] и через [status].
     *
     * Дальше автосохранение молчит до явной правки или ручного повтора (`FR-19`).
     * Уход в фон приостановку не снимает намеренно: в спеке перечислены ровно
     * два способа возобновления, а причины отказа — нет прав, файл исчез — сами
     * собой не проходят, и попытка на каждом сворачивании была бы тем же
     * биением о закрытый файл, только реже.
     */
    fun writeFailed(document: DocumentState): AutosaveOutcome {
        if (writing == null) return AutosaveOutcome(document, AutosaveAction.Idle)
        writing = null
        suspended = true
        dueAt = null
        return AutosaveOutcome(document.saveFailed(), AutosaveAction.Idle)
    }

    /** Начать запись, если есть что писать и автосохранение не приостановлено. */
    private fun startWrite(document: DocumentState): AutosaveAction {
        if (!document.isModified) {
            dueAt = null
            return AutosaveAction.Idle
        }
        if (suspended) return AutosaveAction.Idle
        dueAt = null
        writing = document.text
        return AutosaveAction.Write(document.text)
    }

    /**
     * Что делать сразу после удачной записи.
     *
     * Если во время записи текст меняли, срок уже проставлен правкой: наступил —
     * пишем хвост немедленно, не наступил — продолжаем ждать паузу. Второе важно
     * не меньше первого: пользователь может всё ещё печатать, и дописывать файл
     * после каждой завершённой записи значит вернуть запись на каждый символ.
     */
    private fun resumeAfterWrite(document: DocumentState, now: Long): AutosaveAction {
        if (!document.isModified) {
            dueAt = null
            return AutosaveAction.Idle
        }
        val due = dueAt ?: (now + pauseMillis).also { dueAt = it }
        return if (now >= due) startWrite(document) else AutosaveAction.WaitUntil(due)
    }

    companion object {
        /** Пауза ввода по умолчанию. */
        const val DEFAULT_PAUSE_MILLIS: Long = 1_500
    }
}
