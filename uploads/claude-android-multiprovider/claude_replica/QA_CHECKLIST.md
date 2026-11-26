# QA checklist — Multi-provider implementation

## Static validation
- [x] `node --check app.js`
- [x] `node --check backend/server.mjs`
- [x] Android WebView shell source and manifest added
- [x] Android assets synchronized with current web UI

## Gateway smoke tests
- [x] `/health` returns service status
- [x] Admin token protects provider endpoints (401 without token)
- [x] Provider key POST stores encrypted secret
- [x] Provider list returns metadata only, never secret
- [x] Vault file does not contain submitted test API key in plaintext
- [x] Custom OpenAI-compatible adapter: mock model discovery and SSE delta streaming

## Not yet verified
- [ ] Live provider authentication/model discovery (requires user-owned valid API key and internet)
- [ ] Live Anthropic/OpenAI/Gemini streaming against real accounts
- [ ] Stop-generation cancellation against each upstream provider
- [ ] Android Studio/Gradle APK build (SDK/toolchain unavailable here)
- [ ] Device QA on physical Android hardware

No provider call is represented as tested unless performed with a valid user-owned key.
