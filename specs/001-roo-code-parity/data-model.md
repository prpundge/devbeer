# Data Model: Roo Code-Style Agent in DevBeer AI

**Feature**: `001-roo-code-parity` · **Date**: 2026-10-23 · **Phase**: 1

Entities are described by their fields, validation rules and state transitions. Persistence
locations are in `contracts/ide-integration.md`; wire shapes are in the other contracts.

## Task

One conversation with a goal, in one project.

| Field | Type | Rules |
|---|---|---|
| `id` | string (UUID) | immutable |
| `number` | int | per-project sequence, 1-based |
| `createdAt`, `updatedAt` | epoch ms | |
| `projectHash` | string | hash of the project base path (`workspace` in Roo's history item) |
| `title` | string | first user message, trimmed to 200 chars |
| `modeSlug` | string | must resolve to a known mode at resume; falls back to `code` with a notice |
| `profileId` | string | the profile the task started with; kept for the task's life |
| `status` | enum | see transitions |
| `parentId`, `rootId`, `childIds`, `awaitingChildId`, `completedByChildId` | ids | subtask links |
| `usage` | UsageStats | accumulated |
| `todos` | list of TodoItem | replaced wholesale by `update_todo_list` |
| `checkpoints` | list of Checkpoint | ordered |
| `condensations` | list of Condensation | tokens before/after, cost |
| `redactions` | int | secret-shaped values redacted so far |
| `consecutiveMistakes` | map tool → int | the "stuck" guard; reset on success |

**Status transitions**

```
CREATED ──send──▶ RUNNING ──tool proposed──▶ AWAITING_APPROVAL ──approve/auto──▶ RUNNING
                    │                               └──reject(feedback)──────────▶ RUNNING
                    ├──ask_followup_question / attempt_completion──▶ AWAITING_USER ──reply──▶ RUNNING
                    ├──new_task approved──▶ PAUSED_FOR_SUBTASK ──child completes──▶ RUNNING
                    ├──stop / IDE exit──▶ CANCELLED (resumable) ──resume──▶ RUNNING
                    └──unrecoverable provider error──▶ FAILED (resumable)
AWAITING_USER ──user accepts completion──▶ COMPLETED
```

Roo's history `status` maps as: `active` = RUNNING, AWAITING_*, PAUSED_FOR_SUBTASK, CANCELLED,
FAILED; `completed` = COMPLETED; `delegated` = PAUSED_FOR_SUBTASK with `awaitingChildId`.

**Invariants**: at most one task per project in RUNNING or AWAITING_APPROVAL; a task in
PAUSED_FOR_SUBTASK has exactly one child not yet COMPLETED; a COMPLETED task has no pending
approval.

## Message

| Field | Type | Rules |
|---|---|---|
| `role` | SYSTEM, USER, ASSISTANT, TOOL | |
| `parts` | list of Part | text, image (base64 + mime), tool call, tool result |
| `ts` | epoch ms | |
| `kind` (UI only) | enum | user text, assistant text, tool proposal, tool result, approval decision, completion, question, condensation, checkpoint marker, error |

Two parallel lists exist, as in Roo Code: the API conversation (what is sent to the provider,
after condensing) and the UI transcript (what the user saw, never condensed). Tool results are
sanitized (R-25) before entering the API conversation.

## ToolCall

| Field | Type | Rules |
|---|---|---|
| `id` | string | provider id for native calls, generated for legacy |
| `name` | ToolName | after alias resolution (`write_file` → `write_to_file`, `search_and_replace` → `edit`) |
| `params` | map string → string | legacy format; raw bodies |
| `nativeArgs` | JSON | native format |
| `format` | NATIVE, LEGACY | |
| `group` | read, edit, command, mcp, modes, always | derived from `name` |
| `targetPaths` | list of path | derived; used for mode file restrictions, `.rooignore`, outside-workspace checks |
| `decision` | ApprovalDecision | |
| `result` | ToolResult | text plus optional images, `isError` |

**Transitions**: PROPOSED → (mode check fails) REFUSED → result to model;
PROPOSED → AWAITING → APPROVED | AUTO_APPROVED | REJECTED(feedback);
APPROVED → EXECUTING → DONE | ERROR.

**Validation before approval**: tool exists and is enabled; tool is allowed by the current mode's
groups; every target path is inside the workspace unless the outside-workspace option permits it;
no target matches `.rooignore` (unless the tool documents a bypass); edit targets match the mode's
`fileRegex` where one is set; `execute_command` passes the shell tokenizer and the denied list.

## ApprovalDecision

`{ outcome: APPROVED | AUTO_APPROVED | REJECTED | TIMED_OUT_DEFAULT, feedback?: string,
editedContent?: string, decidedAt }`. `editedContent` carries the user's touch-ups from the diff
view.

## Mode

| Field | Type | Rules |
|---|---|---|
| `slug` | string | `^[a-zA-Z0-9-]+$`, unique per source |
| `name` | string | non-empty |
| `description` | string? | shown in the selector |
| `roleDefinition` | string | non-empty; start of the system prompt |
| `whenToUse` | string? | defaults to the first sentence of `roleDefinition` |
| `customInstructions` | string? | |
| `groups` | list of GroupEntry | no duplicate group names; `GroupEntry = name | (name, { fileRegex?, description? })`; `fileRegex` must compile |
| `source` | BUILT_IN, GLOBAL, PROJECT | project replaces global replaces built-in by slug, whole object |
| `stickyProfileId` | string? | last profile used with this mode |

Built-ins (final source): `code` (read, edit, command, mcp), `ask` (read, mcp), `architect`
(read, edit limited to `\.md$`, mcp), `debug` (read, edit, command, mcp), `orchestrator` (none).

## RuleSet

Ordered list of `RuleSource { origin: GLOBAL | WORKSPACE, kind: MODE_DIR | GENERIC_DIR |
MODE_FILE | GENERIC_FILE | LEGACY_FILE | AGENTS_MD | ROOIGNORE, path, text }`. Assembly order is
the nine-step order in `contracts/config-files.md`. Empty files are skipped; directory sources
are headed `# Rules from <absolute path>:`, file sources `# Rules from <filename>:`.

## IgnoreRules

`{ patterns: list of Pattern(raw, negated, anchored, directoryOnly, compiled), baseDir }` for
`.rooignore` and each `.gitignore`. `.rooignore` always contains an implicit rule protecting
itself. Reloaded on VFS change.

## SlashCommand

`{ name, source: BUILT_IN | MODE | GLOBAL | PROJECT, description?, argumentHint?, mode?, body }`.
Name derives from the file name; project overrides global; built-in and mode commands cannot be
overridden; duplicates created in the UI get a numeric suffix.

## ProviderProfile

| Field | Type | Rules |
|---|---|---|
| `id` | string | UUID; Roo's `id` is kept on import |
| `name` | string | unique; the import merge key |
| `provider` | ProviderKind | one of the usable providers, or UNSUPPORTED(originalName) |
| `transport` | TransportFamily | derived from `provider` |
| `modelId` | string | the provider's model-id key (`apiModelId`, `openRouterModelId`, `ollamaModelId`…) |
| `baseUrl` | string? | provider-specific key |
| `fields` | map string → string | remaining non-secret provider keys, Roo names kept |
| `secretRefs` | set of field name | which keys live in the keychain |
| `temperature` | double? | null = provider default; fixed 1.0 for thinking models |
| `rateLimitSeconds` | int | ≥ 0, default 0 |
| `thinkingBudget`, `maxTokens`, `reasoningEffort` | optional | |
| `diffEnabled` | bool | default true |
| `fuzzyMatchThreshold` | double | 0.8–1.0, default 1.0 |
| `contextWindowOverride` | int? | |
| `pinned` | bool | |

**Rules**: the last remaining profile cannot be deleted; renaming keeps `id`; two profiles may
share a provider.

## SettingsBundle

The import/export unit: `{ providerProfiles: { currentApiConfigName, apiConfigs, modeApiConfigs },
globalSettings }`. Field-level contract in `contracts/settings-export.md`.

## AutoApprovePolicy

`{ enabled, readOnly, readOnlyOutsideWorkspace, write, writeOutsideWorkspace, writeProtected,
writeDelayMs (≥0, default 1000), execute, allowedCommands[], deniedCommands[],
commandExecutionTimeout (0–600), commandTimeoutAllowlist[], mcp, modeSwitch, subtasks,
followupQuestions, followupAutoApproveTimeoutMs (1,000–300,000, default 60,000),
allowedMaxRequests?, allowedMaxCost? }`. Evaluated per ToolCall; the master `enabled` gates all.

## Checkpoint

`{ taskId, index, sha, kind: TASK_START | BEFORE_EDIT, ts, trigger: tool name? }`. Backed by one
shadow commit. Restore semantics in R-08.

## TodoItem

`{ text, status: PENDING | IN_PROGRESS | COMPLETED }`; serialised as `[ ]`, `[-]`, `[x]` lines.

## McpServer

| Field | Type | Rules |
|---|---|---|
| `name` | string | unique after project-over-global resolution |
| `source` | GLOBAL, PROJECT | |
| `transport` | STDIO, STREAMABLE_HTTP, SSE | `type` defaults to stdio when `command` is present; a URL entry without `type` is an error |
| `command`, `args`, `cwd`, `env` | | stdio only; `${env:VAR}` substituted |
| `url`, `headers` | | HTTP and SSE |
| `timeoutSeconds` | int | 1–3600, default 60 |
| `disabled` | bool | |
| `alwaysAllow`, `disabledTools` | list of tool name | |
| `watchPaths` | list of path | |
| `state` | CONFIGURED → STARTING → CONNECTED | ERROR | DISABLED | restart returns to STARTING |
| `tools`, `resources`, `resourceTemplates` | discovered | refreshed on `list_changed` |

## IndexState

`{ enabled, embedder, model, dimension, qdrantUrl, status: STANDBY | INDEXING | INDEXED | ERROR,
fileHashes: map path → hash, lastError? }`. Changing model or dimension clears the collection.

## UsageStats

`{ tokensIn, tokensOut, cacheReads, cacheWrites, totalCost, contextTokens, contextWindow }`;
cost is computed from the model catalogue when the provider does not report it.

## Relationships

- Project 1 — n Task; Task 1 — n Message, ToolCall, Checkpoint, Condensation; Task 0..1 parent.
- Mode n — 0..1 sticky ProviderProfile; Task 1 — 1 ProviderProfile (by id, frozen).
- Settings 1 — n ProviderProfile, 1 AutoApprovePolicy, 0..n McpServer (plus project ones),
  0..1 IndexState per project.
- RuleSet and IgnoreRules are derived per project and rebuilt on file change, never persisted.
