# Asciidoctor build: один репозиторий, три выхода

Режим для случая «дока живёт рядом с кодом и собирается тем же `mvn`/`gradle`».
Даёт то, чего не даёт Antora: **PDF и слайды из тех же исходников** и
включение кода прямо из `src/`.

## Структура

```
project/
├── src/main/java/…
├── src/test/java/…                       примеры отсюда включаются в доку
├── docs/
│   ├── index.adoc                        главная
│   ├── _partials/                        куски (подчёркивание = не собирать как страницу)
│   ├── images/
│   ├── docinfo.html                      <- assets/theme/docinfo.html
│   ├── docinfo-footer.html               <- assets/theme/docinfo-footer.html
│   └── theme/
│       ├── signature.css
│       └── pdf-theme.yml
└── pom.xml
```

Файлы и папки, начинающиеся с `_`, Asciidoctor не конвертирует в отдельные
HTML — идеально для партиалов.

## Maven

```xml
<plugin>
  <groupId>org.asciidoctor</groupId>
  <artifactId>asciidoctor-maven-plugin</artifactId>
  <version>3.1.1</version>
  <dependencies>
    <dependency>
      <groupId>org.asciidoctor</groupId>
      <artifactId>asciidoctorj-pdf</artifactId>
      <version>2.3.19</version>
    </dependency>
    <dependency>
      <groupId>org.asciidoctor</groupId>
      <artifactId>asciidoctorj-diagram</artifactId>
      <version>2.3.1</version>
    </dependency>
  </dependencies>
  <configuration>
    <sourceDirectory>docs</sourceDirectory>
    <preserveDirectories>true</preserveDirectories>
    <attributes>
      <source-highlighter>highlight.js</source-highlighter>
      <icons>font</icons>
      <experimental/>
      <sectanchors/>
      <toc>left</toc>
      <docinfo>shared</docinfo>
      <!-- ключевое: базовые пути для include -->
      <sourcedir>${project.basedir}/src/main/java</sourcedir>
      <testdir>${project.basedir}/src/test/java</testdir>
      <project-version>${project.version}</project-version>
    </attributes>
  </configuration>
  <executions>
    <execution>
      <id>html</id>
      <phase>package</phase>
      <goals><goal>process-asciidoc</goal></goals>
      <configuration>
        <backend>html5</backend>
        <outputDirectory>${project.build.directory}/docs/html</outputDirectory>
      </configuration>
    </execution>
    <execution>
      <id>pdf</id>
      <phase>package</phase>
      <goals><goal>process-asciidoc</goal></goals>
      <configuration>
        <backend>pdf</backend>
        <outputDirectory>${project.build.directory}/docs/pdf</outputDirectory>
        <attributes>
          <source-highlighter>rouge</source-highlighter>
          <pdf-themesdir>${project.basedir}/docs/theme</pdf-themesdir>
          <pdf-theme>pdf</pdf-theme>
        </attributes>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Обрати внимание: для PDF подсветка обязана быть `rouge` — `highlight.js`
работает только в браузере и в PDF даст простыню без цветов.

## Gradle (Kotlin DSL)

```kotlin
plugins {
    id("org.asciidoctor.jvm.convert") version "4.0.4"
    id("org.asciidoctor.jvm.pdf")     version "4.0.4"
}

asciidoctorj {
    modules {
        diagram.use()
        pdf.setVersion("2.3.19")
    }
    attributes(mapOf(
        "source-highlighter" to "highlight.js",
        "icons"              to "font",
        "experimental"       to "",
        "toc"                to "left",
        "docinfo"            to "shared",
        "sourcedir"          to "$projectDir/src/main/java",
        "project-version"    to project.version
    ))
}

tasks.asciidoctor {
    baseDirFollowsSourceDir()
    sources { include("index.adoc") }
}
```

`baseDirFollowsSourceDir()` — без него `include::` с относительными путями
начнёт разъезжаться между IDE и сборкой.

## Живой код из src

Атрибут `{sourcedir}` уже задан выше, дальше в тексте:

```asciidoc
[,java]
----
include::{sourcedir}/com/example/cc/kafka/ChatConsumer.java[tag=consumer,indent=0]
----
```

Самый ценный вариант — **включать тесты**, а не продовый код: тест
одновременно и пример использования, и гарантия, что пример рабочий.

```asciidoc
.Отправка сообщения оператору
[,java]
----
include::{testdir}/com/example/cc/OperatorMessageTest.java[tag=send,indent=0]
----
```

`indent=0` сбрасывает отступ метода — без него в доке будет лесенка из
восьми пробелов.

## Фирменный вид: docinfo

Атрибут `:docinfo: shared` заставляет Asciidoctor подмешать содержимое
`docinfo.html` в `<head>` и `docinfo-footer.html` перед `</body>`. Это
единственный штатный способ добавить свой CSS/JS в standalone-HTML.

```
docs/docinfo.html         → <style> и <link> темы, meta, favicon
docs/docinfo-footer.html  → <script> с копированием кода, тёмной темой, прогрессом
```

Готовые файлы — в `assets/theme/`. Копируй как есть, правь переменные в
`:root`.

Для одного файла без внешних зависимостей добавь `-a data-uri` и
`-a allow-uri-read`: картинки встроятся в base64 и HTML станет
самодостаточным — удобно, когда доку отправляют вложением в письме.

## PDF-тема

`docs/theme/pdf-theme.yml` (Asciidoctor PDF читает файл `<name>-theme.yml`):

```yaml
extends: default
font:
  catalog:
    merge: true
    IBM Plex Sans:
      normal: ibm-plex-sans-regular.ttf
      bold: ibm-plex-sans-bold.ttf
      italic: ibm-plex-sans-italic.ttf
      bold_italic: ibm-plex-sans-bolditalic.ttf
base:
  font-family: IBM Plex Sans
  font-size: 10.5
  line-height: 1.5
heading:
  font-color: '1D3557'
  font-family: IBM Plex Sans
  h1-font-size: 26
link:
  font-color: '2A6F97'
code:
  font-family: JetBrains Mono
  background-color: 'F5F7FA'
  border-color: 'DDE3EA'
admonition:
  column-rule-color: '2A6F97'
  label:
    font-style: bold
page:
  margin: [20mm, 18mm, 22mm, 18mm]
  numbering:
    start-at: toc
footer:
  height: 14mm
  recto:
    right:
      content: '{page-number} / {page-count}'
```

Кириллица: дефолтные шрифты Asciidoctor PDF её поддерживают частично. Если в
PDF полезли квадраты — подкладывай TTF в `docs/theme/fonts/` и прописывай
`font.catalog`, как выше. Это единственная надёжная починка.

## Слайды из той же доки

```bash
npm i -D @asciidoctor/reveal.js
npx asciidoctor-revealjs -a revealjs_theme=night \
    -a revealjsdir=https://cdn.jsdelivr.net/npm/reveal.js@5 \
    docs/talk.adoc
```

Правила, чтобы одни и те же исходники работали и как страница, и как слайды:
каждый `==` становится слайдом, `===` — вертикальным подслайдом; длинные
абзацы прячь в `[.notes]` (уйдут в заметки докладчика, не на экран).

Для закрытого контура `revealjsdir` должен указывать на локальную копию
reveal.js в репозитории, не на CDN.

## Одна команда для всего

```bash
mvn -q package                      # html + pdf в target/docs
./gradlew asciidoctor asciidoctorPdf
```

Если сборка доки требует больше одной команды и абзаца объяснений в README —
её никто не будет запускать локально, и дока начнёт ломаться в main.
