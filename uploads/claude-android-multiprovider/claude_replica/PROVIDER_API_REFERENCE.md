# Provider gateway API reference

All `/v1/*` endpoints require `Authorization: Bearer <ADMIN_TOKEN>`.

## Detect provider from key format

`POST /v1/providers/detect` with `{ "apiKey": "..." }`. This performs prefix-based local gateway classification only; it does not store or forward the key to providers. Ambiguous prefixes return candidate IDs and must be selected manually. It cannot reliably identify every provider key format.

## Add key

`POST /v1/providers`

```json
{"provider":"anthropic","apiKey":"..."}
```

Provider IDs: `anthropic`, `openai`, `gemini`, `openrouter`, `deepseek`, `groq`, `mistral`, `xai`, `custom`. `custom` requires an HTTPS `baseUrl` implementing OpenAI-compatible `/models` and `/chat/completions` endpoints.

## Discover models

`GET /v1/providers/anthropic/models`

Returns `{ "models": [{"id":"..."}] }` from the provider's own API.

## Stream chat

`POST /v1/chat/completions`

```json
{
  "provider":"openai",
  "model":"gpt-...",
  "messages":[{"role":"user","content":"Hello"}]
}
```

SSE events:

- `ready`: stream opened
- `text`: `{ "type":"text", "text":"..." }`
- `finish`: provider finish reason
- `error`: `{ "error":"..." }`
- `done`: stream complete

The client can cancel by aborting its fetch; the gateway aborts the upstream provider request on client disconnect.
