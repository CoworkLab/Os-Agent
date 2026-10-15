# AGENTS.md — JARVIS Android Assistant (Os-Agent)

Persistent working notes for this repository. Read this before making changes.

## Origin and license

This is the **Part 7** source of the JARVIS Voice Assistant from
`https://github.com/techgptx/Playlist` (the latest part in that playlist), imported
here for continued development. Part 7 is the most advanced revision; Parts 1–6 are
earlier stages and are not present in this repo.

`LICENSE` is the custom **Tech GPTx Source Code & Content Creator License (TAMCL v1.0)**.
It requires visible/verbal attribution to "Tech GPTx" in any derived video, course, or
published content, and requires the notice be preserved in all copies and forks.
Do not strip attribution from the codebase, UI credits, or docs.

## What this project is

An Android voice assistant ("JARVIS") in Kotlin. It streams live audio to the
**Gemini Live API** over a WebSocket, does offline wake-word detection with **Vosk**,
and drives the phone through an **AccessibilityService** plus **MediaProjection**
screen capture.

Reasoning and vision are **provider-agnostic**. Gemini Live still owns the realtime
voice loop (it is the only provider with bidirectional audio streaming), but the
thinking and the eyes can run on any OpenAI-compatible endpoint: Groq, Ollama Cloud,
OpenAI, OpenRouter, or a custom self-hosted server.

- Application ID / namespace: `com.jarvis.assistant`
- minSdk 26 (Android 8.0), compileSdk/targetSdk 34, Java/Kotlin target 17
- versionCode 143, versionName 1.8.7
- Gradle 8.9, AGP 8.7.2, Kotlin 2.0.20, kapt (for Room)
- Only one test source set exists: `app/src/test` (JVM unit tests, JUnit 4)

## Build

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:testDebugUnitTest # unit tests
```

`assembleRelease` deliberately fails unless release signing inputs are supplied
(`JARVIS_RELEASE_STORE_FILE`, `JARVIS_RELEASE_STORE_PASSWORD`, `JARVIS_RELEASE_KEY_ALIAS`,
`JARVIS_RELEASE_KEY_PASSWORD`, `JARVIS_RELEASE_CERT_SHA256`) via env vars or Gradle
properties. This is enforced by the `verifyReleaseSecurityInputs` task. Never commit
keystores or credentials.

`local.properties` is machine-local (start from `local.properties.example`) and is
gitignored.

## CI and release automation

`.github/workflows/android-ci.yml` compiles the debug sources and runs the unit tests on
every push to `main` and every pull request. It generates `local.properties` from
`ANDROID_SDK_ROOT` because that file is not committed.

`.github/workflows/android-release.yml` builds a signed release APK on a `v*` tag or on
manual dispatch. It reads signing inputs from repository **secrets**, never from the repo:
`JARVIS_KEYSTORE_BASE64` (base64 of the `.jks`), `JARVIS_KEYSTORE_PASSWORD`,
`JARVIS_KEY_ALIAS`, `JARVIS_KEY_PASSWORD`, and optionally `JARVIS_DEVELOPER_NAME` /
`JARVIS_COPYRIGHT_OWNER`. The workflow derives `JARVIS_RELEASE_CERT_SHA256` from the
keystore itself, so the pin cannot drift out of sync with the signing key. Copy
`.env.example` to `.env` for local values; `.env` is gitignored.

## Architecture

Data flow: mic → `AudioRecorder` → `JarvisConversationService` → `GeminiLiveWebSocket`
→ Gemini Live → PCM audio back through `AudioPlayer`. Gemini can call ~21 declared
tools; the service executes them and returns `tool_response`.

### Key components

| Area | File | Notes |
|---|---|---|
| App wiring / DI | `JarvisApp.kt` | Manual service locator. All managers are `lateinit` properties on the Application. |
| Conversation runtime | `background/JarvisConversationService.kt` | **~3400 lines, the heart of the app.** Foreground service (microphone + mediaPlayback + mediaProjection). Owns the Gemini session, tool-call dispatch, TTS, standby/wake loop, inactivity timer. |
| Live transport | `network/GeminiLiveWebSocket.kt` | OkHttp WebSocket. Sends `BidiSetup` with inline tool declarations, then streams `realtimeInput` audio/video. Handles setupComplete, toolCall, transcription, barge-in, session renewal, reconnect/backoff. |
| Gemini models/DTOs | `data/model/GeminiLiveModels.kt` | Model IDs, voices, personalities, supported languages. |
| Offline wake word | `audio/VoskModelManager.kt` + `assets/model-en-us` | Vosk. Model dirs are runtime dependencies — do not rename or remove. |
| Accessibility automation | `automation/accessibility/JarvisAccessibilityService.kt` | ~2000 lines. Node search, gestures, typing/paste, and "arm/poll" flows for WhatsApp send/call/delete and YouTube play/subscribe/fullscreen. |
| Agentic engine | `automation/agent/AgentTaskEngine.kt` | Authoritative task state machine (UNDERSTANDING → PLANNING → … → COMPLETED/FAILED/CANCELLED). Plans are hardcoded per goal in `planTitles`. |
| Provider abstraction | `data/provider/*` | `AiProviderKind` (Gemini, Groq, Ollama Cloud, OpenAI, OpenRouter, Custom), `AiProviderClient` interface, `OpenAiCompatibleClient`, `GeminiRestClient`. Stateless; credentials passed per call. |
| Vision capability | `data/provider/VisionProbe.kt` + `assets/vision_test.png` | Proves image support by sending a real image with three checkable facts. Capability is **never** inferred from a model name. |
| Agent eyes | `automation/agent/AgentBrain.kt` (`ScreenPerceiver`) | Gathers accessibility tree **and** a real screenshot together; records why vision was skipped so the model is told what it cannot see. |
| Agent reasoning loop | `automation/agent/AgentBrain.kt` (`AgentBrain`, `AgentSession`) | Look → think → act → verify, one action per turn. The LLM only chooses; the runtime performs and verifies. History is kept so a failed step is not repeated. |
| Agent hands | `automation/agent/InteractionEngine.kt`, `GestureDispatcher.kt`, `UiSettler.kt` | Verified clicking/typing/scrolling. `GestureDispatcher` awaits the real gesture callback; `UiSettler` polls the tree instead of sleeping. |
| Action safety | `automation/agent/SerializedActionExecutor.kt` | Global mutex + generation counter so UI mutations never overlap and stale queued actions are rejected. |
| Verification | `automation/agent/ActionVerifier.kt`, `ScreenSignatureCalculator.kt` | Closed-loop before/after screen comparison. Never trusts a gesture returning `true`. |
| Visual/UI tasks | `vision/VisualTaskOrchestrator.kt`, `UiGroundingEngine.kt` | Higher-level click/type/scroll handling with grounding and retries. |
| Screen streaming | `vision/ScreenCaptureManager.kt` | MediaProjection → VirtualDisplay → ImageReader → downscaled JPEG at ≤1 FPS, 768 px max dimension. |
| Local commands | `automation/command/CommandParser.kt` | Regex intent parser (English + Hinglish + several Indian languages). `CommandExecutor` dispatches the sealed `JarvisCommand` hierarchy. This is the offline/non-LLM path. |
| Phone controls | `automation/phone/PhoneControlManager.kt` | Brightness (WRITE_SETTINGS), volume, torch, calls, URL/Chrome tabs, app launch by fuzzy name match. |
| Memory | `data/memory/*` | Room DB (`MemoryDatabase`), plus `MemoryManager` for facts, song tracking, birthday/routine recall. |
| Preferences | `data/preferences/AppPreferences.kt` | EncryptedSharedPreferences for the API key. |
| Security | `security/SecurityManager.kt`, `AppIdentityVerifier.kt` | Package-name and APK-signature checks at startup; remote verification is intentionally reported unavailable. |
| UI | `ui/home/MainActivity.kt`, `HomeFragment.kt`, `ui/orb/OrbHelper.kt` | Native Android views + a WebView orb driven by JS calls (`setOrbState`, `setAudioLevel`). |

### Bundled web assets

`assets/orb.html`, `assets/hud/index.html`, `assets/settings/settings.html`,
`assets/ghost-avatar-v2.html`, `assets/overlay-toys/ghost.html` (the only overlay
character; the other 16 were removed and the avatar preference is locked to ghost),
`assets/three.min.js`, `assets/vision_test.png` (the vision capability test image).
These are loaded into WebViews or read at runtime, not a separate web build.

## Conventions

- Kotlin, 4-space indent; XML/JSON/MD use 2-space (see `.editorconfig`). LF endings
  except `.bat`/`.ps1` which are CRLF.
- ViewBinding and BuildConfig are enabled; no Compose anywhere in this project.
- Coroutines + `StateFlow` for state. Managers expose `StateFlow`; UI collects it.
- Single shared `OkHttpClient` in `GeminiLiveWebSocket` — do not create per-session clients.
- Room entities/DAO live in `data/memory`; kapt is required for schema generation.

## Gotchas learned while reading this code

- **API key**: `GeminiConstants.RELEASE_API_KEY` is intentionally empty; the key comes
  from encrypted preferences entered in Settings. Never hardcode a key.
- **Provider selection**: `AppPreferences.providerId` picks the reasoning provider;
  per-provider key/base URL/model live under `KEY_PROVIDER_KEY_PREFIX`,
  `KEY_PROVIDER_BASE_PREFIX`, `KEY_PROVIDER_MODEL_PREFIX`. `JarvisApp.clientFor`
  returns the matching client.
- **Vision is gated on a real test, not a name**: `AppPreferences.canUseVisionNow`
  returns true only when the user enabled vision *and* that exact model passed
  `VisionProbe` (three checkable facts in `assets/vision_test.png`). Groq, Ollama and
  most `/models` responses carry no vision metadata, so name heuristics like "vl" are
  unreliable. `ScreenPerceiver` reports the skip reason to the model instead of
  silently sending no image.
- **Typing cannot press Enter**: the public accessibility API has no key-code action
  (`ACTION_ARGUMENT_KEY_CODE` is hidden). `InteractionEngine.typeInto` uses
  `ACTION_SET_TEXT`, then clipboard paste, and always reads the field back before
  reporting success. Submitting must be a separate, explicit click.
- **Gestures need their callback**: `dispatchGesture` returning true only means the
  gesture was accepted. `GestureDispatcher` awaits `GestureResultCallback` so a
  rejected tap is not mistaken for a hit.
- **Model IDs are unusual**: `models/gemini-3.1-flash-live-preview`, `models/gemini-3.8-live`,
  `models/gemini-3.1-flash-agent`. `GeminiLiveWebSocket` rewrites/maps the requested model
  before sending setup and auto-falls back to `MODEL_3_8_LIVE` on a 1008 rejection.
- **Field naming mismatch by design**: the Kotlin DTOs use camelCase `@SerializedName`
  (Gson), but `sendSetupMessage` builds the payload by hand with snake_case JSON keys
  (`system_instruction`, `generation_config`, `response_modalities`). Both paths coexist.
- **Video rate limit** is enforced in two places: `ScreenCaptureManager` (min frame
  interval) and `GeminiLiveWebSocket.sendMediaChunk` (1 FPS via CAS on an AtomicLong).
- **`ActionVerifier.verifyClick` intentionally returns `isVerified = false`** even when a
  state change is detected — it only reports that the screen changed, not that the intended
  destination was reached. Callers must confirm the goal separately. This is deliberate
  (avoid false success), not a bug.
- **`ScreenSignature.isEquivalentTo`** treats equal `contentHash` as equivalent, so
  hash collisions could mask a real change.
- `lint.disable += "NullSafeMutableLiveData"` is a workaround for an upstream detector
  crash under Kotlin 2.0 UAST. Keep other checks enabled.
- Lint/manifest: the app requests very broad permissions (QUERY_ALL_PACKAGES,
  SYSTEM_ALERT_WINDOW, WRITE_SETTINGS, accessibility, media projection, contacts, calls).
  Expect Play Store review scrutiny.
- One non-ASCII character exists in `GeminiLiveWebSocket.kt` (a "—" in a comment).
- **`agent_act` is the hands-on entry point**: it runs one look/think/act/verify turn
  through `AgentSession` and returns `success`/`verified` plus the reason. It is treated
  as an automation tool, so the hard-stop guard and step counter apply. The session is
  cleared on task cancel and on verified completion, so a new goal starts fresh.
- `docs/diagnostics/voice-videos/README.md` references three `voice-issue-*.mp4` files
  that are **not** in this repo (only the index is present).

## Working agreements

- Do not commit build output, `local.properties`, keystores, or API keys.
- When touching `JarvisConversationService.kt`, prefer extracting focused helpers over
  growing the file further; it is already the largest source file.
- Preserve the Tech GPTx attribution required by `LICENSE`.
