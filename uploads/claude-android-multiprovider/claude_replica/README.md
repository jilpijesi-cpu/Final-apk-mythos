# Claude Replica — Android-first Multi-Provider build

This package extends the earlier responsive Claude-style interface with a real BYOK AI gateway and an Android WebView shell source project. It is an independent reconstruction, not an official Anthropic app and not a decompiled source release.

## What is implemented

- Existing Claude-inspired mobile/desktop UI and local chat history.
- AI Provider Manager in Settings: configure gateway, add/remove providers, heuristically detect common API-key prefixes, test credentials, discover models, and select active provider/model.
- Node.js 20+ gateway with no third-party runtime dependencies.
- AES-256-GCM encrypted API-key vault; provider secrets are not returned to the UI.
- Streaming adapters: Anthropic Messages SSE, OpenAI Chat Completions SSE and OpenAI-compatible services, Google Gemini streaming API.
- Providers: Anthropic, OpenAI, Gemini, OpenRouter, DeepSeek, Groq, Mistral, xAI, and custom OpenAI-compatible HTTPS endpoint. Ambiguous key formats require manual selection; there is no safe universal key detector.
- Incremental text rendering, chat history context, Stop/Abort propagation, and provider error events.
- Android WebView shell project under `android/`.
- Termux setup script for running the local gateway on the same Android device.

## Android quick start

Read [ANDROID_DEPLOYMENT.md](ANDROID_DEPLOYMENT.md). In short: install Termux, copy `backend/` into Android Downloads, run `setup-android-termux.sh`, start the gateway, then configure the URL and admin token in Settings → AI Providers.

## Build APK source

Open `android/` in Android Studio with JDK 17+ and Android SDK 35, sync Gradle, then run `assembleDebug`. The source shell is included; no signed/compiled APK is included because Android SDK/Gradle and signing credentials were unavailable in the build environment.

## API and backend docs

- [Gateway setup and endpoint guide](backend/README.md)
- [Provider API reference and SSE event format](PROVIDER_API_REFERENCE.md)
- [Android deployment and security notes](ANDROID_DEPLOYMENT.md)
- [QA checklist](QA_CHECKLIST.md)
- [Original APK reverse-engineering notes](REVERSE_ENGINEERING_NOTES.md)

## Security model and limitations

Provider keys are posted to the local gateway and encrypted at rest using AES-256-GCM. The master key and admin token are generated into a mode-0600 `.env` file in Termux private storage; the vault file is also mode 0600. The master key is **not** hardware-backed Android Keystore in this version. Keep Termux bound to loopback; do not expose port 8787 to a network. Remote deployment needs HTTPS and proper per-user authentication.

The adapters normalize text streaming only. Provider-specific tool calling, image/audio inputs, web search, files, artifacts, and Cowork are not yet fully implemented. Live provider verification requires valid API keys and network access. API costs and provider rate limits are billed by each provider to the account associated with the key.

The Android Studio project is source-only and still needs a local SDK build and device QA. No claim of a finished APK or verified live inference is made.
