# Contract: Configuration files read from the project and the home directory

DevBeer reads Roo Code's files unchanged. Paths are relative to the project root unless marked
global; global files live under `~/.roo/` (`%USERPROFILE%\.roo\` on Windows).

## Custom modes

| File | Scope | Format |
|---|---|---|
| `.roomodes` | project | YAML or JSON (YAML tried first) |
| `~/.roo/custom_modes.yaml` (or `.json` when no YAML exists) | global | DevBeer's location; Roo kept this file in the editor's private storage, so Roo's file is brought over with Import Mode |

Schema (top-level key `customModes`, an array; slugs unique):

```yaml
customModes:
  - slug: docs-writer                # ^[a-zA-Z0-9-]+$
    name: 📝 Documentation Writer    # required
    description: Writes docs          # optional, shown in the selector
    roleDefinition: You are …         # required, opens the system prompt
    whenToUse: Use when …             # optional; default = first sentence of roleDefinition
    customInstructions: Prefer …      # optional, appended near the end of the system prompt
    groups:                           # read | edit | command | mcp, no duplicates
      - read
      - - edit
        - fileRegex: \.(md|mdx)$      # JavaScript regex, matched on the relative path
          description: Markdown files only
      - mcp
    # source is set by DevBeer (global | project), never by hand
```

Precedence: project `.roomodes` > global file > built-in; same slug replaces the whole object.
A deprecated `browser` group is dropped silently. An invalid `fileRegex` is rejected with
`Invalid regular expression pattern`. Export writes YAML with a `rulesFiles` list
(`relativePath`, `content`) for the mode's rules directory; Import writes the mode and its rules
to the chosen level.

## Rules and custom instructions

Nine-step assembly order under `USER'S CUSTOM INSTRUCTIONS`:

1. Language preference (settings).
2. Global custom instructions (settings, all modes).
3. Mode-specific instructions (settings, this mode).
4. Rules from `rules-{modeSlug}/`: global `~/.roo/rules-{modeSlug}/` then workspace `.roo/rules-{modeSlug}/`.
5. `.roorules-{modeSlug}` (workspace), only when no mode directory had files.
6. Rules from `.rooignore` (a notice listing the protection).
7. `AGENTS.md`, else `AGENT.md`, in the workspace root, when the agent-rules setting is on (default on).
8. Rules from `rules/`: global `~/.roo/rules/` then workspace `.roo/rules/`.
9. `.roorules` (workspace), else `.clinerules` (legacy), only when no generic directory had files.

Directories are read recursively, files sorted by basename case-insensitively, skipping
`.DS_Store`, `*.bak`, `*.cache`, `*.log`, `*.tmp`, `Thumbs.db` and empty files; symlinks
resolved to depth 5. Each directory source is headed `# Rules from <absolute path>:`, each file
source `# Rules from <filename>:`. Workspace rules win conflicts, because they load later.

## `.rooignore`

Workspace root only; `.gitignore` syntax (`*`, `**`, `?`, `[…]`, leading `/`, trailing `/`, `!`,
last match wins). Always ignores itself. Reloaded on change. Enforced on `read_file`,
`write_to_file`, `apply_diff` and the other edit tools, `list_files` (hidden or 🔒), `@folder/`
and `@file` mentions (`(File is ignored by .rooignore)`), and file-reading shell commands. Blocked
operations return `Access to <path> is blocked by the .rooignore file settings. You must try to
continue in the task without using this file, or ask the user to update the .rooignore file.`

## Slash commands

`.roo/commands/<name>.md` (project) and `~/.roo/commands/<name>.md` (global); the file name is
the command name after lower-casing, space-to-dash, special-character removal and dash
collapsing. Optional front matter:

```markdown
---
description: Review the current changes
argument-hint: <scope>
mode: code
---
The body is sent as the instruction, with $ARGUMENTS replaced when given.
```

Project overrides global; built-ins (`/init`) and mode commands (`/code`, `/ask`, …, one per
mode slug) cannot be overridden. Symlinks are followed.

## Skills

`.roo/skills/<name>/SKILL.md` (project) and `~/.roo/skills/<name>/SKILL.md` (global), with
front matter `name` and `description` — **verify** against the archived source before
implementing the `skill` tool.

## `AGENTS.md`

`AGENTS.md`, else `AGENT.md`, in the workspace root. Whitespace-only files are ignored. Setting
`useAgentRules` (default true) turns it off.

## MCP servers

`~/.roo/mcp_settings.json` (global; DevBeer's location for Roo's private-storage file) and
`.roo/mcp.json` (project; wins by server name). Top-level key `mcpServers`:

```json
{
  "mcpServers": {
    "local-server": {
      "command": "npx", "args": ["-y", "some-server", "${env:HOME}"], "cwd": ".", "env": { "API_KEY": "…" },
      "alwaysAllow": ["tool1"], "disabledTools": ["tool9"], "disabled": false, "timeout": 60,
      "watchPaths": ["./server/dist"]
    },
    "remote": { "type": "streamable-http", "url": "https://host/mcp", "headers": { "Authorization": "Bearer …" } },
    "legacy": { "type": "sse", "url": "https://host/mcp-base" }
  }
}
```

`type` defaults to `stdio` when `command` is present; a URL entry without `type` is an error.
`timeout` is 1–3600 seconds, default 60. Secrets written here by the user stay the user's choice;
DevBeer never writes them.

## Settings export file

`roo-code-settings.json`, see `contracts/settings-export.md`.
