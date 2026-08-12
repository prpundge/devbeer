# DevBeer

An IntelliJ Platform plugin with the small utilities you keep leaving the IDE for.

Formerly *Spring Boot Property Viewer*. The Spring inlay hints are still here — the plugin
just grew a home for the other things.

---

## Modules

### 1. Property & YAML File Compare

Compare two configuration files key by key. Both sides are flattened into the same dotted
key space first, so a `.properties` file can be compared against a YAML file directly.

| | |
|---|---|
| **Open** | `Tools → DevBeer → Compare Property Files…`, or the *Property Compare* tool window |
| **From the Project view** | select one or two files → right-click → `DevBeer → Compare Property Files` |

Once open:

- **Interactive table** — every key with its left value, right value and a status of
  *Identical*, *Different*, *Only left* or *Only right*, colour-coded by row.
- **Filter** by key or value, or tick *Only differences*.
- **Double-click** a value cell to jump to that key's line in that file. Double-clicking the
  key column goes to whichever side has it.
- **Right-click** a row to copy the key or either value, or to jump to a specific side.
- **Side-by-Side (normalized)** opens the IDE's own diff editor over both files rendered into
  the same sorted `key = value` shape — this is what makes a YAML file and a `.properties`
  file line up row for row.
- **Side-by-Side (raw files)** opens the same editor over the literal file contents, fully
  editable, for when you want the real text diff.
- **Ignore case** and **Ignore surrounding whitespace** relax the value comparison.
- **Copy report** / **Save report…** produce a grouped plain-text summary. *Save report…*
  opens it as a scratch file.

Unsaved editor changes are compared, not just what is on disk.

### 2. Folder Compare

Compares two directory trees file by file. `Tools → DevBeer → Compare Folders…`, or select two
folders in the Project view → right-click → `DevBeer → Compare Folders`.

The IDE already has a directory diff, so the reason this exists is that it understands config
files: for `.properties` and YAML pairs an extra column reports **how the keys differ** —
`2 changed, 1 only left` — without opening anything.

- Status per file: identical / different / only left / only right, colour-coded
- Compares by **content hash**, not size and timestamp. Turn contents off for a fast pass that
  treats same-size files as equal.
- Double-click a config pair → key-level Property Compare. Double-click anything else →
  side-by-side diff. A text diff of YAML against `.properties` tells you nothing, which is why
  the two cases are routed differently.
- Skips `build`, `out`, `target`, `node_modules`, `.git`, `.idea`, `__pycache__`, `dist` and
  similar by default
- Runs in the background with a cancellable progress bar; capped at 50,000 files, and the
  summary says so when it truncates
- Copy or open a grouped report

### 3. JSON Formatter

`Tools → DevBeer → JSON Formatter`, or the *JSON Formatter* tool window.

Backed by a real recursive-descent parser rather than whitespace shuffling, which is what makes
the rest possible:

- **Diagnostics** — a syntax error reports its reason with a line and column, and the caret
  moves to the offending character. The old whitespace-based formatter could only produce
  mangled output.
- **Lossless** — numbers keep their source text and objects keep an ordered entry list, so
  `1.0`, `1e3`, `0.1000` and duplicate keys survive a round trip instead of being normalised.
- **Lenient mode** — accepts `//` and `/* */` comments, trailing commas and single-quoted
  strings, the dialect `tsconfig.json` and hand-edited config are written in.
- Pretty print with 2 spaces, 4 spaces or tabs; minify; sort keys recursively.
- Editor: right-click → **Format JSON** reformats the selection, or the whole file, as one
  undoable change.

### 4. String Toolbox

A tool window with 74 text transformations and a live preview. Pick a transform on the left,
type or paste on the top right, read the result on the bottom right.

| Category | Examples |
|---|---|
| **Case** | camelCase, PascalCase, snake_case, CONSTANT_CASE, kebab-case, dot.case, Title Case, Sentence case, swap case |
| **Encode / Decode** | Base64 (standard and URL-safe), URL, HTML entities, hex, Unicode escapes |
| **Hash** | MD5, SHA-1, SHA-256, SHA-512, CRC32 |
| **JSON / XML** | pretty print, minify, escape/unescape as a Java string, XML reindent, JWT decode |
| **Lines** | sort (A→Z, Z→A, ignore case, natural), reverse, shuffle, dedupe, drop blanks, trim, number, join, split, quote, SQL `IN` list |
| **Text** | reverse, collapse whitespace, ROT13, extract URLs / emails / numbers, word frequency, statistics |
| **Config files** | `.properties` ⇄ YAML, sort by key |
| **Time & IDs** | epoch ⇄ ISO-8601, current timestamp, UUID generation |

Case transforms apply per line, so a pasted list of identifiers converts in one go.

Buttons wire it to the editor: **Load editor selection**, **Replace editor selection**, and
**Output → Input** to chain transformations.

In the editor itself:

- right-click → **Transform Selection…** — a searchable popup; the chosen transform rewrites
  the selection as a single undoable command;
- right-click → **Send Selection to String Toolbox**.

Report-style transforms (statistics, word frequency, JWT decode) are hidden from
*Transform Selection…* — replacing your code with a statistics dump is never what you meant.

### 5. Spring Boot Property Hints *(Java IDEs only)*

Resolved property values shown as inlay hints next to the code.

```java
@Value("${server.port:8080}")        // → default=8080 prod=9090
private int port;

@ConfigurationProperties(prefix = "app.mail")   // → default=4 keys prod=2 keys
public class MailProperties {
    private String fromAddress;      // → default=noreply@example.com
}
```

- `@Value` resolves every `${…}` placeholder in the expression, one entry per profile.
- Unconfigured keys fall back to the inline `${key:default}` value, or read *unresolved*.
- `@ConfigurationProperties` reports how many keys bind under the prefix; each field in the
  class resolves its own value.
- Relaxed binding is honoured — a field named `maxPoolSize` matches `max-pool-size`,
  `max_pool_size` and `maxpoolsize`.
- Profiles come from the file name: `application-prod.yml` → `prod`, `application.yml` →
  `default`.

Toggle the hints under `Settings → Editor → Inlay Hints → Values`.

---

## Compatibility

The compare, folder, JSON and string modules depend only on `com.intellij.modules.platform`, so they run in
every JetBrains IDE. The Spring hints are behind an optional dependency on
`com.intellij.modules.java` and simply do not load where Java support is absent.

Supported builds are set in `gradle.properties` (`pluginSinceBuild` / `pluginUntilBuild`).
The current range is **233 – 243.\*** — IDE **2023.3 through 2024.3**.

### Why the floor is 2023.3 and not earlier

`sinceBuild` is not a free dial. The Spring inlay hints are built on the declarative
inlay-hints API (`codeInsight.declarativeInlayProvider`), which JetBrains **introduced in
2023.3**. Lowering the number below that would let the plugin install on IDEs where it then
fails to load with `NoClassDefFoundError` — worse than not offering it at all.

Reaching further back means real work, not a config change:

| Target | What it costs |
|---|---|
| 2022.3 | Rewrite the inlay hints against the pre-declarative API; replace `ContentFactory.getInstance()` (added 2022.3) and `Service.Level` (added 2021.3) |
| 2021.x | The above, plus shims for `ActionUpdateThread` (added 2022.2) |
| 2020.x | All the above, **and** abandon the IntelliJ Platform Gradle Plugin 2.x — its own minimum target is 2022.3 — rebuilding on the legacy 1.x plugin |

Run `./gradlew verifyPlugin` after changing the range; it verifies the declared floor
explicitly, so a too-low `sinceBuild` fails locally instead of at upload.

---

## Building

```bash
./gradlew buildPlugin      # -> build/distributions/dev-utility-<version>.zip
./gradlew test             # parser and transform unit tests
./gradlew runIde           # sandbox IDE with the plugin installed
./gradlew verifyPlugin     # JetBrains Plugin Verifier, same check the Marketplace runs
```

### Publishing

The publish token never lives in the repository. Copy the template and fill it in:

```bash
cp .env.example .env
```

Then open `.env` and replace the placeholder:

```
JETBRAINS_PUBLISH_TOKEN=perm-xxxxx.xxxxx.xxxxxxxx
```

Confirm it is picked up — this prints the source and length, never the token itself:

```bash
./gradlew checkPublishToken
# Publish token found via .env file (57 chars).
```

Then publish:

```bash
./gradlew publishPlugin
```

Generate or revoke tokens at <https://plugins.jetbrains.com/author/me/tokens>.

**Where the token can come from**, in priority order:

| Source | Notes |
|---|---|
| `JETBRAINS_PUBLISH_TOKEN` environment variable | Wins over everything. Best for CI. |
| `.env` in the project root | Gitignored. Best for local use. |
| `jetbrainsPublishToken` in `~/.gradle/gradle.properties` | Lives outside the repo entirely, so it cannot be committed even by accident. |

The untouched placeholder is deliberately *not* treated as a token, so a half-finished `.env`
fails with a clear message instead of sending `<paste-your-token-here>` to the Marketplace.

> **Never commit `.env`, and never paste a token into a chat, an issue or a CI log.** A token
> that has been shared anywhere should be revoked and reissued — it grants publish rights to
> every plugin on the account.

To support newer IDEs, raise `platformVersion` and `pluginUntilBuild` in `gradle.properties`
together, then re-run `./gradlew verifyPlugin` before uploading.

---

## Notes on the parsers

YAML is read by a hand-written indentation parser rather than a third-party engine — no extra
dependency, and it keeps line numbers so a diff row can navigate back to its source. It covers
nested maps, sequences (indexed as `key[0]`), sequences of maps, multi-document files, block
scalars (`|` and `>`) and flow collections (`[a, b]`, `{k: v}`).

It does **not** expand anchors and aliases (`&a` / `*a`), merge keys (`<<`) or explicit tags;
those are kept as literal text. `.properties → YAML` conversion quotes values conservatively
and drops comments.
