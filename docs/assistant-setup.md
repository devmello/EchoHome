# Voice assistant setup (Vesper)

The assistant (`:feature:assistant`, design `design/Assistant.dc.html`) listens, transcribes and
speaks **on the Echo**, and asks **Claude** (Anthropic Messages API, Claude Haiku 5.5 by default)
for the answer. Home Assistant is not needed. Without a key, debug builds with demo data play the
design's three scripted demos from the droplet and the chips.

```
mic (16 kHz) ─► wake word (optional, on device) ─► streaming STT (on device) ─► transcript text
                                                                                    │
            speaker ◄─ TTS (on device) ◄─ answer text + cards ◄─ Claude + tools ◄───┘
                                                                (timers, alarms, weather,
                                                                 calendar, media, volume,
                                                                 brightness, Home Assistant
                                                                 if it's set up)
```

## 1. Anthropic API key

There are two ways to set it. You don't need to rebuild for either one.

- **Settings page (recommended).** Open the Vesper settings page and paste the key into the
  Anthropic API key field (a SECRET field, declared by the settings feature). It's stored
  encrypted on the Echo, and the page only shows whether it's set. The assistant picks it up
  right away: `services.configFlow` emits the change, the status pill switches from "No key" to
  ready, and the next request uses the new key.
- **Build time.** Put `anthropic.apiKey=sk-ant-...` in `android/secrets.properties`. That file is
  git-ignored; copy it from `secrets.properties.example`. A key set on the page takes priority
  over this one.

The key is read for each request. It only goes in the `x-api-key` header and is never logged.
Without a key, the idle screen says "Add an Anthropic API key to talk to Claude".

Requests use:
- `anthropic-version: 2026-10-21`, streaming
- `output_config.effort: low`, at most 1024 output tokens
- thinking off on Haiku, `between_tools` on Sonnet, and left at the default on Opus (Opus can't
  turn it off)

A typical turn is one or two requests of about 2–3k input tokens, mostly the tool definitions.
On Haiku that costs well under a tenth of a cent.

## 2. Voice runtime and models (on-device, sherpa-onnx)

Everything comes from the official sherpa-onnx GitHub releases
(github.com/k2-fsa/sherpa-onnx, Apache-2.0) and is checked against pinned SHA-256 sums. Nothing
large is committed to git.

```sh
# On the Mac: download + verify, unpack the armv7 JNI libs into the module, stage the models.
android/feature/assistant/tools/fetch-voice.sh
# Build and install the app once (this creates the app's external files dir), then push the models:
./scripts/build-install.sh --screen assistant
android/feature/assistant/tools/push-voice-models.sh          # all three
android/feature/assistant/tools/push-voice-models.sh stt tts  # or only some
```

| Part | Model | Licence | Size |
|---|---|---|---|
| Native runtime | sherpa-onnx v2.0.1 `libsherpa-onnx-jni.so` + `libonnxruntime.so` (armeabi-v7a) | Apache-2.0 (onnxruntime: MIT) | 18 MB in the APK |
| Wake word | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (int8 encoder/joiner) | Apache-2.0 | 5.0 MB on device |
| Speech-to-text | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` (int8 encoder/joiner) | Apache-2.0 | 34 MB on device |
| Text-to-speech | `vits-piper-en_US-amy-low-int8` (Piper, 16 kHz, one voice) | MIT (Piper); voice CC BY 4.0, per its MODEL_CARD | 36 MB on device, incl. espeak-ng-data |

The models live in `/sdcard/Android/data/com.echobedside/files/voice/{kws,stt,tts}`. That's the
app's own external files dir: no permission or root is needed, and nothing goes in `/system`. The
push script holds the device lock and replaces each folder.

The libs are packaged from `android/feature/assistant/src/main/jniLibs/` (git-ignored). A build
without them still works, and the idle screen says "The voice models aren’t installed yet".

The Echo has no Android TextToSpeech engine installed (checked: `cmd tts` shows no
`TTS_SERVICE` providers), so Piper is the voice.

**More voices.** Push any sherpa-onnx Piper voice folder to `voice/tts/<name>/`. The folder needs
one `.onnx` file, its `tokens.txt` and `espeak-ng-data`. The voice then shows up in the Voice
setting after the app restarts. Amy is the flat `voice/tts/` folder.

## 3. Microphone permission

The app doesn't ask for the permission at runtime yet. Grant it once over adb:

```sh
./platform-tools/adb -s "$(cat .echo-serial)" shell pm grant com.echobedside android.permission.RECORD_AUDIO
```

## 4. Using it

- **Tap the droplet** to talk (tap-to-talk is the default). The mic opens and the STT model loads
  while the audio is buffered. Your words appear as you speak, and 0.9 s of silence ends the
  turn. Tap again to stop at any time.
- **Chips** send their question to Claude. In demo mode they play the demos.
- While Claude works, each tool call shows as a step ("Timer", "Weather", …). The answer is
  revealed word by word and spoken. Result cards show what the tools did: timer and alarm tiles,
  weather, now playing, brightness.
- **Follow-ups.** A new question within 60 s keeps the conversation. When Claude answers with a
  question, the assistant listens for your reply right away (Follow-up listening). If you say
  nothing, the answer just stays on screen.
- **Quiet at night.** While the display is in night mode, answers are shown but not spoken
  unless "Speak at night" is on.
- The answer stays on screen for 12 s (`assistant.dismissSec`). Then the screen goes back to "How
  can I help?", or to StandBy if the wake word opened it.
- On other screens, the island shows the assistant while it's listening, working or speaking.

### Wake word (optional)

The wake word is off by default. When it's on, the mic and the 3.3M keyword spotter run whenever
the assistant is idle. A voice gate means the model only decodes around speech. Saying the wake
word opens the assistant screen (`assistant.openOnWake`, default true) and starts listening.
Check its measured idle cost in docs/status/assistant.md before you turn it on.

"Hey Nova" is a placeholder. The spotter takes the phrase as the model's BPE tokens, one phrase
per line in `kws/keywords.txt`:

```
▁HE Y ▁NO V A :2.0 #0.25 @HEY_NOVA
```

- `:2.0` is the boost score.
- `#0.25` is the trigger threshold. The Wake word sensitivity setting replaces it: Low 0.35,
  Normal 0.25, High 0.15. Lower means it triggers more easily.
- `@NAME` is the label.

To change the phrase:
1. Split it into pieces that exist in `kws/tokens.txt`. Word-initial pieces start with `▁`, and
   everything is uppercase.
2. Edit `fetch-voice.sh` (or the file on the device).
3. Push `kws` again.

The name Claude uses for itself is a separate setting (`assistant.name`, default "Nova").

### Settings (the "Assistant" card on the settings page)

Every value applies right away, with no restart.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `assistant.enabled` | on/off | on | Off: the assistant never uses the mic |
| `assistant.wakeWord` | on/off | off | Always-on wake word |
| `assistant.wakeSensitivity` | Low / Normal / High | Normal | Keyword spotter threshold 0.35 / 0.25 / 0.15 |
| `assistant.openOnWake` | on/off | on | The wake word opens the assistant screen |
| `assistant.model` | choice | `claude-haiku-5-5` | Haiku 5.5, Sonnet 5.5 or Opus 5.5. A change starts a new conversation |
| `assistant.followUp` | on/off | on | Listen again when Claude asks a question |
| `assistant.speak` | on/off | on | Speak answers (when off, they're only shown) |
| `assistant.speakAtNight` | on/off | off | Speak while night mode is active |
| `assistant.voice` | choice | `amy` | The installed on-device voices |
| `assistant.speechRate` | 0.7–1.4 | 1.0 | Speaking speed |
| `assistant.dismissSec` | 5–60 | 12 | How long an answer stays on screen |
| `assistant.name` (advanced) | text | Nova | The assistant's name in the system prompt |
| `assistant.keepSttLoaded` / `keepTtsLoaded` (advanced) | on/off | off | Keep the models in RAM between turns: faster, but costs memory (see the status file) |

### Debug / measuring (screen args)

Run `./scripts/build-install.sh --screen assistant --extra '--es <key> <value>'` with one of
these:

| Arg | What it does |
|---|---|
| `script a\|b\|c` | Plays a demo; `at <ms>` freezes it at that point |
| `listen 1` | Starts a turn |
| `ask <text>` | Sends text to Claude |
| `echo 1` | Listens and shows the transcript (STT only, no Claude, nothing spoken) |
| `synth <text>` | Synthesizes speech **without playing it**, and logs load time, synthesis time and RTF |
| `say <text>` | Speaks with the on-device voice (**plays sound**) |
| `mictest 1` | Captures the mic without models |
| `wake 1` | Turns the wake word on for this run |

While the wake word runs, logcat (tag `EchoAssistant`) shows every 10 s how much audio the gate
let through to the model.

## 5. Privacy

- **Audio never leaves the Echo.** The wake word, transcription and speech all run locally, and
  no audio is stored.
- These go to Anthropic over HTTPS for that turn:
  - the **transcript text**
  - the local time and city
  - the tool definitions
  - the tool results (for example your timers, the forecast, the titles of today's calendar
    events, the track playing)
- The last minute of conversation is kept in memory for follow-ups, then dropped. It's also
  dropped when you change the model. See Anthropic's API data retention policy for what happens
  server-side.
- Nothing sensitive is logged: not the key, transcripts or answers. Logcat only shows phases,
  timings, gate levels and error types.
- The mic is off unless a turn is active or the wake word is on.
- **Muting** (the mic button or Control Center) stops capture immediately and ends any turn. The
  status pill then says "Mic off".
- Home controls are offered to Claude only when Home Assistant is configured. They're limited to
  lights, switches, fans, covers, scenes, scripts and media players (no locks or alarm panels).
