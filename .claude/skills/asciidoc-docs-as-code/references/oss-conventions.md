# Конвенции опенсорс-проектов

То, что отличает репозиторий, в который приходят контрибьюторы, от репозитория,
в который приходят только его авторы.

## README.adoc

README — не документация, а **витрина и маршрутизатор**. Он отвечает на четыре
вопроса за 30 секунд и отправляет за подробностями:

```asciidoc
= Contact Center Platform
:toc: macro
:toclevels: 2

image:https://img.shields.io/badge/java-21-blue.svg[]
image:https://github.com/org/repo/actions/workflows/ci.yml/badge.svg[Build]

Событийная платформа обработки обращений: 4 канала, Kafka, WebSocket, p99 < 200 мс.

toc::[]

== Зачем это нужно
Два абзаца: какую проблему решает, для кого, чем отличается от альтернатив.

== Быстрый старт
[,bash]
----
git clone …
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
----
Открой http://localhost:8080/ui и отправь тестовое обращение.

== Документация
* https://org.github.io/cc-docs[Портал документации]
* xref:docs/modules/ROOT/pages/tutorial/quickstart.adoc[Пошаговый туториал]
* xref:docs/decisions/[Архитектурные решения (ADR)]

== Как поучаствовать
См. xref:CONTRIBUTING.adoc[CONTRIBUTING].

== Лицензия
Apache-2.0, см. xref:LICENSE[].
```

Чего в README быть не должно: полного справочника конфигурации, истории
изменений (это `CHANGELOG`), длинных объяснений архитектуры (это
`explanation/`).

## CONTRIBUTING.adoc

Каждый пункт здесь экономит один цикл ревью:

- как поднять окружение (одна команда, а не десять);
- формат коммитов — Conventional Commits, если нет причин против;
- как запустить проверки локально: `mvn verify`, `npx vale docs`, `npx antora …`;
- **как собрать документацию и посмотреть её глазами** — этот пункт забывают
  чаще всего, и именно из-за него дока в PR не проверяется;
- политика ветвления, кто ревьюер, DCO/CLA если нужен;
- шаблон PR: что изменилось, как проверить, затронута ли дока.

## ADR

Архитектурные решения записываются в момент принятия, а не восстанавливаются
через год по чату. Формат MADR, файл на решение, нумерация сквозная,
`docs/decisions/0007-event-sourcing-for-dialogs.adoc`:

```asciidoc
= 7. Event sourcing для состояния диалога
:status: accepted
:date: 2026-03-14
:deciders: platform-team

== Контекст
Диалог проходит 12 состояний, аудит требует восстановления любого момента
времени, регулятор требует хранения 5 лет.

== Решение
Хранить события в `dialog_event`, состояние — проекция, снапшоты каждые 200
событий.

== Последствия
Плюсы: полный аудит, дешёвая отладка прода, простой replay.
Минусы: +40% объёма БД, обязательная версионируемость схемы событий, порог
входа для новых разработчиков выше.

== Рассмотренные альтернативы
CRUD + таблица аудита — отклонено: не восстанавливает промежуточные состояния.
```

Статус меняется, а не удаляется: `proposed` → `accepted` → `superseded by
0012`. ADR — это лог, а не описание текущего состояния.

## Vale: линтер прозы

```ini
# .vale.ini
StylesPath = .vale/styles
MinAlertLevel = warning
Vocab = Project

[*.adoc]
BasedOnStyles = Vale, Project
```

`.vale/styles/Project/Weasel.yml` — слова, обесценивающие текст:

```yaml
extends: existence
message: "«%s» — слово, которое ничего не сообщает читателю."
level: warning
ignorecase: true
tokens:
  - просто
  - легко
  - очевидно
  - всего лишь
  - как известно
```

`.vale/styles/config/vocabularies/Project/accept.txt` — терминология
проекта, чтобы «Kafka», «PostgreSQL», «WebSocket» писались единообразно.

Vale читает `.adoc` через `asciidoctor`, поэтому в CI он должен быть
установлен.

## CI: GitHub Actions

```yaml
name: docs
on:
  pull_request:
    paths: ['docs/**', '*.adoc', 'antora-playbook.yml']
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: 20, cache: npm }
      - run: npm ci
      - name: Build site
        run: npx antora --fetch antora-playbook.yml
      - name: Prose lint
        uses: errata-ai/vale-action@reviewdog
        with: { files: docs }
      - name: Link check
        uses: lycheeverse/lychee-action@v2
        with: { args: --no-progress build/site }
      - uses: actions/upload-artifact@v4
        with:
          name: docs-preview
          path: build/site

  publish:
    needs: build
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    permissions: { pages: write, id-token: write }
    steps:
      - uses: actions/download-artifact@v4
        with: { name: docs-preview, path: site }
      - uses: actions/upload-pages-artifact@v3
        with: { path: site }
      - uses: actions/deploy-pages@v4
```

Ключевое здесь — **артефакт с превью на каждом PR**. Ревьюер, который может
скачать и открыть сайт, находит проблемы вёрстки; ревьюер, который смотрит на
диф разметки, — нет.

## Версионирование документации

- Ветка `main` → версия сайта `main` или `next`, помеченная как prerelease.
- Релизные ветки `v2.3`, `v2.4` → соответствующие версии в Antora.
- В `antora-playbook.yml` перечисляются `branches: [main, 'v*']`, старые
  версии остаются доступными в переключателе.
- Устаревшие страницы не удаляются, а помечаются:

```asciidoc
[.admonition.deprecated]
****
Раздел относится к версии 2.3 и ниже. В 2.4 механизм заменён на
xref:how-to/kafka-retry.adoc[ретраи через DLQ].
****
```

## CHANGELOG

Keep a Changelog, в `.adoc`, генерится частично из Conventional Commits.
Важно: пункт changelog ссылается на страницу документации, а не пересказывает
её. Иначе появляется второй источник правды, который расходится с первым.

## Лицензия и заголовки

Для доки — `CC-BY-4.0` или та же лицензия, что у кода. Ставь `LICENSE` в
корень, а не только упоминание в README: автоматические сканеры смотрят на
файл.
