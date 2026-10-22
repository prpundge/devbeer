# Contract: Roo Code settings import and export

Source of truth: `src/core/config/importExport.ts`, `packages/types/src/global-settings.ts`,
`packages/types/src/provider-settings.ts` in the archived Roo Code repository (read 2026-10-23).

## File

Default name `roo-code-settings.json`. Shape:

```json
{
  "providerProfiles": {
    "currentApiConfigName": "default",
    "apiConfigs": {
      "default": { "id": "abc123", "apiProvider": "openrouter", "openRouterApiKey": "…", "openRouterModelId": "anthropic/claude-sonnet-4.5", "rateLimitSeconds": 0, "modelTemperature": null },
      "local": { "id": "def456", "apiProvider": "ollama", "ollamaBaseUrl": "http://localhost:11434", "ollamaModelId": "qwen2.5-coder:7b" }
    },
    "modeApiConfigs": { "code": "abc123", "ask": "def456" }
  },
  "globalSettings": { "autoApprovalEnabled": true, "alwaysAllowReadOnly": true, "allowedCommands": ["npm test"], "customModes": [ … ], "customInstructions": "…" }
}
```

`providerProfiles` is required; `globalSettings` is optional.

## Import algorithm (as Roo Code)

1. Parse with the lenient wrapper: `providerProfiles` object, `apiConfigs` as a map of name →
   anything, `globalSettings` optional.
2. For each profile: if `apiProvider` is present but not a known provider name, remove it and
   record a warning; the profile is kept for reconfiguration. Then validate the profile; a failing
   profile is skipped with a warning.
3. If no profile is valid and there are warnings, the import fails with those warnings.
4. Merge `apiConfigs` as `{ ...existing, ...imported }` (same name = overwrite); existing profiles
   absent from the file are kept.
5. Merge `modeApiConfigs` the same way, unvalidated.
6. `currentApiConfigName`: the imported name if it is among the valid profiles, else the first
   valid imported profile (with a warning), else the previous value.
7. Apply `globalSettings.customModes` per slug; copy the other global keys DevBeer knows; list the
   keys it does not have as "ignored" in the result dialog.
8. Every secret-shaped value is moved into the OS keychain and never persisted in XML; the user is
   reminded that the file itself holds plaintext keys.

## Provider names accepted

Live and usable: `anthropic`, `bedrock`, `vertex`, `gemini`, `openai-native`, `openai`,
`openrouter`, `ollama`, `lmstudio`, `deepseek`, `mistral`, `moonshot`, `minimax`, `xai`, `zai`,
`fireworks`, `sambanova`, `baseten`, `requesty`, `unbound`, `litellm`, `vercel-ai-gateway`, `poe`.
Accepted but marked "needs reconfiguration": `openai-codex`, `gemini-cli`, `qwen-code`,
`vscode-lm`, `fake-ai`, and the retired `cerebras`, `chutes`, `deepinfra`, `doubao`,
`featherless`, `groq`, `huggingface`, `io-intelligence`, `roo`. Anything else is stripped with a
warning, as Roo did.

## Profile keys

Common: `id`, `apiProvider`, `apiModelId`, `includeMaxTokens`, `todoListEnabled`,
`modelTemperature`, `rateLimitSeconds`, `consecutiveMistakeLimit`, `enableReasoningEffort`,
`reasoningEffort`, `modelMaxTokens`, `modelMaxThinkingTokens`, `verbosity`, `diffEnabled`,
`fuzzyMatchThreshold`.

| Provider | Keys (secrets in bold) |
|---|---|
| anthropic | **apiKey**, anthropicBaseUrl, anthropicUseAuthToken, anthropicBeta1MContext |
| openrouter | **openRouterApiKey**, openRouterModelId, openRouterBaseUrl, openRouterSpecificProvider |
| bedrock | **awsAccessKey**, **awsSecretKey**, **awsSessionToken**, awsRegion, awsUseCrossRegionInference, awsUseGlobalInference, awsUsePromptCache, awsProfile, awsUseProfile, **awsApiKey**, awsUseApiKey, awsCustomArn, awsModelContextWindow, awsBedrockEndpointEnabled, awsBedrockEndpoint, awsBedrock1MContext, awsBedrockServiceTier |
| vertex | vertexKeyFile, **vertexJsonCredentials**, vertexProjectId, vertexRegion, vertex1MContext |
| openai (compatible) | openAiBaseUrl, **openAiApiKey**, openAiR1FormatEnabled, openAiModelId, openAiCustomModelInfo, openAiUseAzure, azureApiVersion, openAiStreamingEnabled, openAiHostHeader, openAiHeaders |
| openai-native | **openAiNativeApiKey**, openAiNativeBaseUrl, openAiNativeServiceTier |
| ollama | ollamaModelId, ollamaBaseUrl, **ollamaApiKey**, ollamaNumCtx |
| lmstudio | lmStudioModelId, lmStudioBaseUrl, lmStudioDraftModelId, lmStudioSpeculativeDecodingEnabled |
| gemini | **geminiApiKey**, googleGeminiBaseUrl |
| mistral | **mistralApiKey**, mistralCodestralUrl |
| deepseek | deepSeekBaseUrl, **deepSeekApiKey** |
| moonshot | moonshotBaseUrl, **moonshotApiKey** |
| minimax | minimaxBaseUrl, **minimaxApiKey** |
| xai | **xaiApiKey** |
| zai | **zaiApiKey**, zaiApiLine |
| fireworks | **fireworksApiKey** |
| sambanova | **sambaNovaApiKey** |
| baseten | **basetenApiKey** |
| requesty | requestyBaseUrl, **requestyApiKey**, requestyModelId |
| unbound | **unboundApiKey**, unboundModelId |
| litellm | litellmBaseUrl, **litellmApiKey**, litellmModelId, litellmUsePromptCache |
| vercel-ai-gateway | **vercelAiGatewayApiKey**, vercelAiGatewayModelId |
| poe | **poeApiKey**, poeBaseUrl |

Model-id keys: `apiModelId`, `openRouterModelId`, `openAiModelId`, `ollamaModelId`,
`lmStudioModelId`, `lmStudioDraftModelId`, `requestyModelId`, `unboundModelId`,
`litellmModelId`, `vercelAiGatewayModelId`.

## Global settings keys honoured

Auto-approve: `autoApprovalEnabled`, `alwaysAllowReadOnly`, `alwaysAllowReadOnlyOutsideWorkspace`,
`alwaysAllowWrite`, `alwaysAllowWriteOutsideWorkspace`, `alwaysAllowWriteProtected`,
`alwaysAllowExecute`, `alwaysAllowMcp`, `alwaysAllowModeSwitch`, `alwaysAllowSubtasks`,
`alwaysAllowFollowupQuestions`, `followupAutoApproveTimeoutMs`, `allowedCommands`,
`deniedCommands`, `commandExecutionTimeout`, `commandTimeoutAllowlist`, `writeDelayMs`,
`requestDelaySeconds`, `allowedMaxRequests`, `allowedMaxCost`, `preventCompletionWithOpenTodos`.

Context: `autoCondenseContext`, `autoCondenseContextPercent`, `customCondensingPrompt` (migrated
into `customSupportPrompts.CONDENSE`), `includeCurrentTime`, `includeCurrentCost`,
`maxGitStatusFiles`, `includeDiagnosticMessages`, `maxDiagnosticMessages`, `maxOpenTabsContext`,
`maxWorkspaceFiles`, `showRooIgnoredFiles`, `enableSubfolderRules`, `maxImageFileSize`,
`maxTotalImageSize`, `profileThresholds`.

Checkpoints and terminal: `enableCheckpoints`, `checkpointTimeout`, `terminalOutputPreviewSize`,
`terminalCommandDelay`, `diagnosticsEnabled`.

Modes and prompts: `mode`, `customModes`, `customModePrompts`, `customSupportPrompts`,
`customInstructions`, `language`, `includeTaskHistoryInEnhance`, `enhancementApiConfigId`,
`pinnedApiConfigs`, `modeApiConfigs`, `currentApiConfigName`, `enterBehavior`.

Features: `mcpEnabled`, `experiments` (`preventFocusDisruption`, `imageGeneration`,
`runSlashCommand`; `customTools` is read and reported as not supported), `codebaseIndexConfig`
(`codebaseIndexEnabled`, `codebaseIndexQdrantUrl`, `codebaseIndexEmbedderProvider`,
`codebaseIndexEmbedderBaseUrl`, `codebaseIndexEmbedderModelId`,
`codebaseIndexEmbedderModelDimension`, `codebaseIndexSearchMinScore`,
`codebaseIndexSearchMaxResults`, `codebaseIndexOpenAiCompatibleBaseUrl`,
`codebaseIndexOpenAiCompatibleModelDimension`, `codebaseIndexBedrockRegion`,
`codebaseIndexBedrockProfile`, `codebaseIndexOpenRouterSpecificProvider`), `codebaseIndexModels`,
`imageGenerationProvider`, `openRouterImageGenerationSelectedModel`, `disabledTools`.

Imported but ignored (no equivalent): `taskHistory` (DevBeer keeps its own per project),
`lastShownAnnouncementId`, `dismissedUpsells`, `historyPreviewCollapsed`,
`reasoningBlockCollapsed`, `hasOpenedModeSelector`, `ttsEnabled`, `ttsSpeed`, `soundEnabled`,
`soundVolume`, the VS Code shell-integration keys (`terminalShellIntegrationTimeout`,
`terminalShellIntegrationDisabled`, `terminalPowershellCounter`, `terminalZsh*`,
`terminalZdotdir`, `execaShellPath`), `last*Path`, `worktree*`.

Secrets among global keys (keychain): `openRouterImageApiKey`, `codeIndexOpenAiKey`,
`codeIndexQdrantApiKey`, `codebaseIndexOpenAiCompatibleApiKey`, `codebaseIndexGeminiApiKey`,
`codebaseIndexMistralApiKey`, `codebaseIndexVercelAiGatewayApiKey`, `codebaseIndexOpenRouterApiKey`.

## Export

Same structure. Secret fields are omitted unless the user ticks "Include API keys", in which case
the dialog repeats Roo's warning that the file is highly sensitive. `taskHistory` is not exported.

## Auto-import

Setting `autoImportSettingsPath` (absolute or `~`-prefixed; other relative paths resolve against
the home directory). Runs at every IDE start; success shows `Successfully imported settings from
<file name>`; failure shows a non-blocking warning and keeps the previous settings.

## Reset

Deletes profiles, keychain entries, custom modes (DevBeer's own, not the project files), global
settings and task history after a confirmation dialog.
