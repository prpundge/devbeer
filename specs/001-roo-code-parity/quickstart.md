# Quickstart: validating the Roo Code-style agent

Runnable scenarios that prove the feature end to end. Prerequisites, commands and the expected
outcome for each. Implementation detail lives in `tasks.md` and the code; this is the run guide.

## Prerequisites

- JDK 17, the repository checked out, `./gradlew test` green.
- A local model for the hermetic-but-real runs: `ollama serve` and `ollama pull qwen2.5-coder:7b`.
- `git` on `PATH` (checkpoints).
- The fixture project `src/test/resources/roo/fixture-project/` containing `.roomodes`,
  `.roo/rules/`, `.roo/rules-code/`, `.roorules`, `AGENTS.md`, `.rooignore`,
  `.roo/commands/review.md`, `.roo/mcp.json`, two source files and one test.
- A Roo Code settings export fixture `src/test/resources/roo/roo-code-settings.json` with three
  profiles, one of them invalid.

## Automated checks

```bash
./gradlew test                                   # all suites, no live model needed
./gradlew test --tests '*AgentLoopTest'          # loop with the scripted fake provider (SC-004)
./gradlew test --tests '*RooFixtureTest'         # every project file honoured (SC-001)
./gradlew test --tests '*SettingsImportTest'     # import merge and secret handling (SC-002)
./gradlew test --tests '*CheckpointTest'         # skips itself when git is absent (SC-006)
./gradlew verifyPlugin                           # 231 → 243.*, including the floor (SC-008)
DEVBEER_TEST_OLLAMA_MODEL=qwen2.5-coder:7b ./gradlew test --tests '*LiveAgentTest'   # opt-in
```

Expected: 0 failures; the verifier reports Compatible for every IDE; the live test completes a
two-file edit plus a test run through real approvals driven by the test harness.

## Manual scenario 1 — first task (US1)

1. `./gradlew runIde`, open the fixture project.
2. Settings → Tools → DevBeer AI Assistant: enable, profile "Local" = Ollama,
   `http://localhost:11434`, `qwen2.5-coder:7b`, Test connection.
3. Open the *DevBeer AI* tool window, mode Code, type: "Add a null check to `charge` in
   `PaymentService.kt` and a unit test for it."
4. Expected: a `read_file` proposal appears first; Approve shows the file; an `apply_diff`
   proposal opens the diff viewer; Approve applies it (Undo reverts it in one step); an
   `execute_command` proposal shows `./gradlew test` verbatim; Approve streams output and the exit
   code; `attempt_completion` ends with a summary. The task header shows tokens and cost; a
   checkpoint marker precedes the edit.
5. Press Stop during a provider call: the call is cancelled and nothing further is applied.
6. Restart the IDE: the task is in History and resumes in Code mode with profile "Local".

## Manual scenario 2 — modes (US2)

- Switch to Ask and ask for the same change: the edit is refused before approval and the
  assistant explains it cannot edit in Ask mode.
- Switch to Architect, ask to edit `PaymentService.kt`: FileRestrictionError names the mode,
  `\.md$`, and the path. Ask to update `README.md`: allowed.
- Type `/debug` and send: the mode changes and the input clears. `Ctrl+Alt+Shift+M` cycles.

## Manual scenario 3 — Roo project files (US3)

With the fixture project open:

- The selector lists `docs-writer` from `.roomodes`; an edit to a `.kt` file in that mode is
  blocked by its `\.(md|mdx)$` restriction.
- Settings → Prompts → "Preview system prompt" shows the rules in the nine-step order with
  `# Rules from …` headers; `.roorules` is absent because `.roo/rules/` has files.
- Ask the assistant to read `config/secrets.json`: `Access denied by .rooignore rules.`;
  `list_files` shows it with 🔒.
- Type `/review`: the command appears in the menu with its description; running it switches to
  the mode in its front matter and sends the body.
- Create `~/.roo/rules/00-global.md`: the preview shows it before the workspace rules.

## Manual scenario 4 — settings import (US4)

Settings → Import & Export → Import, choose the fixture export. Expected: two profiles added, one
warning naming the invalid one, keys stored in the keychain (Settings shows "key stored", the XML
file contains none), `modeApiConfigs` applied. Export produces a file with the same structure and
no keys unless "Include API keys" is ticked.

## Manual scenario 5 — auto-approve (US5)

Auto-approve → Execute: allow `./gradlew`, deny `rm`. Run a task that needs both: the Gradle
command runs without a click, `rm -rf build` waits for approval. Turn the master toggle off: both
wait. A command containing `$(…)` always waits.

## Manual scenario 6 — checkpoints (US8)

Run a task that edits three files. History → the first checkpoint → View Differences shows the
three files; Restore Files Only puts every file back byte-identically and keeps the chat; the
project's own `git status` shows nothing from DevBeer. Add a nested repository under `vendor/`:
the next task reports checkpoints disabled.

## Manual scenario 7 — subtasks (US9)

Orchestrator mode: "Design and implement a retry policy for `PaymentClient`." Expected: a
`new_task` proposal for Architect; the parent pauses; the child's `attempt_completion` returns
its summary; a second subtask in Code; the hierarchy is visible in History.

## Manual scenario 8 — MCP and index (US10, US11)

- `.roo/mcp.json` with a stdio server (any reference server): the server starts, its tools are
  listed under MCP, a call waits for approval, Always allow skips it next time. Turn "Enable MCP
  Servers" off: the tools vanish from the prompt and no process is started.
- Index: configure Ollama embeddings and a local Qdrant (`docker run -p 6333:6333 qdrant/qdrant`);
  status turns yellow then green; a `codebase_search` returns scored chunks; edit one file and
  only it is re-indexed.

## Release gate

Before any upload: the automated checks above, the manual scenarios for the stories in the
release, and the release notes' "Not included" list naming browser use, custom tools, PDF
mentions and the OAuth-only providers (SC-007).
