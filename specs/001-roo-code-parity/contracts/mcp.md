# Contract: Model Context Protocol client

DevBeer is an MCP client, never a server. JSON-RPC 2.0, protocol versions `2025-06-18` preferred
with fallback to `2025-03-26` and `2024-11-05`, negotiated in `initialize`.

## Transports

| Transport | How |
|---|---|
| stdio | Process started with `command`, `args` (after `${env:VAR}` substitution), `cwd`, `env` merged over the IDE's environment; one JSON-RPC message per line on stdin/stdout; stderr captured to the server log view |
| streamable-http | `POST {url}` with `Content-Type: application/json`, `Accept: application/json, text/event-stream`, custom `headers`; a JSON reply or an SSE stream of replies; `Mcp-Session-Id` echoed once received; `DELETE {url}` on shutdown when a session id exists |
| sse (legacy) | `GET {url}` opens the event stream; the `endpoint` event gives the POST URL; requests go by `POST` and replies arrive on the stream |

## Lifecycle

1. `initialize` → `{ protocolVersion, capabilities: { roots: {}, sampling: {} absent }, clientInfo: { name: "DevBeer", version } }`; then `notifications/initialized`.
2. `tools/list` (paged with `cursor`), `resources/list`, `resources/templates/list`.
3. On `notifications/tools/list_changed` or `resources/list_changed`: re-list.
4. `ping` every 60 s on idle HTTP sessions; stdio liveness by process state.
5. Restart on `watchPaths` change, on user request, or after a transport error (three attempts, exponential back-off, then ERROR state).
6. Shutdown on IDE exit or project close: close streams, terminate the process tree.

## Calls

- `tools/call` → `{ name, arguments }`; result `content[]` of `text`, `image` (`data`, `mimeType`),
  `audio`, `resource` (`uri`, text or blob) and `isError`.
- `resources/read` → `{ uri }`; result `contents[]` with `uri`, `mimeType`, `text` or `blob`.
- Every call is bounded by the server's `timeout` (1–3600 s, default 60).

## Approval

`use_mcp_tool` requires approval unless the global "Use MCP servers" auto-approve is on **and**
the tool is in the server's `alwaysAllow`. `access_mcp_resource` honours only the global option.
Disabled servers and `disabledTools` are absent from the prompt and refuse calls.

## Settings

`mcpEnabled` (default on) removes the two tools and the MCP prompt section when off and connects
to nothing. "Enable MCP Server Creation" (default on) controls whether the prompt includes the
instructions for building a server (`fetch_instructions` with task `create_mcp_server` — internal,
not a user-facing tool).

## Files

See `contracts/config-files.md` for `.roo/mcp.json` and `~/.roo/mcp_settings.json`.
