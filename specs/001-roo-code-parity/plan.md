# Implementation Plan: Roo Code-Style Agent in DevBeer AI

**Branch**: `feat/roo-code-parity-2.4.0` (spec directory `001-roo-code-parity`) | **Date**: 2026-10-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-roo-code-parity/spec.md`, clarified 2026-10-23.

**Note**: This plan was produced by the `/speckit-plan` command. Phase 0 is in
[research.md](research.md); Phase 1 is [data-model.md](data-model.md), [contracts/](contracts/)
and [quickstart.md](quickstart.md). Tasks come from `/speckit-tasks`.

## Summary

Turn DevBeer's read-mostly assistant into a Roo Code-style coding agent inside JetBrains IDEs:
an approve-per-action loop with Roo Code's tools, modes and configuration files, provider
profiles across six wire formats, checkpoints, subtasks, mentions, Roo's settings import, and
later MCP and a semantic index — all off by default, with every write and command behind consent
(Constitution VII), and no third-party dependency. The target is Roo Code's final documented and
archived behaviour; browser use, JavaScript custom tools, PDF mentions and OAuth-only providers
are explicit exclusions. Delivery is three releases, betas flowing from `main` after each merge.

## Technical Context

**Language/Version**: Kotlin 1.9.22 on the JDK 17 toolchain; Kotlin stdlib supplied by the platform.

**Primary Dependencies**: IntelliJ Platform (built against 2024.1.4, supported 231–243.*), IntelliJ Platform Gradle Plugin 2.1.0. No third-party runtime dependencies: JDK `HttpClient` and `java.net.http.WebSocket`, `javax.crypto` (SigV4), `java.security` (RS256), the JDK HTML parser, DevBeer's own JSON and YAML readers.

**Storage**: `devbeer-ai.xml` through `PersistentStateComponent` (application level); `PasswordSafe` for every secret; per-project files under the IDE system directory for tasks, command artifacts, checkpoints (shadow git) and the index cache; Roo's files in the project and under `~/.roo/`.

**Testing**: JUnit 4; `BasePlatformTestCase` fixtures; `FakeLlmProvider` scripted with tool calls; fixture files for every parser; opt-in live tests against Ollama; JetBrains Plugin Verifier in CI.

**Target Platform**: JetBrains IDEs 2023.1–2024.3 on Windows, macOS and Linux; Java support optional.

**Project Type**: IDE plugin (single Gradle project).

**Performance Goals**: no full repository scan on the normal path (index-backed pre-filtering, 10-second budgets on walks); EDT never blocked; a turn's own CPU cost under 100 ms excluding the model and the index; streamed replies visible within the provider's first-token latency; checkpoint commit under 2 s on a 10,000-file project with a warm shadow repo.

**Constraints**: off by default; one running task per project; context payload bounded by the provider's window with condensing; every tool result sanitized before transmission; verifies clean on the 231 floor; the existing 230 tests stay green.

**Scale/Scope**: projects up to 100,000 files; conversations up to the model's window with condensing; 23 usable providers over 6 transports; ~25 tools; 13 user stories; three releases.

## Constitution Check

*GATE: passed before Phase 0; re-checked after Phase 1 — no violations.*

| Principle / constraint | Status | Evidence |
|---|---|---|
| I. Plugin id immutable | Pass | `com.plugin.springprops.viewer` unchanged; new tool-window content reuses the existing id |
| II. `sinceBuild` is measured | Pass | Every platform API listed in `contracts/ide-integration.md` exists on 231; `verifyPlugin` with the explicit floor stays the gate and runs in CI |
| III. Parse, do not pattern-match | Pass | Dedicated parsers for legacy tool tags, SSE, NDJSON, diff blocks, unified patches, gitignore syntax, YAML tree, front matter, shell commands (research R-01, R-03, R-06, R-07, R-09, R-13) |
| IV. Do not clone what the IDE does | Pass | Platform diff viewer for previews, platform problems for `@problems`, platform structure view for definitions, optional terminal plugin for `@terminal`; the delta over JetBrains AI Assistant and Junie is bring-your-own provider, local models and portable open configuration |
| V. Secrets outside the repository | Pass | Keychain per profile field; export omits keys unless opted in; metadata-only logs; `.env` untouched |
| VI. Report what happened | Pass | SC-007: every Roo feature is implemented or named under "Not included"; measurements in docs are dated |
| VII. The assistant proposes; the user disposes | Pass | `ApprovalGate` is the single path from a tool call to a side effect; checkpoints before edits; edits re-validated against current content; one Undo step; off by default |
| No third-party runtime dependencies | Pass | SigV4, RS256 JWT, JSON-RPC, SSE and gitignore matching are hand-written; git, Qdrant and MCP servers are reached over their protocols (research R-02, R-08, R-18, R-19) |
| Java optional | Pass | New code under `com.devutility.ai.agent` is scanned by `JavaIsolationTest`; only generic PSI used; terminal support behind an optional descriptor (R-24) |
| Prefer stable Java APIs at the boundary | Pass | `getService(Class)`; no reified service helpers in new code |
| Quality gates before upload | Pass | Tests, verifier, artifact inspection; the CI workflow runs the first two before every beta |

## Project Structure

### Documentation (this feature)

```text
specs/001-roo-code-parity/
├── plan.md              # This file
├── spec.md              # Clarified specification
├── research.md          # Phase 0: decisions R-01 … R-25
├── data-model.md        # Phase 1: entities, states, invariants
├── quickstart.md        # Phase 1: validation scenarios
├── contracts/
│   ├── tools.md         # model ↔ plugin tool contract
│   ├── config-files.md  # .roomodes, rules, .rooignore, commands, skills, mcp.json
│   ├── settings-export.md
│   ├── providers.md     # six transport families
│   ├── mcp.md
│   └── ide-integration.md
├── checklists/requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks) — not created here
```

### Source Code (repository root)

```text
src/main/kotlin/com/devutility/
├── ai/                                  # 2.3.0 layer, kept: AiService, context/, error/, fix/, flow/, llm/, parse/, prompt/, settings/, ui/
│   └── agent/                           # NEW — the Roo-style agent
│       ├── task/        AgentTask, TaskRunner (state machine), TaskStore, TaskHistory, UsageStats
│       ├── loop/        TurnAssembler, SystemPromptBuilder, EnvironmentDetails, ToolCallParser (native + legacy), Condenser
│       ├── tools/       ToolRegistry, ToolGroup, one class per tool, ToolResult, path/workspace checks
│       ├── approval/    ApprovalGate, AutoApprovePolicy, CommandValidator (shell tokenizer, prefix lists)
│       ├── modes/       BuiltInModes, ModeRegistry, RooModesLoader, ModeExportImport
│       ├── rules/       RuleLoader (nine-step order), AgentsMd, CustomInstructions
│       ├── ignore/      GitignoreMatcher, RooIgnoreService
│       ├── commands/    SlashCommandRegistry, CommandFileLoader, InitCommand, FrontMatterParser
│       ├── mentions/    MentionParser, FileMention, FolderMention, ProblemsMention, GitMention, UrlMention, HtmlToText
│       ├── checkpoints/ CheckpointService, ShadowGit, Exclusions
│       ├── subtasks/    TaskStack, SubtaskHandoff
│       ├── providers/   ProviderProfile, TransportFamily, OpenAiChatTransport, AnthropicTransport, GeminiTransport,
│       │                OllamaChatTransport, BedrockTransport (+ SigV4), VertexTransport (+ ServiceAccountJwt),
│       │                SseReader, NdjsonReader, ModelCatalog, Pricing, RateLimiter
│       ├── settings/    AgentSettings, SecretStore, SettingsImportExport (Roo JSON), AutoImportStartup
│       ├── mcp/         JsonRpcClient, StdioTransport, StreamableHttpTransport, SseTransport, McpHub, McpConfigLoader
│       ├── index/       Chunker, Embedders, QdrantClient, IndexService, IndexStatus
│       └── ui/          AgentToolWindowPanel (chat), MessageCards, MarkdownToHtml, ApprovalBar, TaskHeader,
│                        HistoryPanel, BatchDiffDialog, MentionPopup, settings pages (Providers, AutoApprove, Modes,
│                        Prompts, Context, Checkpoints, Mcp, Experimental, ImportExport)
├── props/                               # YamlParser gains YamlValue (tree API); flattening becomes a view
└── json/                                # JsonValue builders for request bodies

src/main/resources/
├── META-INF/plugin.xml                  # new services, configurable pages, actions
├── META-INF/devutility-terminal.xml     # optional: @terminal reader
├── ai/models.json                       # model catalogue and prices
└── ai/prompts/                          # built-in mode role definitions, tool descriptions, support prompts, /init

src/test/kotlin/com/devutility/ai/agent/ # mirrors the packages above
src/test/resources/roo/                  # fixture project, settings export, diff/patch/tool-call/gitignore fixtures
```

**Structure Decision**: single Gradle project, one new package tree under the existing AI layer so
the Java-isolation bytecode test and the existing sanitizer, exclusion and verification code are
reused rather than duplicated. The 2.3.0 one-shot features stay as they are and share the
provider transports.

## Complexity Tracking

No constitution violations. Complexity accepted deliberately, for the record:

| Item | Why needed | Simpler alternative rejected because |
|------|------------|--------------------------------------|
| Six provider transports plus two signing schemes | FR-026 lists the providers; Bedrock and Vertex need SigV4 and a service-account JWT | One OpenAI-compatible transport fails the import story and excludes two major enterprise providers; an SDK breaks the no-dependency rule |
| YAML tree API in `props` | `.roomodes` is a nested YAML document | JSON-only `.roomodes` would reject most real files; a YAML library is a dependency |
| Shadow git through the `git` executable | Checkpoints with real diffs and renames, identical to Roo Code | File-copy snapshots have no diff and scale badly; git4idea adds an optional dependency and still shells out |
| Own gitignore matcher | `.rooignore`, `respect_gitignore`, checkpoint exclusions | No platform API exposes gitignore semantics without the git plugin |
| Legacy XML tool parser beside native calling | Local models without function calling | Native-only excludes most Ollama models; XML-only degrades Claude and GPT reliability |

## Delivery plan

| Release | Stories | Gate to merge |
|---|---|---|
| 2.4.0 | US1 loop with read, edit, command and workflow tools; US2 modes; US3 Roo project files; checkpoints; task history; existing Local and OpenAI-compatible transports | `AgentLoopTest`, `RooFixtureTest`, `CheckpointTest`, verifier, quickstart scenarios 1–3 and 6 |
| 2.5.0 | US4 settings import/export; US5 auto-approve; US6 profiles across all six transports; US7 mentions; US9 subtasks; condensing and todo list | `SettingsImportTest`, transport contract tests, scenarios 4, 5, 7 |
| 2.6.0 | US10 MCP; US11 index; US12 Enhance Prompt, `/init`, experimental features, `@terminal` | MCP and index tests against local servers, scenario 8 |

Each merge to `main` publishes a beta through the existing pipeline; each release's notes carry
the "Not included" list (SC-007).

## Phase summary

- **Phase 0 — Research**: complete; all unknowns resolved in `research.md` (two items marked
  *verify* against the archived source: the skills directory layout and the `mode` attribute on
  follow-up suggestions; neither changes the design).
- **Phase 1 — Design**: complete; data model, six contracts and the quickstart written; the
  constitution check above was re-run after design with no change.
- **Phase 2 — Tasks**: next, via `/speckit-tasks`, ordered by the delivery plan.
