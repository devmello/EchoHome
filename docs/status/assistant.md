# Voice assistant (agent G, `:feature:assistant`)

Setup, models, settings and privacy: [docs/assistant-setup.md](../assistant-setup.md).

## Done

- **Assistant screen** `ScreenSpec("assistant", order = 60, inPager = false)`, opened with
  `services.navigator.show("assistant")`. It follows `design/Assistant.dc.html` 1:1 at 960×480:
  - the droplet with a level-driven glow
  - status pill, clock, "How can I help?" with its glass chips
  - live transcript (heard / still to come)
  - "Working on it…" with step chips (spinner, then tick)
  - the answer revealed word by word, with result cards: weather, alarm suggestion sheet,
    action tiles, media card
  - Liquid Glass from a quarter-size blurred snapshot of what was on screen (no RenderEffect)
  - night colours via `Night.map`
- **Live activity** `ASSISTANT` (`LiveContent.Assistant(phase, level)`, `ownerScreen =
  "assistant"`). It shows while listening, working or speaking, with a Stop action. The level is
  updated 10×/s, only while another screen shows the island.
- **On-device voice** (sherpa-onnx v2.0.1, JNI vendored, armv7 libs fetched by
  `tools/fetch-voice.sh` from the official releases, SHA-256 pinned):
  - Streaming zipformer STT with endpoint rules. Loaded per turn while the mic buffers, and
    freed after the transcript.
  - Piper TTS (amy low **fp32**, 3 threads, with the official `lexicon-en-us.txt`). Loaded on its
    own thread while Claude works, freed after speaking. The droplet follows the speech level.
    Off by default (slower than real time here, see the measurements). Numbers, times and units
    are spelled out for speech (`SpeechText`).
  - **Sentence streaming** (`SentenceChunker`, `Speaker.open` / `Utterance`): each sentence of
    Claude's streamed answer (the first may be a clause) is spoken as soon as it's complete;
    piece N+1 is synthesized while N plays (player thread + queue). Text before a tool call that
    isn't finished is dropped.
  - **System voice** (`SystemTtsSpeaker`, `VoiceWithSystemTts`): an installed Android TTS engine
    (eSpeak NG / RHVoice from F-Droid) becomes the voice "System voice", the default voice, and
    turns speaking on by default. Pieces are queued with `QUEUE_ADD`. Not tried on the Echo (no
    engine installed).
  - `scripts/fetch-voice.sh` (`--libs`, `--push`) fetches the pinned libs and models for a fresh
    checkout.
  - Optional keyword-spotter wake word behind a voice gate (`VoiceGate`). The model only decodes
    around speech. Stats go to the log every 10 s.
- **Claude** (Messages API over OkHttp, SSE streaming, `anthropic-version: 2026-10-21`):
  - Tool loop with eager input streaming and client-side schema validation (`is_error`
    INVALID_JSON / INVALID_INPUT).
  - `refusal` / `max_tokens` are checked before any tool runs.
  - One retry on busy/offline, only if nothing was streamed yet.
  - The key is read **per request** from `services.config.secrets.anthropicApiKey`. Availability
    follows `services.configFlow`, so a key set on the settings page works without a restart.
  - The model comes from settings:
    - Haiku 5.5 (default) with `thinking: disabled`
    - Sonnet 5.5 with `between_tools`
    - Opus 5.5 with no thinking param
    - effort `low` on all three
  - History is kept for 60 s and dropped on a model change, or when it grows past 24 messages
    (never prefix-trimmed).
- **Tools** (`EchoTools`), through the services:
  - timers: set / list / cancel
  - alarms: set / list / update / delete
  - weather (`services.location` for the place), calendar (today / tomorrow)
  - now playing (with `can_skip` / `can_pause`), media control (errors CANT_SKIP / CANT_PAUSE
    instead of pretending), volume
  - display: brightness / volume read, `setBrightness(v, showHud = true)`
  - Home Assistant list / control, **only if `haConfigured`** (lights, switches, fans, covers,
    scenes, scripts, media players; no locks)
- **Settings card "Assistant"** (`AssistantSettings.section`, order 60, all applied live):
  - on/off, wake word, wake word sensitivity (threshold per stream), open on wake
  - Claude model, follow-up listening, speak answers, speak at night
  - voice (installed voices), speaking speed, answer time
  - advanced: name, keep STT / TTS loaded
  - The Anthropic key is not declared here (it's agent K's SECRET).
- **Follow-up listening.** When the answer ends in "?", the assistant listens again. Silence
  puts the answer back without an apology.
- **Quiet at night.** While `nightActive`, answers are shown but not spoken unless
  `assistant.speakAtNight` is on.
- **Mic rules.**
  - Tap-to-talk by default; the wake word is off by default.
  - `micMuted` (hardware or software) stops capture at once and ends the turn.
  - The island shows while listening.
  - The echo test never speaks.
- **Demo mode** (demo data, no key): the droplet and chips play the three design scripts
  (a/b/c, with `script` + `at` args to freeze a state).
- **Privacy.** The key, transcripts and answers are never logged; logcat (`EchoAssistant`) shows
  phases, timings, gate levels and error types only. Audio never leaves the device.
- **Tests:** 66 JVM tests, all passing (`:feature:assistant:testDebugUnitTest`):
  - reducer and demo timelines
  - SSE parser
  - agent end to end against MockWebServer: headers, body per model, tool_result shape,
    invalid input, refusal, max_tokens, 401 / 529 / 429, history
  - tool schemas, EchoTools (canSkip, brightness, timers / alarms)
  - engine: tap-to-talk, mute, silence, errors, demo, wake word, sensitivity reload, runtime
    key, night quiet, speak off, follow-up, model change
  - audio gate (incl. steady room noise), keywords threshold rewrite, settings section, speech text (times, numbers, units), sentence chunking, streamed speech, system voice

## Left

- **Not tried against the real API on the Echo:** no Anthropic key is set on the device or in
  `secrets.properties`. The client is tested end to end against a fake Messages API only.
- **Heard once on the Echo** (after quiet hours, stream 3 at volume 2, restored to 5): a
  two-sentence streamed answer with Piper fp32. It played; 8.3 s of speech took 19 s, first
  sound 3.6 s after synthesis began. Ducking and the speaking-level droplet weren't judged.
- **No real-time voice yet.** No sherpa-onnx voice reaches RTF < 0.8 on the Echo (best: Piper amy
  fp32 at 3 threads, RTF 1.22), so speaking is off by default. Next step: an eSpeak NG engine
  APK (needs the user's OK to download; see Needs from the user).
- **Wake word not tried with a real voice** (quiet hours): only on test WAVs and room noise.
- **The mic button** sends KEY_POWER; mapping it to `navigator.show("assistant", listen = 1)`
  belongs to `:app` / system (see Contract requests).
- **RECORD_AUDIO:** `:app` now asks for it (main 24e17cf); it's also granted by adb.
- **Audio focus / ducking** of other players during speech needs C/J to honour focus (contract
  request filed).
- The wake word is the placeholder "Hey Nova" (`kws/keywords.txt`).

## Measured on the Echo

Debug build, Nov 22 2026, `scripts/echo-perf.sh` (PSS from `dumpsys meminfo`, CPU as % of one
core, frames from gfxinfo). All of it was silent: no sound was played.

| State | PSS | CPU | Frames |
|---|---|---|---|
| StandBy, assistant idle (tap-to-talk, mic off) | 68.2 MB | 1.4% | p50/p90 7/8 ms |
| StandBy with the wake word on, quiet room, gate learned | 107.2 MB (Native 34.1) | 4.8% (incl. one frame of 36 ms) | p50/p90 7/9 ms |
| Assistant screen idle (droplet animating, no mic) | 71.7–77.4 MB | 34.5–36.5% | ~30 fps, p50/p90 8–9 / 9–10 ms |
| Assistant screen + wake word (quiet room) | 106.3 MB | 36.5% (same as without) | 449 frames in 15 s |
| Mic capture alone (`mictest`) | 53.5 MB | 39.4% (at 60 fps; mostly drawing) | |
| STT loaded (during a turn) | 133–136 MB | RTF 0.49–0.61 on 2 threads | |
| After the turn (STT freed) | 95.6 MB | | |
| TTS Piper amy fp32, 3 threads (installed) | 157–166 MB | RTF 1.22, load 20 s (+10 s lexicon) | |
| After speaking a streamed answer (fp32) | 150.3 MB | | |
| TTS Piper amy int8, 2 / 4 threads (before) | 120–164 MB | RTF 3.4 / 3.8, load 28–38 s | |

Per model:
- **Wake word (KWS 3.3M, int8):** loads in 2.3–6.5 s. While decoding, RTF 0.42–0.44 on one
  thread (int8 and fp32 encoders are the same speed). It found the test keyword once in a test
  WAV. In a quiet room the gate lets nothing through after the first ~10 s (log: "decoded 0/250
  chunks, floor -48 dB, gate shut"). In those first 10 s, while the gate learns the floor, it
  decoded 256/301 chunks (4.2 s of CPU). The native heap isn't given back after the wake word
  is turned off (90–95 MB afterwards).
- **STT (zipformer 20M, int8):** loads in 2.7–3.3 s while the mic buffers. It transcribed
  15 words of the official test WAV and 26/26 words of the synthesized answer (loopback, silent).
- **TTS:** no sherpa-onnx voice is real time on this CPU (quad A53 at 1.3 GHz, governor
  `performance`). Same ~12 s answer, `bench tts`, silent, on the assistant screen:

  | Voice | Threads | Load | First sentence | RTF | PSS |
  |---|---|---|---|---|---|
  | Piper amy low fp32 (installed) | 3 | 20 s | 3.0 s | **1.22** | 157–166 MB |
  | Piper amy low fp32 | 1 / 2 / 4 | 20 / 20 / 23 s | 5.1 / 3.3 / 5.3 s | 2.27 / 1.43 / 2.08 | 158–186 MB |
  | Piper amy low int8 | 2 / 4 | 35 / 28–38 s | – / 9.3 s | 3.40 / 3.8 | 120–164 MB |
  | Matcha ljspeech + HiFiGAN v2 | 2 | 25 s | 6.7 s | 2.33 | 164–181 MB |
  | icefall VITS ljspeech low | 2 | 8.7 s | 5.1 s | 2.44 | 126–150 MB |
  | Supertonic int8, 4 / 2 steps | 2 / 4 | 11–12 s | 23–25 s | 2.04 / 1.88 | 193–222 MB |

  - int8 is 2.7× slower than fp32 on armv7 (and 2× on the Mac).
  - 3 threads are best; 4 are slower than 2 (the screen's animation and the system need a core).
  - The full lexicon adds about 10 s and 40 MB to the load.
  - lessac-low and ryan-low are the same network as amy-low (same size and speed on the Mac).
  - On the Mac, amy fp32 runs at RTF 0.055, so the Echo is about 26× slower.
- **APK:** 37 MB (debug, current main), 18 MB of it the sherpa-onnx and onnxruntime libs.
- Whole app under the 200 MB PSS budget in every installed configuration (at most 186 MB while
  Piper fp32 speaks).

### Bugs found on the device and fixed

- **Wake word at 100% CPU, voice thread stuck.** The `-mobile` KWS int8 encoder fails on its
  second chunk (Reshape in `/downsample/Reshape_1`; reproduced on the Mac with onnxruntime 1.21).
  Switched to the non-mobile tarball of the same model. Decodes per chunk are also capped (8).
- **Voice gate stuck open.** The room level (-49 dBFS) was above the speech threshold, and the
  noise floor never learned it. The floor now learns slowly while it's loud.
- **TTS produced no audio.** sherpa-onnx v2 dropped espeak-ng; Piper needs `lexicon-en-us.txt`
  (official tts-models release, now fetched and pinned).
- **TTS native crash** (CheckJNI NoSuchMethodError `invoke([F)Ljava/lang/Integer;`). A Kotlin
  lambda has no typed `invoke`; the callback is now a class (`SampleCallback`), kept in release.
- **A turn waited on the slow voice.** TTS now loads on its own thread, and an answer waits at
  most 1.5 s for it.

## Screenshots

`docs/status/shots/assistant/`, from this build on the Echo (`script a|b|c` with `at`), compared
with `design/Assistant.dc.html`:

| Shot | State | Design state | Match |
|---|---|---|---|
| `idle.png` | "How can I help?", chips, status pill | idle | Layout, type and glass chips match. Status text reflects availability ("No key" / demo) |
| `listening-a.png` | live transcript, heard + dimmed rest | listening (a) | Matches; last 4 lines kept |
| `working-a.png` | "Working on it…", Weather done / Calendar busy / Traffic pending | working (a) | Matches |
| `working-b.png` | three steps, scene b | working (b) | Matches |
| `answer-a.png` | answer + weather card + alarm suggestion sheet | answer (a) | Matches; the sheet is glass over the snapshot |
| `answer-b.png` | three action tiles | answer (b) | Matches |
| `answer-c.png` | media card | answer (c) | Matches; the art is procedural (sample data) |

## Open questions

- Is "Hey Nova" the wake phrase? It's a placeholder; the keyword file takes any phrase made of
  the model's tokens.
- Should the wake word be on by default once the user has heard it? It's off now. See its idle
  cost above.
- **Which voice route?** Every sherpa-onnx voice measured is slower than real time here. The
  adapter for an Android TTS engine is built (System voice). The options are:
  1. eSpeak NG from F-Droid (fast, robotic; GPL-3.0; a separate app, not linked);
  2. RHVoice from F-Droid (more natural, heavier; not measured);
  3. keep Piper with "Keep TTS loaded" and accept pauses (RTF 1.22, about 70 MB kept). Without
     it the 20–30 s load is never done within the 1.5 s an answer waits, so Piper only speaks
     with Keep TTS loaded on.
  Until then answers are shown, not spoken.
- Follow-up listening opens the mic without a tap when Claude asks a question. It's on by
  default; turn it off if that's unwelcome at night.

## Needs from the user

- **An Anthropic API key**, set on the Vesper settings page (or in `android/secrets.properties`).
- **OK to download the eSpeak NG TTS engine APK from F-Droid** (f-droid.org; the package is probably
  `com.reecedunn.espeak`) and install it with `adb install` (no root, no /system). Then: pick
  "System voice", measure, and listen at low volume.
- A wake word test with a real voice after quiet hours.

### Device setup done

- `pm grant com.echobedside android.permission.RECORD_AUDIO` (adb, no root).
- Models pushed to `/sdcard/Android/data/com.echobedside/files/voice/{kws,stt,tts}` (106 MB, tts
  now the fp32 voice). The test voices were removed again. `voice/bench/` holds the test WAVs
  and texts (small).
  Nothing in `/system`, nothing flashed.

### Contract requests filed (main `docs/contract-requests.md`)

- Audio focus ducking for C (AirPlay) / J (media).
- `:app` requests RECORD_AUDIO (done on main, 24e17cf).
- FYI: start the assistant via `navigator.show("assistant", bundle listen = 1)`, for the mic
  button.
