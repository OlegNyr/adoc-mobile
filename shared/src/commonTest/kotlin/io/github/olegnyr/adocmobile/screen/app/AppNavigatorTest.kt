package io.github.olegnyr.adocmobile.screen.app

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Навигатор приложения — слайс `SL-1` фичи 009 (`FR-3` каркасом, `FR-4`,
 * `FR-6`, и `FR-5` кроме клаузулы о немедленной записи из редактора: она
 * требует живого `EditorScreenModel` и закрывается врезкой в `SL-2`).
 *
 * Инфраструктуры compose-тестов в проекте нет, поэтому логика переходов живёт
 * в несоставном классе и проверяется без композиции (`NFR-10`, ADR-012) — тем
 * же приёмом, что `EditorScreenModel` и `RepositoryScreenModel`.
 */
class AppNavigatorTest {

    /** SAF-идентификатор настоящей формы: двоеточия и проценты внутри. */
    private val safDocumentId =
        "content://com.android.externalstorage.documents/document/primary%3ADocuments%2Fзаметка.adoc"

    private val secondLevelScreens = listOf(
        AppScreen.Editor(safDocumentId),
        AppScreen.Clone,
        AppScreen.Commit,
        AppScreen.Conflict("docs/руководство.adoc"),
    )

    // Оба receiver-а вносятся в область: `save` — член-расширение `Saver`,
    // и вызвать его через точку от явного получателя Kotlin не даёт.
    private fun savedValueOf(navigator: AppNavigator): Any =
        with(AppNavigator.Saver) { with(SaverScope { true }) { save(navigator) } }
            ?: error("Saver обязан сохранять любое состояние навигатора")

    @Test
    fun TC_2_secondLevelScreensAreReachableFromRoot() {
        val navigator = AppNavigator()
        assertEquals(AppScreen.Root, navigator.current, "приложение открывается корнем (FR-1)")
        assertEquals(listOf(AppScreen.Root), navigator.screens)

        for (screen in secondLevelScreens) {
            navigator.go(screen)
            assertEquals(screen, navigator.current, "переход вперёд ведёт на $screen (FR-3)")
            assertEquals(listOf(AppScreen.Root, screen), navigator.screens, "глубина стека — два экрана")
            assertTrue(navigator.back(), "возврат со второго уровня состоялся")
            assertEquals(AppScreen.Root, navigator.current, "возврат ведёт на корень (FR-4)")
        }
    }

    @Test
    fun TC_2_backReturnsToRootNotToPreviousSecondLevelScreen() {
        val navigator = AppNavigator()
        navigator.go(AppScreen.Commit)
        navigator.go(AppScreen.Conflict("docs/a.adoc"))

        assertEquals(
            listOf(AppScreen.Root, AppScreen.Conflict("docs/a.adoc")),
            navigator.screens,
            "второй экран второго уровня замещает первый, а не громоздится поверх",
        )
        navigator.back()
        assertEquals(
            AppScreen.Root,
            navigator.current,
            "возврат ведёт ровно на корень, а не на экран коммита (FR-4)",
        )
    }

    @Test
    fun TC_2_repeatedSecondLevelNavigationKeepsStackDepthAtTwo() {
        // Инвариант стека при переходе на тот же экран с другим аргументом.
        // Им позже воспользуется очередь конфликтных файлов (`FR-8`), но
        // закрывает `FR-8` слайс `SL-8` — здесь проверяется только то, что
        // возврат остаётся возвратом на корень (`FR-4`).
        val navigator = AppNavigator()
        navigator.go(AppScreen.Conflict("docs/a.adoc"))
        navigator.go(AppScreen.Conflict("docs/b.adoc"))

        assertEquals(AppScreen.Conflict("docs/b.adoc"), navigator.current)
        assertEquals(2, navigator.screens.size, "повторный переход не растит стек")
        navigator.back()
        assertEquals(AppScreen.Root, navigator.current)
    }

    @Test
    fun TC_3_backIsNotInterceptedOnRoot() {
        val navigator = AppNavigator()

        assertFalse(
            navigator.canGoBack,
            "на корне обработчик выключен — «назад» сворачивает приложение платформенным поведением (FR-5)",
        )
        assertFalse(navigator.back(), "навигатор сообщает, что «назад» ему не принадлежит")
        assertEquals(AppScreen.Root, navigator.current, "неудачный возврат состояния не трогает")
    }

    @Test
    fun TC_3_backIsInterceptedOnEverySecondLevelScreen() {
        for (screen in secondLevelScreens) {
            val navigator = AppNavigator()
            navigator.go(screen)
            assertTrue(navigator.canGoBack, "на экране $screen «назад» перехватывается (FR-5)")
            assertTrue(navigator.back())
            assertEquals(AppScreen.Root, navigator.current)
        }
    }

    @Test
    fun TC_4_saverRestoresSameScreenWithArguments() {
        val screens = secondLevelScreens + AppScreen.Conflict("docs/руководство пользователя.adoc")
        for (screen in screens) {
            val navigator = AppNavigator()
            navigator.go(screen)

            val restored = AppNavigator.Saver.restore(savedValueOf(navigator))
                ?: error("Saver обязан восстановить сохранённое состояние")

            assertEquals(screen, restored.current, "восстановлен тот же экран с теми же аргументами (FR-6)")
            assertEquals(navigator.screens, restored.screens, "восстановлен весь стек")
            assertTrue(restored.canGoBack, "восстановленный экран второго уровня по-прежнему ловит «назад»")
        }
    }

    @Test
    fun TC_4_saverRestoresArgumentThatIsAnEmptyString() {
        // Находка ревью SL-1: тег с пустым хвостом («editor:») читался как
        // нечитаемая строка, и весь стек молча уезжал на корень. Кодек не
        // судит о том, бывает ли пустой идентификатор, — он обязан вернуть
        // ровно то, что получил (FR-6).
        for (screen in listOf(AppScreen.Editor(""), AppScreen.Conflict(""))) {
            val navigator = AppNavigator()
            navigator.go(screen)

            val restored = AppNavigator.Saver.restore(savedValueOf(navigator))
            assertEquals(screen, restored?.current, "пустой аргумент переживает сохранение")
        }
    }

    @Test
    fun TC_4_saverNormalisesStackDeeperThanTwoScreens() {
        // Защитный тест инварианта реализации, а не покрытие требования:
        // сохранения глубже двух экранов приложение не пишет, такое может
        // прийти только от чужой или будущей версии. Требования этот случай не
        // описывают, и заявлять по нему покрытие FR/TC нельзя — проверяется
        // ровно то, что инвариант стека переживает неожиданный вход.
        val restored = AppNavigator.Saver.restore(listOf("root", "clone", "commit"))

        assertEquals(AppScreen.Commit, restored?.current, "видимым остаётся верхний экран")
        assertEquals(
            listOf(AppScreen.Root, AppScreen.Commit),
            restored?.screens,
            "промежуточный экран второго уровня не выживает: инвариант важнее буквального восстановления",
        )
    }

    @Test
    fun TC_4_saverRestoresRootAndSurvivesUnreadableValue() {
        val root = AppNavigator()
        val restoredRoot = AppNavigator.Saver.restore(savedValueOf(root))
        assertEquals(AppScreen.Root, restoredRoot?.current, "корень восстанавливается корнем")
        assertEquals(false, restoredRoot?.canGoBack)

        // Значение из чужой или устаревшей версии не должно ронять запуск:
        // худшее, что позволено, — открыть корень (FR-6).
        val garbage = AppNavigator.Saver.restore(listOf("root", "неизвестный-экран:x"))
        assertEquals(AppScreen.Root, garbage?.current, "неизвестный экран уступает корню, а не падает")

        val empty = AppNavigator.Saver.restore(emptyList<Any>())
        assertEquals(AppScreen.Root, empty?.current, "пустое сохранение — тоже корень")
    }
}
