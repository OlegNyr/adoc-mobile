// Прогон корпуса через Asciidoctor.js на настольном Node — быстрый путь к выводу 4.x
// без Android-стенда. Стенд нужен отдельно: он проверяет связку «движок + QuickJS»,
// а этот скрипт — только саму реализацию Asciidoctor.js.
//
//   npm install asciidoctor@4
//   node tools/render-actual.mjs build/actual-node
//
// Набор опций обязан совпадать с тем, которым снят эталон (см. expected/_manifest.json).

import { readdir, readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const TOOLS = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.dirname(TOOLS)
// CORPUS_SRC позволяет держать node_modules вне репозитория: скрипт копируется
// туда, где установлен пакет, а корпус остаётся на месте.
const SRC = path.resolve(process.env.CORPUS_SRC ?? path.join(ROOT, 'src'))
const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, 'build', 'actual-node'))

const OPTIONS = {
  backend: 'html5',
  doctype: 'article',
  standalone: false,
  safe: 'safe',
  base_dir: SRC,
  to_file: false,
}

// API 4.x отличается от 3.x, и на момент написания привязка не проверена запуском.
// Поэтому функция преобразования ищется по нескольким возможным формам, а найденная
// форма печатается — если ни одна не подошла, это видно сразу, а не в виде пустых файлов.
async function resolveConvert () {
  const mod = await import('asciidoctor')
  const candidates = [
    ['именованный экспорт convert', mod.convert],
    ['default.convert', mod.default?.convert],
    ['фабрика default() -> convert', typeof mod.default === 'function'
      ? mod.default()?.convert?.bind(mod.default())
      : undefined],
    ['фабрика Asciidoctor() -> convert', typeof mod.Asciidoctor === 'function'
      ? mod.Asciidoctor()?.convert?.bind(mod.Asciidoctor())
      : undefined],
  ]
  for (const [shape, fn] of candidates) {
    if (typeof fn === 'function') {
      const version = mod.getCoreVersion?.() ?? mod.default?.getCoreVersion?.() ?? 'неизвестна'
      console.log(`API: ${shape}; getCoreVersion(): ${version}`)
      return fn
    }
  }
  const keys = Object.keys(mod).join(', ')
  throw new Error(`не найдена функция convert; экспорты модуля: ${keys}`)
}

const convert = await resolveConvert()
await mkdir(OUT, { recursive: true })

const files = (await readdir(SRC)).filter((name) => name.endsWith('.adoc')).sort()
let failed = 0

for (const name of files) {
  const source = await readFile(path.join(SRC, name), 'utf8')
  const target = path.join(OUT, name.replace(/\.adoc$/, '.html'))
  try {
    const html = await convert(source, { ...OPTIONS, base_dir: SRC })
    await writeFile(target, typeof html === 'string' ? html : String(html), 'utf8')
    console.log(`ok      ${name}`)
  } catch (error) {
    failed += 1
    console.log(`ОШИБКА  ${name}: ${error.message}`)
  }
}

console.log(`\nфайлов: ${files.length}, ошибок: ${failed}`)
console.log(`вывод: ${OUT}`)
console.log(`сравнение: python tools/corpus.py compare --actual ${OUT}`)
process.exit(failed ? 1 : 0)
