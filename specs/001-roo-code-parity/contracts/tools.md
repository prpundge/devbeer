# Contract: Tools the model may call

The interface between the model and the plugin. Names, parameters and result shapes follow Roo
Code's final source and tool reference pages so prompts, rules files and model habits carry over.
Legacy form is shown; the native form carries the same parameter names as JSON arguments.

## Groups, availability and approval category

| Group | Tools | Auto-approve category |
|---|---|---|
| read | `read_file`, `search_files`, `list_files`, `codebase_search` | Read files and directories (`alwaysAllowReadOnly`, outside-workspace option) |
| edit | `apply_diff`, `write_to_file`, `edit`, `edit_file`, `search_replace`, `apply_patch`, `generate_image` | Edit files (`alwaysAllowWrite`, outside-workspace and protected-files options, write delay) |
| command | `execute_command`, `read_command_output` | Execute approved commands (`alwaysAllowExecute`, allowed/denied lists) |
| mcp | `use_mcp_tool`, `access_mcp_resource` | Use MCP servers (`alwaysAllowMcp` plus per-tool always-allow) |
| modes (always available) | `switch_mode`, `new_task` | Switch modes / Create & complete subtasks |
| always available | `ask_followup_question`, `attempt_completion`, `update_todo_list`, `run_slash_command` (experimental), `skill` | Follow-up default answer / none / approval / none |

Aliases: `write_file` → `write_to_file`; `search_and_replace` → `edit`.
Removed upstream and not offered: `browser_action`, `insert_content`, `list_code_definition_names`.

A tool outside the current mode's groups is refused before approval with
`Tool "<name>" is not allowed in <mode> mode.`; an edit outside the mode's `fileRegex` is refused
with the FileRestrictionError text (mode name, pattern, description, path, tool). One tool call
is executed per assistant turn.

## Read group

### read_file
| Param | Req | Meaning |
|---|---|---|
| `path` | yes | relative to the workspace |
| `offset` | no | 1-based first line, default 1 |
| `limit` | no | max lines, default 2000 |
| `mode` | no | `slice` (default) or `indentation` |
| `indentation.anchor_line` (+ `max_levels`, `include_siblings`, `include_header`, `max_lines`) | no | indentation mode only |
| `args/file/path` + repeated `args/file/line_range` (`"10-25"`) | multi-file form | |

Result: `N | content` lines; multi-file wrapped as `<files><file><path>…</path><content>…</content></file>…</files>` with `<error>` per failed file; notices `Showing only X of Y total lines…`, `File truncated to N of M characters`; images as base64 with a size notice; binaries as `<binary_file format="ext"/>`; ignored files as `Access denied by .rooignore rules.`; `maxReadFileLine` (default 500, `0` = definitions only, `-1` = unlimited) applies only without an explicit range.

### search_files
`path` (req), `regex` (req, Java flavour accepting the Rust subset), `file_pattern` (glob), `respect_gitignore` (default true).
Result per file: `# <relative path>` then `NNN | line` with one context line each side and `----` separators; capped at 300 results with a notice; lines over 500 chars end in `[truncated...]`; never leaves the workspace.

### list_files
`path` (req), `recursive` (default false). Directories end with `/` and precede their contents; capped at ~200 entries with `File listing truncated (showing 200 of N files)…`; recursive mode skips `node_modules`, `.git` and honours `.gitignore`; ignored files are hidden or prefixed 🔒 per `showRooIgnoredFiles`; root and home directories refused; 10-second budget.

### codebase_search
`query` (req), `path` (optional scope). Available only when the index is configured. Result rows: relative path, start–end lines, score (0.4–1.0), chunk; max 50.

## Edit group

### write_to_file
`path`, `content`, `line_count` (all required; a wrong or missing `line_count` returns an error suggesting `apply_diff` for existing files). Preprocessing strips code-fence markers, accidental line numbers and, for non-Claude models, HTML entities. Shown as a diff; the user may edit the proposed side; applied as one Undo step.

### apply_diff
`path` (req), `diff` (req), `start_line`/`end_line` (optional hints). Diff format, one or more blocks:

```
<<<<<<< SEARCH
:start_line:10
-------
exact lines to find
=======
replacement lines
>>>>>>> REPLACE
```

Markers that occur in file content are escaped as `\<<<<<<<` etc. Matching: exact → line-trimmed → block-anchor → Levenshtein within the match-precision threshold (default 1.0), searched middle-out within 40 lines of the hint. A failed block reports similarity, best match and searched text; partial application is reported per block. Several files in one proposal are approved as a batch.

### edit
`file_path`, `old_string`, `new_string` (all req), `replace_all` (default false). First occurrence only unless `replace_all`. Exact, case- and whitespace-sensitive.

### edit_file
`file_path`, `old_string`, `new_string` (req), `expected_replacements` (default 1). Errors when the actual count differs. Empty `old_string` creates the file or appends to it.

### search_replace
`file_path`, `old_string`, `new_string` (req). Exactly one occurrence; otherwise an error.

### apply_patch
`patch` (req): sections headed `*** Add File: <path>`, `*** Update File: <path>`, `*** Delete File: <path>`, each followed by a unified diff (`--- a/…`, `+++ b/…`, `@@` hunks). Context must match exactly, no fuzzy matching; targets validated before the batch preview; per-file success or failure reported.

### generate_image (experimental)
`prompt` (req), `path` (req), `image` (optional input image path). Uses the OpenRouter image key and model from settings; writes the file into the workspace after approval.

## Command group

### execute_command
`command` (req), `cwd` (optional, relative to the workspace). Runs in the user's shell as one process; output streamed; result text: the captured output (truncated per settings, with `[...N lines omitted...]` / `[...N characters omitted...]`), then `Exit code: N` or the signal; when truncated the full output is saved as an artifact and the notice `[OUTPUT TRUNCATED - Full output saved to artifact: cmd-<id>.txt]` is appended. Never auto-approved when the command contains `$(`, backticks or `${`, or matches a denied prefix; every sub-command separated by `&&`, `||`, `;` or `|` is validated. File-reading commands on ignored files are blocked. Execution timeout 0–600 s with an allow-list.

### read_command_output
`artifact_id` (req), `search` (optional regex or literal, case-insensitive), `offset` (bytes, default 0), `limit` (bytes, default 40960). Returns matching lines (search mode) or the raw slice (read mode).

## MCP group

### use_mcp_tool
`server_name`, `tool_name` (req), `arguments` (JSON object; `{}` when none). Result: the server's content items (text, image, resource reference); `isError` surfaces as a tool error. Calls run one at a time; per-server timeout 1–3600 s.

### access_mcp_resource
`server_name`, `uri` (req). Result: content items with metadata; text processed for display, images rendered.

## Modes and workflow

### switch_mode
`mode_slug` (req), `reason` (optional). Requires approval unless auto-approved; refused when the mode does not exist or is already active; after the switch the next tool runs after a 500 ms delay.

### new_task
`mode` (req), `message` (req), `todos` (optional checklist). Requires approval; the parent pauses; the child starts with only `message` as context; the parent resumes with the child's `attempt_completion` `result`.

### ask_followup_question
`question` (req), `follow_up` with 2–4 `<suggest>` items (optional; a `mode` attribute on a suggestion proposes a mode switch — **verify**). The answer returns as `<answer>…</answer>`; with auto-answer on, the first suggestion is taken after the timeout unless the user acts.

### attempt_completion
`result` (req), `command` (optional demo command, approved separately). Shown as a completion card; feedback restarts the loop; in a subtask, completion returns `result` to the parent.

### update_todo_list
`todos` (req): the complete single-level checklist with `[ ]`, `[-]`, `[x]`. Replaces the list wholesale; the user may edit items in the approval card.

### run_slash_command (experimental)
`command` (req, name without the slash), `args` (optional). Runs the command file's body as the next instruction.

### skill
`skill` (req, name), `args` (optional). Loads the skill's instructions from the skills directories (**verify** layout per R-13).

## Model-facing message contract

The system prompt is assembled in this order: role definition of the mode; tool descriptions for
the mode's groups (omitting MCP when disabled); shared rules (workspace path, one tool per turn,
wait for the result, legacy format rules when native calling is off); modes list with
`whenToUse`; capabilities; the "USER'S CUSTOM INSTRUCTIONS" block in the nine-step rules order
(`contracts/config-files.md`). Each user turn ends with an `<environment_details>` block: current
time (when enabled), current cost, mode and profile, open files, working directory, the
`REMINDERS` todo table, and the condensing status. Every tool result is wrapped as a tool message
(native) or a user message beginning `[<tool_name> for '<path>'] Result:` (legacy).
