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

### 2. String Toolbox

A tool window with 60+ text transformations and a live preview. Pick a transform on the left,
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

### 3. Spring Boot Property Hints *(Java IDEs only)*

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

The compare and string modules depend only on `com.intellij.modules.platform`, so they run in
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

The publish token is read from the `JETBRAINS_PUBLISH_TOKEN` environment variable and is
never stored in the repository.

```bash
# PowerShell
$env:JETBRAINS_PUBLISH_TOKEN = "<token>"
./gradlew publishPlugin
```

Generate or revoke tokens at <https://plugins.jetbrains.com/author/me/tokens>.

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
