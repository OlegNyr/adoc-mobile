package io.github.olegnyr.adocmobile.screen.app

import io.github.olegnyr.adocmobile.document.DocumentAccessError
import io.github.olegnyr.adocmobile.document.DocumentEditor
import io.github.olegnyr.adocmobile.document.DocumentOpenResult
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.DocumentWriteError
import io.github.olegnyr.adocmobile.document.DocumentWriteResult
import io.github.olegnyr.adocmobile.document.TreeListResult
import io.github.olegnyr.adocmobile.document.TreeSource
import io.github.olegnyr.adocmobile.document.openDocument
import io.github.olegnyr.adocmobile.render.AdocRenderer
import io.github.olegnyr.adocmobile.render.DiagramOptions
import io.github.olegnyr.adocmobile.screen.EditorDocument
import io.github.olegnyr.adocmobile.screen.EditorScreenModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Контракт экрана редактора перед хостингом навигации — слайс `SL-2` фичи 009
 * (`FR-4`, `FR-5`; клаузула `TC-3` про «назад» из документа).
 *
 * Отдельный файл в пространстве фичи 009, а не тесты внутри
 * `EditorScreenModelTest`: там идентификаторы принадлежат фиче 005, и `TC-3`,
 * `TC-9`, `TC-23` в её нумерации заняты совсем другими кейсами. Тест с чужим
 * номером в чужом файле — ровно та болезнь, которую лечило переименование
 * `TC-24`/`TC-25` в спеке 005 (находка ревью `SL-2`).
 *
 * По той же причине здесь больше нет отказа открытия: его несёт `TC-6` фичи
 * 005 — кейс того требования (`FR-9` там), и держать его вторым экземпляром
 * под номером `TC-23` значило бы записать одну проверку в два покрытия
 * (находка ревью `SL-2b`). Корневая половина `TC-23` — что текст доживает до
 * человека — проверяется в `RootListModelTest`.
 *
 * Подделки свои и намеренно тощие: проверяется одно — что модель сообщает
 * хостингу о выходе из документа, и делает это одним путём.
 */
class EditorHostContractTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val access = FakeAccess()
    private val document = DocumentSource(id = "content://doc/1", displayName = "заметка.adoc")

    private fun model(onDocumentClosed: (String?) -> Unit = {}): EditorScreenModel =
        EditorScreenModel(
            editor = DocumentEditor(),
            access = access,
            renderer = FakeRenderer(),
            scope = scope,
            clock = { 0L },
            page = { fragment -> fragment },
            // Пауза автосохранения не истекает никогда: проверяется выход из
            // документа, а он пишет немедленно (`movedToBackground`). Пустая
            // реализация вместо ожидания загоняла исполнителя в холостой цикл
            // «срок истёк — писать нечего — ждать снова».
            delayUntil = { CompletableDeferred<Unit>().await() },
            onDocumentClosed = onDocumentClosed,
        )

    @Test
    fun TC_3_backReportsClosureOnlyAfterTheTextIsOnDisk() {
        access.contents[document.id] = "= Заголовок"
        // Снимок берётся *внутри* колбэка: только так проверяется порядок, а
        // не факт, что к концу теста случилось и то и другое (замечание ревью).
        var diskAtSignal: String? = null
        val model = model(onDocumentClosed = { diskAtSignal = access.written.lastOrNull()?.second })
        model.open(document)
        model.textEdited("= Заголовок\n\nПравка.")

        model.closeRequested()

        assertEquals(
            "= Заголовок\n\nПравка.",
            diskAtSignal,
            "к моменту сигнала правка уже на диске: иначе корень покажет устаревший файл (FR-21 фичи 005)",
        )
    }

    @Test
    fun TC_3_backIsNotReportedWhileTheWriteFails() {
        access.contents[document.id] = "= Заголовок"
        access.writeError = DocumentWriteError.WriteFailed
        val closed = mutableListOf<String?>()
        val model = model(onDocumentClosed = { notice -> closed += notice })
        model.open(document)
        model.textEdited("= Заголовок\n\nПравка.")

        model.closeRequested()

        assertTrue(closed.isEmpty(), "закрыть втихую с потерей правок нельзя — хостинг сигнала не получает")
        assertIs<EditorDocument.Open>(model.document, "документ остаётся на экране")
    }

    @Test
    fun TC_3_changingFolderFromTheDocumentAlsoReportsClosure() {
        // Единственная ветка, которая прежде молчала: навигатор продолжал
        // считать, что документ открыт, тело экрана при хостинге не
        // рисовалось, перехват «назад» был выключен — пользователь оставался
        // на пустом экране без выхода (находка ревью `SL-2`).
        access.contents[document.id] = "= Заголовок"
        val closed = mutableListOf<String?>()
        val model = model(onDocumentClosed = { notice -> closed += notice })
        model.open(document)
        assertIs<EditorDocument.Open>(model.document, "документ открыт до смены папки")

        model.folderChosen()

        assertEquals(listOf<String?>(null), closed, "смена папки — тоже выход из документа, и он сообщается")
    }

    private class FakeRenderer : AdocRenderer {
        override suspend fun render(source: String, diagrams: DiagramOptions): String = source
    }

    /**
     * Тощая подделка шва: экран документа зовёт из него [open] и [write], и
     * больше ничего. Остальное падает, а не возвращает значение, — иначе в
     * тестах заводится подготовка, которую никто не читает.
     */
    private class FakeAccess : DocumentTreeAccess {
        var writeError: DocumentWriteError? = null
        val contents = mutableMapOf<String, String>()
        val written = mutableListOf<Pair<String, String>>()

        override suspend fun open(source: DocumentSource): DocumentOpenResult {
            val text = contents[source.id]
                ?: return DocumentOpenResult.Failed(source, DocumentAccessError.NotFound)
            return openDocument(source, text.encodeToByteArray())
        }

        override suspend fun write(source: DocumentSource, fileText: String): DocumentWriteResult {
            writeError?.let { return DocumentWriteResult.Failed(source, it) }
            written += source.id to fileText
            return DocumentWriteResult.Written
        }

        override fun heldSource(): DocumentSource? =
            fail("подъём удержанного источника — дело корня (FR-8 фичи 005 с оговоркой 009)")

        override fun heldTree(): TreeSource? =
            fail("дерево источника экрану документа не нужно (FR-14)")

        override suspend fun listDocuments(): TreeListResult =
            fail("список файлов — корневой экран, а не редактор (FR-14)")

        override fun release() =
            fail("право на дерево этот экран не отдаёт")
    }
}
