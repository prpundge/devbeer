# DevBeer AI Assistant

A lightweight, repository-aware assistant inside the IDE. It finds the relevant code, explains
errors, says exactly where to fix them, and offers a reviewable fix.

It is **off by default**, needs no database, no cloud account and no vector store, and the
rest of DevBeer works whether or not it is configured.

---

## 1. Architecture

```
User request / error
        │
        ▼
  ContextResolver ──────► IntelliJ word index + PSI
        │                 (no second index, no full scan)
        ▼
  ContextRanker ────────► deterministic scoring, hard budget
        │
        ▼
  ContextSanitizer ─────► secrets redacted here, once, for every path
        │
        ▼
   PromptBuilder ───────► labelled sections, small system prompt
        │
        ▼
   LlmProvider ─────────► Ollama │ OpenAI-compatible   (HTTP only)
        │
        ▼
 AiResponseParser ──────► structured result, degrades to raw text
        │
        ▼
 SourceLocationResolver ► every location verified against the project model
        │
        ▼
 FixSuggestionValidator ► preview → user approval → undoable write
```

Two rules shape the whole design:

**The repository is never sent.** Only ranked, budgeted fragments are. A ten-file project and
a hundred-thousand-file project produce the same order of payload.

**The model never decides where to navigate or what to edit.** It proposes; the plugin
verifies against the real project model before anything becomes clickable or applicable.

## 2. Files added

| Package | Files | Role |
|---|---|---|
| `ai/model` | `AiModels.kt` | Requests, responses, results, parsed errors — no IntelliJ or network types |
| `ai/llm` | `LlmProvider.kt`, `HttpJson.kt`, `Providers.kt` | Provider abstraction, transport, Ollama + OpenAI-compatible |
| `ai/settings` | `DevBeerAiSettings.kt`, `DevBeerAiConfigurable.kt` | Persistence, keychain credentials, settings UI |
| `ai/context` | `ContextExclusionService.kt`, `ContextSanitizer.kt`, `ContextRanker.kt`, `ContextResolver.kt`, `JavaSupport.kt`, `JavaSymbolEnricher.kt` | Retrieval, ranking, budget, privacy |
| `ai/error` | `ErrorParser.kt`, `SourceLocationResolver.kt` | Error → structure → real location |
| `ai/prompt` | `PromptBuilder.kt` | System prompts and section assembly |
| `ai/parse` | `AiResponseParser.kt` | Structured reading of replies |
| `ai/fix` | `FixSuggestionValidator.kt`, `FixApplier.kt` | The safety model |
| `ai/flow` | `CodeFlowAnalyzer.kt` | Call flow resolved from PSI, narrated by the model |
| `ai/action` | `AiActions.kt` | Four editor/menu actions |
| `ai/ui` | `AiToolWindow.kt` | Tool window |
| `ai` | `AiService.kt` | Orchestration, caches, background execution |

**Files modified:** `plugin.xml` (tool window, services, configurable, actions), `build.gradle.kts`
(IntelliJ test framework for the fixture tests). No existing module was touched.

**Icons added:** `ai13.svg`, `ai13_dark.svg`.

## 3. Dependencies added

**None.**

| Need | Chosen | Why not a library |
|---|---|---|
| HTTP | JDK `java.net.http.HttpClient` | Java 17 toolchain already; no client to bundle |
| JSON | `com.devutility.json.JsonParser` | The repo grew its own parser in 2.2.0 — §18's "only if the repository already has one" is satisfied |
| Secure storage | IntelliJ `PasswordSafe` | Platform provides it |
| Search | IntelliJ word index + PSI | A second index would be the thing this design exists to avoid |

No LangChain, no Spring AI, no vector database, no agent framework.

## 4. IntelliJ APIs used

`PsiSearchHelper.processCandidateFilesForText` (index-backed, language-agnostic) ·
`PsiShortNamesCache`, `JavaPsiFacade`, `PsiTreeUtil` (Java only, isolated) · `FilenameIndex` ·
`GlobalSearchScope` · `ReadAction` / `WriteCommandAction` · `Task.Backgroundable` +
`ProgressIndicator` · `PersistentStateComponent` · `PasswordSafe` + `CredentialAttributes` ·
`Configurable` · `ToolWindowFactory` · `OpenFileDescriptor` · `Logger`.

### Working without Java

DevBeer declares `com.intellij.modules.java` **optional**, so the AI layer must run in
WebStorm and PyCharm too. Java PSI cannot simply be null-checked: a class whose method
signatures mention `PsiClass` fails to *link* when the module is absent, throwing
`NoClassDefFoundError` before any check could run.

So the isolation is at class granularity. `JavaSymbolEnricher` is the only class that names a
Java PSI type, it is instantiated reflectively, and only after `Class.forName` confirms
`JavaPsiFacade` is present. Everywhere else uses the platform word index, which works in every
IDE. Without Java, search is slightly less precise; nothing breaks.

**This was verified, not assumed.** Running the Plugin Verifier against PyCharm Community
2023.3 reported 27 problems, every one inside `JavaSymbolEnricher` or the pre-existing
`SpringValueInlayProvider` — the two classes meant to reference Java PSI, each guarded at
runtime. No other AI class appeared.

PyCharm is *not* in the routine verification list, because the verifier cannot distinguish a
guarded reference from a real defect and would therefore fail permanently, making the gate
meaningless. The invariant is enforced instead by `JavaIsolationTest`, which reads the
compiled constant pool and fails only on an actual regression. Re-add the `ide(PyCharmCommunity, …)`
line in `build.gradle.kts` to inspect the full report by hand.

## 5. Configuration

`Settings → Tools → DevBeer AI Assistant`

| Field | Notes |
|---|---|
| Enable AI Assistant | **Off by default.** Nothing is contacted until this is on |
| Provider | Local LLM · OpenAI Compatible |
| Endpoint / Model | Per provider |
| API key | OS keychain via PasswordSafe. Never in `devbeer-ai.xml`, never in the project |
| Request timeout | Default 60 s |
| Maximum files / characters | Default 10 files, 50,000 characters |

### Ollama

```bash
ollama serve
ollama pull qwen2.5-coder:7b
```

Then: Provider **Local LLM**, endpoint `http://localhost:11434`, model `qwen2.5-coder:7b`.
*Test connection* probes `/api/tags`. Nothing leaves the machine.

### OpenAI-compatible

Provider **OpenAI Compatible**, endpoint `https://api.openai.com/v1` (or any gateway speaking
the same API), your model name, and the key. The endpoint is not probed on save — an
unauthenticated request would be a call you did not ask for.

## 6. Features

| Feature | Entry point |
|---|---|
| Ask DevBeer | `Tools → DevBeer → AI Assistant`, or the *DevBeer AI* tool window |
| Explain Selection | Editor right-click → `DevBeer → Explain Selection` |
| Suggest Improvement | Editor right-click → `DevBeer → Suggest Improvement` |
| Repository Search | Ask a question; results appear as verified, clickable locations |
| Analyze Error | Editor right-click → `DevBeer → Analyze Error`, or paste into the tool window |
| Code Flow | Type a method name and press **Explain flow** in the tool window |

### How Code Flow differs from the rest

The chain is built by IntelliJ, not by the model. `CodeFlowAnalyzer` resolves the method,
walks resolved call expressions breadth-first (depth ≤ 6, bounded by the method budget) and
verifies each location; the model is then handed that list as fact and asked only to narrate
it — *"Do not add nodes that are not listed."*

The printed chain comes from PSI even if the narration drifts, so every arrow in

```
OrderService#placeOrder()   OrderService.java:4
    ↓
OrderRepository#save()      OrderRepository.java:2
```

is a place that exists and opens. If no such method is found, the answer is "no method named
X" rather than an invented flow. Needs Java support; in other IDEs the button explains that
and points at Ask.

## 7. Test coverage

**147 AI tests** of 214 total.

| Suite | Tests | Covers |
|---|---|---|
| `ContextExclusionServiceTest` | 10 | Generated dirs, secret files, binaries, size ceiling, Windows paths |
| `ContextSanitizerTest` | 12 | Assignments, provider key shapes, PEM blocks, JWTs, URL credentials |
| `ContextRankerTest` | 14 | Scoring weights, determinism, all three budget ceilings, empty project |
| `ErrorParserTest` | 14 | Java, Spring, javac, Gradle, kotlinc, tests, builds, unknown, caps |
| `AiResponseParserTest` | 21 | Extraction from prose, malformed replies, confidence, unverified locations |
| `FixSuggestionValidatorTest` | 15 | Missing file, out-of-range, stale text, no-op, multi-line |
| `PromptBuilderTest` | 13 | Sections, redaction before transmission, budget, failure paths |
| `AiPipelineIntegrationTest` | 11 | Question→answer, error→fix, degraded paths |
| `JavaIsolationTest` | 5 | Bytecode scan: no class outside `JavaSymbolEnricher` touches Java PSI |
| `ContextResolverFixtureTest` | 16 | Real project via IntelliJ fixtures — index search, file resolution, verification, exclusions, call flow |
| `LiveOllamaTest` | 6 | Real HTTP against a running Ollama; auto-skips when absent |
| `CodeFlowAnalyzerTest` | 9 | Method-name extraction, flow intent, prompt forbids added nodes |

The default suite is hermetic: LLM behaviour runs through `FakeLlmProvider`, and
`LiveOllamaTest` skips itself via `assumeTrue` when no server is reachable, so CI never
depends on a model.

### Fixture tests

`ContextResolverFixtureTest` uses `BasePlatformTestCase` to build a small project in memory
and assert what unit tests could not reach: that a file is actually found by name and by path
suffix, that an **invented path is refused verification** and so never becomes a clickable
link, that an out-of-range line is refused, that the file budget holds, that a stop-word-only
query returns nothing rather than the repository, and that `build/` output and `.env` are
never offered as context.

### Live provider test

`LiveOllamaTest` exercises the real HTTP path — endpoint shape, request body, response
envelope — against a local model. A fake cannot catch a wrong URL path or a field read from
the wrong place; those only appear against a real server.

Run it deliberately:

```bash
ollama serve
DEVBEER_TEST_OLLAMA_MODEL=qwen2.5:3b ./gradlew test --tests '*LiveOllamaTest'
```

## 8. Performance

| Concern | Measure |
|---|---|
| Startup | Nothing is constructed until a feature is invoked; the service is lazy |
| EDT | Search, file reads and LLM calls all run in `Task.Backgroundable`; only rendering is on the EDT |
| Full scans | None. `processCandidateFilesForText` is index-backed |
| Candidate cap | 60 files examined before ranking — bounds read cost, not just payload |
| Payload | Hard budget: 10 files / 50,000 characters / 30 methods, all configurable |
| Concurrency | One in-flight request; a second is refused rather than queued |
| Caches | Bounded LRU: 50 responses, 20 contexts, dropped on any VFS change |
| Cancellation | The progress indicator is polled inside the provider, not only around it |

## 9. Privacy and security

- **No configuration → no network.** Disabled by default.
- **Local provider → nothing leaves the machine.**
- **Cloud provider → only ranked context**, after exclusion and redaction.
- Excluded outright: `.git`, `.idea`, `build`, `out`, `target`, `node_modules`, `dist`,
  `coverage`, `__pycache__`, and `.env`, `*.pem`, `*.key`, `*.p12`, `*.jks`, `credentials*`,
  `secret*`, `id_rsa*`.
- Redacted from what remains: key-shaped assignments, `Bearer`/`Basic` values, OpenAI/GitHub/
  AWS/Slack/Google/JetBrains key shapes, PEM private-key blocks, JWTs, URL credentials. The
  count is shown in the UI after every request.
- Logs carry metadata only — provider, duration, context size, response size. Never a prompt,
  never source, never a key.
- API keys live in the OS keychain.

## 10. Safety model

```
LLM suggestion → plugin validation → preview → user approval → undoable write command
```

A fix is refused unless the file exists in the project, the range is inside it, and the
current text still matches what the model patched against. Refused fixes are shown as preview
only. There is no autonomous agent: no multi-file edits, no command execution, no commits, no
builds.

## 11. Build and verification

```
./gradlew test          214 tests, 0 failures
./gradlew buildPlugin   devbeer-2.2.0.zip
./gradlew verifyPlugin  IC-233 ×2, IC-241, IC-242, IC-243 — all Compatible
./gradlew runIde        loads clean: "Loaded custom plugins: DevBeer (2.2.0)", 0 errors
```
