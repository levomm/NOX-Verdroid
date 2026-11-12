# NOX ElevenLabs Chat TTS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use ElevenLabs' female voice on normal NOX chat replies with streamed playback, character safeguards and Android fallback.

**Architecture:** Reuse VoiceController and existing reply triggers. Isolate pure policy, secure transport/player, persistence, and settings UI. Do not touch the OpenAI Realtime agent path.

**Tech Stack:** Kotlin, Android AudioTrack, OkHttp, Compose, EncryptedSharedPreferences via CustomProviderStore, kotlinx JSON, JUnit, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-11-12-elevenlabs-chat-tts-design.md`

## Global Constraints
- Model `eleven_v4_turbo`, PCM 24000 Hz mono 16bit, `language_code="et"`, original Bri female voice id `pzXYLespwi48YnRBYxIW` is editable.
- Never put API credentials in GitHub, logs, or build outputs. Existing CustomProviderStore handles encryption.
- Explicit Play/Stop behaviour, no background paid calls, no automatic retry of already started audio.
- Preserve existing Android recognizer, Android fallback, hands-free, user controls and OpenAI realtime.
- No merge or shipped APK before latest CI tests/lint/build pass and diff review.

### Task 1: Pure ElevenLabs synthesis policy
**Files:** `data/voice/ElevenLabsTtsPolicy.kt`, `app/src/test/java/com/verdroid/app/data/voice/ElevenLabsTtsPolicyTest.kt`

**Interfaces:** `DEFAULT_VOICE_ID`, `DEFAULT_MODEL`, `fun clipped(text:String, cap:Int):String`, `fun validVoiceId(id:String):Boolean`, `fun allowed(used:Int, requested:Int, dailyCap:Int):Boolean`.

- [ ] Add JUnit tests asserting `clipped("tere maailm", 9) == "tere"`, `validVoiceId(DEFAULT_VOICE_ID)`, invalid id rejected, daily cap boundary accepted and above cap blocked.
- [ ] Run tests before writing implementation (expected unknown type) via CI.
- [ ] Implement conservative truncation to word boundary, strict alphanumeric voice id, and arithmetic-safe cap checks.
- [ ] Verify exact JUnit tests green in CI, commit.

### Task 2: Persist settings + encrypted BYOK
**Files:** `data/voice/VoicePrefs.kt`, `ui/voice/VoiceSettingsScreen.kt`, `data/network/CustomProvider.kt` unchanged.

**Interfaces:** `ttsProvider:StateFlow<String>`, `voiceId:StateFlow<String>`, `dailyCap:StateFlow<Int>`, `replyCap:StateFlow<Int>`, `reserveElevenLabsChars(size:Int):Boolean`.

- [ ] Add a test confirming daily counter reset and max bound using pure policy; run failing.
- [ ] Persist voice settings to existing VoicePrefs; validate voice ID on Save, display configured key presence from `CustomProviderStore.keyFor("elevenlabs")` and save via `store.save("elevenlabs","","",input)` (AES encrypted, no echo).
- [ ] Show Android vs ElevenLabs choice, voice ID, conservative character cap, remaining today, warning text in Settings > Voice.
- [ ] Verify tests and Compose build.

### Task 3: Streaming transport and normal-chat integration
**Files:** `data/voice/ElevenLabsTtsClient.kt`, `ui/voice/VoiceController.kt`, `ui/chat/ChatViewModel.kt`, `ui/chat/ChatDetailScreen.kt`.

**Interfaces:** `ElevenLabsTtsClient.speak(text:String,onDone:(SpeechResult)->Unit):Boolean`, `ElevenLabsTtsClient.cancel()`, `SpeechResult.COMPLETED|BEFORE_AUDIO_FAILURE|AFTER_AUDIO_FAILURE`.

- [ ] Test cancellation and limited text via policy test; run red before implementation.
- [ ] Implement HTTP stream using `OkHttpClient.newCall(...).enqueue`, no retries; response PCM bytes streamed into 24kHz mono AudioTrack on IO thread; drain before releasing; cancellation cancels network and output.
- [ ] Wire `ChatViewModel` with injected `ElevenLabsTtsClient` and hand into `VoiceController`; preserve plain Android TTS if key missing/usage blocked/network fails before audio, handsfree callback after successful readout.
- [ ] Lifecycle stop: on chat ON_STOP invoke `voice.stopSpeaking()` and cancel transport; control only active readout.
- [ ] Verify JUnit, assemble and lint CI; review network/privacy and remove debugging traces.

### Task 4: Review and publish
**Files:** `.github/workflows/nox-android.yml` inherited; `README.md` release notes if needed.

- [ ] Confirm no secrets in diff, pass CI on final head commit; verify result from GitHub Actions JSON.
- [ ] Merge PR only after all checks pass. Deliver APK via GitHub release or CI artifact with exact source SHA.
- [ ] State old CI debug APK signing may prevent upgrading an existing install, and that native Estonian female quality is not verified until device playback.
