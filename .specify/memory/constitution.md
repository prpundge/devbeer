# DevBeer Constitution

The rules this plugin is built by. These are not aspirations — every one of them was
paid for by a bug, a rejected upload, or a wasted afternoon on this codebase.

**Version:** 2.0.0 · **Ratified:** 2026-08-12 · **Amended:** 2026-10-23 · **Applies to:** all work in this repository

---

## Core Principles

### I. The plugin id is immutable

`com.plugin.springprops.viewer` never changes, whatever the plugin is called this month.

The id is the Marketplace listing's identity. Changing it does not rename the listing — it
creates a *second* one and orphans the first, taking its downloads, reviews and rating with
it. The plugin has been renamed twice (Spring Boot Property Viewer → Dev Utility → DevBeer)
and the id survived both, so every rename shipped as an in-place update.

Display name, package names and bundle class names are free to change. They are invisible to
users. The id is not.

### II. `sinceBuild` is a claim about behaviour, not a preference

Lowering `sinceBuild` does not add support. It adds *installability* — and on an IDE missing
an API the plugin uses, that means `NoClassDefFoundError` on load, which is strictly worse
than not offering the plugin at all.

The floor is set by the oldest API actually used, and the reason is recorded next to the
number in `gradle.properties`. Today that is 231, and it was *measured*: the Plugin Verifier
was run against every build from 2021.3 up (`docs/COMPATIBILITY.md`). An earlier version of
this principle said 233 because the declarative inlay-hints API was believed to have arrived
in 2023.3. It arrived in 2023.1. Reasoning about when an API appeared was wrong where
measurement was right, which is the point of the corollary below.

Corollary: **run `verifyPlugin` against the declared floor explicitly.** `recommended()` does
not necessarily include it. Lowering the floor to 233 surfaced a real `NoSuchMethodError` —
the reified `project.service<T>()` helper inlines a call that did not exist before 2024.1 —
that no amount of reading would have caught.

### III. Parse, do not pattern-match

Structured text gets a parser. Not a regex, not a whitespace shuffler.

The JSON pretty-printer walked the text moving whitespace, so it could not say *why* a
document was broken and silently rewrote values. The first YAML reader reset its state on any
dedent, so in a file with more than one nested block **every key after the first block was
silently missed** — a wrong answer delivered confidently, which is the worst failure mode a
tool can have.

A parser buys three things a matcher cannot:
- **Diagnostics** — a reason with a line and column.
- **Losslessness** — `1.0`, `1e3` and duplicate keys round-trip instead of being normalised.
- **Honest failure** — malformed input raises an error rather than producing plausible garbage.

### IV. Do not clone what the IDE already does

The platform ships a directory diff and a file diff. Reimplementing them adds a maintenance
burden and no user value.

Ship the thing the IDE *cannot* do. Folder Compare exists because it is config-aware: it
reports how the **keys** differ for `.properties` and YAML pairs, and routes those to the
key-level view instead of a text diff — because a text diff of YAML against `.properties`
tells you nothing.

When a feature overlaps something built in, state what the delta is or do not build it.

### V. Secrets live outside the repository, always

The Marketplace publish token is read from the environment, a gitignored `.env`, or
`~/.gradle/gradle.properties` — never from a tracked file, never from a command line that
lands in shell history.

Supporting rules:
- A placeholder is never treated as a value. A half-filled `.env` fails with a clear message
  rather than sending `<paste-your-token-here>` to the Marketplace.
- Tooling reports a token's **source and length**, never its value (`gradlew checkPublishToken`).
- A token that has appeared in a chat, an issue, or a log is burned. Rotate it.
- Scan staged content before every commit; verify the branch **as it exists on the remote**
  after every push.

### VI. Report what happened, not what was hoped for

Every claim in a changelog, a store description or a commit message must be checked against
the artifact.

`@ConfigurationProperties` sat in the 1.0.0 store description for a full release without
being implemented. A count of "60+ transformations" is a guess; 74 is a fact you can get from
the source. When a build produces two zips, check which one is about to be published — one of
them was a stale build containing a known crash.

Where something is untested, say so in the same breath as the result. `FolderDiffEngine`'s
tree walk and hashing are not covered by unit tests, and the README, the commit and the PR
all say so.

### VII. The assistant proposes; the user disposes

The AI assistant may read, edit and run things in the project — that is what makes it useful —
but never on its own authority.

- **Every action is proposed before it happens.** A read names the file, an edit is shown as a
  diff, a command is shown verbatim, and nothing happens until the user approves it or has
  pre-approved that category of action in settings. A rejection carries the user's feedback back
  to the model.
- **Every edit is reversible twice over.** It is applied as one undoable change, and it is
  preceded by a checkpoint of the workspace taken outside the project's own version control.
- **The model never navigates or writes unverified.** A location becomes clickable only after
  the plugin has confirmed it exists; an edit is applied only after the plugin has confirmed the
  file still reads as the model believed. (Kept from 2.3.0.)
- **Off by default, and silent when off.** No provider is contacted, no process is started and
  no index is built until the user turns the assistant on and configures it.
- **The repository is never sent.** Only ranked, budgeted, redacted fragments are, and
  `.rooignore` plus the built-in exclusion list decide what may not be read at all.
- **Capabilities that need an external program are reached over its protocol, never bundled.**
  Tool servers, a vector store, a browser and git are driven over stdio, HTTP, a remote-debugging
  protocol and the command line. The platform's own bundled libraries count as platform. The
  no-third-party-dependency rule below stays intact.

This supersedes the 2.3.0 design rule in `docs/AI_ASSISTANT.md` §10 — "there is no autonomous
agent: no multi-file edits, no command execution". That rule was right for a read-mostly
assistant. The owner's decision of 2026-10-23 to adopt the full Roo Code behaviour model
(`specs/001-roo-code-parity/spec.md`) replaces *the assistant never acts* with *the assistant
never acts without consent*. That is the harder rule to keep, because every tool must honour
it, and it is why this principle exists.

---

## Platform Constraints

- **Target:** IntelliJ Platform, built against `platformVersion` in `gradle.properties`.
- **Range:** `pluginSinceBuild`–`pluginUntilBuild`, currently 231–243.\*.
- **Java is optional.** `com.intellij.modules.java` is an optional dependency behind
  `devutility-java.xml`, so the compare, folder, JSON and string modules run in every
  JetBrains IDE and only the Spring hints require a Java-capable one.
- **No third-party runtime dependencies.** The `.properties`, YAML and JSON readers are all
  hand-written. A plugin that drags in a library risks a version clash with whatever the
  platform already loads.
- **Kotlin stdlib comes from the platform** (`kotlin.stdlib.default.dependency=false`).
- **Prefer stable Java APIs over Kotlin convenience helpers** at the platform boundary.
  Reified extensions inline calls that may not exist on older IDEs; `getService(Class)` does
  not (see Principle II).

## Quality Gates

Before any upload to the Marketplace:

1. `./gradlew test` — all green.
2. `./gradlew verifyPlugin` — Compatible on every IDE in the range, **including the declared
   floor**. Deprecation warnings are acceptable when the replacement does not exist on the
   floor; the reason goes in a comment at the call site.
3. Inspect the built artifact: descriptor id/name/version, `idea-version` range, icons
   present, no stale files in `build/distributions/`.
4. Confirm the fix is in the shipped bytecode, not just the source, when fixing a
   platform-compatibility bug.

The same gates run in CI before any automated upload (`.github/workflows/publish-beta.yml`): a
push to `main` publishes `<pluginVersion>-beta.<run>` to the Marketplace **beta** channel, and
only after `test` and `verifyPlugin` have passed. The stable channel is only ever published
deliberately — by hand, or by choosing it explicitly when running the workflow.

Before any push:

1. Scan staged content for credential material.
2. Confirm `.env` is neither staged nor tracked.
3. Branch and open a PR; `main` is not pushed to directly.
4. Verify the remote branch after the push, not just the local one.

## Governance

This constitution outranks convenience. A rule is amended by editing this file and recording
what changed and why — not by making an exception in passing.

Amendments bump the version above: MAJOR for removing or redefining a principle, MINOR for
adding one, PATCH for clarification.

## Amendments

| Version | Date | What changed | Why |
|---|---|---|---|
| 2.0.0 | 2026-10-23 | Added Principle VII and superseded the "no autonomous agent" rule of `docs/AI_ASSISTANT.md` §10. Corrected the floor in Principle II and Platform Constraints from 233 to 231. Recorded the CI gate under Quality Gates. | The owner decided to adopt the full Roo Code behaviour model (`specs/001-roo-code-parity`). MAJOR because it redefines what the AI layer is allowed to do. |
| 1.0.0 | 2026-08-12 | Ratified. | — |
