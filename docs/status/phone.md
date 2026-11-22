# Agent J: iPhone over Bluetooth (`:feature:phone`)

Branch `worktree-agent-aad268db8eaa9bcf9`. Module: `android/feature/phone`.

## Advertising gate: passed (Nov 22, 2026, on the Echo)

| Check | Result |
|---|---|
| `BluetoothAdapter.isMultipleAdvertisementSupported` | **false** (the controller has one advertising instance) |
| `bluetoothLeAdvertiser` | **present** |
| Actual `startAdvertising` (connectable, legacy, 128-bit service UUID, name in scan response) | **onStartSuccess**: `mode=1 tx=2 connectable=true`, in both LOW_POWER and BALANCED |
| LE extended advertising | no |
| `AdvertiseData.Builder.addServiceSolicitationUuid` (ANCS solicitation, AD type 0x15) | **not available**: added in API 31; this is API 30 |

**What that means.** The Echo can be a connectable LE peripheral, so ANCS and AMS can work: the iPhone connects to the Echo, and the Echo is the GATT client on that link. The Echo **can't put the ANCS solicitation in its advertisement**, though, and that solicitation is what makes an accessory offer notifications in the iPhone's Settings › Bluetooth by itself.

**The plan, so the iPhone needs no extra app** (the user's expectation, Nov 22):
1. Pair the Echo from iPhone Settings › Bluetooth as a speaker (Classic).
2. When that phone's A2DP connects, the Echo connects out over LE to the same address, as GATT central, and subscribes to ANCS and AMS (`PhoneController.outboundOk`). That works if the Classic pairing also gave the bond LE keys through cross-transport key derivation (iOS and Android 11 both support SC-based CTKD). Whether this iPhone + controller pair does it is test step A5. It's tried once per audio connection.
3. Fallback, only if no notifications arrive: a free BLE app (nRF Connect) connects to the Echo and reads one encrypted characteristic, which starts the LE pairing.

After that, the Echo advertises again whenever the link drops, and the bonded iPhone should reconnect by itself (this needs checking with the real phone).

**Solicitation on API 30, with priv-app or hidden APIs: no way found** (looked for under 15 min).
- The advertisement is serialised by the Bluetooth app, not by us. This ROM's `com.android.bluetooth.gatt.AdvertiseHelper` (dexdump of `/system/app/Bluetooth/Bluetooth.apk`) only writes these AD types:
  - flags;
  - name (0x08/0x09);
  - service UUIDs;
  - TX power (0x0A);
  - service data (0x16/0x20/0x21);
  - manufacturer data (0xFF).
- The R `AdvertiseData` parcel has no solicitation field, so there's nothing to set by reflection either.
- Manufacturer or service data can't smuggle a separate AD structure, because the helper writes each length byte itself.
- The only ways in are a patched Bluetooth.apk (a /system change) or raw HCI commands to the controller (not reachable from an app). Both are off-limits here; ask the user if it's ever wanted.

Other options I looked at for when solicitation is missing:
- (a) Advertise as a HID device so iOS lists it in Settings. Rejected: iOS treats it as a keyboard and hides the on-screen keyboard.
- (b) The Echo connects to the iPhone as central. Done: when a bonded phone's A2DP connects (a first speaker pairing, or the saved iPhone), the Echo tries ANCS/AMS from its side. This is now step 2 of the plan above.
- (c) The priv-app install doesn't add solicitation either. It's a framework API level, not a permission.

## Done

- **ANCS consumer, pure Kotlin and JVM-tested** (`ancs/`):
  - `Ancs.kt`: UUIDs, IDs, flags and the codec. It parses the Notification Source and encodes Get Notification Attributes, Get App Attributes and Perform Notification Action.
  - `DataSourceAssembler`: puts responses split across MTU fragments back together by parsing them against the request. It handles multi-byte UTF-8 split across packets.
  - `AncsSession` (the state machine):
    - One Control Point request at a time. The next write waits for both the ATT write ack and the full response, because GATT allows one write in flight.
    - The app display name is fetched once per bundle id and cached.
    - Added/Modified fetch attributes. Removed cancels anything queued for that notification.
    - Error codes 0xA0–0xA3 and an 8 s timeout skip to the next request.
    - **PreExisting notifications are tracked but not fetched or bannered**, so nothing re-banners on reconnect. The exception is a ringing incoming call.
  - `AncsPresenter`:
    - Each notification becomes `Banner(appName = display name, appColor/glyph from AppCatalog (~33 common bundle ids plus a per-category fallback), title, body = subtitle · message, postedAt from the ANCS date, sourcePackage = bundle id, sourceDevice = phone name, primaryAction = the phone's PositiveAction label, e.g. "Call Back")`.
    - Silent notifications don't banner unless the setting is on. Hidden apps are configurable.
    - Removed on the phone dismisses the banner on the Echo.
    - **Dismiss on the Echo sends NegativeAction only when its label means Clear** ("Clear"/"Dismiss" plus localised forms). It never does this for calls or for unrecognised labels like "Delete"/"Archive".
    - It reacts only to `NotificationFeed.userDismissals` (contracts round 2: the renderer's `dismiss(id, byUser = true)`). Auto-hide timeouts, our own dismissals and other phones' ids never reach the iPhone.
  - `FakeIPhone`: a byte-exact ANCS provider, used by the tests and the on-device demo.
- **Calls:**
  - IncomingCall becomes `LiveActivity(CALL, LiveContent.Call(caller, "Answer on iPhone", video = false))` with **Decline only** (`DESTRUCTIVE`). It maps to the NegativeAction and ends on Removed.
  - ANCS has no Silence action, so there's none.
  - MissedCall and Voicemail become a normal banner ("Missed call", "Call Back" primary).
  - Incoming calls show on the island, not also as a banner: both would sit at the top edge.
- **LE peripheral** (`ble/LePeripheral`):
  - Connectable advertising with our service UUID `E5B0A1C2-3D4E-4F60-8A9B-0C1D2E3F4A5B` and the name in the scan response.
  - A GATT server with one `PERMISSION_READ_ENCRYPTED` characteristic (`…A1C3…`); reading it starts pairing.
  - Modes: OFF; RECONNECT (LOW_POWER, while a paired iPhone isn't connected); PAIRING (BALANCED, while the pairing screen is open).
  - Advertising stops while ANCS is connected.
  - Handles the start/stop race (a start that succeeds after a stop is undone).
- **ANCS + AMS GATT client** (`ble/AppleGattClient`, was `AncsGattClient`):
  - `connectGatt(TRANSPORT_LE)` on the link the iPhone opened, or out to a speaker-paired phone. Then discovery and MTU 185.
  - Subscriptions, in order: ANCS Data Source CCCD, then Notification Source CCCD; AMS Remote Command CCCD, then Entity Update CCCD; then the Track and Player Entity Update writes.
  - Either service can be missing or switched off. Each setting adds or drops its service on the next connect.
  - Every GATT op (CCCDs, ANCS control point, AMS commands, Entity Attribute write+read) goes through **one queue** with a 10 s watchdog, because GATT allows one op in flight. Before AMS, the ANCS client wrote directly.
  - If iOS refuses with insufficient authentication or encryption, it calls `createBond`, retries after BONDED (3 tries), and gives up.
  - Centrals with neither service are dropped and not probed again.
- **iPhone Now Playing over AMS** (`ams/`, Apple Media Service `89D3502B-…`, the lead's request, Nov 22):
  - **What it shows:** anything playing on the iPhone (Spotify, Music, Podcasts, YouTube…), wherever its audio goes.
  - **Codec and session** (`Ams`, `AmsSession`; pure and JVM-tested):
    - It reads the Track attributes (title, artist, album, duration) and the Player attributes (app name, PlaybackInfo "state,rate,elapsed", volume).
    - Truncated values (flag 1, cut at MTU − 6) are shown as they came. The full text is then read through Entity Attribute, one read at a time; if the value changes in the meantime, the change wins over the read.
    - A multi-byte UTF-8 character cut in half is dropped, not shown as "�".
    - AMS sends the app's **localized name** ("Spotify"), not its bundle id, so labels use that.
  - **Published** (`AmsMediaSource`):
    - MediaHub source `"iphone"`, `OTHER_APP`, `sourceLabel` "Spotify on Maya's iPhone", `route = ["Remote", phone, app]`. D's Now Playing then says "Playing from Spotify on Maya's iPhone", with no UI change.
    - Controls: play/pause (the explicit command, else toggle), next/previous. They're offered only when the iPhone lists them (`canSkip`/`canPause` from Remote Command), and `canSeek = false`.
    - Volume moves the **iPhone's** volume in 16 steps of Volume Up/Down, from the reported level. This happens only for this source.
  - **Precedence** (`AmsMapping.decide`):
    - When the same iPhone is the A2DP source, there's one entry: the Bluetooth one, labelled "Spotify on <phone>" plus an app chip. It keeps the Echo's volume.
    - Any other source playing on the Echo (AirPlay, another phone's Bluetooth, an app) hides the remote-only iPhone entry. When that source stops, the entry comes back.
    - Exception: D's sample media (`fake-airplay`, shown only while no real source exists) never hides it. It steps aside as soon as the iPhone entry appears.
  - **Album art** (`ArtworkLookup`):
    - AMS has none, and AVRCP art is off on this ROM, so both the AMS entry and the Bluetooth entry use the lookup.
    - It asks the keyless iTunes Search API (`term = artist + title`, `entity = song`) and takes `artworkUrl100` resized to 600. That's https only, with 5 s timeouts and size caps.
    - **It asks for `limit=5`, not 1, and uses the first result by the same artist.** On the Echo, `limit=1` for "Bon Iver Holocene" returned a Vitamin String Quartet cover. With no artist match there's no art, so podcast and video titles don't pick up some song's cover.
    - It waits 400 ms for the track's attributes to settle and never looks up a truncated title, so a cut-off or half-updated name is never sent.
    - The image is decoded subsampled and cropped to 324 px (Now Playing's art size) and cached by track: 6 entries, misses included.
    - It's a setting, on by default. Titles never go to the log; the log only says found/none/failed.
  - **Pairing:** the same bond as ANCS, and nothing else to do. iOS's "Share System Notifications" switch is ANCS-only, so Now Playing works even with it off.
- **Bluetooth audio → Now Playing** (`audio/BluetoothMediaSource`):
  - A framework `MediaBrowser` binds to `com.android.bluetooth/.avrcpcontroller.BluetoothMediaBrowserService` (exported, needs no permission), then a `MediaController` on its session.
  - Publishes `MediaHub` source `"bluetooth"`, `MediaSourceKind.BLUETOOTH`, `sourceLabel` = phone name, and `route = ["Bluetooth", phone]`.
  - Fills title, artist, album, duration, and position from `PlaybackState.lastPositionUpdateTime`.
  - Republishes only on UI-visible changes; a position jump over 1.5 s counts as one.
  - Controls: play/pause/next/previous through AVRCP. `canSeek = false` (AVRCP 1.6 has no absolute seek), `canSkip = canPause = true`.
  - Volume goes to the Echo's `STREAM_MUSIC` with no system UI; the iPhone follows it through AVRCP absolute volume.
  - `disconnect()` calls the hidden `BluetoothA2dpSink.disconnect` (reachable on this ROM), and falls back to pause. It never unpairs.
  - Callbacks only, no polling. Agent D skips `com.android.bluetooth` sessions, so the track appears once.
- **BLUETOOTH_CONNECTED island:**
  - Published when the phone's ANCS or A2DP connects. It's visible for 6 s (`staleAfterElapsed`), then stays in `island.activities` while connected, so Control Center can name its Bluetooth cell.
  - It ends when both links are gone. It's re-announced when a new link (audio or notifications) comes up.
- **Pairing screen** `phone-pairing` (`ScreenSpec(order = 90, inPager = false)`, `PhoneFeature.SCREEN_ID`):
  - No artboard exists, so it uses the expanded banner's layout: 64 px margins, a 64 px r18 tile with "Bluetooth · name" at 20/700, a 52/800 title, 22 px body, and 72 px r36 buttons at bottom 34 with one tinted primary.
  - One glass card for the paired phone: name, connection state, Notifications/Audio switches as plain fills inside the glass, and "Forget This iPhone" (tap twice).
  - A glass status capsule. The passkey shows at 96 px when a pairing request arrives.
  - Night mode maps every colour to red.
  - While it's open the Echo is discoverable for 300 s through the system prompt (Settings' RequestPermissionActivity). Going through that prompt also makes Settings show the pairing confirmation as a dialog, not as a notification our kiosk would hide.
  - Turning pairing mode off is debounced, because the system dialogs pause our activity, and the prompt is asked at most once per 300 s.
- **Device name:** the Bluetooth adapter name follows `DeviceName.get(settings)`, applied at start and live when `device.name` changes. **This renames the Echo's Bluetooth name from "Echo Show 5" to "Echo Show"** (lead's request).
- **Settings section "iPhone"** (order 40):
  - Show iPhone notifications.
  - Show what the iPhone is playing (AMS).
  - Look up album art (sends the song title to Apple).
  - Bluetooth audio.
  - Hide notifications from (TEXT_LIST of app names or bundle ids; calls always show).
  - Dismiss on the Echo clears the iPhone.
  - Show notifications delivered quietly (advanced).
  - **Forget iPhone** (ACTION: `removeBond` via reflection, reachable on this ROM).
  - All of them apply live through `SettingsStore.changes`.
- **Priv-app ready:**
  - `BLUETOOTH_PRIVILEGED` is requested in the manifest (it's on the lead's allow-list).
  - Guarded by `checkSelfPermission`, it enables Pair / Cancel on our own screen (`setPairingConfirmation`) and makes Audio off truly refuse A2DP (`A2dpSink.setConnectionPolicy(FORBIDDEN)`). Until the app is a system app, both stay off and the system dialog does the confirming.
- **Reconnect:**
  - After a reboot the app starts as HOME and advertises at low power if a phone is saved, so the bonded iPhone reconnects.
  - When the phone comes back in range the same advertising applies, plus an Echo-side ANCS attempt when its ACL or A2DP connects.
  - Audio is **not** auto-connected. Connecting A2DP would pull all the iPhone's audio to the Echo every time you walk in. You pick the Echo in the iPhone's audio picker instead.
- **Debug demo** (debug builds only; sample data; "Maya's iPhone" is made up): `--es screen phone-pairing --es phone_demo banner|mail|missed|call|media|ams|ams-podcast|connected|pairing|pairing-privileged|paired`.
  - The notification demos run the real `AncsSession` and `AncsPresenter` against a `FakeIPhone` exchanging real ANCS bytes, fragmented at MTU 23.
  - `ams` / `ams-podcast` run the real `AmsSession` and `AmsMediaSource` against a `FakeAmsPhone` (MTU 23, truncated values fetched back through fake Entity Attribute reads). Its fake iPhone answers Remote Commands. Then Now Playing opens.
- **Tests:** 72 JVM tests, all passing (`./gradlew :feature:phone:testDebugUnitTest`):
  - `AncsCodecTest` (11): NS parsing, command bytes, reassembly at MTU 23/27/64/185 and byte by byte, split UTF-8, mismatched uid.
  - `AncsSessionTest` (12): app-name caching, an ack after the response, PreExisting, a pre-existing call, Modified, Removed while queued, errors, timeouts, actions, truncation, reset.
  - `AncsPresenterTest` (19): banner fields, catalog fallback, dates, silent, hidden apps, Echo dismiss → Clear and never Delete, timeouts and other ids ignored, calls with Decline only, missed calls, clearAll.
  - `AvrcpMappingTest` (6).
  - `AmsTest` (14): command/subscription bytes, PlaybackInfo, a recorded Spotify stream at MTU 23 with a truncated album read back whole, reads one at a time with a stale read losing, a failed read, a UTF-8 character cut at the MTU, Podcasts (no album) and YouTube (fast-forward), nothing loaded, commands only when listed, 16-step volume, reset, precedence, iTunes URL and JSON.
  - `AssistantPauseTest` (10): pause/resume, not playing, phase gaps, repeated starts, a command during and after the hold, a restart on the phone, a stale playing state, a long hold, disconnect.

## Assistant audio focus (G's request, Nov 22)

The question: while the phone streams to the Echo, does Android 11 duck the A2DP sink when the assistant holds `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`? **No.** Checked against this ROM's `Bluetooth.apk` (pulled from `/system/app/Bluetooth`, dexdump + aapt2) and the android11-release framework source:

- **The Bluetooth app** (`A2dpSinkStreamHandler`):
  - It holds `AUDIOFOCUS_GAIN` (USAGE_MEDIA) while the phone streams. `a2dp_sink_automatically_request_audio_focus` is `true` on this ROM, with no overlays.
  - It handles its own focus changes:
    - `LOSS_TRANSIENT_CAN_DUCK`: gain to `a2dp_sink_duck_percent` = 25 %.
    - `LOSS_TRANSIENT`: gain 0, then an AVRCP pause after 400 ms (`DELAYED_PAUSE`).
    - `GAIN`: gain back to 1.0, and AVRCP play if it paused.
    - `LOSS`: abandons focus and pauses.
  - **Non-duckable transient losses are therefore already covered.**
- **The framework** (`FocusRequester.frameworkHandleFocusLoss`):
  - On a MAY_DUCK request, a loser that targets SDK > 25 and doesn't set `PAUSES_ON_DUCKABLE_LOSS` is ducked by `PlaybackActivityMonitor.duckPlayers`, and is **not told**. That's the Bluetooth app: targetSdk 30, no flag.
  - `duckPlayers` only ducks players registered with AudioService. The sink plays through a native `android::AudioTrack` (`btif_avrcp_audio_track.cc`), and in Android 11 `libaudioclient` native tracks don't register (no PlayerBase). So nothing is ducked, and the Bluetooth app's own 25 % duck never runs.
- **What's missing, and what I added** (`audio/AssistantPause.kt`, wired in `BluetoothMediaSource.onAssistant`):
  - The assistant's turn pauses the phone over AVRCP, and it resumes 1.2 s after the turn ends.
  - The trigger is the assistant's island activity (`ActivityKind.ASSISTANT`), observed by `PhoneController`. It spans listening, working and speaking, the same span as G's focus request.
  - It resumes only what it paused. It doesn't resume:
    - after a Now Playing or assistant media command during the hold;
    - if the user restarted playback on the phone;
    - after a hold longer than 10 min.
  - **Why a pause and not a duck:**
    - The sink's gain lives in the Bluetooth process, and no API reaches it.
    - Lowering STREAM_MUSIC would also lower the assistant's voice (USAGE_ASSISTANT plays on STREAM_MUSIC) and move the iPhone's slider (AVRCP absolute volume).
    - Design demo B says "I paused music here" anyway.
  - On the Echo (no phone paired): the island hook fires for an assistant demo turn (`VesperPhone: assistant on: phone playing=false`, then `off`). Pausing a real stream still needs an iPhone (test script).
  - Not covered: other apps' duckable focus requests (e.g. a notification sound) still don't duck A2DP. That's the same framework gap.
  - If G would rather signal turns explicitly than through the island, a small core flow (e.g. `assistantActive: StateFlow<Boolean>`) would replace the island observation. Nothing else would change.

## Left

- **Real-iPhone verification** of everything over the air: ANCS, AMS, the speaker-first pairing with the Echo's outbound LE connect (CTKD), and the assistant pause. That needs the user (script below).
- Cover art: this ROM has AVRCP cover art **off** (`Cover Artwork Enabled: False` in dumpsys; it's a Bluetooth-app overlay). Bluetooth tracks have no artwork, and D shows its fallback art. Turning it on needs a ROM overlay or a /system change; ask the user.
- The A2DP sink codec (SBC/AAC) can't be read by apps on Android 11, so there's no codec chip.
- If iOS turns out not to reconnect LE on its own after the first pairing (see "Open questions"), the fallback is to keep nRF Connect connected in the background, or move to a ROM with API 31+ advertising.

## Measured on the Echo (debug build, Nov 22, 2026)

| State | App CPU | Bluetooth process CPU | Notes |
|---|---|---|---|
| Idle on StandBy, 30 s (receivers + AVRCP browser bound, no advertising) | **0.03 %** of a core (1 tick) | 0.00 % | no timers, no polling |
| Pairing screen open, advertising BALANCED, 30 s | **0.07 %** (2 ticks) | 0.00 % | advertising runs in the controller |
| Whole app PSS after the runs (all features on this branch) | 38.8 MB total PSS | | phone adds ~10 small objects, one binder |

## Screenshots (`docs/status/shots/phone/`)

- `pairing-paired.png`: pairing screen, demo state with the phone connected.
- `pairing-code.png`: a pairing request with the passkey (system dialog confirms).
- `pairing-privileged.png`: the same with Pair / Cancel on our screen (after the priv-app install).
- `pairing-real-prompt.png`, `pairing-real.png`: the real screen on the Echo, with the system "make visible for 300 seconds" prompt (I tapped Deny so the Echo didn't stay discoverable), and after it.
- `call-island.png`: a demo incoming call. The real `AncsSession` and `AncsPresenter` run against a FakeIPhone, rendered by agent B's island: "Answer on iPhone", the caller, and Decline only.
  - Taken on a throwaway local merge of B's branch that was **not committed**; this branch has only `:feature:phone`.
  - The demo **banners** (Messages, missed call) ran the same way but don't show in screenshots: tonight the Echo is in Do Not Disturb (quiet hours), and B correctly hides non-critical banners in DND. Re-take them with `--es phone_demo banner` / `missed` once DND is off.
- `run1.sh`–`run3.sh`: the exact device steps used for these.
- `ams-nowplaying.png`, `ams-podcast.png`: the AMS demo. A `FakeAmsPhone` at MTU 23 feeds the real `AmsSession` and `AmsMediaSource`, and D's Now Playing renders the hub entry unchanged ("Playing from Spotify on Maya’s iPhone"). The song's art comes from the real iTunes lookup. Steps: `run-ams.sh`.
- `pairing-real.png` (re-taken Nov 22): the real pairing screen with the speaker-first instructions (iPhone Settings › Bluetooth, nRF Connect only as the fallback). Steps: `run-pairing.sh`, which denies the system "make visible" prompt.
- No design artboard exists for the pairing screen (NOTES §2.2 lists phone pairing under "to design"). It follows the expanded banner's spec and NOTES §3.1/§3.2.

## Open questions

1. **Does iOS reconnect LE by itself** after a pairing made through nRF Connect, once the app is closed? Many ANCS accessories rely on it, but I couldn't check it without the phone. Test steps B6–B8.
2. iOS 13+ asks to "Share System Notifications" per accessory. It must be on, or ANCS is empty.
3. When the iPhone pairs over Classic (speaker) first, does cross-transport key derivation give us the LE keys? If yes, the Echo's outbound LE connect brings up ANCS and AMS with no extra app (test A5). If no, nRF Connect is the fallback (part C).

## Needs from the user

- **Pair your iPhone and run the test script below.** That's the only way to verify ANCS and AVRCP over the air.
- Optional: whether to turn on AVRCP cover art (a ROM overlay or /system change).

## Pair your iPhone: test script

Before you start: the Echo runs a build with `:feature:phone`, and the iPhone has Bluetooth on. No extra app is needed unless part C says so. Logs: `./scripts/echo-adb.sh logcat -d -s VesperPhone:*`. Quiet hours: do the audio steps only when sound is OK.

**A. Pair from iPhone Settings (speaker first, then notifications and Now Playing)**
1. Open the pairing screen on the Echo: `./platform-tools/adb -s "$(cat .echo-serial)" shell am start -n app.vesper/.MainActivity --es screen phone-pairing`, or from Control Center. Tap **Allow** on "make your tablet visible… 300 seconds".
2. On the iPhone, go to Settings › Bluetooth and tap **Echo Show** under Other Devices. The Echo's screen shows the 6-digit code. Check it matches the iPhone, then tap **Pair** on the iPhone and on the Echo's dialog.
3. Play music on the iPhone, open the audio output picker (Control Center › Now Playing › the AirPlay icon), and pick **Echo Show**.
4. Expected (audio):
   - The island briefly shows the phone as connected.
   - Now Playing shows the title, artist and album, "from <app> on <your iPhone>", and album art from the lookup. The route chips read Bluetooth · <iPhone> · <app>.
   - Play/pause and next/previous on the Echo control the iPhone. The volume slider changes the Echo, and the iPhone's slider follows. The log shows `Bluetooth media session connected`.
5. **Notifications without an app (cross-transport keys):** within ~30 s of step 3, the log shows `probing <address> for ANCS/AMS`, then either:
   - `ready (ancs=true ams=true mtu 185)`: iOS may ask **Allow "Echo Show" to receive your iPhone notifications?** Tap **Allow**. If it doesn't ask, turn on Settings › Bluetooth › Echo Show (i) › **Share System Notifications**. Go on to step 6 and skip part C.
   - `GATT client disconnected, status 133` or `giving up on encryption`: the Classic bond has no LE keys. Do part C, then come back to step 6.
6. Send yourself a message from another phone. Expected: a glass banner on the Echo, "Messages · <iPhone> · now", with the sender and the text.
7. Tap the banner and tap **Dismiss**. The notification disappears from the iPhone's Notification Center. Clearing one on the iPhone takes it off the Echo.
8. Call the iPhone from another phone.
   - Expected: the island expands with the caller, "Answer on iPhone" and a red **Decline**. Answer on the iPhone and the island goes away. Call again and tap Decline: the call is declined.
   - Let a call ring out: a "Missed call" banner with **Call Back**.
9. **Assistant pause:** with music playing to the Echo, start the assistant (tap-to-talk). The music pauses while it listens and answers, and resumes about a second after. The log shows `assistant on: phone playing=true, paused it`.
10. Tap **Disconnect** in Now Playing's route popover. The iPhone goes back to its own speaker, and the Echo stays in the iPhone's "My Devices".

**B. Now Playing for audio that isn't on the Echo (AMS)**
1. With the iPhone connected (A5 or C), play something on the iPhone's own speaker or AirPods: Spotify, Music, a podcast, a YouTube video.
2. Expected: Now Playing on the Echo shows it as "Playing from <app> on <iPhone>", with route chips Remote · <iPhone> · <app> and album art for songs. The log shows `AMS: now playing from <app>`.
3. Play/pause, next and previous on the Echo control the iPhone. Buttons the app doesn't offer (e.g. skip on a live stream) are dimmed. The Echo's volume slider moves the **iPhone's** volume.
4. Start AirPlay or Bluetooth audio to the Echo from something else. That wins, and the iPhone entry hides until it stops.
5. Turn off "Look up album art" on the settings page. New tracks show D's fallback art, and nothing is sent to Apple.

**C. Fallback: pair LE from a Bluetooth app (only if A5 failed)**
1. Install **nRF Connect for Mobile** (Nordic Semiconductor, free) from the App Store. Keep the Echo's pairing screen open.
2. Open nRF Connect › Scanner and find **Echo Show**. Its service list includes `E5B0A1C2-3D4E-4F60-8A9B-0C1D2E3F4A5B`. Tap **Connect**.
3. In that service, tap the read arrow (↓) on characteristic `E5B0A1C3-…`. iOS shows **Bluetooth Pairing Request**: tap **Pair**, and tap Pair on the Echo too. After part A, iOS may skip this.
4. iOS asks **Allow "Echo Show" to receive your iPhone notifications?** Tap **Allow**. If it doesn't ask, turn on Settings › Bluetooth › Echo Show (i) › **Share System Notifications**. AMS (part B) needs only the pairing, not this switch.
5. Within a few seconds the log shows `ready (ancs=true ams=true mtu 185)`, the island shows the phone, and the pairing card says "Connected · notifications".
6. Disconnect in nRF Connect and close the app. Lock the iPhone, and repeat A6 to A8.
   - If nothing appears, check the log for `GATT client disconnected`. That means iOS dropped LE when the app closed (open question 1). Re-open nRF Connect and connect once more; if it then works only while the app is connected, report it.

**D. Reconnect**
1. Power-cycle the Echo (or reinstall the app) and keep the iPhone unlocked nearby. Within about 30 s the log shows `ready` again, with no re-banner of old notifications (they come in as PreExisting).
2. Out of range: turn the iPhone's Bluetooth off in Settings (not Control Center), wait 10 s, then turn it on. It reconnects the same way.

If something fails, send `./scripts/echo-adb.sh logcat -d -s VesperPhone:* BtGatt.GattService:* bt_btm:* bt_smp:*` and `./scripts/echo-adb.sh shell dumpsys bluetooth_manager`.

**Forget:** Settings page › iPhone › Forget iPhone, or the pairing card's "Forget This iPhone". Also remove "Echo Show" on the iPhone (Settings › Bluetooth › (i) › Forget This Device).

## Device setup

- No `pm grant` or `appops` needed. `BLUETOOTH` and `BLUETOOTH_ADMIN` are install-time permissions on API 30.
- **Bluetooth name changed:** the app sets the adapter name to `DeviceName` ("Echo Show"; it was "Echo Show 5").
- Bonded devices: none were added or removed. Tests never paired anything. The discoverable prompt was denied each time.
- Hidden APIs on this ROM (reflection check, debug log): `BluetoothA2dpSink.disconnect` ✔, `BluetoothDevice.removeBond` ✔, `BluetoothAdapter.setScanMode` ✘ (blocked), so discoverability goes through the system prompt.
