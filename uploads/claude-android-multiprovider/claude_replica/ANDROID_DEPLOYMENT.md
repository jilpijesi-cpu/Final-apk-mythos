# Android deployment (source project)

This deliverable includes an Android WebView shell source project and a Node.js AI gateway intended to run locally on the same Android device through Termux. A compiled APK is **not** included because this build environment has no Android SDK/Gradle installation or signing configuration.

## 1. Install the gateway on Android

1. Install Termux from its official F-Droid/GitHub distribution (not the obsolete Play Store build).
2. Copy the `backend/` directory from this package into Android shared storage, for example `Download/claude-replica/backend/`.
3. Open Termux and run:

   ```sh
   termux-setup-storage
   bash ~/storage/downloads/claude-replica/backend/setup-android-termux.sh
   ```

   Adjust the path if the package was extracted elsewhere.
4. Start the gateway:

   ```sh
   ~/claude-replica-gateway/start.sh
   ```

5. Keep Termux running. The gateway binds to `127.0.0.1:8787` only.
6. Open the app, navigate to **Settings → AI Providers → Configure**, set Gateway URL to `http://127.0.0.1:8787`, and paste the `ADMIN_TOKEN` printed by the installer.
7. Add a provider API key. The key is sent to the local gateway, encrypted using AES-256-GCM, and stored in Termux's private home directory.

## 2. Build Android shell

Open the `android/` directory in Android Studio (JDK 17+, Android SDK platform 35, Android Gradle Plugin 8.7.3). Sync Gradle and build `app` > `assembleDebug`. The app loads the UI from `app/src/main/assets/` and connects to the local Termux gateway.

## 3. Provider/model flow

- Add a provider under Settings → AI Providers.
- The gateway attempts provider detection from known key prefixes; ambiguous key formats require manual provider selection. It validates the provider ID and encrypts the key before writing the vault.
- Use **Models** to call the provider's model-discovery endpoint.
- Selecting a model updates the active provider/model in local app preferences.
- The model selector in the top bar refreshes live model discovery.
- Chat sends the conversation history to `/v1/chat/completions` and renders SSE text deltas incrementally.
- Stop cancels the browser request; the gateway aborts the upstream provider request when the client disconnects.

## Security notes

- The vault uses AES-256-GCM with a random 96-bit nonce and authentication tag per secret.
- `VAULT_MASTER_KEY` and `ADMIN_TOKEN` are generated randomly and stored in a Termux-private `.env` file with mode 0600; the vault file is mode 0600.
- The browser only holds the gateway admin token in `sessionStorage`; provider API keys are not stored in browser storage or application JavaScript.
- Back up `VAULT_MASTER_KEY` securely. Losing it makes the encrypted vault unrecoverable. Do not share `.env` or the vault file.
- This single-user local gateway is not a multi-tenant production service. If exposed remotely, place it behind HTTPS, restrict network access, and implement per-user authentication/authorization before use.
- The Android shell currently permits cleartext HTTP to support loopback Termux. Do not change the gateway bind address to `0.0.0.0` or expose port 8787 to untrusted networks.
- AES-GCM encryption at rest is implemented, but the master key is stored in Termux private app storage, not hardware-backed Android Keystore. This is not equivalent to a hardware security module.

## Supported adapters

Anthropic Messages API, OpenAI Chat Completions-compatible APIs (OpenAI, OpenRouter, DeepSeek, Groq, Mistral, xAI, custom compatible endpoint), and Google Gemini generateContent streaming API. Each provider has different model availability, context limits, tool calling, image/audio support, and rate limits. This gateway currently normalizes text streaming only; it does not claim full parity for provider-specific tools or multimodal features.
