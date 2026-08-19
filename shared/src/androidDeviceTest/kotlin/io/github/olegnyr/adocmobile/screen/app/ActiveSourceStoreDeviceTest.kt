package io.github.olegnyr.adocmobile.screen.app

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Признак активного источника в настоящем `SharedPreferences` — платформенная
 * половина `FR-11` фичи 009, слайс `SL-3` (`TC-10`).
 *
 * Device, потому что проверяется платформенное хранилище: правило «пустое
 * хранилище — источника нет, сохранённый выбор поднимается» живёт в
 * `commonTest` (`ActiveSourceTest`) и устройства не требует.
 *
 * Что этот тест доказывает, а что нет — сказано прямо, потому что соблазн
 * прочесть его шире велик. Доказывает: обёртка пишет и читает один и тот же
 * ключ настоящего `SharedPreferences`, и незнакомое значение не роняет старт.
 * *Не* доказывает выживание после выгрузки процесса: `getSharedPreferences`
 * отдаёт закэшированный в процессе объект, и второй экземпляр обёртки видит ту
 * же карту в памяти. Настоящее выживание проверяется ручным кейсом на
 * устройстве — перезапуском приложения (находка ревью `SL-3`).
 *
 * Файл настроек тот же, что у продукта; коллизии с данными приложения нет
 * только потому, что device-тесты модуля `shared` самоинструментируются в
 * пакете `…shared.test`. Тест всё равно чистит за собой.
 */
class ActiveSourceStoreDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @AfterTest
    fun cleanUp() {
        context.getSharedPreferences("adoc.active-source", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(TREE_ACCESS_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun TC_10_savedSourceIsReadBackByANewInstanceOfTheStore() {
        AndroidActiveSourceStore(context).saveActiveSource(FileSourceKind.Clone)

        assertEquals(
            FileSourceKind.Clone,
            AndroidActiveSourceStore(context).loadActiveSource(),
            "новый экземпляр обёртки читает тот же ключ, в который писал прежний (FR-11)",
        )
    }

    /**
     * Решение «свой файл настроек» держится на том, что признак не лежит в
     * файле tree-доступа: тот чистится целиком (`clear()`) при взятии нового
     * дерева — то есть ровно в момент, когда пользователь выбирает папку.
     *
     * Правка, от которой тест обязан покраснеть: сделать
     * `AndroidActiveSourceStore.PREFERENCES` равным `"document-tree-access"`.
     */
    @Test
    fun TC_10_theFlagOutlivesTheWipeOfTheTreeAccessPreferences() {
        AndroidActiveSourceStore(context).saveActiveSource(FileSourceKind.Folder)

        // То же, что делает `takePersistableTreeAccess` при взятии дерева.
        context.getSharedPreferences(TREE_ACCESS_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        assertEquals(
            FileSourceKind.Folder,
            AndroidActiveSourceStore(context).loadActiveSource(),
            "выбор источника переживает взятие новой папки, иначе он терялся бы в момент выбора (FR-11)",
        )
    }

    @Test
    fun TC_10_unknownStoredNameReadsAsNoSourceInsteadOfCrashing() {
        context.getSharedPreferences("adoc.active-source", Context.MODE_PRIVATE)
            .edit()
            .putString("active-source", "Ftp")
            .commit()

        assertNull(
            AndroidActiveSourceStore(context).loadActiveSource(),
            "имя из будущей версии читается как «выбора не было», а не роняет старт",
        )
    }

    private companion object {
        /** Имя файла настроек tree-доступа — то же, что в `AndroidDocumentTreeAccess`. */
        const val TREE_ACCESS_PREFERENCES = "document-tree-access"
    }
}
