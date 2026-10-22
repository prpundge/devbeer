# Contract: IDE integration surface

What the plugin registers, persists and writes on disk. Everything here must exist on IDE 2023.1
(build 231) and verify clean up to 243.*.

## Extension points added to `plugin.xml`

| Element | Value |
|---|---|
| `toolWindow` | id `DevBeer AI` (existing), content replaced by the chat panel; the 2.3.0 panel's Ask, Analyze and Explain flow remain reachable from the chat's "One-shot" tab |
| `applicationService` | `com.devutility.ai.agent.settings.AgentSettings` (state in `devbeer-ai.xml`, merged with the existing `DevBeerAiSettings` component so the IDE's settings export keeps carrying one file) |
| `projectService` | `AgentTaskManager`, `ModeRegistry`, `RuleLoader`, `RooIgnoreService`, `SlashCommandRegistry`, `CheckpointService`, `McpHub`, `IndexService` (all lazy, created on first use) |
| `applicationConfigurable` | parent `tools`, id `com.devutility.ai.settings` (existing) with child pages: Providers, Auto-approve, Modes, Prompts, Context, Checkpoints, MCP, Experimental, Import & Export |
| `notificationGroup` | `DevBeer` (existing) |
| `actions` | `DevBeer.Ai.CycleMode` (default `ctrl alt shift M`), `DevBeer.Ai.ToggleAutoApprove` (`ctrl alt shift A`), `DevBeer.Ai.NewTask`, `DevBeer.Ai.ImportSettings`, `DevBeer.Ai.ExportSettings`; editor popup additions: Add to Context, Fix with DevBeer, Improve with DevBeer (the support prompts) |
| `postStartupActivity` | auto-import of settings when `autoImportSettingsPath` is set; nothing else at startup |
| optional descriptor `devutility-terminal.xml` | depends on `org.jetbrains.plugins.terminal`; registers the `@terminal` reader only when the terminal plugin is present |
| `vfs.asyncListener` | watches `.rooignore`, `.roomodes`, `.roo/**`, `AGENTS.md`, MCP `watchPaths`, and index freshness |

No new `depends`; the Java module stays optional.

## Persisted state

`devbeer-ai.xml` (application level, exported by the IDE's Settings → Export):

- existing 2.3.0 fields, kept;
- `profiles[]` (id, name, provider, modelId, baseUrl, fields map, pinned, numeric options),
  `currentProfileId`, `modeProfiles` map;
- the auto-approve policy fields, the context, checkpoint, terminal, MCP, experiment and index
  fields named as in `contracts/settings-export.md`;
- `customInstructions`, `customModePrompts`, `customSupportPrompts`, `language`;
- `autoImportSettingsPath`, `gitExecutable`, `shell`.

Keychain (`PasswordSafe`), service name `DevBeer AI`: `profile/<id>/<field>` per secret field,
`global/<field>` for global secrets, and the existing `apiKey` entry kept for 2.3.0 settings
migration (moved into the first profile on first run).

## Per-project data under the IDE system directory

```
<system>/devbeer-ai/
├── tasks/<projectHash>/<taskId>/
│   ├── history.json              # HistoryItem fields (see data-model.md)
│   ├── api_conversation.json     # provider-neutral messages, tool calls and results
│   ├── ui_messages.json          # the rendered transcript
│   ├── todos.md
│   └── artifacts/cmd-<id>.txt    # full command outputs for read_command_output
├── checkpoints/<projectHash>/    # shadow git repository (bare-style, work tree = project)
└── index/<projectHash>/cache.json
```

`projectHash` is a stable hash of the project base path. Reset deletes these directories for
every project.

## Checkpoint repository

`git init` once; `config` sets `user.name=DevBeer`, `user.email=devbeer@localhost`,
`core.autocrlf=false`, `commit.gpgsign=false`; `info/exclude` holds the built-in exclusions
(dependency and build directories, media and binaries, caches and temp files, `.env*`, large data
and database files, logs) plus the project's `.gitattributes` LFS patterns. Commands are run with
`--git-dir` and `--work-tree`, never inside the project's own `.git`.

## Threading rules

Network, process and git calls run on pooled threads inside cancellable background tasks; file
reads inside `ReadAction`; edits inside `WriteCommandAction` on the EDT; the chat UI only on the
EDT. Approval waits are futures, never EDT blocks.

## Compatibility rules

Platform APIs used must be present on 231: `DiffManager` and `SimpleDiffRequest`,
`GeneralCommandLine` and `OSProcessHandler`, `PasswordSafe`, `EditorTextField`, `JBPopupFactory`,
`BulkFileListener`, `DaemonCodeAnalyzerEx`, `WolfTheProblemSolver`, `StructureViewBuilder`.
Prefer `getService(Class)` over reified helpers at the boundary (Principle II). The Plugin
Verifier against the declared floor is the gate.
