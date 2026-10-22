# Contract: Provider transports

All transports use the JDK `HttpClient`, DevBeer's JSON parser and formatter, and the same
`LlmProvider` abstraction the 2.3.0 layer defines, extended with streaming, tool definitions and
usage reporting. No SDK, no third-party library.

## Common interface

```
interface ChatProvider {
  val family: TransportFamily
  fun supportsNativeTools(model: String): Boolean
  fun stream(request: ChatRequest, sink: ChatSink, cancelled: () -> Boolean): ChatOutcome
  fun listModels(): List<ModelInfo>          // where the provider has an endpoint
  fun embed(texts: List<String>, model: String): List<FloatArray>   // embedding-capable families
}
ChatRequest  = messages (system, user, assistant, tool), tools (name, description, JSON schema),
               model, temperature?, maxTokens?, thinking?, metadata
ChatSink     = onText(delta), onToolCall(partial id/name/args), onUsage(stats), onReasoning(delta)
ChatOutcome  = Success(finishReason, usage) | Failure(kind, detail)   // kinds as in LlmFailure
```

Failure kinds map HTTP 401/403 → AUTH, 429 → RATE_LIMITED, 408/timeout → TIMEOUT, connection
refused → UNREACHABLE, 4xx with a context-length message → CONTEXT_WINDOW (triggers condensing),
everything else → OTHER with the body summarised to 200 characters.

## OpenAI Chat Completions family

- Endpoint `POST {base}/chat/completions`; `Authorization: Bearer <key>`; Azure uses
  `api-key: <key>` and `?api-version=<azureApiVersion>` on `{base}/openai/deployments/{model}/…`.
- Body: `model`, `messages` (`role`: system/user/assistant/tool; `tool_calls` on assistant;
  `tool_call_id` on tool messages; image parts as `image_url` data URLs), `tools` (`type:
  function`, `function.parameters` JSON schema), `temperature`, `max_tokens` or
  `max_completion_tokens`, `stream: true`, `stream_options: { include_usage: true }`,
  `reasoning_effort` when enabled.
- Streaming: SSE `data:` lines of chat chunks; `choices[0].delta.content`,
  `choices[0].delta.tool_calls[].function.{name,arguments}` accumulated by index; `usage` on the
  final chunk; `[DONE]` terminator.
- Extras: OpenRouter adds `HTTP-Referer` and `X-Title`, supports `provider.order` from
  `openRouterSpecificProvider`, reports cost in `usage.cost`, and lists models at `GET /models`
  with pricing and context length. DeepSeek R1-style models use `openAiR1FormatEnabled` (no
  system role). LM Studio and compatible gateways may ignore `tools`; the legacy format is used
  when `supportsNativeTools` is false.
- Embeddings: `POST {base}/embeddings` with `input[]`, `model`; `data[].embedding`.

## Anthropic Messages

- `POST {base}/v1/messages`; headers `x-api-key` (or `Authorization: Bearer` when
  `anthropicUseAuthToken`), `anthropic-version: 2023-06-01`, optional
  `anthropic-beta: context-1m-2025-08-07` when `anthropicBeta1MContext`.
- Body: `model`, `system`, `messages` (`content` blocks: text, image, `tool_use`, `tool_result`),
  `tools` (`input_schema`), `max_tokens`, `temperature` (omitted when thinking), `thinking:
  { type: enabled, budget_tokens }`, `stream: true`; optional `cache_control` on system and the
  last user turns.
- Streaming events: `message_start` (usage input tokens, cache reads and writes),
  `content_block_start` / `content_block_delta` (`text_delta`, `input_json_delta`,
  `thinking_delta`), `message_delta` (stop reason, output tokens), `message_stop`.

## Gemini

- `POST {base}/v1beta/models/{model}:streamGenerateContent?alt=sse`, header `x-goog-api-key`.
- Body: `systemInstruction`, `contents` (`role`: user/model; parts: text, inlineData,
  functionCall, functionResponse), `tools: [{ functionDeclarations }]`, `generationConfig`
  (temperature, maxOutputTokens, thinkingConfig).
- Streaming: SSE of `candidates[0].content.parts`, `usageMetadata` (prompt, candidates, cached).
- Embeddings: `:embedContent` / `:batchEmbedContents`.

## Ollama native

- `POST {base}/api/chat` with `messages`, `tools`, `stream: true`, `options: { temperature,
  num_ctx }`, `keep_alive`; NDJSON lines with `message.content`, `message.tool_calls`, final
  line `done: true` with `prompt_eval_count` and `eval_count`. `GET /api/tags` lists models;
  `POST /api/show` reports the context length. Optional `Authorization: Bearer` for hosted
  Ollama. Embeddings: `POST /api/embed`.
- The 2.3.0 one-shot actions keep `POST /api/generate`.

## AWS Bedrock Converse

- `POST https://bedrock-runtime.{region}.amazonaws.com/model/{modelId}/converse` (and
  `converse-stream` once event-stream decoding is implemented); custom endpoint when
  `awsBedrockEndpointEnabled`; inference profile ARNs via `awsCustomArn`; cross-region
  inference prefixes when `awsUseCrossRegionInference`.
- Auth: SigV4 (`AWS4-HMAC-SHA256`, service `bedrock`) over static keys plus optional session
  token, or a named profile read from `~/.aws/credentials`; or `awsApiKey` as a bearer token
  when `awsUseApiKey`.
- Body: `system`, `messages` (content blocks text, image, toolUse, toolResult), `toolConfig`,
  `inferenceConfig` (temperature, maxTokens), optional prompt caching points.
- Usage from `usage.inputTokens` / `outputTokens` / cache fields.

## Vertex AI

- Credentials from `vertexJsonCredentials` or `vertexKeyFile`: a service-account JWT
  (RS256, scope `https://www.googleapis.com/auth/cloud-platform`) exchanged at
  `https://oauth2.googleapis.com/token`; cached until expiry.
- Anthropic models: `POST https://{region}-aiplatform.googleapis.com/v1/projects/{project}/locations/{region}/publishers/anthropic/models/{model}:streamRawPredict`
  with the Anthropic body and `anthropic_version: vertex-2023-10-16`.
- Gemini models: the Gemini body at the Vertex `generateContent` endpoint.

## Model catalogue and pricing

`resources/ai/models.json` ships context window, max output, prices per million tokens, cache
prices, and capability flags (images, native tools, thinking) for well-known model ids per
family; unknown ids fall back to the profile's overrides and a conservative 128k window. Cost per
turn = inputs × input price + outputs × output price + cache reads × cache-read price + cache
writes × cache-write price, unless the provider reports a cost directly.

## Rate limiting

Per profile: `rateLimitSeconds` minimum spacing between requests, enforced before sending;
`requestDelaySeconds` is applied between consecutive automatic turns; 429 responses back off
with the `Retry-After` header when present, else 5, 10, 20 seconds, three attempts.
