package io.github.olegnyr.adocmobile.git

/**
 * Арифметика прогресса клонирования: сырые сигналы монитора транспорта — в
 * срезы [CloneProgress] (`FR-5`).
 *
 * Живёт в общем коде намеренно: платформенной реализации остаётся только
 * переложить колбэки своего монитора (у JGit — `ProgressMonitor`) в
 * [beginPhase]/[advance], и проценты со скоростью считаются одинаково на всех
 * платформах и проверяются в `commonTest` (`NFR-2`, `NFR-10`).
 *
 * Не потокобезопасен — как и остальные модели проекта: монитор транспорта
 * зовёт колбэки последовательно из рабочего потока операции.
 *
 * @param clock источник времени в миллисекундах — параметр ради тестов,
 * по прецеденту `AutosaveRunner`
 */
class CloneProgressTracker(private val clock: () -> Long) {

    private var phase = ""
    private var completed = 0
    private var total: Int? = null
    private var phaseStartedAt = 0L

    /**
     * Началась новая фаза; числа предыдущей не подмешиваются.
     *
     * @param totalWork объём фазы; `0` и меньше — объём неизвестен
     * (JGit передаёт `ProgressMonitor.UNKNOWN = 0`), процент не вычисляется
     */
    fun beginPhase(title: String, totalWork: Int): CloneProgress {
        phase = title
        completed = 0
        total = totalWork.takeIf { it > 0 }
        phaseStartedAt = clock()
        return snapshot()
    }

    /** Фаза продвинулась на [units] единиц работы. */
    fun advance(units: Int): CloneProgress {
        completed += units
        return snapshot()
    }

    private fun snapshot(): CloneProgress {
        val elapsedMs = clock() - phaseStartedAt
        return CloneProgress(
            phase = phase,
            completedObjects = completed,
            totalObjects = total,
            // Умножение в Long и зажим в 0–100: монитор транспорта вправе
            // переоценить объём или перешагнуть его, а контракт CloneProgress
            // обещает процент в диапазоне.
            percent = total?.let { (completed * 100L / it).coerceIn(0L, 100L).toInt() },
            objectsPerSecond = if (elapsedMs > 0) {
                (completed * 1000L / elapsedMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            } else {
                null
            },
        )
    }
}
