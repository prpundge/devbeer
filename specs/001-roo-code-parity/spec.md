# Feature Specification: Roo Code-Style Agent in DevBeer AI

**Feature Branch**: `001-roo-code-parity`

**Created**: 2026-10-22

**Status**: Clarified 2026-10-23 — ready for `/speckit-plan`. Decisions: full Roo Code model (constitution amended to 2.0.0, Principle VII); repository renamed to `devbeer`; the settings file in the request was an IDE settings export, which already works.

**Input**: User description: "how this ai-assistant-2.3.0 will behave same like a roocode even setting.xml if I provide it should work with dev beer / will all the feature provided by the roo code / and by default this features are disbale by default / and chenge the project repo name too" — with the link https://roocodeinc.github.io/Roo-Code/getting-started/

DevBeer 2.3.0 ships a read-mostly assistant: it answers questions about the repository, explains a
selection, suggests one improvement, analyses an error, and applies a single validated fix when the
user clicks Apply. Roo Code is an autonomous coding agent: it plans a task, reads and edits any
number of files, runs commands, calls external tool servers, and loops until the task is done, with
the user approving each action. This feature closes that gap so that DevBeer behaves the way Roo
Code behaves, inside JetBrains IDEs, and accepts Roo Code's own configuration files and settings
export unchanged.

Roo Code was discontinued on 15 May 2026. Its documentation and source remain public and define the
target behaviour (see *Reference* at the end). Everything in this specification was checked against
those documents on 2026-10-22.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Delegate a task and approve each step (Priority: P1)

A developer types a task in plain language ("add a null check to `PaymentService.charge` and write
a unit test for it"). The assistant proposes one action at a time — read this file, change these
lines (shown as a diff), run this command — and waits for **Approve** or **Reject** (with optional
feedback) before each. It keeps going until it declares the task complete with a summary of what it
did. Nothing is read, written or executed without an approval.

**Why this priority**: The approve-as-you-go agent loop is what "behaves like Roo Code" means.
Every other story configures or extends this loop.

**Independent Test**: With a provider configured, ask for a change spanning two files plus a test
run. Verify each action is previewed before it happens, each needs a click, a rejection with
feedback changes the next proposal, and the closing summary lists exactly what changed.

**Acceptance Scenarios**:

1. **Given** the assistant is enabled and a task is typed, **When** it is sent, **Then** the first
   proposed action is shown with Approve and Reject, and no file or terminal has been touched.
2. **Given** a proposed edit, **When** it is approved, **Then** the file changes as one undoable
   step and the change made is byte-identical to the diff that was shown.
3. **Given** a proposed command, **When** it is approved, **Then** it runs in the project directory,
   its output and exit code are shown to the user, and the same output is handed back to the
   assistant for its next step.
4. **Given** a proposed action, **When** it is rejected with feedback, **Then** the feedback is
   part of what the assistant sees next and a different action is proposed.
5. **Given** a fresh installation, **When** any entry point is used, **Then** the user is told the
   assistant is off and how to enable it, and no network call has been made.
6. **Given** a running task, **When** the user presses Stop, **Then** the in-flight action is
   cancelled and nothing further is applied.
7. **Given** a completed task, **When** the IDE is restarted, **Then** the task appears in the
   task history and can be reopened and continued in the mode and with the provider profile it
   started with.

---

### User Story 2 - Work in modes with scoped permissions (Priority: P1)

The developer picks a mode for the job: **Code**, **Ask**, **Architect**, **Debug** or
**Orchestrator**. Each mode has its own role, its own allowed tool groups and its own file
restrictions (Architect may only edit Markdown; Ask never edits or runs anything). Modes are
switched from a selector, by typing `/code`, `/ask`, `/architect`, `/debug` or `/orchestrator` at
the start of a message, by a keyboard shortcut that cycles through them, or by accepting a switch
the assistant suggests. Each mode remembers the provider profile last used with it.

**Why this priority**: Modes are how Roo Code scopes what the agent may do; without them every
task runs with full permissions.

**Independent Test**: Switch to Ask and request an edit; switch to Architect and request a Kotlin
edit and then a Markdown edit; type `/debug` and confirm the switch.

**Acceptance Scenarios**:

1. **Given** Ask mode, **When** the assistant proposes an edit or a command, **Then** the proposal
   is refused before it reaches the user, and the assistant is told why.
2. **Given** Architect mode, **When** an edit to a non-Markdown file is proposed, **Then** it is
   blocked with a message naming the mode, the allowed pattern and the attempted path; an edit to
   a `.md` file is allowed.
3. **Given** `/debug` typed at the start of a message, **When** sent, **Then** the mode changes and
   the input field is cleared.
4. **Given** profile A chosen while in Code and profile B chosen while in Ask, **When** switching
   between the two modes, **Then** the profile follows the mode, and the choice survives a restart.

---

### User Story 3 - Bring an existing Roo Code project configuration (Priority: P1)

A project that was already set up for Roo Code — `.roomodes`, `.roo/rules/`, `.roo/rules-code/`,
`.roorules`, `AGENTS.md`, `.rooignore`, `.roo/commands/`, `.roo/mcp.json` — is opened in a JetBrains
IDE. DevBeer honours every one of those files exactly as Roo Code documents them, with no edits and
no conversion step. Global files under the user's home `.roo/` directory are honoured too.

**Why this priority**: "If I provide it, it should work with DevBeer" is an explicit requirement,
and it is what makes switching editors painless for a team that has already invested in Roo
configuration.

**Independent Test**: Open a fixture project containing one of each file and confirm each has its
documented effect without touching the files.

**Acceptance Scenarios**:

1. **Given** `.roomodes` defines a `docs-writer` mode whose edit access is limited to `\.md$`,
   **When** the project opens, **Then** the mode appears in the selector and the restriction is
   enforced.
2. **Given** `.roo/rules/` contains files, **When** any task runs, **Then** their contents are
   included in the assistant's instructions in Roo Code's documented order, and `.roorules` is
   ignored because the directory has files.
3. **Given** `.rooignore` lists `config/secrets.json`, **When** the assistant tries to read, edit or
   list it, **Then** the operation is blocked with the documented message and the file is marked
   with a lock in listings.
4. **Given** `.roo/commands/review.md` with a `mode:` front-matter line, **When** `/review` is
   typed, **Then** the command appears in the menu, the mode switches first, and the file's body is
   run as the instruction.
5. **Given** `~/.roo/rules/` exists, **When** a task runs, **Then** those rules load before the
   workspace rules and the workspace wins on conflict.
6. **Given** `.roomodes` is malformed, **When** the project opens, **Then** the user sees an error
   naming the file and the built-in modes still work.

---

### User Story 4 - Import a Roo Code settings export (Priority: P2)

The developer exported their Roo Code settings to `roo-code-settings.json`. In DevBeer they choose
**Import**, pick that file, and their provider profiles (with keys moved into the OS keychain) and
global settings (auto-approve choices, mode preferences, context settings) are merged into DevBeer.
Invalid profiles are skipped with a warning. DevBeer can export the same structure back out, and
can import a file automatically at startup from a configured path.

**Why this priority**: A parity feature. The owner's own need — carrying DevBeer settings
between machines — is met today by the IDE's *Settings → Export/Import*, which includes
`devbeer-ai.xml` (API keys stay in the keychain and travel separately). Importing Roo Code's
own export matters for users migrating from Roo Code, so it stays in scope, behind the P1 loop.

**Independent Test**: Import a fixture export with three profiles, one of them invalid.

**Acceptance Scenarios**:

1. **Given** an export with three valid profiles, **When** imported, **Then** all three are
   selectable, no credential was retyped, and no key is stored in plain text anywhere.
2. **Given** a profile already exists with the same name, **When** imported, **Then** it is
   updated, not duplicated, and settings absent from the file are left untouched.
3. **Given** one profile is invalid, **When** imported, **Then** the others import and a warning
   names the invalid one; **Given** all are invalid, **Then** the import fails with a message.
4. **Given** an auto-import path is configured, **When** the IDE starts, **Then** the file is
   imported and a notification names it; a failure shows a non-blocking warning and the previous
   settings stay in force.
5. **Given** **Export**, **When** run, **Then** the produced file follows the documented structure
   and excludes API keys unless the user opts in.
6. **Given** **Reset**, **When** confirmed, **Then** profiles, keys, custom modes, global settings
   and task history are removed and the assistant is back to a fresh-install state.

---

### User Story 5 - Auto-approve trusted actions (Priority: P2)

Once the developer trusts the assistant for a category of action, they switch that category to
auto-approve: reading files, writing files, running commands whose prefix is on an allowed list,
using tool servers, switching modes, creating and completing subtasks, and answering follow-up
questions with the default after a timeout. A master toggle pauses all of it.

**Why this priority**: Auto-approve is what makes long tasks practical, but the per-action loop
must exist first.

**Independent Test**: Allow the prefix `./gradlew test`, deny `rm`, and run a task that needs both.

**Acceptance Scenarios**:

1. **Given** "Execute approved commands" is on with `./gradlew` allowed, **When** the assistant
   proposes `./gradlew test`, **Then** it runs without a click; **When** it proposes `rm -rf build`,
   **Then** approval is required.
2. **Given** a command matches both an allowed and a denied prefix, **When** proposed, **Then** the
   longest prefix decides and a tie goes to deny.
3. **Given** "Always approve write operations" is on without "Include files outside workspace",
   **When** an edit outside the project is proposed, **Then** approval is still required.
4. **Given** the master toggle is off, **When** any auto-approved action is proposed, **Then**
   approval is required, and the individual selections are remembered for when it is switched back
   on.
5. **Given** "Always default answer for follow-up questions" is on with a 60-second timeout,
   **When** a question is asked and the user does nothing, **Then** the default answer is taken at
   60 seconds; any user action cancels the countdown.

---

### User Story 6 - Keep several provider profiles (Priority: P2)

The developer keeps named profiles — "Local Qwen", "Sonnet via OpenRouter", "Company gateway" —
each with its provider, credentials, model, temperature and rate limit. Profiles are switched from
settings or from the chat, pinned to the top, and linked to modes. A task keeps the profile it
started with.

**Why this priority**: Roo Code's value is being model-agnostic; one endpoint-and-model pair is not
enough for real use.

**Independent Test**: Create two profiles on different providers, link one to Ask, and run a task
under each.

**Acceptance Scenarios**:

1. **Given** two profiles, **When** one is selected in the chat, **Then** the next request uses it
   and the status line shows it.
2. **Given** the only remaining profile, **When** delete is attempted, **Then** it is refused.
3. **Given** a profile with a rate limit of 5 seconds, **When** two requests are made back to back,
   **Then** the second waits at least 5 seconds.
4. **Given** any provider from the supported list in FR-026, **When** a profile is created for it,
   **Then** the provider-specific fields are offered and a request succeeds against a conforming
   endpoint.

---

### User Story 7 - Point the assistant at exact context (Priority: P2)

While typing, the developer references context precisely: `@/path/to/file`, `@/path/to/folder/`,
`@problems`, `@terminal`, `@git-changes`, `@<commit hash>` and `@https://...`. The referenced
content is inserted into the request in the documented form, and the `@` menu lists candidate files
while hiding ignored and generated ones by default.

**Why this priority**: Mentions are how Roo Code users steer context; without them the assistant
must guess.

**Independent Test**: Use each mention once in a request and inspect what was sent.

**Acceptance Scenarios**:

1. **Given** `@/src/Main.kt`, **When** sent, **Then** the file's full content with line numbers is
   included even if the file is ignored, as documented.
2. **Given** `@/src/`, **When** sent, **Then** only the directory's direct text files are
   included, and the trailing slash is required.
3. **Given** `@problems`, **When** sent, **Then** the IDE's current errors and warnings are
   included, grouped by file with line numbers.
4. **Given** `@git-changes`, **When** sent, **Then** the working-tree status and diff of uncommitted
   changes are included, respecting the repository's ignore rules.

---

### User Story 8 - Undo a whole task with checkpoints (Priority: P2)

Before each change the assistant makes, a snapshot of the workspace is taken outside the project's
own version control. From any snapshot in the task history the developer can **View Differences**,
**Restore Files Only** (keep the conversation) or **Restore Files & Task** (also drop later
messages, with confirmation).

**Why this priority**: Checkpoints are the safety net that makes auto-approve acceptable.

**Independent Test**: Run a task that edits three files, then restore to the task start.

**Acceptance Scenarios**:

1. **Given** a task that changed files, **When** Restore Files Only is used on the starting
   checkpoint, **Then** every file is byte-identical to its state at task start and the
   conversation is unchanged.
2. **Given** a nested repository inside the project, **When** the task starts, **Then** checkpoints
   are disabled and the user is notified why.
3. **Given** the project's own version control, **When** any checkpoint is taken or restored,
   **Then** no commit, branch or staged change appears in it.

---

### User Story 9 - Orchestrate subtasks (Priority: P2)

In Orchestrator mode the assistant breaks a large task into subtasks, each started in the right
mode with its own instructions and its own isolated conversation. The parent pauses, the child
finishes with a summary, and only that summary returns to the parent. Creating and completing a
subtask each require approval unless auto-approved. The task hierarchy is visible and navigable.

**Why this priority**: This is the feature Roo Code users know as Boomerang tasks; it depends on
modes and the loop being in place.

**Independent Test**: In Orchestrator mode, give a task that needs a design step and a code step,
and confirm two subtasks run in Architect and Code with only summaries returning.

**Acceptance Scenarios**:

1. **Given** Orchestrator mode, **When** a subtask is proposed, **Then** its mode and instructions
   are shown for approval and the parent is paused once it starts.
2. **Given** a running subtask, **When** it completes, **Then** the parent resumes with the result
   summary and nothing else from the child's conversation.
3. **Given** default Orchestrator mode, **When** it tries to read or edit a file or run a command
   directly, **Then** it is refused; a customised Orchestrator with the read group added may read.

---

### User Story 10 - Connect external tool servers (Priority: P3)

The developer lists Model Context Protocol servers in a global file or in `.roo/mcp.json`, over
stdio, streamable HTTP or SSE. Their tools and resources become available to the assistant, each
use needing approval unless marked always-allow, with per-server enable, restart, delete and
timeout controls, and a global switch to turn MCP off entirely.

**Why this priority**: Important for parity, but it is an extension point rather than the core
loop, and it is the first story that requires launching external processes.

**Independent Test**: Configure one stdio server and one HTTP server in a fixture project and call
one tool from each.

**Acceptance Scenarios**:

1. **Given** a stdio server in `.roo/mcp.json`, **When** the project opens, **Then** the server is
   started, its tools are listed, and a use of one requires approval.
2. **Given** the same server name in the global and the project file, **Then** the project entry
   wins.
3. **Given** "Enable MCP Servers" is off, **Then** no server is contacted and the MCP tools are
   unavailable to the assistant.

---

### User Story 11 - Search the codebase by meaning (Priority: P3)

Optionally, the developer configures an embedding provider and a vector store; the project is
indexed in the background, kept up to date as files change, and the assistant gains a semantic
search tool. Status is shown as a coloured indicator, and the index can be cleared.

**Why this priority**: Parity feature with an external service dependency; DevBeer's existing
index-backed search already covers the common case.

**Independent Test**: Point indexing at a local embedding model and a local vector store, index a
fixture project, and ask a question that only a meaning-based search can answer.

**Acceptance Scenarios**:

1. **Given** no embedding provider is configured, **Then** nothing is indexed, nothing is sent, and
   the semantic search tool is absent.
2. **Given** indexing is configured, **When** a file changes, **Then** only that file is
   re-indexed.

---

### User Story 12 - Remaining Roo Code conveniences (Priority: P3)

Enhance Prompt (wand), the task todo list, intelligent context condensing, the `/init` command,
image generation, background editing and `run_slash_command`. The last three are experimental in
Roo Code (`imageGeneration`, `preventFocusDisruption`, `runSlashCommand`) and stay off by default.
Browser use and custom tools are excluded (FR-036, FR-038).

**Why this priority**: Each is documented Roo Code behaviour and is needed for "all the features",
but none changes what the agent can do to the project.

**Independent Test**: Exercise each convenience once from the chat and confirm the documented
behaviour.

**Acceptance Scenarios**:

1. **Given** text in the input, **When** the wand is clicked, **Then** the text is replaced by an
   enhanced version and Undo restores the original.
2. **Given** a conversation at the condensing threshold, **When** the next request is made,
   **Then** earlier messages are summarised, the token counts before and after are shown, and the
   originals remain reachable through checkpoints.
3. **Given** a multi-step task, **When** it starts, **Then** a todo list with pending, in-progress
   and completed items is shown and can be edited during approval.

---

### User Story 13 - Rename the repository (Priority: P1)

The GitHub repository `prpundge/ethereal-crater` is renamed so that it matches the product, the
local remote is updated, and links in the documentation are updated. The old name keeps
redirecting.

**Why this priority**: Explicit request, small, and independent of everything else. Done on
2026-10-23: `prpundge/ethereal-crater` is now `prpundge/devbeer`, the local remote is updated,
and no tracked file referenced the old name.

**Independent Test**: After the rename, clone from the new URL and fetch from the old one.

**Acceptance Scenarios**:

1. **Given** the new name `devbeer` (decided 2026-10-23), **When** the rename is done, **Then**
   the remote URL in the local clone points at the new name, the old URL redirects, and no
   document in the repository links to the old name.

### Edge Cases

- A file the assistant read has changed before its edit is approved: the edit is refused, the
  file is re-read, and a fresh proposal is made (the existing validator rule, kept).
- A proposed command contains a dangerous substitution or matches a denied prefix: it is never
  auto-approved, whatever the allowed list says.
- `.roomodes`, `.roo/mcp.json` or an imported settings file is malformed: the error names the
  file and the position, and everything not depending on it keeps working.
- `.rooignore` attempts to un-ignore itself, or uses symlinks: the file is always protected and
  symlinks are resolved to a bounded depth.
- The IDE has no Java support: every story works; only the Java-only enrichments are absent, as
  today.
- The provider's context window is exceeded: the context is condensed or reduced and the request
  retried, as Roo Code documents, rather than failing the task.
- The provider becomes unreachable mid-task: the task pauses in a resumable state and the user is
  told; nothing applied so far is lost.
- A second task is started while one is running in the same project: it is refused with a
  message, matching the current single-request rule.
- A file to be read is larger than the limit: it is truncated with a marker, not silently cut.
- A settings export containing plaintext keys is imported: the keys go to the keychain, the user
  is warned that the file is sensitive, and DevBeer never writes such a file without opt-in.
- Secret-shaped values appear in files that are allowed: they are still redacted before anything
  is sent, as today.

## Requirements *(mandatory)*

### Functional Requirements

**Activation and privacy**

- **FR-001**: Every capability in this feature MUST be off until the user enables the assistant.
  While off, no network call is made and no background work is started.
- **FR-002**: Any action that modifies a file, runs a command, or calls an external tool server
  MUST require explicit approval per action unless the user has auto-approved that category.
- **FR-003**: Secret-shaped values MUST be redacted from everything sent to a provider, and files
  matched by `.rooignore` or by DevBeer's existing exclusion rules MUST never be read or sent, except
  where Roo Code documents a deliberate bypass (direct `@` mentions).
- **FR-004**: Credentials MUST live only in the OS keychain. Settings files, exports and logs MUST
  NOT contain them unless the user explicitly opts in at export time.

**The agent loop**

- **FR-005**: The assistant MUST offer these tools with Roo Code's documented semantics:
  `read_file`, `list_files`, `search_files`, `codebase_search` (only when indexing is configured),
  `read_command_output`, `write_to_file`, `apply_diff`, `apply_patch`, `edit`, `edit_file`,
  `search_replace`, `execute_command`, `use_mcp_tool`, `access_mcp_resource`, `switch_mode`,
  `new_task`, `ask_followup_question`, `attempt_completion`, `update_todo_list`, `skill`,
  `run_slash_command` (experimental) and `generate_image` (experimental), plus the aliases
  `search_and_replace` → `edit` and `write_file` → `write_to_file`. This is the tool list of Roo
  Code's final source; `browser_action` is not in it (see FR-036).
- **FR-006**: Every proposed edit MUST be shown as a diff before approval and applied as one
  undoable change; a proposal touching several files MUST be reviewable and approvable as a batch.
- **FR-007**: Command output MUST be streamed, truncated to the configured limits (500 lines by
  default, keeping roughly the first 20% and last 80% with an omission marker, plus a character
  cap), reported with its exit code, stoppable from the chat, and subject to an execution timeout
  of 0 to 600 seconds (0 = none) with an allow-list of exempt commands.
- **FR-008**: Follow-up questions MUST be presented with suggested answers; task completion MUST
  present a result summary.
- **FR-009**: A task todo list with pending, in-progress and completed states MUST be shown in the
  task header and be editable during approval.
- **FR-010**: Context condensing MUST trigger automatically at a configurable threshold (default
  100% of the window), be triggerable manually, accept a custom prompt, and display token counts
  before and after and the cost of the condensing call.
- **FR-011**: Task history MUST persist per project with resume, delete and export; a task MUST
  resume in the mode and with the provider profile it started with.
- **FR-012**: A task MUST be cancellable at any time, and one task at a time MUST run per project.

**Modes**

- **FR-013**: The built-in modes Code, Ask, Architect, Debug and Orchestrator MUST exist with the
  documented tool groups and file restrictions.
- **FR-014**: Custom modes MUST load from `.roomodes` (YAML or JSON) in the project and from a
  global modes file, with the properties `slug`, `name`, `description`, `roleDefinition`,
  `whenToUse`, `customInstructions` and `groups` including per-group file restrictions; a project
  mode MUST fully replace a global one with the same slug; an invalid restriction pattern MUST be
  rejected with the documented error.
- **FR-015**: Modes MUST be switchable from a selector, by a `/slug` prefix, by a cycling keyboard
  shortcut and by accepting a suggestion; each mode MUST remember its last provider profile; the
  selected mode MUST persist across sessions.
- **FR-016**: Modes MUST be exportable and importable in Roo Code's YAML format, including their
  rules files.

**Rules and instructions**

- **FR-017**: Rules MUST load from global then workspace locations, mode-specific before general,
  in Roo Code's documented nine-step order, with the documented fallbacks (`.roorules` and
  `.roorules-{mode}` only when the corresponding directory is empty, `.clinerules` as legacy),
  read recursively in case-insensitive filename order, skipping cache and temporary files,
  resolving symlinks to depth 5, and labelled with the documented headers.
- **FR-018**: `AGENTS.md` (or `AGENT.md`) in the project root MUST be honoured, controlled by a
  setting that defaults to on.
- **FR-019**: The user MUST be able to set global custom instructions, per-mode custom
  instructions, a language preference, and overrides for the support prompts (enhance, condense,
  explain, fix, improve, add to context, terminal helpers).

**Ignore rules**

- **FR-020**: `.rooignore` in the project root MUST be honoured with `.gitignore` syntax, reloaded
  on change, always protecting itself, enforced on reads, writes, diffs, listings, mentions and
  file-reading commands, with ignored files shown with a lock marker by default.

**Slash commands**

- **FR-021**: `/init` MUST analyse the project and generate mode-specific `AGENTS.md` files under
  `.roo/rules-*`; custom commands MUST load from `.roo/commands/*.md` and the global commands
  directory with the front-matter fields `description`, `argument-hint` and `mode`; project
  commands MUST override global ones; built-in and mode commands MUST NOT be overridable.

**Context mentions**

- **FR-022**: The mentions `@file` (text, DOCX, and images for vision-capable models),
  `@folder/` (non-recursive), `@problems`, `@terminal`, `@<commit>`, `@git-changes` and `@url`
  MUST be supported with the documented content and ignore behaviour, and the `@` menu MUST hide
  ignored and generated locations by default. PDF text extraction is **not included**: it needs a
  PDF parser the platform does not bundle. The release notes MUST say so.

**Auto-approve**

- **FR-023**: Auto-approve MUST offer a master toggle with a keyboard shortcut and the categories:
  read (with an outside-workspace option), write (with outside-workspace and protected-files
  options and a write delay, default 1000 ms), execute (allowed and denied prefix lists, longest
  prefix wins, deny wins ties, dangerous-substitution guard), tool servers (requiring per-tool
  always-allow), mode switching, subtasks, and follow-up default answers (timeout 1 to 300
  seconds, default 60). These are the `alwaysAllow*` keys of Roo Code's final settings schema.
- **FR-024**: Auto-approve choices MUST persist and MUST be included in settings export and import.

**Providers and profiles**

- **FR-025**: Named provider profiles MUST support create, rename, delete (never the last one),
  pin, switch from settings and from the chat, linking to modes, per-task persistence, a per-profile
  rate limit (default 0), a custom temperature (defaults 0.0; 0.3 for reasoning models; fixed 1.0
  for thinking-enabled models), a thinking budget and diff-editing settings.
- **FR-026**: Every `apiProvider` value in Roo Code's final source MUST be accepted on import, and
  these MUST be usable as providers: `anthropic`, `openai-native` (OpenAI), `openai` (OpenAI
  Compatible, including Azure), `openrouter`, `ollama`, `lmstudio`, `gemini`, `bedrock`, `vertex`,
  `deepseek`, `mistral`, `moonshot`, `minimax`, `xai`, `zai`, `fireworks`, `sambanova`, `baseten`,
  `requesty`, `unbound`, `litellm`, `vercel-ai-gateway` and `poe`. Not included, and said so in
  the release notes: `vscode-lm` (no equivalent outside VS Code), `openai-codex` (ChatGPT
  Plus/Pro), `gemini-cli` and `qwen-code` (each needs that product's own OAuth client and terms),
  `fake-ai`, and the retired providers (`cerebras`, `chutes`, `deepinfra`, `doubao`,
  `featherless`, `groq`, `huggingface`, `io-intelligence`, `roo`). A profile importing with one
  of those keeps its name and settings but is marked as needing reconfiguration, which is what
  Roo Code itself did with an unknown provider.
- **FR-027**: Token usage, cost and context-window fill MUST be shown per task when the provider
  reports them.

**Settings import and export**

- **FR-028**: Import MUST accept Roo Code's settings export (`roo-code-settings.json`): provider
  profiles and global settings, merged rather than replaced, validated against the expected
  structure, importing valid profiles and warning about invalid ones, failing only when all are
  invalid.
- **FR-029**: An auto-import path MUST be supported, imported at every start with a notification
  on success and a non-blocking warning on failure.
- **FR-030**: Export MUST produce the same structure; Reset MUST return the assistant to a
  fresh-install state after confirmation.

**Checkpoints**

- **FR-031**: Checkpoints MUST be taken at task start and before each modification, outside the
  project's own version control, honouring the project's ignore rules and the documented built-in
  exclusions; nested repositories MUST disable checkpoints with a notification; the initialisation
  timeout MUST be configurable from 10 to 60 seconds (default 30).
- **FR-032**: View Differences, Restore Files Only and Restore Files & Task (with confirmation)
  MUST be available from the task history.

**Subtasks**

- **FR-033**: `new_task` MUST start an isolated subtask in the given mode; the parent MUST pause;
  only the child's completion summary MUST return; creation and completion MUST each require
  approval unless auto-approved; the hierarchy MUST be navigable; the default Orchestrator MUST have
  no read, edit, command or tool-server access.

**Tool servers (MCP)**

- **FR-034**: Servers MUST be configurable in a global file and in `.roo/mcp.json` with the
  documented fields for stdio (`command`, `args`, `cwd`, `env`), streamable HTTP and SSE (`type`,
  `url`, `headers`), and the shared fields `alwaysAllow`, `disabled`, `timeout` (1 to 3600 seconds,
  default 60), `watchPaths` and `disabledTools`; a project entry MUST win over a global one of the
  same name; per-server enable, restart, delete and timeout controls MUST exist; "Enable MCP
  Servers" and "Enable MCP Server Creation" MUST exist and default to on.

**Codebase indexing**

- **FR-035**: An optional semantic index MUST support the documented embedding providers (OpenAI,
  Google Gemini, Ollama, OpenAI Compatible, Mistral, Vercel AI Gateway, Bedrock, OpenRouter) and a
  Qdrant store with URL and optional key; blocks of 100 to 1,000 characters; files over 1 MB,
  binaries and images skipped; incremental updates; a score threshold (default 0.4) and a maximum
  result count; a status indicator; and a clear-index action. Nothing MUST be indexed or sent until
  configured.

**Browser**

- **FR-036**: Browser use is **not included**. Roo Code retired it before the shutdown: the final
  source deprecates and strips the `browser` tool group, has no `browser_action` tool and no
  browser settings keys, and the documentation page is empty. Per SC-007 the release notes MUST
  say so. Revisit only on request.

**Other**

- **FR-037**: Enhance Prompt MUST rewrite the input using a customisable template with a
  `${userInput}` placeholder, optionally with the last 10 messages of history and a separate
  profile, and MUST be undoable.
- **FR-038**: Image generation, background editing and `run_slash_command` (Roo Code's experiment
  ids `imageGeneration`, `preventFocusDisruption`, `runSlashCommand`) MUST be available as
  experimental features that default to off. Custom tools (`customTools`) are **not included**:
  Roo Code's are JavaScript modules, and the IDE has no JavaScript engine without a dependency.
  The release notes MUST say so.
- **FR-039**: Everything MUST work in IDEs without Java support; Java-only enrichments degrade
  silently, as today.
- **FR-040**: The existing DevBeer modules and the existing five assistant actions MUST keep
  working unchanged; the single-fix error analysis remains available as an entry point.
- **FR-041**: The repository MUST be renamed to `devbeer` with the remote and documentation
  links updated. *(Done 2026-10-23.)*

### Key Entities

- **Task**: One conversation with a goal; owns messages, tool calls, approvals, checkpoints, a mode,
  a provider profile, a todo list and an optional parent task.
- **Tool call**: A proposed action with parameters, its approval decision (approved, rejected with
  feedback, auto-approved), and its result.
- **Mode**: Slug, name, role, when-to-use text, custom instructions, allowed tool groups with file
  restrictions, source (built-in, global, project).
- **Rule set**: Instruction files gathered from global and workspace locations in precedence order.
- **Ignore rules**: Patterns from `.rooignore` plus DevBeer's built-in exclusions.
- **Provider profile**: Name, provider, credentials reference, model, temperature, rate limit,
  thinking budget, diff settings, pinned flag, linked modes.
- **Settings bundle**: The importable and exportable set of profiles and global settings.
- **Checkpoint**: A workspace snapshot tied to a task and a point in its history.
- **Tool server**: A configured MCP server with transport, state, tools, resources and
  per-tool approval.
- **Slash command**: Name, description, argument hint, target mode, body; built-in or custom.
- **Index**: Embedding provider, vector store, status, per-file freshness.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A project configured for Roo Code opens in a JetBrains IDE and every documented
  project file (`.roomodes`, `.roo/rules*`, `.roorules*`, `AGENTS.md`, `.rooignore`,
  `.roo/commands/`, `.roo/mcp.json`) takes effect with zero edits — 100% of the file types in the
  Reference section, verified by a fixture project.
- **SC-002**: A valid Roo Code settings export imports with 100% of its valid profiles usable and
  zero credentials retyped.
- **SC-003**: On a fresh installation, zero network connections and zero background work occur
  until the user enables the assistant.
- **SC-004**: Across the whole automated suite, zero files are modified and zero commands are run
  without an explicit approval or a matching auto-approve rule.
- **SC-005**: A representative task — edit two files and run the tests — completes end to end in
  one chat session with no manual edit by the user.
- **SC-006**: Every file change made by a task can be reverted with one action, leaving the project
  byte-identical to the task's starting checkpoint.
- **SC-007**: Every tool, mode, file, setting and provider named in the Reference section is either
  implemented or listed under "Not included" in the release notes — no silent gaps.
- **SC-008**: Behaviour is identical across the supported IDE range and in IDEs without Java
  support, verified by the existing compatibility process.
- **SC-009**: The existing test suite stays green, and the new behaviour is covered by tests that
  need no live model.

## Assumptions

- **Target definition**: "Behave like Roo Code" means the behaviour documented at
  https://roocodeinc.github.io/Roo-Code/ as frozen at the shutdown on 15 May 2026 (last release
  3.50.0, 19 February 2026). Where the documentation is silent, Roo Code's archived public source
  is the reference. Roo Code's successors (ZooCode, Cline, Kilo Code) share this behaviour model.
- **"setting.xml"**: Clarified 2026-10-23 — the owner meant an IDE settings export from another
  machine. That already works through the IDE's own *Settings → Export/Import*: `devbeer-ai.xml`
  is included, and API keys stay in the keychain rather than in the export. Roo Code's own JSON
  export (`roo-code-settings.json`) remains in scope as a parity feature (User Story 4, P2).
- **Delivery order**: Stories are delivered by priority; P1 forms the first release. "All the
  features" is the end state, not the first release.
- **Deliberate deviation from Roo Code**: settings export excludes API keys unless the user opts
  in. Roo Code exported them in plaintext and warned; DevBeer's constitution forbids secrets in
  files.
- **IDE equivalents**: VS Code's Problems panel maps to the IDE's problems and inspections; the VS
  Code terminal maps to the IDE terminal; VS Code secret storage maps to the OS keychain; the
  `roo-cline.*` setting names are kept where they make sense so documentation carries over.
- **Why this exists alongside JetBrains AI Assistant and Junie** (Constitution §IV): the delta is
  bring-your-own provider including fully local models, open and portable configuration files, and
  nothing proprietary in the loop.
- **Constitution**: Amended to 2.0.0 on 2026-10-23 with the owner's decision to adopt the full
  Roo Code model. Principle VII, "the assistant proposes; the user disposes", replaces the 2.3.0
  rule "no autonomous agent, no multi-file edits, no command execution" in
  docs/AI_ASSISTANT.md §10. The no-third-party-dependency rule stands: external programs (tool
  servers, a vector store, a browser, git) are reached over their protocols, never bundled.
- **Unchanged**: The non-AI modules, the plugin id, the optional Java dependency and the supported
  IDE range are not affected.
- **Marketplace**: The listing must disclose that code is sent to the configured third-party
  provider; the assistant stays optional and off by default.

## Reference: Roo Code behaviour model (verified 2026-10-22 against the docs, 2026-10-23 against the archived source)

Facts below were read from the documentation and repository on the dates above. The docs site
redirects from docs.roocode.com to roocodeinc.github.io/Roo-Code; the `/getting-started/` link in
the request is a section index that returns 404 — its pages are `installing`,
`connecting-api-provider` and `your-first-task`.

| Topic | What Roo Code documents | Source page |
|---|---|---|
| Status | Extension shut down 15 May 2026; repository archived read-only the same day; docs point to ZooCode (community fork) and Cline; 24.3k stars | index, github.com/RooCodeInc/Roo-Code |
| Last releases | 3.50.0 and 3.49.0 on 2026-02-19; 3.48.x in mid-February | update-notes |
| Editors | VS Code 1.84+, Cursor, VSCodium, Windsurf; no JetBrains version | getting-started/installing |
| First task loop | Propose → Approve/Reject (with feedback) → result → next step → final result; reads show contents, writes show green/red diff, commands show the exact command | getting-started/your-first-task |
| Modes | Code (read, edit, command, mcp); Ask (read, mcp); Architect (read, mcp, edit limited to Markdown); Debug (all four); Orchestrator (none directly, delegates via `new_task`). Switch by dropdown, `/slug`, Ctrl+. / ⌘+. cycling, or suggestion. Sticky model per mode; selection persists | basic-usage/using-modes |
| Tools (final source) | Groups: read = `read_file`, `search_files`, `list_files`, `codebase_search`; edit = `apply_diff`, `write_to_file`, `generate_image`, `edit`, `search_replace`, `edit_file`, `apply_patch`; command = `execute_command`, `read_command_output`; mcp = `use_mcp_tool`, `access_mcp_resource`; modes = `switch_mode`, `new_task` (always available). Also always available: `ask_followup_question`, `attempt_completion`, `update_todo_list`, `run_slash_command`, `skill`. Aliases `write_file` → `write_to_file`, `search_and_replace` → `edit`. No `browser` group. Tool calls carry `nativeArgs` or `usedLegacyFormat`: native function calling with a legacy XML-tag fallback | src/shared/tools.ts, advanced-usage/available-tools/* |
| Settings export (final source) | `{ providerProfiles: { currentApiConfigName, apiConfigs: { <name>: <provider settings + id> }, modeApiConfigs: { <modeSlug>: <profileId> } }, globalSettings: { … } }`; default file name `roo-code-settings.json`; import merges `apiConfigs` by name, validates each profile separately, strips an unknown `apiProvider` and keeps the profile, fails only when no profile is valid; `globalSettings.customModes` applied per slug | src/core/config/importExport.ts, packages/types/src/global-settings.ts |
| Providers (final source) | Live: anthropic, bedrock, vertex, gemini, openai-native, openai (compatible/Azure), openrouter, ollama, lmstudio, deepseek, mistral, moonshot, minimax, xai, zai, fireworks, sambanova, baseten, requesty, unbound, litellm, vercel-ai-gateway, poe; OAuth-based: openai-codex, gemini-cli, qwen-code; internal: vscode-lm, fake-ai; retired: cerebras, chutes, deepinfra, doubao, featherless, groq, huggingface, io-intelligence, roo | packages/types/src/provider-settings.ts |
| Custom modes | `.roomodes` (project, YAML or JSON) and global `custom_modes.yaml` (or `.json`); properties `slug`, `name`, `description`, `roleDefinition`, `whenToUse`, `customInstructions`, `groups` with `fileRegex` on edit; project replaces global by slug; export/import as YAML with `rulesFiles` | features/custom-modes |
| Rules | Global `~/.roo/rules/`, `~/.roo/rules-{mode}/`; workspace `.roo/rules/`, `.roo/rules-{mode}/`, fallbacks `.roorules`, `.roorules-{mode}`, legacy `.clinerules`; `AGENTS.md`/`AGENT.md` via `roo-cline.useAgentRules` (default true); nine-step system-prompt order; recursive, case-insensitive sort, cache files skipped, symlink depth 5 | features/custom-instructions |
| Ignore | `.rooignore`, gitignore syntax, self-protected, hot reload; blocks read/write/diff, filters listings and `@` mentions, blocks `cat`/`grep` style commands on ignored files; 🔒 marker; `showRooIgnoredFiles` default true | features/rooignore |
| Slash commands | `/init` generates `AGENTS.md` under `.roo/rules-*`; custom `.roo/commands/*.md` and `~/.roo/commands/`, front matter `description`, `argument-hint`, `mode`; project overrides global; built-ins not overridable | features/slash-commands |
| Mentions | `@/path`, `@/folder/`, `@problems`, `@terminal`, `@<commit>`, `@git-changes`, `@https://…`; file and folder mentions bypass ignore files; dropdown hides `node_modules`, `.git`, `dist`, `out` | basic-usage/context-mentions |
| Auto-approve | Master toggle, Ctrl+Alt+A / Cmd+Alt+A (`roo-cline.toggleAutoApprove`); read (outside workspace), write (outside workspace, protected files, write delay default 1000 ms), execute (`roo-cline.allowedCommands`, `roo-cline.deniedCommands`, longest prefix wins, deny wins ties, dangerous-substitution guard), browser, MCP (needs per-tool Always allow), mode switch, subtasks, follow-up default answer 1–300 s default 60 | features/auto-approving-actions |
| Settings | Export/Import/Reset at the bottom of settings; JSON `roo-code-settings.json`; export includes API keys in plaintext; import merges and validates, partial import with warnings; `roo-cline.importSettings`; `roo-cline.autoImportSettingsPath` at every start; Reset wipes profiles, keys, custom modes, settings, history | features/settings-management |
| Profiles | Provider, keys, model, temperature, thinking budget, provider settings, diff config, rate limit (default 0); pin, link to modes, per-task persistence; keys in secret storage | features/api-configuration-profiles |
| Providers (22) | Anthropic, ChatGPT Plus/Pro, AWS Bedrock, DeepSeek, Fireworks AI, Google Gemini, LM Studio, LiteLLM, Mistral AI, Moonshot, Ollama, OpenAI, OpenAI Compatible, OpenRouter, Qwen Code CLI, Requesty, SambaNova, Vercel AI Gateway, GCP Vertex AI, VS Code Language Model API, xAI (Grok), Z AI | providers/ |
| Temperature | Default 0.0; 0.3 for DeepSeek R1 and similar; fixed 1.0 for `:thinking` models; per-profile, recommended ranges Code/Debug 0.0–0.3, Architect 0.4–0.7, Ask 0.7–1.0 | features/model-temperature |
| Checkpoints | Shadow git repository, enabled by default, init timeout 10–60 s default 30; task checkpoint plus one before each modification; View Differences; Restore Files Only / Restore Files & Task; respects `.gitignore` and LFS; nested repos disable it | features/checkpoints |
| Subtasks | Orchestrator mode; `new_task` (`message`, `mode`); isolated context; only `attempt_completion` `result` returns; both steps need approval by default; default Orchestrator has no read/edit/command/mcp | features/boomerang-tasks |
| Todo list | `update_todo_list`; pending / in progress / completed; header summary, tool block, REMINDERS section; editable at approval; `roo-cline.newTaskRequireTodos`, `roo-cline.preventCompletionWithOpenTodos` (both default false) | features/task-todo-list |
| Condensing | On by default; threshold default 100%; manual Condense Context button; custom prompt (`customSupportPrompts.CONDENSE`); 30% of window reserved; 25% reduction and retry on overflow | features/intelligent-context-condensing |
| Multi-file edits | `apply_diff` batch; Batch Diff Approval; graduated from experimental, on by default | features/experimental/concurrent-file-edits |
| MCP | Global `mcp_settings.json`, project `.roo/mcp.json`, project wins; stdio (`command`, `args`, `cwd`, `env`), `streamable-http` and `sse` (`type`, `url`, `headers`); `alwaysAllow`, `disabled`, `timeout` 1–3600 default 60, `watchPaths`, `disabledTools`; Enable MCP Servers and Enable MCP Server Creation both default on; no pre-installed servers | features/mcp/using-mcp-in-roo |
| Indexing | Tree-sitter blocks → embeddings → Qdrant; providers OpenAI, Gemini, Ollama, OpenAI Compatible, Mistral, Vercel AI Gateway, Bedrock, OpenRouter; blocks 100–1,000 chars; skip >1 MB and binaries; score threshold default 0.4; status colours; Clear Index Data | features/codebase-indexing |
| Terminal | `execute_command`; inline terminal (recommended) or VS Code terminal; output limit 500 lines (20% head / 80% tail), character limit, compress progress bars; `roo-cline.commandExecutionTimeout` 0–600 default 0; `roo-cline.commandTimeoutAllowlist`; shell-integration timeout 15 s; command delay 0 ms | features/shell-integration |
| Enhance Prompt | Wand icon; template with `${userInput}`; Test Enhancement; separate API profile option; "Include task history" uses last 10 messages | features/enhance-prompt |
| Experimental | Custom Tools, Background Editing, Image Generation, Run Slash Command; experiment ids `customTools`, `preventFocusDisruption`, `imageGeneration`, `runSlashCommand` | features/experimental/experimental-features, packages/types/src/experiment.ts |
| Support prompts | ENHANCE (`${userInput}`), CONDENSE, EXPLAIN, FIX, IMPROVE, ADD_TO_CONTEXT (`${filePath}`, `${startLine}`, `${endLine}`, `${selectedText}`, `${userInput}`, `${diagnosticText}`), TERMINAL_ADD_TO_CONTEXT, TERMINAL_FIX, TERMINAL_EXPLAIN (`${terminalContent}`), NEW_TASK | src/shared/support-prompt.ts |
| Task history item | `id`, `number`, `ts`, `task`, `tokensIn`, `tokensOut`, `cacheWrites`, `cacheReads`, `totalCost`, `size`, `workspace`, `mode`, `apiConfigName`, `status` (active, completed, delegated), `rootTaskId`, `parentTaskId`, `childIds`, `awaitingChildId`, `completedByChildId`, `completionResultSummary` | packages/types/src/history.ts |
| Browser use | Retired upstream before the shutdown: the `browser` group is deprecated and stripped from mode configs, there is no `browser_action` tool and no browser settings key, and the documentation page is empty. Excluded here (FR-036) | packages/types/src/mode.ts, src/shared/tools.ts |
| Not retrieved | The fast-edits page returned no body on the archived site; diff strategy details come from the apply_diff page and src/shared/tools.ts | — |
