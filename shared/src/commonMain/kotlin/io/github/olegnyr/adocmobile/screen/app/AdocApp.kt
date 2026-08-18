// `BackHandler` мультиплатформенного Compose ещё помечен экспериментальным —
// тем же opt-in, что в `EditorScreen`, где он уже используется.
@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.olegnyr.adocmobile.screen.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.olegnyr.adocmobile.document.DocumentSource
import io.github.olegnyr.adocmobile.document.DocumentTreeAccess
import io.github.olegnyr.adocmobile.document.TreeSource
import io.github.olegnyr.adocmobile.preview.PreviewImageSource
import io.github.olegnyr.adocmobile.screen.EditorScreen
import io.github.olegnyr.adocmobile.theme.AdocTheme
import io.github.olegnyr.adocmobile.theme.AdocTypography
import io.github.olegnyr.adocmobile.theme.adocTextStyle
import kotlinx.coroutines.launch

/**
 * Приложение целиком: навигация, корневой список и экран документа.
 *
 * Живёт в `commonMain`, а не в платформенном хостинге, и это не вкус, а
 * причина трижды возвращавшегося дефекта «экран без выхода». Пока хостинг был
 * платформенным, перехват «назад» приходилось ставить либо чужим API (порядок
 * между двумя реализациями ничем не гарантирован), либо внутри экрана
 * документа — а тот не смонтирован, пока документ не опознан. Признак
 * [AppNavigator.canGoBack] при этом существовал, был покрыт тестами и не
 * читался продуктовым кодом ни разу: проверялось одно понятие «назад»,
 * работало другое.
 *
 * Здесь перехват — свойство *стека*: есть куда возвращаться, значит «назад»
 * принадлежит навигатору, на любом экране второго уровня, включая тот, что
 * ещё ничего не нарисовал. Экран, которому перед уходом надо доделать работу
 * (редактор с несохранёнными правками), ставит свой обработчик внутри себя, и
 * вложенный выигрывает — оба одного API, поэтому порядок определён.
 *
 * Git-экранов тут нет: их врезка — слайс `SL-4`. Гейт `FR-17` выражен тем, что
 * реализации Git-шва хостинг не строит вовсе, и приложение без неё остаётся
 * полезным редактором документов из папки.
 *
 * @param access источник файлов; сегодня один — папка SAF, второй (клон) и
 * переключение между ними приходят слайсом `SL-3`
 * @param requestFolder платформенный диалог выбора папки
 * @param foreground приложение на переднем плане — сигнал платформы
 * @param imageSource байты картинок рядом с документом для превью
 * @param shareDocument системная отправка документа (`FR-20` фичи 005)
 * @param sourceTakenOutOfBand папка, право на которую платформа взяла без
 * ждущего запроса: активность пересоздали, пока был открыт системный диалог.
 * `null` — такого не случилось
 * @param onSourceTakenConsumed сигнал платформе, что [sourceTakenOutOfBand]
 * учтён и его можно погасить
 */
@Composable
fun AdocApp(
    access: DocumentTreeAccess,
    requestFolder: suspend () -> TreeSource?,
    foreground: Boolean = true,
    imageSource: PreviewImageSource? = null,
    shareDocument: (DocumentSource) -> Unit = {},
    sourceTakenOutOfBand: TreeSource? = null,
    onSourceTakenConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    // Стек переживает поворот и выгрузку процесса (`FR-6`).
    val navigator = rememberSaveable(saver = AppNavigator.Saver) { AppNavigator() }
    val rootList = remember(access) { RootListModel(access = access, scope = scope) }

    // Единственный перехват уровня приложения (`FR-5`). Условие — состояние
    // стека, а не состояние какого-либо экрана: именно поэтому окна без
    // выхода не остаётся ни на одном шаге, включая чтение файла.
    BackHandler(enabled = navigator.canGoBack) { navigator.back() }

    // Смена источника проходит одной дверью, откуда бы её ни попросили —
    // с корня или из меню открытого документа. Иначе плашка отказа, которую
    // обязана гасить смена источника, переживала бы смену папки из документа
    // и висела над списком новой папки (находка ревью `SL-2`).
    val chooseSource: suspend () -> TreeSource? = {
        val chosen = requestFolder()
        if (chosen != null) rootList.sourceChosen()
        chosen
    }

    // Папка, выбранная в диалоге, который пережил пересоздание активности:
    // ждущей корутины уже нет, но право взято, и список обязан это увидеть.
    LaunchedEffect(sourceTakenOutOfBand) {
        if (sourceTakenOutOfBand != null) {
            rootList.sourceChosen()
            onSourceTakenConsumed()
        }
    }

    AdocTheme {
        when (val screen = navigator.current) {
            AppScreen.Root -> RootListScreen(
                model = rootList,
                onOpenDocument = { source -> navigator.go(AppScreen.Editor(source.id)) },
                onChooseSource = { scope.launch { chooseSource() } },
            )

            is AppScreen.Editor -> EditorDestination(
                sourceId = screen.sourceId,
                rootList = rootList,
                access = access,
                requestFolder = chooseSource,
                foreground = foreground,
                imageSource = imageSource,
                shareDocument = shareDocument,
                onLeave = navigator::back,
            )

            // Экраны клонирования, коммита и слияния приходят с `SL-4`: пока
            // Git-шов не врезан, попасть сюда неоткуда.
            AppScreen.Clone, AppScreen.Commit, is AppScreen.Conflict ->
                LaunchedEffect(screen) { navigator.back() }
        }
    }
}

/**
 * Экран документа: опознание источника по идентификатору из стека и сам
 * редактор.
 *
 * Пока источник не опознан, рисуется не пустота, а шапка с пояснением
 * (`FR-6`): чтение через SAF занимает заметное время, а при зависшем
 * поставщике не кончается вовсе, и голый экран читается как сбой. «Назад» в
 * это время работает — его держит перехват уровня приложения, а не этот
 * экран.
 */
@Composable
private fun EditorDestination(
    sourceId: String,
    rootList: RootListModel,
    access: DocumentTreeAccess,
    requestFolder: suspend () -> TreeSource?,
    foreground: Boolean,
    imageSource: PreviewImageSource?,
    shareDocument: (DocumentSource) -> Unit,
    onLeave: () -> Unit,
) {
    // Восстановление после выгрузки процесса приходит сразу сюда, минуя
    // корень, и опознать документ без прочитанного источника нечем. На пути
    // «корень → документ» источник уже прочитан, и повторного обращения не
    // будет — читается только непрочитанное.
    LaunchedEffect(rootList) {
        if (rootList.state is RootListState.Loading) rootList.start()
    }

    val listed = rootList.state as? RootListState.Listed
    val source = remember(sourceId, listed) {
        editorSourceFor(sourceId, listed, access.heldSource())
    }

    if (source == null) {
        // Решение «уходить или ждать» принимает `commonMain` и оно проверено
        // без экрана (`NFR-10`).
        LaunchedEffect(rootList.state) {
            if (shouldLeaveEditorForRoot(source = null, state = rootList.state)) onLeave()
        }
        OpeningDocument()
        return
    }

    EditorScreen(
        access = access,
        requestFolder = requestFolder,
        foreground = foreground,
        imageSource = imageSource,
        shareDocument = shareDocument,
        openSource = source,
        onDocumentClosed = { notice ->
            notice?.let(rootList::documentOpenFailed)
            onLeave()
        },
    )
}

/** Документ ещё открывается: шапка вместо пустоты. */
@Composable
private fun OpeningDocument() {
    Surface(color = AdocTheme.colors.ground, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AdocTheme.colors.chrome)
                    .statusBarsPadding()
                    .height(52.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "ОТКРЫТИЕ ДОКУМЕНТА…",
                    style = adocTextStyle(AdocTypography.sectionLabel),
                    color = AdocTheme.colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
