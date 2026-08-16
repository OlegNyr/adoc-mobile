#!/usr/bin/env python3
"""
scaffold.py — разворачивает каркас документации в проекте.

Только стандартная библиотека, работает на Windows/macOS/Linux.

    python scripts/scaffold.py --mode antora      --target . --name platform
    python scripts/scaffold.py --mode asciidoctor --target . --name platform

Что делает:
  * создаёт дерево каталогов под выбранный режим сборки;
  * копирует тему (signature.css, enhance.js, docinfo*.html, theme.puml);
  * кладёт index.adoc, nav.adoc, .vale.ini и заготовку ADR;
  * ничего не перезаписывает без --force.
"""

import argparse
import shutil
import sys
from pathlib import Path

SKILL_ROOT = Path(__file__).resolve().parent.parent
ASSETS = SKILL_ROOT / "assets"

DIRS = {
    "antora": [
        "docs/modules/ROOT/pages/tutorial",
        "docs/modules/ROOT/pages/how-to",
        "docs/modules/ROOT/pages/reference",
        "docs/modules/ROOT/pages/explanation",
        "docs/modules/ROOT/partials",
        "docs/modules/ROOT/examples/diagrams",
        "docs/modules/ROOT/images",
        "docs/modules/ROOT/attachments",
        "docs/decisions",
        "supplemental-ui/css",
        "supplemental-ui/js",
        "supplemental-ui/partials",
    ],
    "asciidoctor": [
        "docs/tutorial",
        "docs/how-to",
        "docs/reference",
        "docs/explanation",
        "docs/_partials",
        "docs/images",
        "docs/style",
        "docs/theme",
        "docs/decisions",
    ],
}

NAV = """\
* xref:index.adoc[Обзор]

.Первые шаги
* xref:tutorial/quickstart.adoc[]

.Практика
* xref:how-to/index.adoc[]

.Справочник
* xref:reference/config.adoc[]

.Как это устроено
* xref:explanation/architecture.adoc[]
"""

ANTORA_YML = """\
name: {name}
title: {title}
version: true
start_page: ROOT:index.adoc
nav:
  - modules/ROOT/nav.adoc
asciidoc:
  attributes:
    experimental: ''
    idprefix: ''
    idseparator: '-'
    kroki-fetch-diagram: true
"""

PLAYBOOK = """\
site:
  title: {title}
  start_page: {name}::index.adoc

content:
  sources:
    - url: .
      branches: HEAD
      start_path: docs

ui:
  bundle:
    url: https://gitlab.com/antora/antora-ui-default/-/jobs/artifacts/HEAD/raw/build/ui-bundle.zip?job=bundle-stable
    snapshot: true
  supplemental_files: ./supplemental-ui

asciidoc:
  attributes:
    source-highlighter: highlight.js
    kroki-fetch-diagram: true
  extensions:
    - '@asciidoctor/tabs'
    - asciidoctor-kroki

output:
  dir: ./build/site

runtime:
  fetch: true
  log:
    failure_level: warn
"""

HEAD_STYLES = """\
<link rel="stylesheet" href="{{{uiRootPath}}}/css/site.css">
<link rel="stylesheet" href="{{{uiRootPath}}}/css/tabs.css">
<link rel="stylesheet" href="{{{uiRootPath}}}/css/signature.css">
"""

FOOTER_SCRIPTS = """\
<script id="site-script" src="{{{uiRootPath}}}/js/site.js" data-ui-root-path="{{{uiRootPath}}}"></script>
<script async src="{{{uiRootPath}}}/js/vendor/highlight.js"></script>
<script src="{{{uiRootPath}}}/js/enhance.js"></script>
"""

VALE_INI = """\
StylesPath = .vale/styles
MinAlertLevel = warning
Vocab = Project

[*.adoc]
BasedOnStyles = Vale, Project
"""

VALE_WEASEL = """\
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
  - разумеется
"""

ADR = """\
= 1. Документация как код
:status: accepted
:date: {date}

== Контекст
Документация расходилась с реальностью, потому что жила отдельно от кода и
не проверялась при ревью.

== Решение
Исходники документации хранятся в этом репозитории в `docs/`, собираются в
CI, примеры кода включаются из компилируемых файлов через `include` с тегами.

== Последствия
Плюсы: дока проверяется на каждом PR, примеры не могут протухнуть.
Минусы: порог входа выше, чем у правки страницы в вебе; нужен запуск сборки
локально.
"""


def write(path: Path, content: str, force: bool) -> None:
    if path.exists() and not force:
        print(f"  = пропущен (уже есть): {path}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    print(f"  + {path}")


def copy(src: Path, dst: Path, force: bool) -> None:
    if not src.exists():
        print(f"  ! нет исходника: {src}", file=sys.stderr)
        return
    if dst.exists() and not force:
        print(f"  = пропущен (уже есть): {dst}")
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(src, dst)
    print(f"  + {dst}")


def main() -> int:
    ap = argparse.ArgumentParser(description="Каркас документации на AsciiDoc")
    ap.add_argument("--mode", choices=["antora", "asciidoctor"], required=True)
    ap.add_argument("--target", default=".", help="корень проекта")
    ap.add_argument("--name", default="docs", help="имя компонента (slug)")
    ap.add_argument("--title", default=None, help="человекочитаемое название")
    ap.add_argument("--force", action="store_true", help="перезаписывать существующие файлы")
    args = ap.parse_args()

    from datetime import date

    root = Path(args.target).resolve()
    title = args.title or args.name.replace("-", " ").capitalize()

    print(f"Каркас документации: режим {args.mode}, каталог {root}")

    for d in DIRS[args.mode]:
        (root / d).mkdir(parents=True, exist_ok=True)

    if args.mode == "antora":
        pages = root / "docs/modules/ROOT/pages"
        copy(ASSETS / "starter/index.adoc", pages / "index.adoc", args.force)
        write(root / "docs/modules/ROOT/nav.adoc", NAV, args.force)
        write(root / "docs/antora.yml", ANTORA_YML.format(name=args.name, title=title), args.force)
        write(root / "antora-playbook.yml", PLAYBOOK.format(name=args.name, title=title), args.force)
        write(root / "supplemental-ui/partials/head-styles.hbs", HEAD_STYLES, args.force)
        write(root / "supplemental-ui/partials/footer-scripts.hbs", FOOTER_SCRIPTS, args.force)
        copy(ASSETS / "theme/signature.css", root / "supplemental-ui/css/signature.css", args.force)
        copy(ASSETS / "theme/enhance.js", root / "supplemental-ui/js/enhance.js", args.force)
        copy(ASSETS / "plantuml/theme.puml",
             root / "docs/modules/ROOT/examples/diagrams/theme.puml", args.force)
    else:
        copy(ASSETS / "starter/index.adoc", root / "docs/index.adoc", args.force)
        copy(ASSETS / "theme/signature.css", root / "docs/theme/signature.css", args.force)
        copy(ASSETS / "theme/enhance.js", root / "docs/theme/enhance.js", args.force)
        copy(ASSETS / "theme/docinfo.html", root / "docs/docinfo.html", args.force)
        copy(ASSETS / "theme/docinfo-footer.html", root / "docs/docinfo-footer.html", args.force)
        copy(ASSETS / "plantuml/theme.puml", root / "docs/style/theme.puml", args.force)

    write(root / ".vale.ini", VALE_INI, args.force)
    write(root / ".vale/styles/Project/Weasel.yml", VALE_WEASEL, args.force)
    write(root / "docs/decisions/0001-docs-as-code.adoc",
          ADR.format(date=date.today().isoformat()), args.force)

    print("\nГотово. Дальше:")
    if args.mode == "antora":
        print("  npm i -D @asciidoctor/tabs asciidoctor-kroki @antora/cli @antora/site-generator")
        print("  npx antora --fetch antora-playbook.yml && npx http-server build/site")
    else:
        print("  asciidoctor -a docinfo=shared -D build docs/index.adoc")
    print("  Не забудь заменить содержимое index.adoc на своё — это демо-страница.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
