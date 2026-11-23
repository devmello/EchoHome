# Voice assistant setup (EchoHome)

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

- **Settings page (recommended).** Open the EchoHome settings page and paste the key into the
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
./scripts/fetch-voice.sh            # libs + models (the module's tools/fetch-voice.sh does the work)
./scripts/fetch-voice.sh --libs     # only the JNI libs: run this once in every checkout that builds
# Build and install the app once (this creates the app's external files dir), then push the models:
./scripts/build-install.sh --screen assistant
android/feature/assistant/tools/push-voice-models.sh          # all three
android/feature/assistant/tools/push-voice-models.sh stt tts  # or only some
```

**A checkout without the libs builds an APK without a voice.** Installing it on the Echo replaces
a working one (the assistant then says the voice models aren't installed). Every worktree that
installs on the Echo should run `./scripts/fetch-voice.sh --libs` once.

| Part | Model | Licence | Size |
|---|---|---|---|
| Native runtime | sherpa-onnx v2.0.1 `libsherpa-onnx-jni.so` + `libonnxruntime.so` (armeabi-v7a) | Apache-2.0 (onnxruntime: MIT) | 18 MB in the APK |
| Wake word | `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (the non-mobile tarball; int8 encoder/joiner) | Apache-2.0 | 5.8 MB on device |
| Speech-to-text | `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` (int8 encoder/joiner) | Apache-2.0 | 34 MB on device |
| Text-to-speech | `vits-piper-en_US-amy-low` (Piper fp32, 16 kHz, one voice; not the -int8 build, which is 2.7× slower here) | MIT (Piper); voice CC BY 4.0, per its MODEL_CARD | 66 MB on device, incl. the lexicon |
| Pronunciation | `lexicon-en-us.txt` from the sherpa-onnx `tts-models` release | Not stated by the release; derived from CMUdict (BSD-2) and espeak-ng's en_list (GPL-3.0). A data file, not linked code | 6.3 MB, in `tts/` |

Two things to know about these files:
- **Use the non-mobile KWS tarball.** The `-mobile` int8 encoder of the same model fails on its
  second chunk (a Reshape of {17,1,128} to {8,2,1,128} in `/downsample/Reshape_1`, reproduced on
  the Mac with onnxruntime 1.21). On the Echo the failed decode kept the voice thread at 100% CPU.
- **sherpa-onnx v2 has no espeak-ng.** Piper voices need the `lexicon-en-us.txt` word list
  instead of `espeak-ng-data`, which v2 ignores. Without it the voice produces no audio.

The models live in `/sdcard/Android/data/app.vesper/files/voice/{kws,stt,tts}`. That's the
app's own external files dir: no permission or root is needed, and nothing goes in `/system`. The
push script holds the device lock and replaces each folder.

The libs are packaged from `android/feature/assistant/src/main/jniLibs/` (git-ignored). A build
without them still works, and the idle screen says "The voice models aren’t installed yet".

### How fast the voices are on the Echo

The Echo has no Android TextToSpeech engine installed (checked: no `TTS_SERVICE` providers), so
the built-in voice is Piper. Measured on the Echo (quad Cortex-A53 at 1.3 GHz, governor
`performance`), silently with `bench tts,...` on the assistant screen, for the same three-sentence
answer (about 12 s of speech):

| Voice (sherpa-onnx v2.0.1) | Threads | Load | First sentence | RTF | PSS (app) |
|---|---|---|---|---|---|
| Piper amy low **fp32** (installed) | **3** | 20 s (+10 s with the full lexicon) | 3.0 s | **1.22** | 157–166 MB |
| Piper amy low fp32 | 2 | 20 s | 3.3 s | 1.43 | 161–186 MB |
| Piper amy low fp32 | 1 | 20 s | 5.1 s | 2.27 | 158–180 MB |
| Piper amy low fp32 | 4 | 23 s | 5.3 s | 2.08 | 167–186 MB |
| Piper amy low int8 (before) | 2 | 35 s | – | 3.40 | 139–164 MB |
| Piper amy low int8 | 4 | 28–38 s | 9.3 s | 3.8 | 120–161 MB |
| Matcha ljspeech (3 steps) + HiFiGAN v2 | 2 | 25 s | 6.7 s | 2.33 | 164–181 MB |
| icefall VITS ljspeech low | 2 | 8.7 s | 5.1 s | 2.44 | 126–150 MB |
| Supertonic int8 (4 steps; 44.1 kHz) | 2 | 11 s | 25 s (no sentence split) | 2.04 | 193–222 MB |
| Supertonic int8 (2 steps) | 4 | 12 s | 23 s | 1.88 | 193–202 MB |

lessac-low and ryan-low are the same network as amy-low (same size and speed on the Mac), so
they're no faster. Four threads are slower than three: the screen's animation and the system
need a core. None of these reaches real time, so **speaking stays off by default** with Piper.

What's in place anyway:
- **Sentence streaming.** Claude's answer is cut into sentences as it streams (the first piece may
  be a clause after four words). Each piece is spoken as soon as it's complete, and piece N+1 is
  synthesized while piece N plays (a player thread with a queue). With a voice faster than real
  time this starts speech on the first sentence and plays without gaps. With Piper it starts
  about 3.5 s after the first sentence and pauses between sentences: measured with a
  two-sentence answer at volume 2, 8.3 s of speech took 19 s.
- **System voice.** An installed Android TTS engine (eSpeak NG or RHVoice from F-Droid) shows up
  as the voice "System voice", is the default voice, and turns speaking on by default. Pieces go
  to it with `QUEUE_ADD`, so it streams the same way. Not tried on the Echo yet: no engine is
  installed (it needs your OK to download the APK).
- The voice loads on its own thread while Claude works. An answer never waits more than 1.5 s for
  it: if the voice isn't ready by then, the answer is only shown. "Keep TTS loaded" avoids the
  20–30 s load at the cost of about 70 MB.

**More voices.** Push any sherpa-onnx Piper voice folder to `voice/tts/<name>/`. The folder needs
one `.onnx` file and its `tokens.txt`, plus `lexicon-en-us.txt` (or it uses the one in
`voice/tts/`). The voice then shows up in the Voice
setting after the app restarts. Amy is the flat `voice/tts/` folder.

## 3. Microphone permission

The app asks for the microphone once (`:app`). Without a touch on the Echo, grant it over adb:

```sh
./platform-tools/adb -s "$(cat .echo-serial)" shell pm grant app.vesper android.permission.RECORD_AUDIO
```

## 4. Using it

- **Tap the droplet** to talk (tap-to-talk is the default). The mic opens and the STT model loads
  while the audio is buffered. Your words appear as you speak, and 0.9 s of silence ends the
  turn. Tap again to stop at any time.
- **Chips** send their question to Claude. In demo mode they play the demos.
- While Claude works, each tool call shows as a step ("Timer", "Weather", …). The answer is
  revealed word by word (and spoken, if Speak answers is on). Result cards show what the tools did: timer and alarm tiles,
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
Measured idle cost in a quiet room: about +40 MB PSS (the model stays loaded) and about 5% of
one core on StandBy (1.4% without it). The gate learns the room's noise floor in the first ~10 s,
and the model decodes until then. Loading takes 2–6 s.

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
| `assistant.speak` | on/off | off (on with a system voice) | Speak answers, starting on the first sentence (when off, they're only shown). Off with Piper, which is slower than real time here |
| `assistant.speakAtNight` | on/off | off | Speak while night mode is active |
| `assistant.voice` | choice | `system` if an engine is installed, else `amy` | The system voice and the installed on-device voices |
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
| `synth <text>` | Synthesizes speech **without playing it**, logs load time, synthesis time and RTF, and saves `voice/bench/synth.wav` |
| `bench stt:<file>` | Transcribes a 16 kHz WAV under `voice/` and logs load time, RTF and the word count (not the words) |
| `bench kws:<file>` | Runs the wake word over a WAV and logs load time, RTF and hits |
| `voice <id>` | Switches the voice for this run |
| `bench tts,<threads>,<dir>,<model>,<lexicon>[,<vocoder>]:<text file>` | Synthesizes a text file silently with any voice and logs load time, time to the first sentence, RTF and PSS (`supertonic` as the model for Supertonic) |
| `say <text>` | Speaks with the on-device voice, fed word by word like Claude's stream (**plays sound**) |
| `mictest 1` | Captures the mic without models |
| `wake 1` | Turns the wake word on for this run |

While the wake word runs, logcat (tag `VesperAssistant`) shows every 10 s how much audio the gate
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
