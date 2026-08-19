package io.github.olegnyr.adocmobile.screen.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess

/**
 * Источник файлов, стоящий за корневым списком (`FR-10`).
 *
 * Их ровно два и активен ровно один; оба реализуют [DocumentTreeAccess],
 * поэтому ни список, ни редактор не различают, откуда пришёл файл
 * (`ADR-013`).
 */
enum class FileSourceKind(val title: String) {

    /** Папка пользователя через SAF — `AndroidDocumentTreeAccess`. */
    Folder(title = "ПАПКА"),

    /** Рабочая копия склонированного репозитория — `RepoDocumentAccess`. */
    Clone(title = "РЕПОЗИТОРИЙ"),
}

/**
 * Хранение признака активного источника между запусками (`FR-11`).
 *
 * Интерфейс, а не `expect`/`actual`-шов: новых швов фича не заводит (`NFR-2`),
 * реализация платформенная — по образцу удержанных ссылок
 * `AndroidDocumentTreeAccess` на `SharedPreferences`.
 *
 * Хранится *признак*, а не сам источник: ссылку на папку держит SAF-половина,
 * рабочую копию — Git-слой, и дублировать их здесь значило бы завести второе
 * место правды.
 */
interface ActiveSourceStore {

    /** Сохранённый выбор или `null` — выбора не было ни разу (первый запуск). */
    fun loadActiveSource(): FileSourceKind?

    /** Запомнить выбор пользователя. */
    fun saveActiveSource(kind: FileSourceKind)
}

/**
 * Действие пользователя над источниками — пункт меню корня (`FR-12`) и кнопка
 * пустого состояния (`FR-2`).
 *
 * Подписи живут здесь, а не в композиции, ровно по той же причине, что и
 * правила видимости: их проверяет `commonTest`, а экран не сочиняет текстов.
 *
 * Подпись одна, а не две: у кнопки она отличается от пункта меню только
 * отсутствием многоточия, и хранить второй экземпляр текста значило бы завести
 * данные, до которых код не дотягивается (находка ревью `SL-3`: у переключений
 * «кнопочная» подпись не показывалась никогда).
 *
 * @property menuLabel пункт меню; многоточие — у действий, ведущих в системный
 * диалог или на другой экран, как в меню документа
 */
enum class SourceAction(val menuLabel: String) {
    OpenFolder(menuLabel = "ОТКРЫТЬ ПАПКУ…"),
    Clone(menuLabel = "КЛОНИРОВАТЬ…"),
    SwitchToFolder(menuLabel = "ПЕРЕЙТИ К ПАПКЕ"),
    SwitchToClone(menuLabel = "ПЕРЕЙТИ К РЕПОЗИТОРИЮ");

    /** Подпись кнопки: то же действие там, где идти уже некуда. */
    val buttonLabel: String get() = menuLabel.removeSuffix("…")
}

/**
 * Что хостинг делает по выбранному действию — правило перехода, а не разводка
 * композиции (`FR-2`, `FR-12`, `NFR-10`).
 *
 * Вынесено из `@Composable` намеренно: границы фичи запрещают держать логику
 * переходов в композиции, и ровно про такую ветку («переключение источника
 * никуда не ведёт, потому что о нём забыли») ни один тест композиции сказать
 * не может — их инфраструктуры в проекте нет.
 *
 * Смена активного источника здесь имеет *два* следствия сразу: выбор
 * запоминается (`FR-11`) и список перечитывает уже новый источник (`FR-7`).
 * Разводить их по местам вызова значит однажды забыть одно из двух.
 *
 * @param openFolder показать системный диалог выбора папки
 * @param goCloning увести на экран клонирования (`FR-3`, врезка `SL-5`)
 */
fun applySourceAction(
    action: SourceAction,
    sources: ActiveSource,
    rootList: RootListModel,
    openFolder: () -> Unit,
    goCloning: () -> Unit,
) {
    when (action) {
        SourceAction.OpenFolder -> openFolder()
        SourceAction.Clone -> goCloning()
        SourceAction.SwitchToFolder -> switchSource(sources, rootList, FileSourceKind.Folder)
        SourceAction.SwitchToClone -> switchSource(sources, rootList, FileSourceKind.Clone)
    }
}

/**
 * Источник стал активным: запомнить выбор (`FR-11`) и перечитать список
 * (`FR-7`).
 *
 * Зовётся и [applySourceAction], и хостингом после системного диалога папки:
 * дверь одна, поводов несколько.
 */
fun switchSource(sources: ActiveSource, rootList: RootListModel, kind: FileSourceKind) {
    if (sources.switchTo(kind)) rootList.sourceChosen()
}

/**
 * Пояснение пустого состояния под тот состав действий, который в нём показан
 * (`FR-2`).
 *
 * Текст выводится из действий, а не пишется рядом с ними: сборка без Git-слоя
 * показывает одну кнопку, и приглашение «склонируйте репозиторий» под ней было
 * бы обещанием того, чего в приложении нет (`FR-17`).
 */
fun noSourceMessage(actions: List<SourceAction>): String =
    if (SourceAction.Clone in actions) {
        "Выберите папку с документами или склонируйте репозиторий — " +
            "приложение покажет его файлы и запомнит выбор."
    } else {
        "Выберите папку с документами — приложение покажет её файлы и запомнит выбор."
    }

/**
 * Какой источник активен и что предлагается пользователю (`FR-10`, `FR-11`,
 * `FR-12`, `FR-2`).
 *
 * Несоставной holder с наблюдаемым состоянием — образец `RootListModel`:
 * правило выбора проверяется без композиции (`NFR-10`).
 *
 * Отсутствие реализации Git выражено типом, а не флагом: [clone] — `null`, и
 * из этого одного факта следуют и пустое состояние без `КЛОНИРОВАТЬ`, и меню
 * без переключения, и невозможность поднять сохранённый выбор `Clone`
 * (`FR-17`). Так гейт нельзя забыть в одном из трёх мест.
 *
 * @param folder шов папки SAF: он есть всегда — выбрать папку можно на любой
 * платформе
 * @param clone шов рабочей копии клона либо `null` на сборке без Git-слоя
 */
class ActiveSource(
    private val store: ActiveSourceStore,
    private val folder: DocumentTreeAccess,
    private val clone: DocumentTreeAccess? = null,
) {

    /**
     * Активный источник; `null` — источника нет: выбора не было и подставить
     * нечего, либо сохранённому выбору нет реализации.
     *
     * Значение поднимается один раз, при создании: хранилище и удержанные
     * ссылки — платформенный ввод-вывод, и спрашивать их на каждую перерисовку
     * незачем.
     */
    /**
     * Источники, у которых уже что-то есть, на момент создания.
     *
     * Спрашивается один раз: это платформенный ввод-вывод, а нужен ответ
     * только веткам «записанного выбора нет», которые после первой же смены
     * источника недостижимы.
     */
    private val heldAtStart: Set<FileSourceKind> =
        FileSourceKind.entries.filterTo(mutableSetOf()) { accessOf(it)?.heldTree() != null }

    var kind: FileSourceKind? by mutableStateOf(store.loadActiveSource()?.takeIf(::implemented) ?: soleHeld())
        private set

    /**
     * Единственный источник, у которого уже что-то есть, — когда записанного
     * выбора нет.
     *
     * `FR-2` и `TC-6` условие пустого состояния ставят не на хранилище, а на
     * источники: «нет ни клона, ни удержанной папки». Приложение, которому
     * папку уже отдали, обязано показать её файлы, а не просить выбрать папку
     * заново — иначе обновление версии выглядит потерей источника.
     * Молчаливого выбора при этом не возникает: когда есть оба, не выбирается
     * ни один (`TC-10`), и решает пользователь — пунктами перехода, которые
     * [menuActions] в этом случае и предлагает.
     *
     * Результат сознательно не сохраняется: это прочтение обстановки, а не
     * решение человека, и записать его значило бы выдать одно за другое.
     */
    private fun soleHeld(): FileSourceKind? = heldAtStart.singleOrNull()

    /** Шов активного источника; `null` — источника нет, читать нечего. */
    val access: DocumentTreeAccess?
        get() = kind?.let(::accessOf)

    private fun accessOf(kind: FileSourceKind): DocumentTreeAccess? = when (kind) {
        FileSourceKind.Folder -> folder
        FileSourceKind.Clone -> clone
    }

    /**
     * Действия пустого состояния первого запуска (`FR-2`): завести источник.
     *
     * Переключения здесь нет намеренно — переключаться не с чего, и кнопка
     * «перейти к папке», ведущая в то же пустое состояние, была бы ложным
     * обещанием.
     */
    val startActions: List<SourceAction>
        get() = buildList {
            add(SourceAction.OpenFolder)
            if (clone != null) add(SourceAction.Clone)
        }

    /**
     * Пункты меню корневого экрана (`FR-12`): те же два действия, что в пустом
     * состоянии, плюс переход на *другой* источник, когда он есть.
     *
     * Переход предлагается по наличию реализации, а не по наличию готового
     * репозитория: клона может не быть на диске, и тогда корень покажет своё
     * состояние с объяснением (`FR-15`) — это честнее, чем спрятать пункт и
     * оставить пользователя гадать, куда делся репозиторий.
     */
    val menuActions: List<SourceAction>
        get() = buildList {
            addAll(startActions)
            when (kind) {
                FileSourceKind.Folder -> if (clone != null) add(SourceAction.SwitchToClone)
                FileSourceKind.Clone -> add(SourceAction.SwitchToFolder)
                // Активного источника нет, а удержаны оба: выбрать за
                // пользователя нельзя (`TC-10`), но и оставить его без выбора
                // тоже — иначе единственным путём к уже существующему
                // источнику была бы попытка завести его заново (находка ревью
                // `SL-3`). Предлагается переход к каждому, у кого что-то есть.
                null -> heldAtStart.forEach { held ->
                    when (held) {
                        FileSourceKind.Folder -> add(SourceAction.SwitchToFolder)
                        FileSourceKind.Clone -> if (clone != null) add(SourceAction.SwitchToClone)
                    }
                }
            }
        }

    /**
     * Сделать источник активным: выбрана папка, готов клон, нажат пункт меню.
     *
     * Выбор сохраняется сразу же (`FR-11`): выгрузка процесса не спрашивает
     * разрешения, и «сохраню на выходе» означало бы «не сохраню».
     * Источник без реализации не выбирается и в хранилище не уезжает — иначе
     * сборка без Git записала бы себе состояние, из которого сама же не смогла
     * бы выйти.
     *
     * @return принят ли выбор. `false` — источнику нет реализации; повторный
     * выбор уже активного источника принимается (`true`), и это не описка:
     * так «Открыть папку…» над той же папкой заказывает честную перечитку.
     * Отказ возвращается значением, а не молчит: у смены есть второе
     * следствие — перечитка списка, — и исполнять его при непринятом выборе
     * значит гасить плашку отказа и читать заново то же самое (находка ревью
     * `SL-3`).
     */
    fun switchTo(kind: FileSourceKind): Boolean {
        if (!implemented(kind)) return false
        this.kind = kind
        store.saveActiveSource(kind)
        return true
    }

    private fun implemented(kind: FileSourceKind): Boolean = when (kind) {
        FileSourceKind.Folder -> true
        FileSourceKind.Clone -> clone != null
    }
}
