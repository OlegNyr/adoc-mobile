# Antora: портал документации

Antora собирает сайт из **нескольких git-репозиториев и веток одновременно** —
именно поэтому на ней сидят docs.asciidoctor.org, Camel, Neo4j, Vert.x,
Quarkus-родственники. Версии доки = ветки/теги репозиториев, и это её главное
преимущество перед голым Asciidoctor.

## Две сущности, которые все путают

| Файл | Где лежит | Отвечает за |
| --- | --- | --- |
| `antora-playbook.yml` | репозиторий сайта (может быть отдельным) | какие репозитории и ветки собирать, UI, куда публиковать |
| `antora.yml` | внутри каждого компонента, рядом с `modules/` | имя компонента, версию, nav, атрибуты компонента |

Playbook — один на сайт. `antora.yml` — по одному на компонент-версию.

## Минимальный playbook

```yaml
site:
  title: Contact Center Platform
  url: https://example.github.io/cc-docs
  start_page: platform::index.adoc
  robots: allow

content:
  sources:
    - url: https://github.com/org/cc-platform.git
      branches: [main, 'v*']
      start_path: docs
    - url: .                       # локальный репозиторий, для preview
      branches: HEAD
      start_path: docs

ui:
  bundle:
    url: https://gitlab.com/antora/antora-ui-default/-/jobs/artifacts/HEAD/raw/build/ui-bundle.zip?job=bundle-stable
    snapshot: true
  supplemental_files: ./supplemental-ui   # <- сюда кладётся фирменный вид

asciidoc:
  attributes:
    kroki-fetch-diagram: true
    source-highlighter: highlight.js
    experimental: ''
    idprefix: ''
    idseparator: '-'
  extensions:
    - '@asciidoctor/tabs'
    - asciidoctor-kroki

output:
  dir: ./build/site

runtime:
  fetch: true
  log:
    failure_level: warn     # битый xref валит сборку — это то, что нужно
```

`failure_level: warn` — критично. Без него Antora молча выпускает сайт с
битыми ссылками.

## antora.yml компонента

```yaml
name: platform
title: Платформа контакт-центра
version: '2.4'
start_page: ROOT:index.adoc
nav:
  - modules/ROOT/nav.adoc
asciidoc:
  attributes:
    java-version: '21'
    spring-boot-version: '3.5.0'
```

Для ветки `main` обычно ставят `version: true` (версия берётся из имени ветки)
или `prerelease: true`.

## Resource IDs — то, чем Antora отличается от Asciidoctor

Внутри Antora пути не относительные, а «семейные»:

```asciidoc
xref:how-to/kafka-retry.adoc[Ретраи в Kafka]          — другая страница
xref:platform:ROOT:index.adoc[]                        — другой компонент
include::partial$disclaimer.adoc[]                     — переиспользуемый кусок
include::example$kafka/ChatConsumer.java[tag=consumer] — код
image::images/topology.svg[Топология]                  — картинка (image$ подразумевается)
link:attachment$openapi.yaml[OpenAPI спецификация]     — файл на скачивание
```

Полная форма ID: `версия@компонент:модуль:семейство$путь.adoc#якорь`.
На практике почти всегда хватает `модуль:путь.adoc`.

Частая ошибка: `include::../examples/Foo.java[]` вместо `example$Foo.java` —
работает локально в IDE, падает в Antora.

## nav.adoc

```asciidoc
* xref:index.adoc[Обзор]

.Первые шаги
* xref:tutorial/quickstart.adoc[]
* xref:tutorial/first-bot.adoc[]

.Практика
* xref:how-to/kafka-retry.adoc[]
* xref:how-to/tracing.adoc[]

.Справочник
* xref:reference/config.adoc[]
* xref:reference/api.adoc[]
```

Пустые скобки `[]` — заголовок подставится из самой страницы. Это правильный
дефолт: заголовок меняется в одном месте.

## Фирменный вид без форка UI-бандла

`supplemental_files` — тот самый механизм, ради которого не нужно форкать
`antora-ui-default`. Структура:

```
supplemental-ui/
├── css/
│   └── signature.css            <- assets/theme/signature.css отсюда
├── js/
│   └── enhance.js               <- скрипты из docinfo-footer.html
├── img/
│   └── logo.svg
└── partials/
    ├── head-styles.hbs          подключение css
    ├── footer-scripts.hbs       подключение js
    └── header-content.hbs       свой хедер
```

`partials/head-styles.hbs`:

```handlebars
<link rel="stylesheet" href="{{{uiRootPath}}}/css/site.css">
<link rel="stylesheet" href="{{{uiRootPath}}}/css/signature.css">
```

`partials/footer-scripts.hbs`:

```handlebars
<script id="site-script" src="{{{uiRootPath}}}/js/site.js" data-ui-root-path="{{{uiRootPath}}}"></script>
<script async src="{{{uiRootPath}}}/js/vendor/highlight.js"></script>
<script src="{{{uiRootPath}}}/js/enhance.js"></script>
```

Важно: если переопределяешь партиал — копируй оригинал из бандла и дополняй,
а не пиши с нуля, иначе потеряешь подсветку кода и мобильное меню.

## Табы

Расширение `@asciidoctor/tabs` требует и JS, и CSS. JS уже подключён через
`extensions`, CSS кладётся в supplemental UI:

```bash
npm i -D @asciidoctor/tabs
cp node_modules/@asciidoctor/tabs/data/css/tabs.css supplemental-ui/css/
```

Синтаксис:

````asciidoc
[tabs]
======
Maven::
+
[,xml]
----
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
----

Gradle::
+
[,kotlin]
----
implementation("org.springframework.boot:spring-boot-starter-web")
----
======
````

## Поиск

Antora из коробки поиска не даёт. Рабочие варианты:

- **`@antora/lunr-extension`** — индекс генерится при сборке, всё локально,
  без внешних сервисов. Единственный вариант для закрытого контура.
- **Algolia DocSearch** — лучше качеством, но это внешний сервис и краулер;
  за периметром отпадает.

```yaml
antora:
  extensions:
    - require: '@antora/lunr-extension'
      index_latest_only: true
      languages: [ru, en]
```

## Сборка и публикация

```bash
npx antora --fetch antora-playbook.yml          # полная сборка
npx antora --to-dir build/preview playbook.yml  # быстрый локальный preview
npx http-server build/site -p 8080              # посмотреть глазами
```

GitHub Pages через Actions — см. `oss-conventions.md`.

## Типовые грабли

- **Страница не появилась в сайте** — её нет в `nav.adoc`. Antora собирает
  всё, но без навигации страница считается «сиротой».
- **`include` работает в IDE, падает в сборке** — использован относительный
  путь вместо `example$`/`partial$`.
- **Локальные правки не видны** — в источнике указана ветка (`branches: main`),
  а не рабочая копия. Для preview добавь `url: .` + `branches: HEAD`.
- **Версия задваивается в меню** — `version:` в `antora.yml` конфликтует с
  именем ветки; на релизных ветках ставь явную версию, на `main` — `true`.
- **Диаграммы не рендерятся** — не установлен `asciidoctor-kroki` или не задан
  `kroki-server-url` для локального Kroki.
