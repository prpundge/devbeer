# Research: Roo Code-Style Agent in DevBeer AI

**Feature**: `001-roo-code-parity` · **Date**: 2026-10-23 · **Phase**: 0

**Inputs**: `spec.md` (clarified 2026-10-23), constitution 2.0.0, the Roo Code documentation as
read on 2026-10-22, the archived Roo Code source as read on 2026-10-23
(`packages/types/src/*.ts`, `src/shared/tools.ts`, `src/core/config/importExport.ts`,
`src/shared/support-prompt.ts`), and DevBeer 2.3.0's own source.

Every unknown in the plan's Technical Context is resolved below. Each entry records the decision,
why, and what was rejected. Items marked **verify** are facts to confirm against the archived
source during implementation; they do not change the decision.

---

## R-01 How the model asks for a tool

**Decision**: Native function calling wherever the provider supports it (OpenAI `tools` /
`tool_calls`, Anthropic `tools` / `tool_use`, Gemini `functionDeclarations`, Ollama `/api/chat`
`tools` for models that advertise it), with Roo Code's legacy XML-tag format as the universal
fallback: `<tool_name><param>value</param></tool_name>` inside the assistant text. One tool call
per assistant turn is executed; a second call in the same turn is reported back as an error, as
Roo Code did. The provider transport reports which format a call arrived in (`nativeArgs` vs
`usedLegacyFormat` in Roo's own model).

**Rationale**: Roo Code's final source carries both (`ToolUse.nativeArgs`,
`ToolUse.usedLegacyFormat`). Native calls are far more reliable with Claude and GPT models; the
XML form is what makes small local models usable at all. The legacy parser is a tag scanner, not
an XML parser: parameter bodies are taken raw until the matching close tag, so code containing `<`
survives.

**Alternatives rejected**: XML only (throws away structured calling where it exists); native only
(excludes most Ollama models); JSON-in-text (no Roo Code compatibility, and models drift).

## R-02 Provider transports without SDKs

**Decision**: Six transport families, all on the JDK `HttpClient` and DevBeer's own JSON:

| Family | Providers | Notes |
|---|---|---|
| OpenAI Chat Completions | `openai` (compatible, Azure variant), `openai-native`, `openrouter`, `deepseek`, `mistral`, `moonshot`, `minimax`, `xai`, `zai`, `fireworks`, `sambanova`, `baseten`, `requesty`, `unbound`, `litellm`, `vercel-ai-gateway`, `poe`, `lmstudio` | Base URL, auth header and a few extra headers differ (OpenRouter `HTTP-Referer`/`X-Title`; Azure `api-key` + `api-version`). SSE streaming. |
| Anthropic Messages | `anthropic` | `anthropic-version` header, `tools`, `thinking` budget, optional `cache_control`, optional 1M-context beta header. SSE events. |
| Gemini | `gemini` | `generateContent` / `streamGenerateContent?alt=sse`, `x-goog-api-key`. |
| Ollama native | `ollama` | `/api/chat` (NDJSON streaming), `num_ctx`, `/api/tags` for models. The 2.3.0 `/api/generate` path stays for the five one-shot actions. |
| AWS Bedrock Converse | `bedrock` | SigV4 request signing hand-written on `javax.crypto` (HMAC-SHA256) and `java.security` (SHA-256). Non-streaming `converse` first; `converse-stream` needs AWS event-stream binary framing, which is a later task. Static keys, session token, or `~/.aws/credentials` profile. |
| Vertex AI | `vertex` | Service-account JWT signed with RS256 via `java.security.Signature`, exchanged for an access token; Anthropic-on-Vertex (`rawPredict`) and Gemini-on-Vertex endpoints. |

**Not included** (spec FR-026): `openai-codex`, `gemini-cli`, `qwen-code` (each is an OAuth
device flow against that product's own client id and terms), `vscode-lm`, `fake-ai`, and the
retired providers. Import keeps such a profile, strips the provider and marks it "needs
reconfiguration", exactly as Roo Code's importer did for an unknown `apiProvider`.

**Rationale**: 18 of the 23 usable providers are one wire format. The no-dependency rule makes an
AWS or Google SDK impossible; both signatures are small, well-specified algorithms.

**Alternatives rejected**: bundling vendor SDKs (constitution platform constraints); supporting
only Ollama and OpenAI-compatible (fails FR-026 and the import story).

## R-03 Streaming and cancellation

**Decision**: Stream every chat completion that the provider can stream. SSE is read with
`HttpResponse.BodyHandlers.ofLines()` and a small line-state parser (`event:`, `data:`, blank-line
dispatch); Ollama's NDJSON is one JSON object per line. The request is sent with `sendAsync`; Stop
cancels the future and closes the body stream, and the progress indicator is polled between
chunks as the 2.3.0 provider already does. Non-streaming is the fallback when a provider rejects
`stream: true`.

**Rationale**: The chat is unusable without visible progress on a 30-second reply. Streaming also
lets a legacy-format tool call be detected and shown as it arrives.

## R-04 The loop, threads and approvals

**Decision**: One `AgentTask` runs per project at a time on a pooled thread under a cancellable
`Task.Backgroundable`. The loop is a state machine (see `data-model.md`): assemble turn → call
provider → parse tool call → check mode permission → ask approval → execute → append result →
repeat, until `attempt_completion` or a follow-up question hands control to the user. Approval is
a `CompletableFuture<Decision>` completed from the EDT when the user clicks, or completed
immediately by the auto-approve policy. After every state change the task is persisted, so an IDE
restart can resume it. All file and document access happens inside read or write actions; the EDT
never waits on the network.

**Rationale**: Mirrors the 2.3.0 rule that nothing blocks the EDT, keeps a single choke point for
Principle VII, and makes the loop unit-testable with a scripted fake provider.

## R-05 Previewing and applying edits

**Decision**: Previews use the platform diff viewer (`DiffManager` with a `SimpleDiffRequest`
built from the current document and the proposed content), with the proposed side editable so the
user can touch up before approving, as Roo Code allows. Approval applies the edit in one
`WriteCommandAction` so it is one Undo step. New files are created through the VFS so the IDE
indexes them. Multi-file proposals (`apply_patch`, several `apply_diff` blocks across files) open
a batch dialog listing every file with its own diff tab and an approve-all button. Before
applying, the current document text is re-validated against what the model edited (the 2.3.0
validator rule, kept).

**Rationale**: Constitution IV — the IDE's diff viewer is better than anything a plugin would
draw; Principle VII — a preview the user can edit is the consent step.

## R-06 `apply_diff` matching

**Decision**: Port Roo Code's multi-search-replace strategy: parse `<<<<<<< SEARCH` /
`:start_line:N` / `-------` / `=======` / `>>>>>>> REPLACE` blocks (escaped markers `\<<<<<<<`
honoured), locate the search text by exact match first, then line-trimmed match, then a
block-anchor match on first and last lines, then Levenshtein similarity against the "match
precision" threshold (default 1.0, settable 0.8–1.0, Roo's `fuzzyMatchThreshold`), searching
middle-out within 40 lines of the hint (Roo's `BUFFER_LINES`). Levenshtein is ~30 lines of
Kotlin. A failed block reports similarity, the best candidate and the searched text so the model
can retry, and the per-file consecutive-mistake counter feeds the "stuck" guard.

**Rationale**: This is the behaviour models were trained against by Roo Code's prompts; changing
it changes success rates.

## R-07 Running commands

**Decision**: Each command runs through the platform's `GeneralCommandLine` wrapped in the user's
shell (`$SHELL -c` on Unix, PowerShell or `cmd /c` on Windows, selectable in settings, default
auto), with `OSProcessHandler` streaming output lines to the chat and to a per-command artifact
file. ANSI sequences are stripped; carriage-return progress bars are compressed when the setting
is on; the chat keeps 500 lines by default (20% head, 80% tail, omission marker) and a character
cap; the full output stays in the artifact for `read_command_output`. Exit code and signal are
reported. Stop kills the process tree. The execution timeout (0–600 s, default 0) and its
allow-list are honoured. This is Roo Code's "inline terminal" model, which Roo itself recommends
over shell integration.

**Command safety**: a shell tokenizer splits on `&&`, `||`, `;`, `|` and validates every
sub-command: `$(`, backticks and `${` are never auto-approved; the longest matching prefix in the
allowed and denied lists decides; a tie goes to deny; commands that read an ignored file (`cat`,
`grep`, `head`, `tail`, `less`, `more`, `type`, `Get-Content`…) are blocked by `.rooignore`.

**Rejected**: the IDE Terminal tool window (the terminal plugin is an optional dependency and its
API changed across the supported range; may be added later as an optional descriptor).

## R-08 Checkpoints

**Decision**: A shadow git repository, driven through the `git` executable found on `PATH` or
configured in settings, never through the project's own `.git`: `git init` under
`<IDE system dir>/devbeer-ai/checkpoints/<project hash>/`, then every checkpoint is
`git --git-dir=<shadow> --work-tree=<project> add -A && commit --allow-empty`. The project's
`.gitignore` files apply automatically because the work tree is the project; Roo Code's built-in
exclusions are written to the shadow repo's `info/exclude`; `.gitattributes` LFS patterns are
added too. View Differences runs `git diff <a> <b>` and opens the platform diff viewer. Restore is
`add -A && commit` (so nothing is lost), then `reset --hard <sha>`, then a VFS refresh. A nested
`.git` directory below the project root disables checkpoints with a notification. The
initialisation timeout is 10–60 s (default 30; Roo's constant was 15 but its docs say 30).

**Rationale**: Identical semantics to Roo Code, zero dependencies, and git is already a
prerequisite for Roo Code users.

**Rejected**: the bundled git4idea plugin API (would add an optional dependency and still shell
out); copying files into snapshots (no diff, no rename detection, large).

## R-09 Ignore matching

**Decision**: A hand-written `.gitignore`-syntax matcher (`*`, `**`, `?`, character classes,
leading `/` anchoring, trailing `/` directory-only, `!` negation, last match wins, per-directory
scoping for nested `.gitignore` files). It backs `.rooignore`, the `respect_gitignore` behaviour
of `search_files` and `list_files`, and the checkpoint exclusions. `.rooignore` is watched
through the VFS and reloaded; the file itself is always protected.

**Rationale**: Constitution III; there is no platform API that exposes gitignore semantics
without the git plugin.

## R-10 `search_files` and `list_files`

**Decision**: Walk the file tree under the requested path with `Files.walkFileTree`, pruning with
the ignore matcher, and apply the regex with `java.util.regex` to each text file (binary files
skipped by extension and a NUL sniff). Where the regex contains a literal word of three or more
characters, the platform word index (`PsiSearchHelper.processCandidateFilesForText`, already used
by 2.3.0) pre-filters candidate files so a 100,000-file project is not read end to end. Results
follow Roo's format: `# path` headers, `NNN | line` with one context line each side, `----`
separators, 300-result cap, 500-character line truncation. `list_files` caps at 200 entries,
marks ignored files with 🔒 when `showRooIgnoredFiles` is on, and refuses the root and home
directories. Both have a 10-second budget.

**Rust vs Java regex**: Roo documented Rust syntax. Java accepts the common subset; Java-only
extras (look-around, back-references) simply work; Rust-only syntax (`(?P<name>)`) is rewritten
to Java's `(?<name>)` before compiling. Documented in the tool description sent to the model.

**Rejected**: ripgrep (not bundled across the whole supported IDE range); `FindInProjectUtil`
(respects project exclusions, so `respect_gitignore=false` could not work).

## R-11 `read_file`

**Decision**: Read from the open `Document` when there is one (unsaved edits included, as the
compare module already does), else from disk. Line-numbered `N | content`, `offset`/`limit`
(default limit 2000), `maxReadFileLine` default 500 with `0` meaning definitions-only (built
from the platform structure view), the multi-file `args/file/line_range` form, image files as
base64 for vision-capable models (5 MB per image, 20 MB per request), DOCX text through the JDK's
zip and XML readers, `.rooignore` as "Access denied by .rooignore rules.", and the documented
truncation notices. Indentation mode is a later task (language-agnostic indentation blocks).

**Not included**: PDF text extraction (no bundled parser; spec FR-022).

## R-12 Mentions

| Mention | Source |
|---|---|
| `@/path` and `@/folder/` | VFS; bypass ignore files as documented |
| `@problems` | Highlighting of open editors (`DaemonCodeAnalyzerEx`) plus the problem-file list (`WolfTheProblemSolver`), grouped by file; the platform equivalent of VS Code diagnostics |
| `@terminal` | Needs the terminal plugin; shipped behind an optional descriptor (`devutility-terminal.xml`) with a reflectively loaded reader, the same isolation pattern as Java support. Until then the mention explains that it needs the Terminal plugin |
| `@<commit>`, `@git-changes` | `git show` / `git status --porcelain` / `git diff` through the same git executable as checkpoints |
| `@https://…` | JDK `HttpClient` plus the JDK's own HTML parser (`javax.swing.text.html.parser`) to drop scripts, styles and navigation and emit Markdown-ish text |
| `/command` | Slash command registry (R-13) |

## R-13 Modes, rules and commands on disk

**Decision**:

- `.roomodes` (project) and `~/.roo/custom_modes.yaml` (global; `.json` accepted) are parsed into
  a tree. DevBeer's YAML reader today flattens to dotted keys for the compare module, so it gains a
  tree-producing API (`YamlValue`) over the same tokenizer; flattening becomes a view of the tree.
  Nested sequences (`groups: - read - - edit - fileRegex: …`) are the first fixture. Anchors,
  merge keys and tags stay unsupported, as before; a file using them fails with a clear error.
  **verify** that the tokenizer handles sequence-of-sequence indentation; if not, that is the
  first task in this area.
- Roo Code stored its global modes file in VS Code's private storage. DevBeer reads
  `~/.roo/custom_modes.yaml` instead, the natural sibling of `~/.roo/rules/`; Roo's own file can be
  imported through "Import Mode". Same for the global MCP file: `~/.roo/mcp_settings.json`.
- `fileRegex` is a JavaScript regular expression; it is compiled with `java.util.regex` after the
  few JavaScript-only escapes are rewritten, and an invalid pattern is rejected with Roo's message.
- Rules load in Roo's nine-step order (see `contracts/config-files.md`), recursively, sorted by
  basename case-insensitively, skipping cache and temporary files, resolving symlinks to depth 5.
- Slash commands come from `.roo/commands/*.md` and `~/.roo/commands/*.md` with a small
  front-matter parser (`description`, `argument-hint`, `mode`); built-in commands are `/init` and
  one `/slug` per mode. Skills (`skill` tool) load `SKILL.md` files from `.roo/skills/<name>/` and
  `~/.roo/skills/<name>/` — **verify** the exact directory layout and front matter against the
  archived source before implementing the tool.

## R-14 Settings, profiles and the Roo export

**Decision**: `DevBeerAiSettings` (still stored in `devbeer-ai.xml`, so the IDE's own settings
export keeps working) grows a list of provider profiles (id, name, provider, non-secret fields),
the current and pinned profiles, the mode-to-profile map, and the global flags under Roo Code's
own key names wherever they make sense (`autoApprovalEnabled`, `alwaysAllowReadOnly`,
`allowedCommands`, `writeDelayMs`, `enableCheckpoints`, `autoCondenseContextPercent`,
`customInstructions`, `customModePrompts`, `customSupportPrompts`, `mcpEnabled`, `experiments`,
`codebaseIndexConfig`, terminal limits, `language`, `showRooIgnoredFiles`, `maxReadFileLine`,
`diffEnabled`, `fuzzyMatchThreshold`…). Every secret-shaped field (`apiKey`, `*ApiKey`,
`awsSecretKey`, `awsSessionToken`, `vertexJsonCredentials`, `codeIndex*Key`,
`codebaseIndex*ApiKey`, `openRouterImageApiKey`) lives in `PasswordSafe` under
`DevBeer AI/profile/<id>/<field>`.

Import follows `importExport.ts` exactly: profiles merged by name, each validated on its own, an
unknown `apiProvider` stripped with a warning and the profile kept, failure only when nothing was
valid, `modeApiConfigs` merged, `currentApiConfigName` resolved in the documented order,
`globalSettings.customModes` applied per slug, remaining global keys copied where DevBeer has
them and listed as ignored where it does not. Export writes the same structure; secrets are
omitted unless the user ticks the opt-in, which is the one deliberate deviation.

## R-15 Task persistence

**Decision**: `<IDE system dir>/devbeer-ai/tasks/<project hash>/<task id>/` holding
`history.json` (Roo's `HistoryItem` fields), `api_conversation.json` (provider-neutral message
list including tool calls and results), `ui_messages.json` (what the chat rendered), and
`todos.md`. Written after every state change with DevBeer's JSON formatter; read on demand. Task
export produces one Markdown file. Reset deletes the directory.

## R-16 User interface

**Decision**: Native Swing, as the 2.3.0 rule (§20) requires and as every supported IDE build can
render: a chat tool window made of message cards in a `JBScrollPane`, Markdown rendered to HTML by
a small own converter into `JEditorPane`, code blocks with Copy, an `EditorTextField` input with
an `@` popup, mode and profile combo boxes, an approval bar (Approve, Reject, feedback field,
auto-approve chips and master toggle), a task header (tokens, cost, context bar, todo summary,
Condense Context), a history panel, and settings pages for Providers, Auto-approve, Modes,
Prompts, Context, Checkpoints, MCP, Experimental and Import/Export. Diffs open in the platform
diff viewer (R-05).

**Rejected**: an embedded browser (JCEF) — unavailable in some headless and remote setups across
the range, heavier, and against §20.

## R-17 Tokens, context window, cost, condensing

**Decision**: Token counts come from the provider's usage fields (OpenAI `usage`, Anthropic
`usage` with cache reads and writes, Gemini `usageMetadata`, Ollama `prompt_eval_count` /
`eval_count`); the pre-send estimate is characters ÷ 4. Context windows and prices come from a
shipped `models.json` resource (well-known models) with a per-profile override, and from
OpenRouter's `/models` at runtime. Condensing keeps the first user message, replaces the middle
with the CONDENSE summary, keeps the three most recent exchanges, triggers at the configured
percentage (default 100% — in practice when the next request would overflow the 70% usable
window), is available manually, and reports tokens before and after plus its own cost. On a
context-window error the request is retried with 25% less context.

## R-18 Tool servers (MCP)

**Decision**: An own JSON-RPC 2.0 client over three transports: stdio (`GeneralCommandLine`,
newline-delimited messages, `${env:VAR}` substitution in `args`), streamable HTTP (POST with
`Accept: application/json, text/event-stream`, SSE replies parsed by the R-03 reader, session
header kept) and legacy SSE (GET event stream providing the POST endpoint). Handshake
`initialize` with protocol version negotiation, then `tools/list`, `tools/call`,
`resources/list`, `resources/templates/list`, `resources/read`, and the `list_changed`
notifications. Per-server timeout, restart, disable, `alwaysAllow`, `disabledTools`,
`watchPaths` (VFS listener restarts the server). The global "Enable MCP Servers" switch removes
the two tools and the MCP prompt section.

## R-19 Codebase index

**Decision**: Chunks come from the platform's generic PSI where a language plugin exists (named
elements with bodies, split at 1,000 characters, merged below 100) and from line windows
otherwise; Markdown headers start chunks. Embeddings through the OpenAI-compatible
`/v1/embeddings` family (OpenAI, OpenAI Compatible, Mistral, Vercel AI Gateway, OpenRouter),
Gemini `embedContent`, Ollama `/api/embed`, and Bedrock (SigV4 `invoke-model`, later). Vectors go
to Qdrant over REST (`PUT /collections/{c}`, `PUT /collections/{c}/points`,
`POST /collections/{c}/points/search`, delete by file-path filter). Incremental updates ride on
the VFS bulk listener with a per-file content hash cache. Status: standby, indexing, indexed,
error. Nothing runs until configured.

## R-20 Exclusions and experimental features

| Item | Decision |
|---|---|
| Browser use | Not included; retired upstream (spec FR-036). |
| Custom tools | Not included; Roo's are JavaScript modules (spec FR-038). |
| PDF in `@file` | Not included; no bundled parser (spec FR-022). |
| OAuth providers, `vscode-lm`, retired providers | Not usable; import keeps the profile marked for reconfiguration (spec FR-026). |
| `imageGeneration` | OpenRouter image models via chat completions with `modalities: ["image"]`; the image is written into the workspace at the requested path; off by default. |
| `preventFocusDisruption` (background editing) | Auto-approved writes do not open editors or diff tabs; off by default. |
| `runSlashCommand` | The tool runs a command file's body as the next instruction; off by default. |

## R-21 Keyboard shortcuts

**Decision**: Mode cycling and the auto-approve master toggle are registered as actions with
default shortcuts that do not collide with the IDE's defaults (`Ctrl+Alt+Shift+M` and
`Ctrl+Alt+Shift+A`); users rebind them in the Keymap. Roo's `Ctrl+.` and `Ctrl+Alt+A` are taken
in JetBrains keymaps.

## R-22 Testing

**Decision**: Hermetic by default. Unit tests for every parser (legacy tool-call tags, SSE and
NDJSON readers, diff blocks, unified patches, gitignore matcher, YAML tree, front matter, shell
tokenizer, settings import mapping) with fixture files under `src/test/resources/roo/`. The agent
loop is driven by `FakeLlmProvider` scripted to emit tool calls, asserting that no side effect
happens before approval (SC-004). File tools, checkpoints and `.rooignore` run under
`BasePlatformTestCase` on a temporary project; checkpoint tests skip when `git` is absent. Live
provider tests stay opt-in like `LiveOllamaTest`. The Plugin Verifier remains the compatibility
gate and runs in CI before every beta.

## R-23 Release slicing

| Release | Stories | Content |
|---|---|---|
| 2.4.0 | US1, US2, US3, US13 | The loop with the read, edit, command and workflow tools; five modes and `.roomodes`; rules, ignore and commands files; task history; the existing Local and OpenAI-compatible transports; checkpoints are included early because Principle VII leans on them |
| 2.5.0 | US4, US5, US6, US7, US8, US9 | Roo settings import/export, auto-approve matrix, profiles across the transport families, mentions, subtasks, condensing, todo list |
| 2.6.0 | US10, US11, US12 | MCP, codebase index, Enhance Prompt, `/init`, experimental features, `@terminal` |

Betas flow from `main` continuously through the pipeline; each story merges only with its tests.

## R-24 Keeping Java optional

**Decision**: New code lives under `com.devutility.ai.agent` so `JavaIsolationTest` keeps
scanning it. Only generic platform PSI is used (named elements, structure view); Java PSI stays
confined to `JavaSymbolEnricher`. The terminal reader follows the same reflective pattern behind
an optional descriptor.

## R-25 What leaves the machine

**Decision**: Every tool result that goes back to the model (file contents, command output,
search hits, MCP results) passes through the 2.3.0 `ContextSanitizer` before transmission, so
secret-shaped values are redacted in the loop exactly as they are in the one-shot actions; the
count is shown in the task header. Logs stay metadata-only.
