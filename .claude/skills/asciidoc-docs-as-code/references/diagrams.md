# Диаграммы как код

Картинка, которую нельзя сдиффить, — это будущая ложь в документации. Правило
простое: **исходник диаграммы лежит в git рядом с текстом, SVG генерируется
сборкой.**

## Что чем рисовать

| Задача | Инструмент | Почему |
| --- | --- | --- |
| Последовательности вызовов, взаимодействие сервисов | PlantUML `sequence` | лучший в классе, читаемый исходник |
| Контекст/контейнеры/компоненты системы | PlantUML + C4-PlantUML | готовые макросы, единый визуальный язык |
| Быстрый флоучарт в README | Mermaid | рендерится прямо на GitHub |
| Архитектурная схема со сложной раскладкой | D2 | автораскладка заметно лучше PlantUML |
| ER, схема БД | PlantUML `entity` или dbml | диффится, в отличие от скриншота из DBeaver |
| Художественная схема для презентации | draw.io | коммить `.drawio` **и** экспортированный `.svg` |

Скриншот UI — только когда объясняешь расположение элементов на экране.
Во всех остальных случаях он протухнет к следующему релизу.

## Два способа рендеринга

### Kroki — рекомендуемый

Один сервис рендерит все языки (PlantUML, Mermaid, D2, Graphviz, BPMN,
Excalidraw и ещё десяток). Не нужен Java/Graphviz на машине сборки.

```bash
npm i -D asciidoctor-kroki
```

```asciidoc
:kroki-server-url: https://kroki.io
:kroki-fetch-diagram: true
:kroki-default-format: svg

[plantuml,dialog-flow,svg]
----
include::example$diagrams/dialog-flow.puml[]
----
```

`:kroki-fetch-diagram:` — скачивает SVG на этапе сборки и кладёт рядом. Без
него в HTML останется ссылка на kroki.io, и **страница будет ходить во
внешний сервис при каждом открытии** — в корпоративном контуре это блокер и
для безопасности, и для работоспособности.

Для закрытого контура поднимается свой Kroki:

```yaml
# docker-compose.yml
services:
  kroki:
    image: yuzutech/kroki
    ports: ["8000:8000"]
    environment:
      KROKI_MERMAID_HOST: mermaid
  mermaid:
    image: yuzutech/kroki-mermaid
```

и `:kroki-server-url: http://kroki.internal:8000`.

### Asciidoctor Diagram — когда нельзя внешний сервис вообще

Рендерит локально, PlantUML-jar тянется через Maven/Gradle:

```xml
<dependency>
  <groupId>org.asciidoctor</groupId>
  <artifactId>asciidoctorj-diagram</artifactId>
  <version>2.3.1</version>
</dependency>
```

```asciidoc
[plantuml,dialog-flow,svg]
----
include::{diagramsdir}/dialog-flow.puml[]
----
```

Требует Graphviz для не-sequence диаграмм. Плюс — ноль сетевых обращений;
минус — Mermaid/D2 недоступны.

## Единый стиль

Разнокалиберные диаграммы ломают впечатление сильнее, чем плохая вёрстка
текста. Заведи один файл темы и подключай его первой строкой каждого `.puml`:

```plantuml
@startuml
!include ../style/theme.puml

participant "Оператор" as op
participant "Chat GW" as gw
queue "Kafka" as k

op -> gw : WebSocket frame
gw -> k  : dialog.event.v1
@enduml
```

Готовая тема — `assets/plantuml/theme.puml`, палитра совпадает с
`signature.css`.

## C4

```plantuml
@startuml
!include <C4/C4_Container>
!include ../style/theme.puml

Person(agent, "Оператор", "Работает в веб-клиенте")
System_Boundary(cc, "Контакт-центр") {
  Container(gw, "Chat Gateway", "Spring Boot, WebSocket", "Держит соединения операторов")
  Container(core, "Dialog Core", "Spring Boot", "Маршрутизация и состояние диалога")
  ContainerDb(pg, "PostgreSQL", "", "Диалоги и события")
}
System_Ext(crm, "CRM", "Карточка клиента")

Rel(agent, gw, "WSS")
Rel(gw, core, "Kafka: dialog.event.v1")
Rel(core, pg, "JDBC")
Rel(core, crm, "REST")
@enduml
```

Держи ровно три уровня: контекст (C1), контейнеры (C2), компоненты (C3) для
одного-двух самых нетривиальных контейнеров. C4 на уровне классов не рисует
никто и никогда не поддерживает.

## Тёмная тема

SVG с белым `<rect>` фоном в тёмной теме выглядит как дыра. Варианты по
возрастанию усилий:

1. `skinparam backgroundColor transparent` в теме PlantUML — минимум работы,
   покрывает большинство случаев.
2. Нейтральная палитра, читаемая на обоих фонах (серо-синие заливки,
   тёмно-серые обводки).
3. Две сборки диаграмм и переключение через CSS
   `[data-theme="dark"] .diagram-light { display: none }` — делать только
   если диаграммы центральны для продукта.

## Проверка в CI

```bash
# все .puml должны рендериться
find docs -name '*.puml' -print0 | xargs -0 -n1 plantuml -checkonly
```

Битая диаграмма должна валить сборку так же, как битый `xref:`.

## Антипаттерны

- **PNG-экспорт вместо SVG** — не масштабируется, не ищется по тексту, весит
  больше.
- **Диаграмма без исходника** — через полгода её нельзя поправить, только
  перерисовать с нуля.
- **`!include` по URL** — сборка становится зависимой от чужого сервера.
  Копируй C4-PlantUML в репозиторий или фиксируй версию через `!includeurl`
  с зеркалом.
- **Схема на 40 узлов** — её не читают. Разбей на уровни C4 или на несколько
  диаграмм по сценариям.
