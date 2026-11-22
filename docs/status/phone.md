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

**What that means.** The Echo can be a connectable LE peripheral, so ANCS can work: the iPhone connects to the Echo, and the Echo is the GATT client on that link. The Echo **can't put the ANCS solicitation in its advertisement**, though, and that solicitation is what makes an accessory appear in the iPhone's Settings › Bluetooth by itself. So the first LE pairing starts from a free BLE app on the iPhone (nRF Connect or LightBlue): you connect to the Echo and read one encrypted characteristic, and iOS shows its pairing prompt. After that, the Echo advertises again whenever the link drops, and the bonded iPhone should reconnect by itself (this needs checking with the real phone, see the test script).

Other options I looked at for when solicitation is missing:
- (a) Advertise as a HID device so iOS lists it in Settings. Rejected: iOS treats it as a keyboard and hides the on-screen keyboard.
- (b) The Echo connects to the iPhone as central. Done opportunistically: when the paired iPhone's A2DP or LE ACL connects, the Echo tries ANCS from its side.
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
- **ANCS GATT client** (`ble/AncsGattClient`):
  - `connectGatt(TRANSPORT_LE)` on the link the iPhone opened. Then discovery, MTU 185, Data Source CCCD, then Notification Source CCCD.
  - If iOS refuses with insufficient authentication or encryption, it calls `createBond`, retries after BONDED (3 tries), and gives up.
  - Centrals without ANCS are dropped and not probed again.
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
- **Debug demo** (debug builds only; sample data; "Maya's iPhone" is made up): `--es screen phone-pairing --es phone_demo banner|mail|missed|call|media|connected|pairing|pairing-privileged|paired`. It runs the real `AncsSession` and `AncsPresenter` against a `FakeIPhone` exchanging real ANCS bytes, fragmented at MTU 23.
- **Tests:** 58 JVM tests, all passing (`./gradlew :feature:phone:testDebugUnitTest`):
  - `AncsCodecTest` (11): NS parsing, command bytes, reassembly at MTU 23/27/64/185 and byte by byte, split UTF-8, mismatched uid.
  - `AncsSessionTest` (12): app-name caching, an ack after the response, PreExisting, a pre-existing call, Modified, Removed while queued, errors, timeouts, actions, truncation, reset.
  - `AncsPresenterTest` (19): banner fields, catalog fallback, dates, silent, hidden apps, Echo dismiss → Clear and never Delete, timeouts and other ids ignored, calls with Decline only, missed calls, clearAll.
  - `AvrcpMappingTest` (6).
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
  - On the Echo (no phone paired): the island hook fires for an assistant demo turn (`EchoPhone: assistant on: phone playing=false`, then `off`). Pausing a real stream still needs an iPhone (test script).
  - Not covered: other apps' duckable focus requests (e.g. a notification sound) still don't duck A2DP. That's the same framework gap.
  - If G would rather signal turns explicitly than through the island, a small core flow (e.g. `assistantActive: StateFlow<Boolean>`) would replace the island observation. Nothing else would change.

## Left

- **Real-iPhone verification** of everything over the air. That needs the user (script below).
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
- No design artboard exists for the pairing screen (NOTES §2.2 lists phone pairing under "to design"). It follows the expanded banner's spec and NOTES §3.1/§3.2.

## Open questions

1. **Does iOS reconnect LE by itself** after a pairing made through nRF Connect, once the app is closed? Many ANCS accessories rely on it, but I couldn't check it without the phone. Test steps B6–B8.
2. iOS 13+ asks to "Share System Notifications" per accessory. It must be on, or ANCS is empty.
3. When the iPhone pairs over Classic (speaker) first, does cross-transport key derivation give us the LE keys, so the LE side pairs without a second prompt? Either way works; it changes only how many prompts the user sees.

## Needs from the user

- **Pair your iPhone and run the test script below.** That's the only way to verify ANCS and AVRCP over the air.
- Optional: whether to turn on AVRCP cover art (a ROM overlay or /system change).

## Pair your iPhone: test script

Before you start: the Echo runs a build with `:feature:phone`, and the iPhone has Bluetooth on. Install **nRF Connect for Mobile** (Nordic Semiconductor, free) from the App Store. Logs: `./scripts/echo-adb.sh logcat -d -s EchoPhone:*`. Quiet hours: do part A only when sound is OK.

**A. Speaker (Bluetooth audio → Now Playing)**
1. Open the pairing screen on the Echo: `./platform-tools/adb -s "$(cat .echo-serial)" shell am start -n com.echobedside/.MainActivity --es screen phone-pairing`, or from Control Center once B links it. Tap **Allow** on "make your tablet visible… 300 seconds".
2. On the iPhone, go to Settings › Bluetooth and tap **Echo Show** under Other Devices. The Echo's screen shows the 6-digit code. Check it matches the iPhone, then tap **Pair** on the iPhone and on the Echo's dialog.
3. Play music on the iPhone, open the audio output picker (Control Center › Now Playing › the AirPlay icon), and pick **Echo Show**.
4. Expected:
   - The island briefly shows the phone as connected.
   - Now Playing shows the title, artist and album with "from <your iPhone>". The route chips read Bluetooth · <iPhone>.
   - Play/pause and next/previous on the Echo control the iPhone. The volume slider changes the Echo, and the iPhone's slider follows.
   - The log shows `Bluetooth media session connected`.
5. Tap **Disconnect** in Now Playing's route popover. The iPhone goes back to its own speaker, and the Echo stays in the iPhone's "My Devices".

**B. Notifications (ANCS)**
1. Keep the pairing screen open on the Echo.
2. Open nRF Connect › Scanner and find **Echo Show**. Its service list includes `E5B0A1C2-3D4E-4F60-8A9B-0C1D2E3F4A5B`. Tap **Connect**.
3. In that service, tap the read arrow (↓) on characteristic `E5B0A1C3-…`. iOS shows **Bluetooth Pairing Request**: tap **Pair**, and tap Pair on the Echo too. If you already did part A, iOS may skip this.
4. iOS asks **Allow "Echo Show" to receive your iPhone notifications?** Tap **Allow**. If it doesn't ask: Settings › Bluetooth › Echo Show (i) › **Share System Notifications** on.
5. Within a few seconds the log shows `ANCS ready (mtu 185)`, the island shows the phone, and the pairing card says "Connected · notifications".
6. Disconnect in nRF Connect and close the app. Lock the iPhone.
7. Send yourself a message from another phone. Expected: a glass banner on the Echo, "Messages · <iPhone> · now", with the sender and the text.
   - If nothing appears, check the log for `GATT client disconnected`. That means iOS dropped LE when the app closed (open question 1). Re-open nRF Connect and connect once more; if it then works only while the app is connected, report it.
8. Tap the banner and tap **Dismiss**. The notification disappears from the iPhone's Notification Center. Clearing one on the iPhone takes it off the Echo.
9. Call the iPhone from another phone.
   - Expected: the island expands with the caller, "Answer on iPhone" and a red **Decline**. Answer on the iPhone and the island goes away. Call again and tap Decline: the call is declined.
   - Let a call ring out: a "Missed call" banner with **Call Back**.
10. Reconnect: power-cycle the Echo (or reinstall the app) and keep the iPhone unlocked nearby. Within about 30 s the log shows `ANCS ready` again, with no re-banner of old notifications (they come in as PreExisting).
11. Out of range: turn the iPhone's Bluetooth off in Settings (not Control Center), wait 10 s, then turn it on. It reconnects the same way.

If something fails, send `./scripts/echo-adb.sh logcat -d -s EchoPhone:* BtGatt.GattService:* bt_btm:* bt_smp:*` and `./scripts/echo-adb.sh shell dumpsys bluetooth_manager`.

**Forget:** Settings page › iPhone › Forget iPhone, or the pairing card's "Forget This iPhone". Also remove "Echo Show" on the iPhone (Settings › Bluetooth › (i) › Forget This Device).

## Device setup

- No `pm grant` or `appops` needed. `BLUETOOTH` and `BLUETOOTH_ADMIN` are install-time permissions on API 30.
- **Bluetooth name changed:** the app sets the adapter name to `DeviceName` ("Echo Show"; it was "Echo Show 5").
- Bonded devices: none were added or removed. Tests never paired anything. The discoverable prompt was denied each time.
- Hidden APIs on this ROM (reflection check, debug log): `BluetoothA2dpSink.disconnect` ✔, `BluetoothDevice.removeBond` ✔, `BluetoothAdapter.setScanMode` ✘ (blocked), so discoverability goes through the system prompt.
