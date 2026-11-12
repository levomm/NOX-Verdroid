# NOX Chat TTS: ElevenLabs female voice — 2026-11-12

## Approved direction
User approved replacing NOX's normal chat reply readout with ElevenLabs, with a calm, smart, dry-humoured female "Sol-like" delivery. Do not clone anyone's identity or voice; choose a normal ElevenLabs library voice. Available search found no Estonian-labeled female voice, so default to **Bri** (voice id `pzXYLespwi48YnRBYxIW`, English-labeled female) with `eleven_v4_turbo`; Estonian pronunciation remains unverified until a device sound test. Make voice ID editable.

## Data flow
Normal NOX chat `ChatDetailScreen` receives completed assistant reply and keeps existing `VoiceText.forSpeech` markdown stripping. `VoiceController` selects TTS backend based on `VoicePrefs`: default ElevenLabs with valid BYOK key; Android TTS if key missing, quota exhausted, request rejected before audio, or selected Android. Existing Android SpeechRecognizer, hands-free callback, stop button, and OpenAI Realtime separate screen remain unchanged. Never send replies to ElevenLabs unless `Speak replies aloud` is enabled and user selected ElevenLabs.

## Network and streaming
POST `https://api.elevenlabs.io/v1/text-to-speech/{voice_id}/stream?output_format=pcm_24000`, header `xi-api-key`, JSON `{"text":"...","model_id":"eleven_v4_turbo","language_code":"et"}`. Decode raw 24kHz mono PCM16 response chunks directly to Android `AudioTrack`, without temporary files. No extra generation in background. Cancel the HTTP call and audio immediately on Stop, mic press, leaving chat, or app background. Never log API key or reply content; do not persist synthesis bytes. Do not automatically resend a request after audio has begun.

## Safety and settings
Use existing `CustomProviderStore` encrypted `key_elevenlabs` slot for credentials; expose Save/Delete controls in Settings > Voice, without showing saved values. Persist `tts_provider`, `elevenlabs_voice_id`, `elevenlabs_max_reply_chars`, `elevenlabs_daily_chars` in existing VoicePrefs. Default per-reply cap 1200 characters, daily cap 8000. Quota consumes characters before network request (conservative if it fails). Show remaining daily budget and explicit note that TTS consumes ElevenLabs credits and sends assistant reply text to ElevenLabs.

## Tests and limits
JVM tests for pure policy: clip without cutting word when possible, max limits, voice ID strict validation, daily quota boundaries. Android CI: `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. No paid live call without user key; cannot claim Estonian voice quality or full device audio tested from CI. Existing NOX v0.1 debug signing was ephemeral: a new CI debug build likely cannot install over the old one without uninstall; disclose this clearly before publishing.
