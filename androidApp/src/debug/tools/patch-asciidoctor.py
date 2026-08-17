#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Готовит бандл Asciidoctor.js к запуску в QuickJS.

Рецепт взят из разведки (doc/research/render-stack.adoc, раздел «Спайк: бандл
запускается») и здесь только автоматизирован, чтобы результат воспроизводился, а
не правился руками.

Вход  — build/browser/index.js из npm-пакета @asciidoctor/core (чистый ESM).
Выход — самодостаточный скрипт для глобального eval: androidApp/src/debug/assets/
asciidoctor/asciidoctor.js.

Запуск (нужен только при смене версии Asciidoctor.js, в сборку не входит):

    curl -sL -o core.tgz https://registry.npmjs.org/@asciidoctor/core/-/core-4.0.9.tgz
    tar xzf core.tgz
    python androidApp/src/debug/tools/patch-asciidoctor.py \
        package/build/browser/index.js \
        shared/src/androidMain/assets/asciidoctor/asciidoctor.js
"""

import re
import sys

# Правка 3: QuickJS — голый ES без веб-API. Asciidoctor меряет длину строки в
# байтах через TextEncoder в _limitBytesize, без него конвертация падает на
# разборе атрибутов документа. Заглушка console нужна не для работы, а для
# внятной диагностики: без неё ошибка теряется.
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
// --- Конец добавленного блока ---

"""

EXPORT_RX = re.compile(r"export\s*\{([^{}]*)\}\s*;?\s*$")


def patch(source: str) -> str:
    # Правка 1: import.meta вне модуля — синтаксическая ошибка, то есть падение
    # на разборе. Бандл вычисляет через него пути файловой системы внутри
    # try/catch с комментарием «в браузере это будут пустые строки», так что
    # пустая строка воспроизводит задуманное поведение.
    source, meta_hits = re.subn(r"import\.meta\.url", "''", source)
    if meta_hits == 0:
        raise SystemExit("не найдено ни одного import.meta.url — бандл не тот")

    # Правка 2: финальный export превращается в присваивание globalThis.
    # Заменяет собой возню с загрузчиком модулей: скрипт исполняется глобально.
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
        names.append(f"{parts[1].strip()}: {parts[0].strip()}" if len(parts) == 2 else item)

    tail = "globalThis.Asciidoctor = {\n  " + ",\n  ".join(names) + "\n};\n"
    source = source.rstrip()[: match.start()] + tail

    return PRELUDE + source, meta_hits, len(names)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    with open(sys.argv[1], encoding="utf-8") as handle:
        original = handle.read()
    patched, meta_hits, exported = patch(original)
    with open(sys.argv[2], "w", encoding="utf-8", newline="\n") as handle:
        handle.write(patched)
    print(f"import.meta.url: {meta_hits}, экспортируемых имён: {exported}")
    print(f"{len(original)} -> {len(patched)} Б: {sys.argv[2]}")


if __name__ == "__main__":
    main()
