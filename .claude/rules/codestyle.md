---
apply: always
---

# Java Code Style Guide for LLM (Prescriptive)

## 1. General Requirements
- **Encoding**: UTF-8.
- **File names**: match the name of the top-level public class, extension `.java`.
- **File structure** (in the specified order, separated by one blank line):
    1. License/copyright information (if any).
    2. Package statement – no line breaks.
    3. Imports.
    4. Exactly one top-level class.
- **Imports**:
    - No wildcard imports (`import foo.*;`).
    - Each import on a separate line, no line breaks.
    - Group order (no blank lines between groups):
        1. Third-party (not `java.*`, not `javax.*`)
        2. Special (`javax.*`)
        3. Standard (`java.*`)
        4. Static imports
    - Within a group – lexicographic ASCII sorting.
    - No static import for nested classes.
- **Characters**:
    - Only space (0x20) for indentation, tabs forbidden.
    - For special characters (`\b`, `\t`, `\n`, `\f`, `\r`, `\"`, `\'`, `\\`) use escape sequences, not Unicode.
    - For other non-ASCII characters prefer actual characters; use Unicode escapes only when necessary, with a comment.

## 2. Naming
- All identifiers: only ASCII letters/digits, underscore allowed only in constants and test methods. No prefixes/suffixes (except underscore).
- **Packages**: lowercase, no underscores (e.g., `com.example.deepspace`).
- **Classes/Interfaces/Enum/Annotations**: UpperCamelCase, typically nouns. Pattern: `^[A-Z][a-zA-Z0-9]*(?:[0-9](?:_[0-9]+)*)?$`. Test classes end with `Test`.
- **Methods**: lowerCamelCase, typically verbs. Pattern: `^(?![a-z]$)(?![a-z][A-Z])[a-z][a-z0-9]*(?:[A-Z][a-z0-9]*)*(?:_[0-9]+)*$`. Underscore allowed only in test methods to separate parts.
- **Constants** (`static final` immutable): CONSTANT_CASE (uppercase, underscores).
- **Non-constant fields**: lowerCamelCase.
- **Parameters**: lowerCamelCase, avoid single-character names in public methods. Pattern: `^[a-z]([a-z0-9][a-zA-Z0-9]*)?$`.
- **Local variables**: lowerCamelCase (even `final` ones – not constants). `_` is allowed for ignored variables.
- **Type variables**:
    - a single uppercase letter (possibly with a digit) – e.g., `E`, `T`, `T2`;
    - or a name in UpperCamelCase with suffix `T` – e.g., `RequestT`.
- **Abbreviations**: treat as ordinary words if length > 5 characters (e.g., `XmlHttpRequest`, not `XMLHTTPRequest`).

## 3. Code Formatting
### 3.1 Indentation and Braces
- Indent: 2 spaces per level.
- Braces are mandatory for all control structures (`if`, `for`, `while`, `do`, `else`), even if the body is empty or a single line.
- K&R style (Egyptian braces):
    - Opening brace on the same line.
    - Line break after the opening brace.
    - Closing brace on a separate line.
    - Line break after the closing brace only if it terminates a construct (otherwise continuation on the same line, e.g., `} else`).
- Empty blocks may be `{}` (no line break), **except** for multi-block constructs (`if/else`, `try/catch/finally`), where an empty block must occupy a separate line.

### 3.2 Line Length and Wrapping
- Maximum 120 characters. Exceptions: `package`, `import`, URLs, command lines in comments.
- Line wrapping:
    - Before binary operators (except assignment), before `.`, `::`, `&`, `|`.
    - After assignment operators (or before, but consistently).
    - Method name and opening parenthesis are not split.
    - Comma stays with the previous token.
    - Lambda arrow is not split; if the body is a single expression, a line break after `->` is allowed.
- Continuation indent: at least +4 spaces; for multiple parallel elements – consistent indent.

### 3.3 Whitespace
#### 3.3.1 Vertical
- One blank line between class members (fields, constructors, methods, etc.). Exception: blank lines between fields may be omitted for grouping.
- Blank lines are allowed for logical separation within methods.
- Do not place a blank line before the first member or after the last member.

#### 3.3.2 Horizontal
A single space is used:
- Between a keyword and an opening parenthesis: `if (`, `for (`, `catch (`.
- Between a closing parenthesis and a keyword: `} else`, `} catch`.
- Before an opening brace (except in cases of annotations with `{` and nested array initializers).
- Around binary/ternary operators, including `&` in type bounds, `|` in multi-catch, `:` in foreach, `->` in lambdas.
- After commas, semicolons, and a closing parenthesis of a cast.
- On both sides of `//` at the end of a line (optional but recommended).
- Between type and variable name: `List<String> list`.
- Inside array initializer braces – optional.
- Between a type annotation and `[]`/`...`.

**No spaces**:
- Around `.` and `::`.
- Before a colon in `case`/`default`.
- Inside empty blocks (e.g., `{}` not `{ }`).
- Inside angle brackets: `List<String>` (not `List< String >`).

### 3.4 Special Constructs
#### 3.4.1 Enum
- Constants may be on one line or each on a new line, possibly with blank lines.
- If there are methods, format as a class.

#### 3.4.2 Switch
- Indent: +2 for contents, labels at the block level, code after a label +2.
- No spaces before colon in `case`/`default`.
- Fall-through must be commented with `// fall through` (except the last group).
- `default` block is recommended (except for enums with full coverage).

#### 3.4.3 Annotations
- On a separate line before the class/method/constructor declaration (multiple per line allowed for fields).
- Without parameters – may be on the same line as the declaration (e.g., `@Override public int hashCode()`).

#### 3.4.4 Arrays
- Type with square brackets: `String[] args` (not `String args[]`).
- Initializers may be formatted block-style.

#### 3.4.5 Modifiers
- Order: `public protected private abstract default static final transient volatile synchronized native strictfp`.

## 4. Programming Practices
- **`@Override`**: mandatory for all overridden methods (except when the parent method is `@Deprecated`).
- **Exceptions in `catch`**: do not ignore. An empty block is allowed only if the parameter is named `expected` (in tests) or there is a comment.
- **Static members**: access via class name, not through an instance.
- **Finalizers**: do not use (`finalize`).
- **TODO comments**: strictly in the format `TODO:` (all uppercase, with colon).
- **Parentheses grouping**: use for clarity if misinterpretation is possible without them.

## 5. Javadoc
- Allowed only before declarations (not inside a method).
- Format:
    - Multiline or single-line (if it fits and has no block tags).
    - Paragraphs separated by a blank line (only `*`). Start each paragraph with `<p>` immediately before the text.
    - Block tags (`@param`, `@return`, `@throws`, `@deprecated`) – each on a new line; description cannot be empty; line breaks with indent +4.
- Summary fragment – a brief noun/verb phrase, capitalised and ending with a period (recommended, not enforced).

## 6. Miscellaneous
- **Implementation comments**: indent as code. Multiline `/* ... */` should align `*` on the left.
- **Numeric literals**: suffix `L` in uppercase for `long`.
- **Variables**: declare near first use (distance not exceeding 10 lines).
- **One variable per declaration**: `int a; int b;` (not `int a, b;`).

**Violation of any of these rules is not allowed.** Code must strictly adhere to the described style.