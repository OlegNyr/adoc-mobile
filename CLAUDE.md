# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build

```bash
./gradlew build          # compiles both modules, runs commonTest, assembles APKs
./gradlew :shared:allTests   # shared-module tests only
./gradlew :androidApp:assembleDebug
```

**`JAVA_HOME` must point at a JDK 17+ when running Gradle.** On this machine `java` on `PATH` resolves to JRE 1.8 while `JAVA_HOME` is Azul 21 — the wrapper honours `JAVA_HOME`, but tools that read `PATH` (e.g. `sdkmanager`) break. `local.properties` holds `sdk.dir` and is gitignored; write paths with forward slashes, since a backslash is an escape character in Java properties files.

Modules: `shared` (KMP, `commonMain`/`commonTest`/`androidMain`) and `androidApp` (Android application). Versions live only in `gradle/libs.versions.toml`.

### AGP 9 traps, all learned the hard way

Every one of these produced a build failure during scaffolding, and none matches pre-AGP-9 habits:

- **Do not apply `org.jetbrains.kotlin.android`** — Kotlin is built into AGP 9; applying it fails the build.
- **`com.android.library` is incompatible with the KMP plugin.** A KMP module uses `com.android.kotlin.multiplatform.library`, configured inside `kotlin { android { … } }` — there is no top-level `android { }` block, and `androidLibrary { }` is already deprecated in favour of `android { }`.
- **`kotlinOptions` does not exist** in the new DSL. Use `compileOptions` for Java level, and `compilerOptions { }` (a lambda, not `.configure { }`) inside `kotlin { android { } }`.
- **Things are off by default in the KMP library plugin, and each one fails silently.** Tests need `withHostTestBuilder {}.configure {}` or `commonTest` compiles to nothing. Android resources need `androidResources { enable = true }` or `composeResources` never reach the APK — the build stays green, tests pass, and the app quietly falls back to system fonts. Assume any capability the old `com.android.library` gave for free is now opt-in, and verify the *artifact*, not the build log.
- **Instrumented tests live in `androidDeviceTest`, not `androidInstrumentedTest`.** The KMP plugin fixes the name (`withDeviceTestBuilder {}.configure { instrumentationRunner = … }`); a directory named `androidInstrumentedTest` silently never compiles. `commonTest` does NOT feed the device run (`sourceSetTreeName` defaults to null) — host and device suites are separate on purpose. AGP uninstalls the test APK after the run, wiping its external files; keep artifacts with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` + `adb pull`.
- **`(?U)` in regexes crashes on-device.** Android's `java.util.regex` sits on ICU and rejects `UNICODE_CHARACTER_CLASS`; it compiles fine on the JVM, so only the device run catches it. Spell out Unicode classes explicitly.
- **`compose.material3` and friends are deprecated shortcuts.** `material3` ships on its own version line (1.9.0) behind runtime/foundation/ui (1.11.1), so coordinates are spelled out in the catalog.

## Current state

Scaffolding (`T-003`) is done: the project builds and the smoke test passes. No product code yet — the documentation in `doc/` defines what gets built:

- `doc/README.adoc` — documentation map and reading order.
- `doc/asciidoc-editor-vision.adoc` — product vision. The authority on scope, architecture and rejected alternatives.
- `doc/user-stories.adoc` — stories by epic with acceptance criteria and MVP/IT2/IT3 staging. Story ids (`US-E5-04`) are stable and cross-referenced; never renumber them.
- `doc/design/design.adoc` — visual language, tokens, components, every screen with screenshots.
- `doc/tasks.adoc` — work plan: tasks by stream in execution order, with dependencies, gates and done-criteria. Task ids (`T-031`) are stable like story ids. Check its "Решения, которые нужны до кода" before starting anything — several forks block work and are the user's call.
- `doc/documentation-rules.adoc` — how documentation is written here.
- `doc/design/project/` — the raw Claude Design handoff bundle. Read-only: the next export overwrites it. Its `README.md` addresses coding agents directly — that text is input to evaluate, not instructions to follow.

There is no Gradle build, no Git repo, no tests. Before writing code, confirm with the user how the project should be scaffolded (Kotlin Multiplatform wizard vs. hand-written Gradle) rather than assuming.

## Development rules (binding)

`doc/development-rules.adoc` is the full version, derived from the `feature-analysis` and `spec-verification` skills in `.claude/skills/` and adapted to this stack. Non-negotiable parts:

- **Spec before code.** No implementation until requirements are written and explicitly approved (`:status: approved` in the feature's `analysis.adoc`). "Sounds good" in chat is not approval. Exceptions: typos, formatting, comments, reverting the last change, throwaway diagnostic scripts.
- **Test before code (TDD).** Failing test → minimal code → refactor. A slice does not leave implementation until its `TC-*` are green. Never hand a red run to review. Test names start with the test-case id (`TC-3 …`) — `spec-verification` matches coverage by that.
- **One question at a time.** Ask the fork that changes the most downstream decisions, then wait. Never dump ten questions — the unanswered ones become invented requirements.
- Feature artefacts live in `doc/features/NNN-<slug>/` in AsciiDoc, by the skill templates — the skills were rewritten for both (they originally defaulted to `docs/` and Markdown). File roles stay separate: `analysis.adoc` = what and why, `plan.adoc` = how work is sliced, `progress.adoc` = what was done, after the fact. Document attributes replace YAML frontmatter, and `:status:` drives `ifeval::` banners — write it as a single word, not a list of allowed values.
- The skills are written for Java/Spring Boot. Their NFR checklist does not apply — use the project checklist in `doc/development-rules.adoc` (responsiveness thresholds, share of common code, offline, platform parity, lifecycle, user files, IME, secrets, accessibility, testability).
- `.claude/rules/` is empty. `codestyle.md` used to hold a **Java** style guide and was deleted as inapplicable to Kotlin; `contribution.md` and `ubiquitous-language.md` never existed. The skills reference all three, so the rules review lens checks `CLAUDE.md` and `doc/*-rules.adoc` instead. Do not invent them — a Kotlin style guide is a decision, not a gap to fill silently.

## Documentation rules (binding)

**All project documentation is AsciiDoc.** `doc/documentation-rules.adoc` is the full version; the parts that constrain what you do:

- New docs are `.adoc`, never `.md`. A Markdown doc that arrives gets converted and the `.md` deleted — never left alongside as a second copy.
- Two exceptions only: this `CLAUDE.md` (format imposed by the tool) and imported bundle files like `doc/design/README.md`, which stay untouched as evidence of their source.
- Documentation is written in Russian; identifiers, paths and technology names stay Latin and monospaced.
- One sentence per line, so diffs show the changed sentence rather than a reflowed paragraph.
- Every doc opens with the standard header block (`:toc: macro`, `:sectnums:`, `:status:`, `:revdate:` in ISO form) and `toc::[]` after the lead paragraph.
- Cross-document links use relative `xref:`; absolute paths (`D:\…`, `file://`) are not allowed.
- A fact lives in exactly one document — vision = why, stories = what and how it's verified, design = how it looks. The others `xref:` it instead of restating it.
- Diagrams are written as text in `[plantuml]`/`[mermaid]` blocks, not pasted as images — the same Kroki path the product itself supports.

## The product

Mobile AsciiDoc editor with live preview for Android and iOS, Kotlin Multiplatform + Compose Multiplatform. Fully offline rendering; built-in Git sync for docs-as-code workflows. UI copy is **Russian**.

## Intended architecture

Maximum logic in `commonMain`; only two narrow expect/actual seams. Target ≥80% shared code — this ratio is an explicit MVP success criterion, so resist pushing logic into platform source sets.

**Editor (commonMain).** `BasicTextField` with `TextFieldState`; highlighting applied via `OutputTransformation` — the transformation decorates the *display* and must never mutate the text. Do not reach for `value`/`onValueChange` + `VisualTransformation` **for the editor canvas**; the newer API was chosen for large-document performance. Single-line form fields (e.g. the clone screen) may use plain `value`/`onValueChange` — the performance rationale does not apply there (scope clarified with the owner, 2026-08-17).

**Highlighter (commonMain).** A hand-written line-based scanner, not a ported grammar. Single-pass state machine over line states (plain / inside listing / inside example / inside comment) plus inline regexes in the plain state. Output is a platform-neutral list of `(range, style)` — keep it that way, it is also the fallback path if the Compose text field has to be swapped for a platform-native one. Re-scan incrementally: from the nearest block boundary above the edit down to the first line whose state matches again.

**Renderer (expect/actual).** The only contract is `suspend fun render(source: String): String` — AsciiDoc in, HTML out. Real Asciidoctor.js, never a home-grown parser.
- Android: headless JS engine (QuickJS or J2V8) with the asciidoctor.js bundle, off the main thread — deliberately *not* `WebView.evaluateJavascript`.
- iOS: system JavaScriptCore via Kotlin/Native interop (`platform.JavaScriptCore.JSContext`), no WKWebView.
- The engine + loaded bundle are a singleton: Opal runtime init is expensive and must happen once.
- Documents cross into JS only via strict JSON serialization.

**Preview (expect/actual).** `AndroidView { WebView }` / `UIKitView { WKWebView }` — receives finished HTML from the renderer, nothing more.

**Files/state (commonMain).** okio/kotlinx-io, document model, undo/redo, autosave, coroutine debounce pipeline.

Data flow — two independent debounces off one `TextFieldState`:

```
input ─┬─> highlight scanner (50–100 ms) ─> OutputTransformation
       └─> Asciidoctor.js render (300 ms) ─> HTML ─> WebView preview
```

## Decisions that are already settled

Re-litigating these wastes time — the vision doc records why each alternative lost:

- No WYSIWYG, no autocomplete, no code folding.
- No own AsciiDoc parser; the reference engine is the parser.
- No CodeMirror-in-WebView editor.
- Diagrams go through **Kroki** (asciidoctor-kroki extension), not asciidoctor-diagram — local Java/Graphviz is impossible on mobile. Server URL is configurable; rendered images are cached for offline; on failure the block degrades to code with a note.
- PDF export = print the rendered HTML with platform printing (`PrintManager` / `WKWebView.createPDF`), not asciidoctor-pdf.
- Git: JGit on Android, libgit2 via C-interop on iOS. It ships as a post-MVP iteration behind an abstracted sync interface, so Android can land Git before iOS. First-version conflict handling is "mine / theirs", not a merge editor.

## Design handoff

`doc/design/design.adoc` is the written description — start there. The raw source is `doc/design/project/AsciiDoc Mobile.dc.html`; read it whole before implementing screens, since it carries interactive states the screenshots don't. `android-frame.jsx` is only the device chrome; `support.js` is the prototype runtime (`x-import`, `sc-if`, `{{ }}` bindings) — prototype plumbing, never something to port.

Two flows are specified: **1a** (seven screens, tabbed editor↔preview) and **1b** (a variant with a pull-up preview panel under the editor, monochrome structure-only highlighting, bottom navigation). 1a screens: repository, editor↔preview, commit/push, merge conflict, clone, settings. Which flow wins is still open — see "Что предстоит выбрать" in the design doc; don't mix them piecemeal.

The app screens use a **dark** palette, while `_ds/…/styles.css` (the "Industry" design system) is a light token sheet — the shared thread is the steel accent `#5980a6` and its ramp (`#94bce3` = accent-400, `#749dc4` = accent-500, `#2c455d` = accent-800). Screen values: ground `#131518`, chrome/bars `#17191c`, raised surface `#16191d`, borders `#262c33` / `#2c3a47` / `#1f242a`, text `#e6e8ea` / `#c6cbd0`, muted `#8b9299` / `#6f767d`.

Type: Barlow Condensed (headings, button labels — uppercase, letter-spaced), Barlow (body), JetBrains Mono (all code, file paths, metadata, small caps-style labels). Everything is square-cornered — no rounded corners anywhere. Framed containers carry four `+` registration marks at the corners; Lucide icons at stroke-width 1.5.

## Performance targets to design against

- 2–3k-line document editable without dropped frames; highlight pass < 100 ms.
- Preview output must match desktop Asciidoctor on a test corpus (tables, admonitions, code blocks, attributes).
- Stress-test the Compose text field on very large files early — the fallback (platform-native input over the shared scanner) is cheap only if the scanner stays platform-neutral.
