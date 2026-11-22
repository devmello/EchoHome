# Voice assistant setup

The assistant (`:feature:assistant`, design `design/Assistant.dc.html`) listens, transcribes and
speaks **on the Echo**, and asks **Claude** (Anthropic Messages API, Claude Haiku 5.5) for the
answer. Home Assistant is not needed. Without a key and with demo data on (debug builds), the
droplet and the chips play the design's three scripted demos.

```
mic (16 kHz) ─► wake word (optional, on device) ─► streaming STT (on device) ─► transcript text
                                                                                    │
            speaker ◄─ TTS (on device) ◄─ answer text + cards ◄─ Claude + tools ◄───┘
                                                                (timers, alarms, weather,
                                                                 calendar, media, volume,
                                                                 Home Assistant if set up)
```

## 1. Anthropic API key

Add the key to `android/secrets.properties` (git-ignored; copy `secrets.properties.example`):

```
anthropic.apiKey=sk-ant-...
```

Rebuild and install (`./scripts/build-install.sh --screen assistant`). The key is compiled into
the debug build's config, used only in the `x-api-key` header, and never logged. Without it the
idle screen says "Add an Anthropic API key to talk to Claude" (or plays the demos in demo mode).

Requests use `claude-haiku-5-5`, `anthropic-version: 2026-10-21`, streaming, thinking off,
`output_config.effort: low`, at most 1024 output tokens. A typical turn is one or two requests of
~2–3k input tokens (the tool definitions dominate), which costs well under a tenth of a cent.

## 2. Voice runtime and models (on-device, sherpa-onnx)

Everything comes from the official sherpa-onnx GitHub releases
(github.com/k2-fsa/sherpa-onnx, Apache-2.0) and is checked against pinned SHA-256 sums. Nothing
large is committed.

```sh
# On the Mac: download + verify, unpack the armv7 JNI libs into the module, stage the models.
android/feature/assistant/tools/fetch-voice.sh
# Build and install the app once (creates the app's external files dir), then push the models:
./scripts/build-install.sh --screen assistant
android/feature/assistant/tools/push-voice-models.sh          # all three
android/feature/assistant/tools/push-voice-models.sh stt tts  # or some
```

| Part | Model | Licence | Size on device |
|---|---|---|---|
| Native runtime | sherpa-onnx v2.0.1 `libsherpa-onnx-jni.so` + `libonnxruntime.so` (armeabi-v7a) | Apache-2.0 (onnxruntime: MIT) | 18 MB in the APK |
| Wake word | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (int8 encoder/joiner) | Apache-2.0 | 5.0 MB |
| Speech-to-text | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` (int8 encoder/joiner) | Apache-2.0 | 34 MB |
| Text-to-speech | `vits-piper-en_US-amy-low-int8` (Piper, 16 kHz, one voice) | MIT (Piper) / voice: CC BY 4.0 per its MODEL_CARD | 36 MB (incl. espeak-ng-data) |

The models live in `/sdcard/Android/data/com.echobedside/files/voice/{kws,stt,tts}` (the app's own
external files dir: no permission, no root, nothing in `/system`). The push script holds the
device lock and replaces each folder. The libs are packaged from
`android/feature/assistant/src/main/jniLibs/` (git-ignored); a build without them still works and
the idle screen says "The voice models aren’t installed yet".

The Echo has no Android TextToSpeech engine installed (checked: `cmd tts` / no `TTS_SERVICE`
providers), so Piper is the voice.

## 3. Microphone permission

The app asks for nothing at runtime yet; grant it once over adb:

```sh
./platform-tools/adb -s "$(cat .echo-serial)" shell pm grant com.echobedside android.permission.RECORD_AUDIO
```

## 4. Using it

- **Tap the droplet** to talk (tap-to-talk is the default). The mic opens, the STT model loads
  (the audio is buffered meanwhile) and the words appear as you speak; 0.9 s of silence ends it.
  Tap again to stop at any time.
- **Chips** send their question to Claude (in demo mode they play the demos).
- While Claude works, each tool call is a step ("Timer", "Weather", …). The answer is revealed
  word by word and spoken; result cards show what the tools did (timer/alarm tiles, weather,
  now playing).
- A follow-up within 60 s keeps the conversation.
- The answer stays 12 s (`assistant.dismissSec`), then the screen returns to "How can I help?"
  (or to StandBy if the wake word opened it).
- The island shows the assistant while listening / working / speaking on other screens.

### Wake word (optional)

Off by default (`assistant.wakeWord = false`). When on, the mic and the 3.3M keyword spotter run
whenever the assistant is idle; a voice gate means the model only decodes around speech. Saying
the wake word opens the assistant screen (`assistant.openOnWake`, default true) and starts
listening. See docs/status/assistant.md for its measured idle cost before turning it on.

"Hey Nova" is a placeholder. The spotter takes the phrase as the model's BPE tokens in
`kws/keywords.txt`, one phrase per line:

```
▁HE Y ▁NO V A :2.0 #0.25 @HEY_NOVA
```

`:2.0` is the boost score, `#0.25` the trigger threshold (higher = fewer false wakes), `@NAME` the
label. To change the phrase, split it into pieces that exist in `kws/tokens.txt` (word-initial
pieces start with `▁`; uppercase), edit `fetch-voice.sh` (or the file on the device) and push
`kws` again. The name Claude uses for itself is `assistant.name` (default "Nova").

### Settings (SettingsStore keys)

| Key | Default | Meaning |
|---|---|---|
| `assistant.enabled` | true | Off: no listening at all |
| `assistant.wakeWord` | false | Always-on wake word |
| `assistant.openOnWake` | true | The wake word opens the assistant screen |
| `assistant.speak` | true | Speak answers (else only shown) |
| `assistant.keepSttLoaded` / `assistant.keepTtsLoaded` | false | Keep models in RAM between turns (faster, ~+60–90 MB) |
| `assistant.dismissSec` | 12 | How long an answer stays |
| `assistant.name` | Nova | The assistant's name in the system prompt |

### Debug / measuring (screen args)

`./scripts/build-install.sh --screen assistant --extra '--es <key> <value>'`:
`script a|b|c` plays a demo (`at <ms>` freezes it), `listen 1` starts a turn, `echo 1` listens and
repeats the transcript with the on-device voice (no Claude), `say <text>` speaks, `mictest 1`
captures the mic without models, `wake 1` turns the wake word on for this run.

## 5. Privacy

- **Audio never leaves the Echo.** Wake word, transcription and speech run locally; no audio is
  stored.
- Only the **transcript text**, the local time / city, the tool definitions and the tool results
  (e.g. your timers, the forecast, the titles of today's calendar events, the track playing) are
  sent to Anthropic over HTTPS, for that turn. The last minute of conversation is kept in memory
  for follow-ups, then dropped. See Anthropic's API data retention policy for what happens
  server-side.
- Nothing is logged: not the key, not transcripts, not answers (logcat only shows phases, timings
  and error types, tag `EchoAssistant`).
- The mic is off unless a turn is active or the wake word is enabled. **Muting** (the mic button
  or Control Center) stops capture immediately and ends any turn; the status pill says "Mic off".
- Home controls are offered to Claude only when Home Assistant is configured, and are limited to
  lights, switches, fans, covers, scenes, scripts and media players (no locks or alarm panels).
