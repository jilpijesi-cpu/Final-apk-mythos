# Claude Replica AI Gateway

Node.js 20+ backend; zero third-party runtime dependencies.

## Local development

```sh
cp .env.example .env
# Replace both secrets with random values (openssl rand -hex 32)
set -a; . ./.env; set +a
node server.mjs
```

## Endpoints

- `GET /health` — health probe (public)
- `POST /v1/providers/detect` — heuristic key-prefix detection; ambiguous formats require manual provider selection
- `GET /v1/providers` — configured provider metadata (admin bearer token)
- `POST /v1/providers` — `{provider, apiKey, baseUrl?}`; encrypts key at rest
- `GET /v1/providers/:id/models` — live provider model discovery
- `POST /v1/providers/:id/test` — validates credentials via model discovery
- `DELETE /v1/providers/:id` — removes provider key/config
- `POST /v1/chat/completions` — normalized SSE stream; body `{provider, model, messages:[{role,content}]}`

Provider keys are encrypted with AES-256-GCM. `VAULT_MASTER_KEY` must be at least 32 bytes; `ADMIN_TOKEN` at least 24 characters. Keep both secret and back up the master key separately from the vault.
