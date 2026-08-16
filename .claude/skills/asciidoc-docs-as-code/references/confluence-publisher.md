# Публикация в Confluence

Сценарий корпоративного контура: исходники в git, ревью в merge request,
читатели — в Confluence, потому что там уже все и туда ходит бизнес.

Инструмент — **Confluence Publisher** (`asciidoc-confluence-publisher-maven-plugin`).
Работает с Server/Data Center и Cloud через REST API.

## Правило номер один

**Опубликованная страница — артефакт сборки, а не рабочий документ.** Любая
правка в UI Confluence будет затёрта следующей публикацией. Это надо
проговорить команде до, а не после первого инцидента, и вынести баннером на
каждую страницу:

```asciidoc
:page-notice: Страница генерируется из git. Правки в Confluence будут потеряны — присылайте MR в `docs/`.

[NOTE]
====
{page-notice} Исходник: {docs-repo-url}/{docfile}
====
```

Атрибут `{docfile}` подставляет путь к текущему файлу — читатель сразу видит,
что править.

## Структура = дерево страниц

Публикатор превращает дерево файлов в дерево страниц:

```
docs/
├── index.adoc              → страница «Платформа контакт-центра»
└── index/                  → её дочерние страницы
    ├── architecture.adoc
    ├── how-to.adoc
    └── how-to/
        ├── kafka-retry.adoc
        └── tracing.adoc
```

Заголовок страницы берётся из `= Заголовок` документа, **не из имени файла**.
Два документа с одинаковым заголовком в одном пространстве — конфликт: в
Confluence заголовок уникален в пределах space.

## Конфигурация Maven

```xml
<plugin>
  <groupId>org.sahli.asciidoc.confluence.publisher</groupId>
  <artifactId>asciidoc-confluence-publisher-maven-plugin</artifactId>
  <version>0.24.0</version>
  <configuration>
    <asciidocRootFolder>${project.basedir}/docs</asciidocRootFolder>
    <sourceEncoding>UTF-8</sourceEncoding>
    <rootConfluenceUrl>https://confluence.corp.local</rootConfluenceUrl>
    <spaceKey>CCP</spaceKey>
    <ancestorId>${confluence.ancestorId}</ancestorId>
    <publishingStrategy>APPEND_TO_ANCESTOR</publishingStrategy>
    <orphanRemovalStrategy>REMOVE_ORPHANS</orphanRemovalStrategy>
    <versionMessage>Published from ${git.commit.id.abbrev}</versionMessage>
    <notifyWatchers>false</notifyWatchers>
    <maxRequestsPerSecond>10</maxRequestsPerSecond>
    <attributes>
      <docs-repo-url>https://git.corp.local/cc/platform/-/blob/main/docs</docs-repo-url>
      <project-version>${project.version}</project-version>
    </attributes>
  </configuration>
</plugin>
```

Разбор неочевидных параметров:

- `orphanRemovalStrategy: REMOVE_ORPHANS` — удалённый в git файл удаляется и
  в Confluence. Без этого пространство зарастает страницами-призраками. Но
  включать его можно, **только** если под `ancestorId` нет ничего, кроме
  сгенерированного.
- `notifyWatchers: false` — иначе каждая публикация рассылает уведомления
  всем подписчикам пространства. Первый же прогон CI породит сотню писем.
- `maxRequestsPerSecond` — Data Center за прокси часто режет всплески;
  10 — безопасный дефолт.
- `versionMessage` с хешем коммита — единственный способ потом понять, из
  какого состояния репозитория собрана страница.

Аутентификация — **только** через переменные окружения или Maven settings,
никогда в pom:

```bash
mvn org.sahli.asciidoc.confluence.publisher:asciidoc-confluence-publisher-maven-plugin:publish \
  -Dasciidoc-confluence-publisher.personalAccessToken="$CONFLUENCE_PAT"
```

Для Data Center предпочтителен Personal Access Token, а не пара
логин/пароль: токен отзывается точечно и не ломает учётку при ротации.

## Прогон вхолостую

Перед первым запуском на живое пространство:

```bash
mvn …:publish -Dasciidoc-confluence-publisher.convertOnly=true
```

Конвертация без записи — покажет, что получится в storage format, и поймает
несовместимости заранее.

## Что поддерживается, а что нет

Работает и мапится в нативные макросы Confluence:

- заголовки, списки, таблицы, ссылки, якоря;
- адмонишены `NOTE/TIP/IMPORTANT/WARNING/CAUTION` → info/tip/note/warning;
- блоки кода → макрос Code Block с подсветкой;
- `:toc:` → макрос Table of Contents;
- изображения и вложения — заливаются как attachments;
- `include::` и атрибуты — раскрываются на этапе конвертации;
- PlantUML через `asciidoctorj-diagram` — рендерится в PNG/SVG и прикрепляется.

Не работает — и это надо учитывать при выборе режима:

- **кастомный CSS и темы** — Confluence рендерит своим стилем, `docinfo` и
  `signature.css` игнорируются;
- **табы** `@asciidoctor/tabs` — нет эквивалента без Marketplace-плагина;
- **`[.role]`** — роли не превращаются в оформление;
- **xref между компонентами** в стиле Antora — только обычные ссылки.

Практический вывод: если задача — «красивый сайт», Confluence не тот выход.
Если задача — «дока там, где её читают», делай **обе публикации из одних
исходников**: Antora/HTML для инженеров, Confluence для остальных, а
Confluence-специфику прячь в `ifdef::confluence[]`.

## Двойная публикация

```asciidoc
ifdef::confluence[]
NOTE: Актуальная версия с диаграммами и поиском — на {portal-url}.
endif::[]

ifndef::confluence[]
[.cards]
* xref:tutorial/quickstart.adoc[Быстрый старт]
  Локальный стенд за 15 минут.
endif::[]
```

Атрибут `confluence` задаётся только в конфигурации публикатора — так один
исходник даёт два корректных выхода.

## Обратное направление: миграция существующей Confluence

Если в Confluence уже накоплены сотни страниц, писать их заново с нуля
бессмысленно. Конвертация storage format → AsciiDoc делается инструментом
**adoct** (https://github.com/OlegNyr/adoct) — Java-утилита с Maven-плагином
и плагином для IntelliJ IDEA, конвертирует в обе стороны.

Порядок миграции, который не заканчивается брошенной веткой:

1. Выгрузить одно поддерево (не всё пространство сразу).
2. Сконвертировать, прогнать сборку — она покажет битые ссылки и таблицы.
3. Руками привести к Diátaxis: экспортированные страницы почти всегда
   смешивают туториал, справочник и объяснение в одном документе.
4. Заменить скопированные примеры кода на `include::` с тегами.
5. Опубликовать обратно с `convertOnly=true`, сравнить, потом уже вживую.
6. Закрыть исходные страницы на редактирование и поставить баннер со ссылкой
   на git.

Шаг 6 обязателен. Без него в пространстве остаются две версии правды, и через
месяц никто не знает, какая живая.

## Data Center: что проверить до внедрения

- Разрешён ли исходящий трафик со сборочного агента до Confluence и по каким
  портам; часто CI стоит в другом сегменте.
- Есть ли ограничение на размер вложений — большие SVG-диаграммы упираются.
- Установлены ли Marketplace-плагины, на которые ты рассчитываешь; в
  Data Center набор плагинов отличается от Cloud, и часть макросов
  недоступна.
- Права сервисной учётки: нужны создание/правка/удаление страниц и
  прикрепление файлов под конкретным `ancestorId`, не глобальный админ.
