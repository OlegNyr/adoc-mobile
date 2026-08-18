#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Готовит бандлы Asciidoctor.js и asciidoctor-kroki к запуску в QuickJS.

Рецепт взят из разведки (doc/research/render-stack.adoc, раздел «Спайк: бандл
запускается», и doc/features/008-diagrams/research.adoc, Q1) и здесь только
автоматизирован, чтобы результат воспроизводился, а не правился руками.

Режима два, потому что бандла два и правки у них разные:

    core   — @asciidoctor/core, build/browser/index.js (чистый ESM);
    kroki  — asciidoctor-kroki, build/browser/index.js (тоже ESM).

Общее: финальный `export { … }` превращается в присваивание `globalThis` —
загрузчика модулей у движка нет, скрипт исполняется глобально. Разное: ядру
нужны пролог с веб-API и подстановка `import.meta.url`, а расширению — обёртка
в IIFE (ADR-008): оно объявляет на верхнем уровне имя `packageJson`, которое
объявляет и ядро, и два скрипта в одной глобальной области падают на
`SyntaxError: redeclaration of 'packageJson'`.

Запуск (нужен только при смене версий; в сборку Gradle не входит):

    curl -sL -o core.tgz https://registry.npmjs.org/@asciidoctor/core/-/core-4.0.10.tgz
    tar xzf core.tgz
    python androidApp/src/debug/tools/patch-asciidoctor.py core \
        package/build/browser/index.js \
        shared/src/androidMain/assets/asciidoctor/asciidoctor.js

    curl -sL -o kroki.tgz https://registry.npmjs.org/asciidoctor-kroki/-/asciidoctor-kroki-1.0.1.tgz
    tar xzf kroki.tgz
    python androidApp/src/debug/tools/patch-asciidoctor.py kroki \
        package/build/browser/index.js \
        shared/src/androidMain/assets/asciidoctor/asciidoctor-kroki.js

Результат сверяется задачей сборки :shared:verifyKrokiAsset (TC-4 фичи 008).
"""

import re
import sys

# Пролог ядра: QuickJS — голый ES без веб-API.
#
# * TextEncoder — Asciidoctor меряет им длину строки в байтах в _limitBytesize,
#   без него конвертация падает на разборе атрибутов документа;
# * console — заглушка нужна не для работы, а для внятной диагностики: без неё
#   ошибка теряется;
# * btoa — им расширение Kroki кодирует сжатые байты диаграммы в адрес
#   (KrokiDiagram.encode); без него диаграмма не превращается в изображение.
#
# Пролог один на оба бандла: ядро исполняется первым, и к моменту загрузки
# расширения глобальные объекты уже на месте.
PRELUDE = r"""// --- Добавлено patch-asciidoctor.py: окружение, которого нет в QuickJS ---
if (typeof globalThis.console === 'undefined') {
  globalThis.console = {
    log: function () {}, info: function () {}, warn: function () {},
    error: function () {}, debug: function () {}, trace: function () {}
  };
}
if (typeof globalThis.TextEncoder === 'undefined') {
  globalThis.TextEncoder = class TextEncoder {
    get encoding () { return 'utf-8' }
    encode (input) {
      const str = String(input === undefined ? '' : input);
      const out = [];
      for (let i = 0; i < str.length; i++) {
        let cp = str.charCodeAt(i);
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < str.length) {
          const low = str.charCodeAt(i + 1);
          if (low >= 0xdc00 && low <= 0xdfff) {
            cp = (cp - 0xd800) * 0x400 + (low - 0xdc00) + 0x10000;
            i++;
          }
        }
        if (cp < 0x80) {
          out.push(cp);
        } else if (cp < 0x800) {
          out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
        } else if (cp < 0x10000) {
          out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
        } else {
          out.push(
            0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f),
            0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f)
          );
        }
      }
      return Uint8Array.from(out)
    }
    encodeInto (input, dest) {
      const encoded = this.encode(input);
      const written = Math.min(encoded.length, dest.length);
      dest.set(encoded.subarray(0, written));
      return { read: input.length, written }
    }
  };
}
if (typeof globalThis.btoa === 'undefined') {
  globalThis.btoa = function (input) {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    const str = String(input);
    let out = '';
    for (let i = 0; i < str.length; i += 3) {
      const c1 = str.charCodeAt(i);
      const c2 = str.charCodeAt(i + 1);
      const c3 = str.charCodeAt(i + 2);
      if (c1 > 0xff || (!isNaN(c2) && c2 > 0xff) || (!isNaN(c3) && c3 > 0xff)) {
        throw new Error('btoa: символ вне диапазона Latin-1');
      }
      out += alphabet[c1 >> 2];
      out += alphabet[((c1 & 3) << 4) | (isNaN(c2) ? 0 : c2 >> 4)];
      out += isNaN(c2) ? '=' : alphabet[((c2 & 15) << 2) | (isNaN(c3) ? 0 : c3 >> 6)];
      out += isNaN(c3) ? '=' : alphabet[c3 & 63];
    }
    return out;
  };
}
// --- Конец добавленного блока ---

"""

EXPORT_RX = re.compile(r"export\s*\{([^{}]*)\}\s*;?\s*$")


def replace_export(source, global_name):
    """Меняет финальный `export { … }` на присваивание `globalThis.<global_name>`."""
    match = EXPORT_RX.search(source.rstrip())
    if match is None:
        raise SystemExit("не найден финальный export { ... } — бандл не тот")

    names = []
    for item in match.group(1).split(","):
        item = item.strip()
        if not item:
            continue
        parts = item.split(" as ")
        # `X as Y` в объектном литерале записывается как `Y: X`.
        names.append("{}: {}".format(parts[1].strip(), parts[0].strip()) if len(parts) == 2 else item)

    tail = "globalThis." + global_name + " = {\n  " + ",\n  ".join(names) + "\n};\n"
    return source.rstrip()[: match.start()] + tail, len(names)


def patch_core(source):
    # import.meta вне модуля — синтаксическая ошибка, то есть падение на разборе.
    # Бандл вычисляет через него пути файловой системы внутри try/catch с
    # комментарием «в браузере это будут пустые строки», так что пустая строка
    # воспроизводит задуманное поведение.
    source, meta_hits = re.subn(r"import\.meta\.url", "''", source)
    if meta_hits == 0:
        raise SystemExit("не найдено ни одного import.meta.url — бандл не тот")

    source, exported = replace_export(source, "Asciidoctor")
    return PRELUDE + source, "import.meta.url: {}, экспортируемых имён: {}".format(meta_hits, exported)


def patch_kroki(source):
    """Расширение: то же присваивание globalThis, но всё тело — внутри IIFE.

    Обёртка обязательна (ADR-008): без неё второй бандл падает на
    `SyntaxError: redeclaration of 'packageJson'` — проверено спайком, см.
    doc/features/008-diagrams/research.adoc, Q1.

    Пролога здесь нет: он один на оба бандла и приезжает с ядром.
    """
    if "import.meta" in source:
        raise SystemExit("в бандле расширения появился import.meta — рецепт устарел")

    source, exported = replace_export(source, "AsciidoctorKroki")
    wrapped = "(function () {\n" + source.rstrip() + "\n})();\n"
    return wrapped, "экспортируемых имён: {}".format(exported)


MODES = {"core": patch_core, "kroki": patch_kroki}


def main():
    if len(sys.argv) != 4 or sys.argv[1] not in MODES:
        raise SystemExit(__doc__)
    mode, source_path, target_path = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(source_path, encoding="utf-8") as handle:
        original = handle.read()
    patched, report = MODES[mode](original)
    with open(target_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(patched)
    print("{}: {}".format(mode, report))
    print("{} -> {} Б: {}".format(len(original), len(patched), target_path))


if __name__ == "__main__":
    main()
