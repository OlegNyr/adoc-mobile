#!/usr/bin/env python3
"""Оснастка сверки вывода Asciidoctor на тестовом корпусе.

Подкоманды:
  reference           прогнать корпус через локальный Ruby-asciidoctor -> expected/
  normalize <путь>    вывести каноническую форму HTML-файла в stdout
  compare --actual D  сравнить каталог D с expected/ через каноническую форму

Зависимостей нет: только стандартная библиотека Python 3.9+.
"""

from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import date
from html.parser import HTMLParser
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
EXPECTED = ROOT / "expected"
WORK = ROOT / "build"

# Опции, которыми снимается эталон. Тот же набор обязан использовать движок 4.x,
# иначе сравниваются не реализации, а конфигурации.
RENDER_OPTIONS = {
    "backend": "html5",
    "doctype": "article",
    "standalone": False,  # CLI: --embedded
    "safe_mode": "safe",
    "attributes": [],  # ни одного атрибута из командной строки
}

VOID_TAGS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr",
}

VERBATIM_TAGS = {"pre", "script", "style", "textarea"}

# Теги, вокруг которых пробельный текст не значит ничего.
BLOCK_TAGS = {
    "html", "head", "body", "div", "p", "section", "article", "aside", "nav",
    "header", "footer", "main", "figure", "figcaption", "details", "summary",
    "h1", "h2", "h3", "h4", "h5", "h6", "hr", "br",
    "ul", "ol", "li", "dl", "dt", "dd",
    "table", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup",
    "caption", "pre", "blockquote", "form", "fieldset", "legend",
    "video", "audio", "source", "iframe", "meta", "link", "title",
    "script", "style", "hgroup",
}

# Содержимое, которое меняется от прогона к прогону или от версии к версии
# и к совместимости отношения не имеет.
VOLATILE_ELEMENTS = (
    ("meta", "name", "generator"),
)
VOLATILE_IDS = {"footer", "footer-text"}

NUMBER_RE = re.compile(r"\d+\.\d{5,}")


def round_numbers(value: str) -> str:
    """33.333333% -> 33.3333%: разница округления шириной колонки не значима."""
    def repl(m: re.Match[str]) -> str:
        return f"{round(float(m.group(0)), 4):g}"
    return NUMBER_RE.sub(repl, value)


class Canonicalizer(HTMLParser):
    """Разбирает HTML в поток событий, устойчивый к незначащим расхождениям."""

    def __init__(self, *, sort_classes: bool = True, keep_comments: bool = False,
                 round_floats: bool = True, fold_presentational: bool = False) -> None:
        super().__init__(convert_charrefs=True)
        self.sort_classes = sort_classes
        self.keep_comments = keep_comments
        self.round_floats = round_floats
        self.fold_presentational = fold_presentational
        self.events: list[tuple[str, str]] = []
        self.verbatim_depth = 0
        self.skip_depth = 0
        self._skip_tag: str | None = None

    # --- нормализация атрибутов -------------------------------------------------

    def _fold(self, tag: str, attrs: list[tuple[str, str | None]]) -> list[tuple[str, str | None]]:
        """Сводит уже разобранные системные расхождения к одной форме записи.

        Включается флагом и гасит ровно два случая, разобранные вручную:
        устаревший презентационный атрибут `width` вместо `style="width: …"`
        и разрыв страницы классом вместо инлайнового стиля.
        """
        amap = {k.lower(): v for k, v in attrs}
        if tag in {"col", "table"} and amap.get("width"):
            width = amap.pop("width")
            style = amap.get("style", "") or ""
            if "width" not in style:
                style = (style + " " if style else "") + f"width: {width};"
            amap["style"] = style.strip()
        if tag == "div":
            style = (amap.get("style") or "").replace(" ", "")
            classes = (amap.get("class") or "").split()
            if "page-break-after:always;" in style or "page-break" in classes:
                return [("class", "page-break")]
        return list(amap.items())

    def _attrs(self, tag: str, attrs: list[tuple[str, str | None]]) -> str:
        if self.fold_presentational:
            attrs = self._fold(tag, attrs)
        parts = []
        for name, value in attrs:
            name = name.lower()
            if value is None:
                parts.append(name)
                continue
            value = re.sub(r"\s+", " ", value).strip()
            if name == "class" and self.sort_classes:
                value = " ".join(sorted(value.split()))
            if self.round_floats and name in {"style", "width", "height"}:
                value = round_numbers(value)
            parts.append(f"{name}={json.dumps(value, ensure_ascii=False)}")
        parts.sort()
        return " ".join(parts)

    def _is_volatile(self, tag: str, attrs: list[tuple[str, str | None]]) -> bool:
        amap = {k.lower(): (v or "") for k, v in attrs}
        for vtag, vattr, vvalue in VOLATILE_ELEMENTS:
            if tag == vtag and amap.get(vattr, "").lower() == vvalue:
                return True
        return amap.get("id", "") in VOLATILE_IDS

    # --- обработчики парсера ----------------------------------------------------

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if self.skip_depth:
            if tag not in VOID_TAGS and tag == self._skip_tag:
                self.skip_depth += 1
            return
        if self._is_volatile(tag, attrs):
            if tag not in VOID_TAGS:
                self.skip_depth = 1
                self._skip_tag = tag
            return
        rendered = self._attrs(tag, attrs)
        self.events.append(("tag", f"<{tag}{' ' + rendered if rendered else ''}>"))
        if tag in VERBATIM_TAGS:
            self.verbatim_depth += 1

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if self.skip_depth or self._is_volatile(tag, attrs):
            return
        rendered = self._attrs(tag, attrs)
        self.events.append(("tag", f"<{tag}{' ' + rendered if rendered else ''}>"))

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in VOID_TAGS:
            return  # </br> и подобное — мусор разметки, не различие
        if self.skip_depth:
            if tag == self._skip_tag:
                self.skip_depth -= 1
                if self.skip_depth == 0:
                    self._skip_tag = None
            return
        if tag in VERBATIM_TAGS and self.verbatim_depth:
            self.verbatim_depth -= 1
        self.events.append(("tag", f"</{tag}>"))

    def handle_data(self, data: str) -> None:
        if self.skip_depth:
            return
        if self.verbatim_depth:
            self.events.append(("verbatim", data.replace("\r\n", "\n")))
        else:
            self.events.append(("text", data))

    def handle_comment(self, data: str) -> None:
        if self.keep_comments and not self.skip_depth:
            self.events.append(("comment", data.strip()))

    def handle_decl(self, decl: str) -> None:
        self.events.append(("tag", f"<!{decl.split()[0].lower()}>"))

    # --- сборка канонической формы ---------------------------------------------

    def canonical_lines(self) -> list[str]:
        kinds = [k for k, _ in self.events]
        payloads = [p for _, p in self.events]

        def is_block_boundary(index: int) -> bool:
            if index < 0 or index >= len(kinds):
                return True  # край документа
            if kinds[index] != "tag":
                return False
            name = payloads[index].lstrip("</").split(" ", 1)[0].rstrip(">")
            return name in BLOCK_TAGS

        lines: list[str] = []
        for i, (kind, payload) in enumerate(self.events):
            if kind == "tag":
                lines.append(payload)
            elif kind == "comment":
                lines.append(f"<!--{payload}-->")
            elif kind == "verbatim":
                text = payload
                if text.startswith("\n"):
                    text = text[1:]
                if text:
                    lines.append("verbatim " + json.dumps(text, ensure_ascii=False))
            else:
                text = re.sub(r"\s+", " ", payload)
                if is_block_boundary(i - 1):
                    text = text.lstrip()
                if is_block_boundary(i + 1):
                    text = text.rstrip()
                if text:
                    lines.append("text " + json.dumps(text, ensure_ascii=False))
        return lines


def canonicalize(html: str, **options) -> list[str]:
    parser = Canonicalizer(**options)
    parser.feed(html)
    parser.close()
    return parser.canonical_lines()


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def corpus_files() -> list[Path]:
    return sorted(p for p in SRC.glob("*.adoc"))


# --- reference ------------------------------------------------------------------

def cmd_reference(args: argparse.Namespace) -> int:
    binary = shutil.which(args.asciidoctor) or args.asciidoctor
    try:
        version = subprocess.run([binary, "--version"], capture_output=True,
                                 text=True, encoding="utf-8", errors="replace")
    except OSError as exc:
        print(f"не найден asciidoctor ({binary}): {exc}", file=sys.stderr)
        return 2
    version_line = (version.stdout or version.stderr).splitlines()[0].strip()

    EXPECTED.mkdir(parents=True, exist_ok=True)
    warnings_log: list[str] = []
    manifest = {
        "generated": date.today().isoformat(),
        "renderer": version_line,
        "options": RENDER_OPTIONS,
        "files": {},
    }

    files = corpus_files()
    failed = 0
    for source in files:
        target = EXPECTED / (source.stem + ".html")
        cmd = [
            binary,
            "--backend", RENDER_OPTIONS["backend"],
            "--doctype", RENDER_OPTIONS["doctype"],
            "--safe-mode", RENDER_OPTIONS["safe_mode"],
            "--embedded",
            "--base-dir", str(SRC),
            # вывод берётся из stdout: safe mode не даёт писать за пределы base-dir
            "--out-file", "-",
            str(source),
        ]
        run = subprocess.run(cmd, capture_output=True)
        stderr = run.stderr.decode("utf-8", "replace").strip()
        if stderr:
            warnings_log.append(f"### {source.name}\n{stderr}\n")
        if run.returncode != 0:
            failed += 1
            print(f"ОШИБКА  {source.name}: код {run.returncode}")
            continue
        target.write_bytes(run.stdout.replace(b"\r\n", b"\n"))
        manifest["files"][source.name] = {
            "source_sha256": sha256(source),
            "html_sha256": sha256(target),
            "canonical_lines": len(canonicalize(read_text(target))),
            "warnings": stderr.count("\n") + 1 if stderr else 0,
        }
        mark = "предупреждения" if stderr else "чисто"
        print(f"ok      {source.name} -> {target.name} ({mark})")

    (EXPECTED / "_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    log = EXPECTED / "_warnings.log"
    if warnings_log:
        log.write_text("\n".join(warnings_log), encoding="utf-8")
    elif log.exists():
        log.unlink()

    print()
    print(f"движок: {version_line}")
    print(f"файлов: {len(files)}, ошибок: {failed}, файлов с предупреждениями: {len(warnings_log)}")
    if warnings_log:
        print(f"предупреждения записаны в {log.relative_to(ROOT)}")
    return 1 if failed else 0


# --- normalize ------------------------------------------------------------------

def cmd_normalize(args: argparse.Namespace) -> int:
    path = Path(args.path)
    lines = canonicalize(read_text(path), sort_classes=not args.no_sort_classes,
                         keep_comments=args.keep_comments,
                         round_floats=not args.no_round,
                         fold_presentational=args.fold_presentational)
    print("\n".join(lines))
    return 0


# --- compare --------------------------------------------------------------------

def diff_stats(diff: list[str]) -> dict[str, int]:
    """Считает различающиеся строки по имени тега — быстрый профиль расхождений."""
    profile: dict[str, int] = {}
    for line in diff:
        if not line or line[0] not in "+-" or line[:3] in ("+++", "---"):
            continue
        payload = line[1:].strip()
        if payload.startswith("<"):
            key = payload.lstrip("</").split(" ", 1)[0].rstrip(">")
        elif payload.startswith("verbatim "):
            key = "(дословный текст)"
        elif payload.startswith("text "):
            key = "(текст)"
        else:
            key = "(прочее)"
        profile[key] = profile.get(key, 0) + 1
    return profile


def cmd_compare(args: argparse.Namespace) -> int:
    expected_dir = Path(args.expected)
    actual_dir = Path(args.actual)
    report_dir = Path(args.report)
    norm_dir = report_dir / "normalized"
    for directory in (report_dir, norm_dir / "expected", norm_dir / "actual"):
        directory.mkdir(parents=True, exist_ok=True)

    options = dict(sort_classes=not args.no_sort_classes,
                   keep_comments=args.keep_comments,
                   round_floats=not args.no_round,
                   fold_presentational=args.fold_presentational)

    expected_files = {p.stem: p for p in sorted(expected_dir.glob("*.html"))}
    actual_files = {p.stem: p for p in sorted(actual_dir.glob("*.html"))}

    missing = sorted(set(expected_files) - set(actual_files))
    extra = sorted(set(actual_files) - set(expected_files))
    shared = sorted(set(expected_files) & set(actual_files))

    summary: list[str] = []
    total_profile: dict[str, int] = {}
    differing = 0

    for stem in shared:
        exp_html = read_text(expected_files[stem])
        act_html = read_text(actual_files[stem])
        if args.raw:
            exp_lines = exp_html.replace("\r\n", "\n").split("\n")
            act_lines = act_html.replace("\r\n", "\n").split("\n")
        else:
            exp_lines = canonicalize(exp_html, **options)
            act_lines = canonicalize(act_html, **options)
            (norm_dir / "expected" / f"{stem}.txt").write_text(
                "\n".join(exp_lines) + "\n", encoding="utf-8")
            (norm_dir / "actual" / f"{stem}.txt").write_text(
                "\n".join(act_lines) + "\n", encoding="utf-8")

        if exp_lines == act_lines:
            summary.append(f"СОВПАЛО   {stem}")
            continue

        differing += 1
        diff = list(difflib.unified_diff(exp_lines, act_lines,
                                         fromfile=f"expected/{stem}",
                                         tofile=f"actual/{stem}", lineterm="", n=2))
        (report_dir / f"{stem}.diff").write_text("\n".join(diff) + "\n", encoding="utf-8")
        profile = diff_stats(diff)
        for key, count in profile.items():
            total_profile[key] = total_profile.get(key, 0) + count
        changed = sum(profile.values())
        top = ", ".join(f"{k}:{v}" for k, v in
                        sorted(profile.items(), key=lambda kv: -kv[1])[:5])
        summary.append(f"РАЗОШЛОСЬ {stem}  строк: {changed}  [{top}]")

    for stem in missing:
        summary.append(f"НЕТ ВЫВОДА {stem}")
    for stem in extra:
        summary.append(f"ЛИШНИЙ    {stem}")

    header = [
        f"эталон: {expected_dir}",
        f"проверяемый вывод: {actual_dir}",
        f"режим: {'построчный (raw)' if args.raw else 'канонический'}",
        f"файлов сопоставлено: {len(shared)}, разошлось: {differing}, "
        f"без вывода: {len(missing)}, лишних: {len(extra)}",
        "",
    ]
    if total_profile:
        header.append("профиль расхождений по конструкциям:")
        for key, count in sorted(total_profile.items(), key=lambda kv: -kv[1]):
            header.append(f"  {key}: {count}")
        header.append("")

    text = "\n".join(header + summary) + "\n"
    (report_dir / "summary.txt").write_text(text, encoding="utf-8")
    print(text)
    print(f"отчёт: {report_dir}")
    return 1 if (differing or missing) else 0


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_ref = sub.add_parser("reference", help="снять эталон локальным asciidoctor")
    p_ref.add_argument("--asciidoctor", default="asciidoctor",
                       help="исполняемый файл Ruby-asciidoctor")
    p_ref.set_defaults(func=cmd_reference)

    p_norm = sub.add_parser("normalize", help="каноническая форма одного HTML-файла")
    p_norm.add_argument("path")
    p_norm.set_defaults(func=cmd_normalize)

    p_cmp = sub.add_parser("compare", help="сравнить вывод с эталоном")
    p_cmp.add_argument("--actual", required=True, help="каталог с HTML от проверяемого движка")
    p_cmp.add_argument("--expected", default=str(EXPECTED))
    p_cmp.add_argument("--report", default=str(WORK / "report"))
    p_cmp.add_argument("--raw", action="store_true",
                       help="сравнивать построчно, без канонизации")
    p_cmp.set_defaults(func=cmd_compare)

    for sp in (p_norm, p_cmp):
        sp.add_argument("--keep-comments", action="store_true",
                        help="не выбрасывать HTML-комментарии")
        sp.add_argument("--no-sort-classes", action="store_true",
                        help="не сортировать значения class")
        sp.add_argument("--no-round", action="store_true",
                        help="не округлять дробные числа в style/width/height")
        sp.add_argument("--fold-presentational", action="store_true",
                        help="свести к одной форме уже разобранные системные расхождения "
                             "(width на col/table, разрыв страницы) и увидеть остальное")

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
