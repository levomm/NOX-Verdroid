# NOX Verdroid v0.1 implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Each task is independently testable.

**Goal:** Side-by-side NOX build with safe BYOK OpenAI Realtime voice and optional terminal-green palette.

**Architecture:** Follow `docs/superpowers/specs/2026-11-12-nox-verdroid-design.md`; keep existing chat voice pipeline unchanged and add a separate realtime session.

**Tech Stack:** Kotlin 2.x, Compose, Android SDK 35, OkHttp, kotlinx.serialization, Hilt, JUnit.

**Spec:** `docs/superpowers/specs/2026-11-12-nox-verdroid-design.md`

## Global constraints
- Do not change upstream default branch.
- No secrets; no embedded API key; retain GPLv3.
- Recording only after user activation and permission, stop on dispose.
- The Realtime mode is a voice conversation, NOT a full agent-tool bridge.
- Android appId `ee.osx01.nox`, source namespace unchanged `com.verdroid.app`.

### Task 1: Isolated app identity
**Files:** `app/build.gradle.kts`, `app/src/main/res/values/strings.xml`
- [ ] Change `applicationId`, app label and build APK name.
- [ ] Check explicit Uri authorities use applicationId placeholders, and compile.
- [ ] Ensure original Verdroid data is not overwritten.

### Task 2: NOX green theme
**Files:** `ui/theme/Theme.kt`, `strings.xml`, JVM theme test
- [ ] First add failing test for palette selection and readable contrast.
- [ ] Add `Palette.NOX` and two theme strings.
- [ ] Define dark deep-green surfaces, light-green readable typography, green primary, and darker onPrimary.
- [ ] Re-run theme tests.

### Task 3: Realtime protocol
**Files:** `data/voice/RealtimeProtocol.kt`, `data/voice/RealtimeProtocolTest.kt`
- [ ] Write tests for GA `session.update` JSON shape, model selection, 24k input/output, VAD and protocol event parsing.
- [ ] Verify tests fail when source implementation is absent.
- [ ] Implement protocol conversion and validate tests.

### Task 4: Live audio transport and UI
**Files:** `data/voice/RealtimeVoiceClient.kt`, `ui/voice/RealtimeVoiceScreen.kt`, `VoiceSettingsScreen.kt`, `VerdroidRoot.kt`
- [ ] Wire OpenAI encrypted key from `CustomProviderStore.keyFor("openai")` to WebSocket connect over TLS.
- [ ] Stream 24kHz PCM16 mono capture using `AudioRecord`; play delta chunks through `AudioTrack`.
- [ ] Handle session created, updated, output audio delta, input speech interruption and errors.
- [ ] Handle permission denial, explicit close, leaving screen and 10-minute cap; avoid runaway costs.
- [ ] Add Voice settings entry, nav route, status display and explicit start/stop controls; keep Android STT/TTS untouched.
- [ ] Build and inspect any Kotlin/Compose errors.

### Task 5: CI, verification and rollout
**Files:** `.github/workflows/nox-android.yml`, `README.md`
- [ ] Configure Android build with JDK17, SDK35, NDK 27.1, unit tests, lint, debug APK artifact.
- [ ] Run tests and build on branch.
- [ ] Inspect logs; fix only confirmed failures.
- [ ] Create draft PR to fork main with changelog, known caveats and device-test checklist.
