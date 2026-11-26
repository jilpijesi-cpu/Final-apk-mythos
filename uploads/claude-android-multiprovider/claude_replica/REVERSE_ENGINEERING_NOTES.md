# Reverse-engineering notes — supplied Android package

## Scope and method

Input archive: `aplikasi.zip`  
Archive content: exactly one file, `Claude.apk`  
APK package: `com.anthropic.claude`

The package was inspected as a ZIP/APK container. The binary Android manifest was parsed, DEX string identifier tables and class definition tables were enumerated, and application/resource paths were inventoried. The six DEX files were read for metadata and class descriptors. The inspection environment did not contain `jadx`, `apktool`, `aapt`, `apkanalyzer`, or an Android SDK, and outbound package installation was unavailable. Therefore this is a structural and string-level reverse engineering pass, not a source-equivalent decompilation of every method and Compose function.

## Confirmed package metadata

| Field | Value |
|---|---|
| Application ID | `com.anthropic.claude` |
| Version name | `1.261120.19` |
| Version code | `26112019` |
| Minimum SDK | 32 |
| Target SDK | 37 |
| Compile SDK | 37 |
| Android Gradle Plugin metadata | 9.4.1 |
| DEX files | 6, each DEX 038 |
| Main activity | `com.anthropic.claude.mainactivity.MainActivity` |
| Application class | `com.anthropic.claude.application.ClaudeApplication` |
| Application UI technology | Jetpack Compose / Kotlin Multiplatform Compose resources are present |

The six DEX files contain a total of 65,147 class definitions and 274,390 string identifier entries. The class definition list includes approximately 1,388 classes under `com.anthropic.claude.*`.

## DEX distribution

Manifest component inventory: 26 activities, 6 activity aliases, 23 services, 19 receivers, 10 providers, and 66 `uses-permission` declarations.

| DEX | String IDs | Type IDs | Class definitions |
|---|---:|---:|---:|
| `classes.dex` | 46,749 | 26,086 | 10,317 |
| `classes2.dex` | 47,658 | 20,201 | 8,844 |
| `classes3.dex` | 50,121 | 26,445 | 12,508 |
| `classes4.dex` | 45,576 | 27,024 | 12,559 |
| `classes5.dex` | 41,105 | 23,098 | 11,251 |
| `classes6.dex` | 43,181 | 19,150 | 9,668 |

## Application modules found in class descriptors

Class counts below are descriptors in the application's own namespace, grouped by the first package segment. They are structural indicators, not counts of user-visible screens.

| Module | Classes |
|---|---:|
| chat | 404 |
| app | 268 |
| code | 183 |
| settings | 88 |
| project | 80 |
| conversation | 50 |
| api | 33 |
| sessions | 27 |
| artifact | 26 |
| login | 19 |
| networking | 18 |
| filedownload | 16 |
| ui | 15 |
| bell | 14 |
| mcpapps | 13 |
| core | 12 |
| bardhub | 12 |
| connector | 11 |
| stt | 11 |
| widget | 10 |
| deeplink | 8 |
| notification | 6 |
| media | 6 |
| Other supporting modules | logout, usage, sentry, chatlist, db, design, webview, sunnypuddle, application, agentchat, mainactivity, observability, personalfinance, artifactapp, firebase, remotebrowser, serverdrivenaction, and supporting packages |

## Main navigation / destinations found

The application destination model includes:

- Chat and all-chats list.
- All projects, project details, project knowledge, project memory, project templates, and project documents.
- Artifact gallery, artifact detail, and full-screen artifact views.
- Code projects, remote sessions, session overview, shared sessions, routines, notifications, pull requests, and repository selection.
- Cowork remote, scheduled tasks, task history, and scheduled task runs.
- Settings, plan usage, usage transactions, and account-related flows.
- Onboarding, login/logout, account verification, and deep links.

Representative fully named classes extracted from DEX descriptors include:

- `ClaudeAppDestination$List$AllChatsList`
- `ClaudeAppDestination$List$AllProjectsList`
- `ClaudeAppDestination$List$ArtifactGallery`
- `ClaudeAppDestination$List$CodeProjects`
- `ClaudeAppDestination$List$CodeRemote`
- `ClaudeAppDestination$List$CoworkRemote`
- `ClaudeAppDestination$List$Settings`
- `ClaudeAppDestination$Detail$Chat`
- `ClaudeAppDestination$Detail$ProjectDetails`
- `ClaudeAppDestination$Detail$ProjectKnowledge`
- `ClaudeAppDestination$Detail$CodeRemoteSession`
- `ClaudeAppDestination$Detail$CoworkScheduledTask`
- `SettingsAppScreen$CapabilitiesScreen`
- `SettingsAppScreen$ConnectorsScreen`
- `SettingsAppScreen$NotificationSettingsScreen`
- `SettingsAppScreen$PrivacyScreen`
- `ChatScreenArtifactSheetDestination`
- `ModelSelectorBottomSheetDestination`

## Manifest components and permissions

The main exported activity is `MainActivity`. Other app-owned activities/services include:

- `AssistantOverlayActivity`
- `ArtifactAppActivity`
- `DeepLinkActivity`, `SecretLinkActivity`, `BringToFrontActivity`, `NewWindowActivity`
- `PermissionsRationaleActivity`
- `ClaudeWidgetConfigActivity`
- `MessageSseService`
- `VoiceModeService`
- `ClaudeVoiceInteractionService`
- `ClaudeVoiceInteractionSessionService`
- `ClaudeRecognitionService`
- `TTSPlaybackService`
- `AnthropicFirebaseMessagingService`
- `FilePlaybackService`
- Receivers for notifications, Code/Cowork actions, widgets, and app updates

Manifest permissions include internet/network state, microphone/audio recording, fine/coarse location, calendar read/write, vibration, screen-capture detection, overlay-window hiding, Android Health data categories, alarm scheduling, and backward-compatible storage access.

The manifest declares verified/deep links for `claude.ai` and routes covering chat, projects, artifacts, Code, Cowork, scheduled tasks, settings, account return flows, and connector authentication callbacks.

## Assets and resources

| Asset group | Entries | Uncompressed size |
|---|---:|---:|
| `assets/` | 48 | ~3.33 MB |
| `res/` | 1,115 | ~1.77 MB |
| Drawable resources | 282 | included above |

Assets include Compose theme resources, code-language grammars under `assets/pegasus/`, suffix/locale data, and application fonts. The supplied APK contains Anthropic Sans, Anthropic Serif, Anthropic Interface, JetBrains Mono, and an icon-symbol font. These proprietary font binaries are not redistributed in this web reconstruction.

Drawable resource names include Claude spark/logotype/splash resources and CDS icons for chat, chats, projects, artifacts, code, create, dispatch, files, globe, image, microphone, search, tasks, and voice.

## Feature evidence found in DEX strings/classes

- Chat message streaming (`MessageSseService`), message upload worker, local cached message persistence, conversation search, model selector, thinking state, and chat action sheets.
- Project creation, details, visibility, instructions, memory, files/knowledge, and project chat organization.
- Artifact listing, creation, detail, full-screen, file save, and sharing flows.
- Code remote sessions, repositories, GitHub connection, branch/repository pickers, environments, tool approvals, diffs, routines, scheduled runs, notifications, and usage.
- Cowork sessions, remote device, messages, scheduled tasks, task status, and outcome worker.
- Voice mode, speech recognition, text-to-speech, voice interaction services, audio session playback.
- Settings categories: account, capabilities, connectors, notifications, accessibility, appearance-related settings, privacy, Code settings, cloud environments, usage, and billing.
- Connectors and external integrations, including calendar and other providers.

## What cannot be established from this APK inspection

- Exact runtime layout measurements, screen transitions, animations, and visual states across devices.
- Complete method-level implementation or exact Compose UI function bodies.
- Backend behaviour, server prompts, model routing, authorization, feature-flag values, organization entitlements, and private APIs.
- Valid account state, chat history, project content, remote repositories, connector grants, or live task data.
- Exact reply quality or model behaviour, since inference runs on Anthropic infrastructure.

Those gaps cannot be filled by copying APK resources alone. A genuinely 1:1 runtime clone would require the original source, server contracts/authorizations, representative device captures, and a licensed/authorized backend.

## Reconstruction decisions

The delivered build reproduces the module map and common UI workflows as a responsive web app. It uses localStorage to retain conversations, projects, artifacts, and display preferences. Actions that normally contact Anthropic services are shown as unavailable offline or as explicitly simulated local behaviour. This avoids misrepresenting the reconstruction as a working authenticated Claude client.


## Supplemental machine-readable inventories

To make the binary inspection reproducible, the deliverable also includes:

- `APK_MANIFEST_INVENTORY.md`: full component/permission declaration index decoded from the binary manifest.
- `APK_BINARY_INVENTORY.md`: archive hash, 1,228 APK entry count, aggregate sizes, and DEX class totals.
- `APK_FILE_INVENTORY.csv`: all APK paths and file-level sizes/compression metadata.
- `ANTHROPIC_CLASS_INDEX.txt`: all 1,388 class descriptors in the `com.anthropic.claude` namespace.
- `QA_CHECKLIST.md`: browser interaction tests for the reconstructed UI.

These inventories increase traceability but do not substitute for a complete method-level decompilation.
