# NOX Verdroid v0.1 design — 2026-11-12

## Purpose
Maintain a reversible Android fork of chr243/Verdroid for the owner's own phone. App id `ee.osx01.nox` allows side-by-side installation. GPLv3 notices and license stay intact.

## Features and boundaries
1. Independently installable NOX app with recognizable app name and optional dark green-on-green terminal palette, preserving existing palettes and base Android functionality.
2. New OpenAI Realtime voice screen linked from Settings > Voice, using a user-owned OpenAI API key retrieved from Verdroid's encrypted provider store. Default economical `gpt-realtime-2.1-mini`, optional `gpt-realtime-2.1`. Native PCM16 24k mono capture/playback via AudioRecord/AudioTrack, OkHttp WebSocket and documented GA event names. On-device encrypted key is never committed or logged. Explicit Connect/Disconnect control, microphone permission check, and time cap.
3. Voice is a SEPARATE Realtime session, not secretly assumed to execute Verdroid chat tools; retain existing Android STT/TTS mode. Display this distinction in the UI.
4. Future extension: Responses API adaptation for Sol tool calls. Not piggybacked on voice branch until converter/tool mapping is verified, to avoid regressing working gpt-4.1.
5. Build verification: unit tests for protocol/event parsing and palette; Android CI builds, test runner; no signed release without proper signing. No automatic server-side spending.

## Architecture
- `data/voice/RealtimeProtocol.kt`: pure JSON message/event mapping, testable.
- `data/voice/RealtimeVoiceClient.kt`: WebSocket lifecycle, 24k audio input/output, interruption/cancellation, state flow, 10-minute session cap, cleanup.
- `ui/voice/RealtimeVoiceScreen.kt`: Compose controls and runtime microphone permission, remote voice session state, model selection, independent of chat agent.
- `ui/voice/VoiceSettingsScreen.kt`: launches new screen from existing Voice settings, keeps original toggles.
- `ui/VerdroidRoot.kt`: new route.
- `ui/theme/Theme.kt`: optional NOX green terminal palette with accessible contrast.
- `app/build.gradle.kts`, `strings.xml`: app ID, name and label.
- JVM tests under `app/src/test/java/com/verdroid/app/data/voice/` and `.../ui/theme/`.

## Security and privacy
User-entered key stays encrypted in app storage, never appears in logs or bundle. Voice conversations send PCM/audio to OpenAI when connected. No autonomous call initiation. Explicit visible paid-usage warning. No hidden recording when screen is left. No external proxy required, mobile client uses BYOK over TLS; this differs from ephemeral-proxy recommended for third-party/public app distribution.

## Acceptance
- Side-by-side package name differs from Verdroid.
- NOX green theme selectable; old palettes unaffected.
- New voice screen reachable, displays state, connects only on tap, releases microphone and socket on leave.
- Errors show provider/network/permission issues without leaking secrets.
- Tests and debug Android build pass on CI, else report failures and do not claim APK works.
