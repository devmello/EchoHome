# EchoHome software updates (plan)

Status: **plan** (Nov 23, 2026). **A first, smaller version is built (Nov 24): see `docs/status/update.md`.** It differs from this plan on purpose: it reads GitHub's `releases/latest` API (no `channels` manifest), keeps the debug signing key (no release key or migration yet, section 3), has no beta channel, rollout buckets, APK upload or asset updater. Sections 3, 6.4 (init guard), 7 (migrations) and 8 are still open.

Today an update means plugging in a Mac: `./scripts/echo-deploy.sh` builds the release APK and `adb install -r`s it. This plan lets the Echo update itself from GitHub Releases without a Mac, and never leaves it without a working HOME.

Read first: `NOTES.md` §1 (device), §7 (open questions, backups), `docs/architecture.md`, `scripts/echo-system-install.sh`, `device/privapp-permissions-app.vesper.xml`.

## 1. Goals and non-goals

Goals:
- The Echo checks for a new version at night, downloads it over Wi-Fi, verifies it, installs it when idle, and says "Updated to 0.4".
- A bad update rolls back by itself.
- The settings page shows the status and can check, install or roll back by hand.
- No telemetry beyond the update check.

Non-goals:
- Updating the ROM (LineageOS 18.1 build `20261101-UNOFFICIAL-checkers`). See 2.5.
- A Play Store or F-Droid listing. This is a sideloaded, one-device-class project.
- Updating anything under `/system` from the app (it can't, see 2.4).

## 2. What gets updated

| Piece | Where it lives | Updated by | Needs `/system` write |
|---|---|---|---|
| App APK (Kotlin code, resources, fonts, face models in `assets/models/`) | `/data/app/...` as an update of the system app | The updater (PackageInstaller) | No |
| Native libs: `libvesper_airplay.so` (Rust, `android/feature/airplay/rust/build.sh`), sherpa-onnx JNI + onnxruntime (`fetch-voice.sh`, pinned `SHERPA=v2.0.1`) | Inside the APK (`jniLibs/armeabi-v7a`) | Rides with the APK | No |
| Voice models: wake word (kws), STT, TTS (Piper amy) | `/sdcard/Android/data/app.vesper/files/voice/{kws,stt,tts}`, pushed today by `push-voice-models.sh` | Asset updater (section 8) | No |
| Face models (BlazeFace, EdgeFace XXS/XS `.tflite`) | Inside the APK today | Rides with the APK. Move to downloadable assets only if the APK gets too big (open question 3). | No |
| The priv-app copy `/system/priv-app/Vesper/Vesper.apk` | `/system` | `echo-system-install.sh` by hand | Yes |
| Privapp allow-list `/system/etc/permissions/privapp-permissions-app.vesper.xml` | `/system` | `echo-system-install.sh` by hand | Yes |
| Mic button key layout `/system/usr/keylayout/gating.kl` | `/system` | `echo-system-install.sh` by hand | Yes |
| Init file `device/echohome.rc` (the optional boot guard, see 6.4). It isn't in the repo yet. | `/system/etc/init/` | By hand, once | Yes |
| ROM overlays `device/overlays/*` (volume panel off, Bluetooth cover art on) | `/system` or `/product` | `echo-overlays.sh` by hand | Yes |
| The ROM | `system`, `boot`, ... partitions | Never by the app | Flash |

### 2.1 The APK

The APK is a **privileged system app update**. The first copy was pushed to `/system/priv-app/Vesper` (version 0.1.0, versionCode 1). Every `adb install -r` since then is an `UPDATED_SYSTEM_APP` update in `/data/app`. The update keeps the privileged flag and the permissions granted by the allow-list. The updater uses the same mechanism, so a self-update behaves like `build-install.sh` today.

Consequence: the `/system` copy is the **permanent fallback**. It is old (0.1.0) and gets older. Rolling back to it must still boot and show a usable HOME, so each release must keep reading data written by newer versions (section 7.3), and the `/system` copy is refreshed now and then (section 2.4).

### 2.2 Native libs

They are inside the APK, so they update with it. Two build rules:
- The release script refuses to build if `libsherpa-onnx-jni.so`, `libonnxruntime.so` or `libvesper_airplay.so` is missing (`build-install.sh` already fetches the voice libs; the release script adds the check and the Rust lib). An APK without voice must never replace a working one.
- Only `armeabi-v7a` (the `abiFilters` line). The manifest says so (`abi`) and the updater refuses an APK that doesn't ship it.

### 2.3 Downloadable assets

Voice models first. See section 8.

### 2.4 `/system` pieces: what can't be updated by the app

The app is not root and has no `su`. `/system` is read-only until `adb root` + `adb remount` (needs the "Rooted debugging" toggle, which is a security setting the user turns on). So the app can't touch the priv-app copy, the allow-list, `gating.kl`, the init file or overlays, and shouldn't be able to.

What this means for releases:
- **Every release declares its `/system` requirements** in the manifest (`systemRevision`, an integer; see section 4.1). The app compares it with the revision of what is installed on the device (a marker file written by `echo-system-install.sh`, or the existence of the allow-list entries; open question 5).
- If a release needs a newer `/system` piece (a new privileged permission, a new key layout), the updater **does not install it** and shows "Update 0.5 needs a one-time step from your Mac" with the exact command. It installs the next version that doesn't need it.
- A new privileged permission is the one case that matters. Android checks the allow-list when a privileged app requests a signature|privileged permission. The check differs for the two copies:
  - The `/system` copy requesting an unlisted privileged permission **fails the boot** (boot loop). `echo-system-install.sh` already aborts on that.
  - An update in `/data` requesting an unlisted permission just doesn't get it (the comment in `privapp-permissions-app.vesper.xml`).
  So an updater feature that needs a new permission is blocked on a `/system` step (section 5.1), and that step must be done before shipping the update that uses it.
- Refresh the `/system` APK copy only when needed (a new privileged permission in the manifest, or the copy is so old that a fallback to it would be bad). `echo-system-install.sh` does it, reboots, and needs a complete partition backup first (NOTES §7).

### 2.5 The ROM is out of scope

- It is an unofficial LineageOS 18.1 build for a device with an exploit-based bootloader unlock (amonet). An OTA means flashing `boot`/`system`/`vendor` through recovery or fastboot. A bad flash is the one way to brick the Echo for real.
- The recovery path needs the Mac and a cable. There's no A/B slot to fall back to on this device.
- The ROM has no updater service we could hook into, and a self-flash would need privileges the app must never have.
- Updates for the ROM are rare and unofficial. The user decides, backs up (`./scripts/echo-backup.sh`) and flashes by hand. Any ROM change needs the user's explicit approval (CLAUDE.md).

The app only reads the ROM version (`ro.build.version.incremental`) and puts it in the update check as "supported ROM" so a release can say "needs ROM 20261101 or newer" (`minRom`), and the updater can then refuse it.

## 3. Signing

### 3.1 The problem

`android/app/build.gradle.kts` signs release with the debug key (`signingConfig = signingConfigs.getByName("debug")`). The debug key is `~/.android/debug.keystore`, generated per machine, with a public default password. Reasons it has to go before any public update channel:
- Anyone with the default password can sign an APK that Android accepts as an update (the installed copy is debug-signed). With a public channel and a fork, the "same certificate" check protects nothing.
- Two machines have two different debug keys. An APK built on another Mac can't update the one on the Echo.
- Contributors who build the repo will sign with their own debug key, which can't update a user's Echo.

### 3.2 The release key

- Generate one RSA 4096 (or EC P-256, which is smaller and fast to verify on a 1.3 GHz A53) key, valid 25+ years, on the Mac: `keytool -genkeypair -v -keystore echohome-release.p12 -storetype PKCS12 -alias echohome -keyalg RSA -keysize 4096 -validity 10000`.
- Store it **outside git**. Suggested: `~/.echohome-signing/echohome-release.p12`, mode 600, plus an encrypted backup in the user's password manager. Losing the key means no more in-place updates ever (every install would need an uninstall and a data loss). Backup is not optional.
- Gradle reads it from `android/keystore.properties` (git-ignored, with a `keystore.properties.example` next to it, like `secrets.properties.example`): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Passwords come from the environment when present.
- The release build fails (no silent fallback to debug) when the file is missing. Debug builds keep the debug key unless `keystore.properties` exists (see 3.4).
- The signing certificate's SHA-256 is written in `docs/` and in the update manifest (`signerSha256`). The updater checks it twice (section 4.4).
- Build with APK signature scheme v2 and v3 (AGP does this at minSdk 30). Never v1 only.
- Keep the key on one Mac. GitHub Actions can't build this repo easily anyway (Rust NDK target, voice libs, JDK 17), and putting the key in CI secrets makes the repo owner's account the weak point. Releases are built and published by `scripts/echo-release.sh` on the Mac (section 4.5).

### 3.3 Existing installs (one-time migration via adb)

The Echo now has the debug-signed app as an update on top of a debug-signed `/system` copy. Android refuses an update signed with a different key ("signatures do not match previously installed version"). Two ways:

**Option A, key rotation (preferred, try first).** APK Signature Scheme v3 lets a new key sign an update when the APK carries a proof-of-rotation lineage that includes the old key. Android 9+ accepts it, including for system app updates (open question 6: verify on the Echo before relying on it).
1. `apksigner rotate --out echohome.lineage --old-signer --ks ~/.android/debug.keystore --new-signer --ks echohome-release.p12`
2. Sign the first migration release with `--lineage echohome.lineage` (Gradle: `signingConfig` with `enableV3Signing` and the lineage via a custom `apksigner` step in `echo-release.sh`).
3. `adb install -r` it. No data loss, no reboot.
4. Keep shipping releases with the lineage forever (it is small), so devices that skip a version still update. Rotation is one-way: once an installed copy has the new key, only the new key updates it.
5. Refresh the `/system` copy to the release-signed APK in the next planned `/system` step. The `/system` copy stays debug-signed until then; that's fine because the lineage covers it.

**Option B, uninstall and reinstall (fallback if A is rejected on this ROM).**
1. Back up the data first. With `adb root`: `adb pull /data/data/app.vesper` and `/sdcard/Android/data/app.vesper/files` (voice models, photos cache). Also export from the settings page if it has an export. Face templates and per-profile secrets (Anthropic key, iCal URLs, Immich) are in there.
2. `adb shell pm uninstall-system-updates app.vesper` (removes the debug-signed update; data stays on a system app in most builds, but don't rely on it: check with `adb shell ls /data/data/app.vesper` and keep the pull).
3. The `/system` copy is debug-signed too, so the release-signed APK still can't install over it. Run `echo-system-install.sh` to replace `/system/priv-app/Vesper/Vesper.apk` with the release-signed APK (needs a full backup, a remount, a reboot, and **the user's approval**).
4. Restore data if it was lost (`adb push`, then `chown` with `run-as`-less `adb shell chown` as root).
5. Re-grant nothing: permissions come from the allow-list again.

Either way, do it once, with the user at the Echo, after a full backup in `backups/`. Document the exact commands in `docs/status/` when it's done.

### 3.4 Developer flow after the change

`build-install.sh` installs debug builds as `app.vesper`, over whatever is on the Echo. Once the Echo has the release key, a debug-key build is refused (signature mismatch). The package name can't change for debug (the allow-list is per package). So:
- When `keystore.properties` exists, the `debug` build type is signed with the release key too (same certificate, `debuggable` still true). The dev Mac always has the key.
- Other contributors build with their own key and their own Echo; the update channel in their build is off by default (`updates.channel = off` when the build isn't signed with the project key; the app compares its own signer to `signerSha256` compiled in at build time).
- `echo-deploy.sh` becomes `echo-release.sh` for publishing; `echo-deploy.sh` keeps meaning "release build, install over adb".

## 4. Delivery

### 4.1 The update manifest

Hosted as release assets on GitHub (github.com/devmello/EchoHome). Two fixed URLs that don't change per release, so the app never calls the GitHub API (no token, no rate limit):

```
https://github.com/devmello/EchoHome/releases/download/channels/update-stable.json
https://github.com/devmello/EchoHome/releases/download/channels/update-beta.json
```

`channels` is a pinned release whose two assets are replaced (`gh release upload --clobber`) by `echo-release.sh`. The APKs are normal assets on the version's release (`v0.4.0`). Beta releases are marked prerelease. (`releases/latest` skips prereleases, which is why the channels release exists.)

```json
{
  "schema": 1,
  "channel": "stable",
  "generated": "2026-12-01T21:40:00Z",
  "releases": [
    {
      "versionName": "0.4.0",
      "versionCode": 4,
      "minVersionCode": 2,
      "abi": "armeabi-v7a",
      "minSdk": 30,
      "minRom": "20261101",
      "systemRevision": 1,
      "signerSha256": "AB:CD:...",
      "apk": {
        "url": "https://github.com/devmello/EchoHome/releases/download/v0.4.0/EchoHome-0.4.0.apk",
        "size": 31457280,
        "sha256": "9f2c..."
      },
      "rollout": 100,
      "notes": "Faster face check. Fixes the alarm snooze label.",
      "assetsVersion": 2
    }
  ],
  "assets": {
    "version": 2,
    "voice": { "files": [ { "path": "kws/tokens.txt", "url": "...", "size": 1234, "sha256": "..." } ] }
  }
}
```

Fields:
- `versionCode`: the integer Android compares. `versionName`: shown to the user. Today `versionCode = 1`, `versionName = "0.1.0"`. Rule: `versionCode = major*10000 + minor*100 + patch`, set in one place, bumped by the release script.
- `minVersionCode`: the oldest installed version that may jump straight to this one. Lower than that: the updater installs an intermediate release first (the list holds the last few releases, newest first, and the updater picks the **newest release it is allowed to install** whose chain is satisfied). This is how a data migration that needs to run in order is forced.
- `systemRevision`: what `/system` pieces this build needs (section 2.4). `minRom`: ROM build date.
- `signerSha256`: the certificate (colon-free hex, lower case in the real file).
- `rollout`: percent of devices that may install it now (section 6.6). `notes`: plain text, under 400 characters, English.
- Everything is verified by the updater from the APK itself too; the manifest is a hint, not the authority (section 4.4).

The `assets` block is the asset update list (section 8).

### 4.2 When to check

- **Daily at night:** `AlarmManager.setInexactRepeating` is not enough alone (alarms die with the process and after a boot), so: a boot receiver and the app start re-arm it. Window 02:00 to 04:00 local, with a random offset per install (so a release doesn't hit GitHub from every Echo at once; it is 1 request anyway). Skip if Wi-Fi is down; try again on the next window, not in a loop.
- **At app start** if the last check is more than 24 h old (covers a night with no power).
- **From the settings page** ("Check for updates"), always, ignoring the window.
- Never on cellular or metered networks (the Echo has Wi-Fi only; still check `ConnectivityManager.isActiveNetworkMetered()` for a phone hotspot).
- Conditional GET (`If-None-Match` with the ETag) so the usual answer is a tiny 304.

### 4.3 Download

- Into the app's external files dir, `files/updates/EchoHome-<versionName>.apk.part`, **not** `cache/` (the system may clear cache at low space, mid-download).
- With **resume**: an HTTP `Range` request from the `.part` size; check `Content-Range`; if the server answers 200 instead of 206, start over. Keep `.part` between attempts and nights. Verify the final file size equals `apk.size` before hashing.
- GitHub release downloads redirect to `objects.githubusercontent.com`. OkHttp (already a dependency, pinned 4.12) follows redirects; allow only `https` and only the two hosts `github.com` and `*.githubusercontent.com`. No other host is ever fetched from a manifest field (except the asset URLs in section 8, which use the same allow-list).
- One download at a time, low priority thread, no more than ~1 MB/s average (the Echo streams audio and runs the camera; a 30 MB download at full speed is fine for the network but not for a 1 GB RAM device doing GC. Stream to disk with an 8 KB buffer, never into memory).
- Free-space check before starting: free `/data` must be at least **3× the APK size** plus 100 MB (the `.part` file, the session copy PackageInstaller makes, and the kept previous APK). If not, say so in the settings page and don't start.
- Skip downloads while an AirPlay or Bluetooth audio stream is running, to protect the audio buffers (retry later). The Echo is mains powered, so battery doesn't matter.

### 4.4 Verification (before install, and again at install)

1. **Size** equals the manifest.
2. **SHA-256** of the file equals the manifest (`MessageDigest`, streamed). Mismatch: delete the file, retry once the next night, show "Download failed checksum" in settings.
3. **Parse the APK without installing:** `PackageManager.getPackageArchiveInfo(path, GET_SIGNING_CERTIFICATES)`:
   - `packageName == app.vesper`
   - `versionCode` (longVersionCode) equals the manifest and is **greater** than the installed one (no downgrade through the updater, except an explicit rollback, 6.3)
   - `applicationInfo.minSdkVersion`/`targetSdkVersion` sane
   - **Signing certificate:** the archive's `signingInfo` must be accepted against ours. Use `signingInfo.getApkContentsSigners()` and compare SHA-256 of the cert with the **installed** package's current signer (`getPackageInfo(own package, GET_SIGNING_CERTIFICATES)`), or with an ancestor in the lineage (`hasPastSigningCertificates`). Also compare with `signerSha256` in the manifest. All three must agree, or the update is rejected. This is what makes the HTTPS manifest not the root of trust: a hijacked release can't install an APK the real key didn't sign.
4. The check on `getPackageArchiveInfo` is also what PackageManager does at install, so a mismatch fails twice, safely.
5. The verified file moves to `files/updates/ready/` and gets a small `ready.json` (versions, hashes, verified time). Install only uses a file with a matching `ready.json`, and re-hashes it just before the session (the file could have been changed on disk in between; it's in app storage so only the app or root could).

**Not planned:** a second signature over the manifest. The APK signature is the root of trust, and the manifest can't make the device install anything the signing key didn't sign. A malicious manifest can only point to older signed releases (blocked by the "greater versionCode" rule) or waste bandwidth. A hijacked key is the real risk and is handled by keeping the key off the network (section 3.2).

### 4.5 Publishing a release (on the Mac)

`scripts/echo-release.sh <version>` (new):
1. Check the git tree is clean and on `main`; check libs are present (2.2).
2. Bump `versionCode`/`versionName`, build the signed release APK, run `apksigner verify --print-certs` and compare to the stored certificate hash.
3. Run the unit tests (including the migration tests, 7.3).
4. Compute size and SHA-256, write the manifest entry (keeping the previous 4 entries), `gh release create v0.4.0 EchoHome-0.4.0.apk --prerelease` for beta or normal for stable.
5. Upload the manifest to the `channels` release with `--clobber`. Beta channel gets the entry first; promote to stable by copying the same APK entry into `update-stable.json` (no rebuild; the stable file just references the same asset and hash).
6. Install on the dev Echo with the updater itself as the first test (section 9, phase 5).

Nothing is pushed or published by agents; the user runs this script. The script prints what it will upload and asks.

## 5. Install

### 5.1 Silent install as a privileged app

The app is in `/system/priv-app`, so it can hold privileged permissions. Install without a user prompt uses `PackageInstaller`:

```
val pi = context.packageManager.packageInstaller
val params = PackageInstaller.SessionParams(SessionParams.MODE_FULL_INSTALL).apply {
    setAppPackageName("app.vesper")
    setRequireUserAction(SessionParams.USER_ACTION_NOT_REQUIRED)   // API 31+, ignored on 30
}
val id = pi.createSession(params)
pi.openSession(id).use { s ->
    FileInputStream(apk).use { input -> s.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
    s.commit(statusReceiverIntentSender)
}
```

Permissions and gotchas:
- **`android.permission.INSTALL_PACKAGES`** is a signature|privileged permission. It must be in the manifest of the app (module `:feature:update`) **and** in `device/privapp-permissions-app.vesper.xml`. On API 30, `USER_ACTION_NOT_REQUIRED` isn't enough alone for a non-system app, but with `INSTALL_PACKAGES` held the session commits without a prompt.
- **`DELETE_PACKAGES`** (normal) for the rollback uninstall, and `REQUEST_INSTALL_PACKAGES` is not needed with `INSTALL_PACKAGES`.
- **The boot-loop rule.** The allow-list lists what the `/system` copy may request. `echo-system-install.sh` aborts if the APK (the new, updater-enabled one) requests a privileged permission missing from the XML; that is the check this relies on (lines "The /system APK must not request a privileged permission missing from the allow-list"). The order is therefore:
  1. Add `INSTALL_PACKAGES` to `privapp-permissions-app.vesper.xml` (the list may name permissions the app doesn't request yet; harmless).
  2. **With the user's approval** and after a full backup: run `echo-system-install.sh --no-build` (it pushes the XML and the APK, reboots). Only the XML change is needed, but the script is the tested path. A smaller script, `echo-system-allowlist.sh`, that only pushes the XML (remount, push, chmod, restorecon, reboot) is a possible later simplification.
  3. Verify on the Echo: `dumpsys package app.vesper | grep INSTALL_PACKAGES` shows granted only **after** an update requests it.
  4. Ship the first updater-enabled release.
  Skipping step 2 doesn't boot-loop (the update in `/data` just doesn't get the permission), but the updater would fail with `STATUS_FAILURE_INCOMPATIBLE`-style errors and the feature would be dead. The updater checks `checkSelfPermission(INSTALL_PACKAGES)` at start and shows "Needs a one-time setup from your Mac" if it's missing.
- **Self-replace:** committing a session for our own package makes PackageManager kill our process during the commit. The result broadcast may never reach the old process. So the status receiver is a manifest receiver, and a state file is written before `commit` (section 5.4).
- **dexopt cost:** PackageManager compiles after install. On the A53 with 1 GB RAM this may take tens of seconds and compete with HOME. Measure (phase 1). If it's bad, install with the filter `verify`/`speed-profile` via `SessionParams` hidden fields or accept the JIT.
- `INSTALL_REASON_*` and `setInstallScenario` aren't available on API 30; skip.

### 5.2 When to install

The updater has two steps with different rules: *download* (night window, section 4.2) and *install* (a separate, stricter gate). The install gate is evaluated by one function, `InstallGate.canInstallNow()`, and it asks the existing services (contracts in `:core:model`):

Install only when **all** are true:
- Nothing is ringing, and the **next alarm is more than 90 minutes away** (`services.alarms`). With a 06:30 alarm the usable window is 02:00 to 05:00. "Never update during an alarm window" is absolute for automatic installs.
- No timer is within 10 minutes of finishing.
- No call (`services.phone`), no AirPlay session, no Bluetooth or other music playing (`services.media`). A sleeping Echo with a paused session is fine.
- Idle: no touch for 30 minutes, and the screen is off or the room is empty (presence from `:feature:camera`, when enabled). The Night mode flag is not the test (it also runs in the evening).
- Local time is inside the install window (default 02:00 to 05:00, a setting).
- Free memory is above ~150 MB (`ActivityManager.MemoryInfo`) so dexopt doesn't thrash.
- The previous update (if any) was confirmed healthy (6.2), and **no update is installed within 24 h of another**.
- It's not already in a crash-loop state (Watchdog's counter, section 6.4).

If the gate says no, the updater waits and re-checks every 10 minutes (not a busy loop, and `Handler` only while the process is up; a boot receiver re-arms). An update stays "ready" for as long as needed. A newer release replaces it.

**Manual install** (from the settings page, or a tap on the island) skips the window and idle rules but not these: no alarm ringing, no call. The user is present, so it asks for a confirm on the Echo itself ("Install 0.4 now? The screen restarts for about 30 seconds."), unless the request came from a paired browser session, in which case the web page's confirm is enough.

### 5.3 Restart and how HOME comes back

1. The updater shows the island state `Updating to 0.4` (a `LiveActivity`) for about 2 s, writes `pending.json` and calls `commit`.
2. PackageManager replaces the package. The process dies. For a few seconds nothing draws. Android may show the **stock Launcher3** if it needs a HOME while ours is mid-replace (Launcher3 stays installed on purpose, NOTES §8 step 0).
3. After the replace, the system sends `ACTION_MY_PACKAGE_REPLACED` to the new version. A manifest receiver (`UpdateReceiver`) starts `MainActivity` (allowed: `START_ACTIVITIES_FROM_BACKGROUND` is in the allow-list). It also re-asserts HOME if needed (open question 7: does the preferred HOME set by `cmd package set-home-activity` survive an update? On a system-app update it should, because the package doesn't go away. If not, the receiver calls `PackageManager.addPreferredActivity`/`RoleManager` with the right permission, which has to be listed in the allow-list; or the init guard in 6.4 does it).
4. `VesperApp` starts, sees `pending.json` (state `INSTALLING`, `toVersion == BuildConfig.VERSION_CODE`), switches it to `UNCONFIRMED`, and starts the health check (section 6.2).
5. After the check passes, the island shows `Updated to 0.4` (about 5 s) and a banner goes into `NotificationFeed` (so it's in the recent list) with the release notes.

Expected black gap: about 10 to 30 seconds (open question 8: measure).

### 5.4 State kept across the process death

`files/updates/state.json` (written atomically: write temp, `fsync`, rename):

```
{ "state": "IDLE|DOWNLOADING|READY|INSTALLING|UNCONFIRMED|CONFIRMED|ROLLED_BACK|FAILED",
  "fromVersionCode": 3, "toVersionCode": 4, "since": 1764000000,
  "launches": 0, "lastError": null, "previousApk": "files/updates/previous/EchoHome-0.3.0.apk" }
```

The Watchdog and the updater both read it; only `UpdateGuard` writes it.

### 5.5 UX

- **Island:** `Updating to 0.4` (spinner, same style as "Checking face"), then `Updated to 0.4` with a check. Rollback: `Went back to 0.3` (amber). A failed download isn't shown on the island; it is only on the settings page, so a flaky network doesn't nag.
- **Notification banner** (via `NotificationFeed`): "EchoHome 0.4: Faster face check." Tap opens a small glass card with the notes.
- **Night mode:** while `nightActive`, no island or banner for "update available" (silent). The post-update "Updated to 0.4" waits until the screen is on and the user touches it or wakes it, so the dim room isn't lit at 03:00.
- No "update available" banner for the daily check. The settings page shows it; the island shows only the install and the result.

### 5.6 Manual path

Two ways, both on the settings page (port 8080, only for browsers already paired through `Pairing.kt`, so nobody on the LAN can push an APK):
- **Check for updates:** `POST /api/action/update-check` (the existing `/api/action/<name>` route in `SettingsApi.kt`). Shows version, channel, last check, status, release notes, "Download", "Install now", "Install tonight".
- **Upload an APK:** `POST /api/update/upload` (streamed multipart, written to `files/updates/upload.apk.part`, not held in memory; limit 200 MB). It goes through exactly the same verification (4.4). There's no path that installs an unverified APK. A different `versionCode` lower than the installed one is refused unless the page's "Allow downgrade" box is ticked **and** the signer matches (a rollback to an older signed build, needs `INSTALL_ALLOW_DOWNGRADE`, 6.3).
- The same path covers the offline Echo (no internet): download the APK on the Mac, open the settings page, upload.
- `adb install -r` still works and stays the developer path.

## 6. Safety: never brick

Principle: **the OS and bootloader are never touched by an update**, so the worst case is "EchoHome is broken", never "the Echo doesn't boot". Everything below is about getting a working HOME back without a Mac, and the Mac path (`echo-restore-launcher.sh`, `echo-system-undo.sh`, the partition backup) stays as the last resort.

### 6.1 Keep the previous version

Before committing the session, copy the **currently installed** APK (`/data/app/.../base.apk`, from `ApplicationInfo.sourceDir`) to `files/updates/previous/EchoHome-<old>.apk`. Keep exactly one previous APK, deleted after the new version has been confirmed healthy for 7 days (or on the next update). The `/system` copy is the second fallback (6.3).

### 6.2 Health check after an update

The new version is `UNCONFIRMED` until it proves itself. It is **healthy** when all hold within 5 minutes of the first launch:
1. `MainActivity` is created and `VesperApp.installFeatures` finished (no exception).
2. HOME resolves to us: `PackageManager.resolveActivity(HOME intent)` returns `app.vesper`.
3. The heartbeat: the Watchdog's main-thread ping/pong has answered for 60 consecutive seconds without a HANG entry, and no crash since the first launch.
4. The settings server answers on its own port from inside the process (a loopback request), the AirPlay service is bound (if enabled), the alarm scheduler re-armed the next alarm.
5. The process has been alive for at least 120 seconds.

Then: `CONFIRMED`, island message, previous APK kept 7 more days.

### 6.3 Automatic rollback

Triggers (any one, while `UNCONFIRMED` or within the first 30 minutes after confirm):
- 3 launches that never reach healthy (counted in `state.json` at the **very start** of `Application.onCreate`, before features load, so a crash while loading is counted).
- 2 crashes or hangs recorded by `Watchdog` (its existing counter is 4 in 10 minutes; for a fresh update the limit is tighter).
- The health check fails the 5 minute deadline.
- No heartbeat for 5 minutes.
- The user taps "Roll back" in settings.

Action (`UpdateGuard.rollback()`):
1. **Preferred, to the kept previous APK:** install it as a new session with downgrade allowed (`PackageInstaller.SessionParams` installFlags `INSTALL_ALLOW_DOWNGRADE` via reflection on `setRequestDowngrade(boolean)`; it needs INSTALL_PACKAGES, which we hold. Open question 9: verify on API 30).
2. **If that fails**, or the previous APK is missing, or the previous version also isn't healthy: remove the update so the **`/system` copy** takes over. In the app: `PackageInstaller.uninstall(VersionedPackage("app.vesper", ...), ...)` for an updated system app only deletes the update (needs `DELETE_PACKAGES`). From a shell: `adb shell pm uninstall-system-updates app.vesper` (the documented manual recovery; put it in this doc's recovery section).
3. Record `ROLLED_BACK`, remember the bad `versionCode` (`blockedVersions`), and **don't try it again**; a newer versionCode clears the block.
4. Island/banner: "Went back to 0.3. Update 0.4 had a problem." The reason (crash text) goes to `CrashLog` and the settings page's Diagnostics.

Downgrades and data: the older version must open the data the newer one wrote. That is the data rule in 7.3 (additive only for a release, migration backups). If a release can't meet it, the manifest sets `noRollbackBelow: <versionCode>` and the updater says up front "this update can't be undone automatically" in the settings page; the install gate then requires a manual click.

### 6.4 Watchdog and the out-of-process guard

The in-app `Watchdog` (`app/.../Watchdog.kt`) already restarts after a crash with an alarm (1.5 s, 2 min after 4 crashes in 10 min) and detects hangs. It gets one new input: while the update is `UNCONFIRMED`, it calls `UpdateGuard` with the crash instead of only restarting.

Its limit is that it **runs inside the broken app**. Cases it can't see: a native crash (SIGSEGV in `libvesper_airplay.so` or onnxruntime) before the Java handler is installed, a crash in static init, a dex that fails verification. The launch counter in 6.3 covers these because the counter is written before anything risky and a later start of the same broken version sees "3 launches, never healthy" and rolls back before loading features.

What if the app never starts at all (nothing runs to count)? Then nobody rolls back, and the Echo shows Launcher3 or a "keeps stopping" dialog. Two cheap answers:
- Android itself falls back to Launcher3 as HOME if our HOME activity can't resolve. The user can use the stock UI and the settings page is down. Recovery is `adb`/`pm uninstall-system-updates`.
- **Optional guard (needs the user's approval):** `device/echohome.rc` in `/system/etc/init/`: a tiny `oneshot` service that runs at `sys.boot_completed=1`, waits 3 minutes, and if `/data/data/app.vesper/files/updates/state.json` says `UNCONFIRMED` for more than 10 minutes with no heartbeat file newer than 2 minutes, runs `pm uninstall-system-updates app.vesper` (init services run as root, so it can). Needs SELinux domain checks (the ROM is userdebug and may be permissive; enforce state to be checked first, open question 10), a shell script in `/system/bin/`, and it is a `/system` change, so it's a one-time user-approved step. It is the only thing in this plan that works when the app can't even start, and it is why the bar for building it is "after the first real update lands" (phase 6).

### 6.5 Never update during an alarm window

Repeated from 5.2 because it matters most: no automatic install when an alarm rings or the next alarm is under 90 minutes away; no install from 20 minutes before a "leave by" alarm until it is dismissed. The alarm scheduler itself must come back after an update: `AlarmManager` alarms are cleared when the package is replaced? Treat as **yes they can be lost**: `UpdateReceiver` (and the existing boot path) re-arms the next alarm on `MY_PACKAGE_REPLACED`. Add a test device step: set an alarm, update, check `dumpsys alarm | grep app.vesper`.

### 6.6 Staged rollout

- `rollout` in the manifest is 0 to 100. A device installs when `bucket < rollout`, where `bucket = SHA-256(installId + versionCode) mod 100`. `installId` is a random UUID created on the device, **never sent anywhere**. So rollout needs no server and no telemetry.
- Beta channel first (the dev's Echo), then stable at 20, 50, 100 over a few days, raising `rollout` by re-uploading the manifest.
- A bad release is stopped by setting `rollout: 0` or deleting it from the manifest (devices that haven't downloaded it never will; ones that did and have `READY` re-check the manifest before install and drop it).
- With one Echo today this is mostly future-proofing; it costs little.

### 6.7 Failure table

| Failure | What happens |
|---|---|
| No Wi-Fi at night | Skip, try next window. |
| Download interrupted | `.part` kept, resumes next try. |
| Checksum or certificate mismatch | File deleted, error on settings page, retry next night (max 3, then wait for a new release). |
| Disk too full | No download, message on settings page. |
| Session commit fails | `FAILED` with PackageInstaller's status text in `CrashLog`; old version keeps running. |
| New version crashes at start | Counter hits 3, auto rollback, version blocked. |
| New version starts but hangs | Watchdog hang restart counts, rollback after 2. |
| Previous APK also broken | Remove update, `/system` copy (0.1.0) takes over. |
| Nothing starts | Launcher3 shows. `pm uninstall-system-updates app.vesper`, or the optional init guard does it. |
| `/system` change gone wrong | Not an update failure: restore from `backups/` (NOTES §7). That is why `/system` steps are manual, one at a time, with a backup. |

## 7. Data and settings migrations

### 7.1 What data exists

- `PrefsSettingsStore` (SharedPreferences) and the SecretStore (keys, URLs).
- Alarms and timers stores, profiles and face templates (in the app's data), crash log, photo cache, the voice models.
- Update state (new).

There is no schema version anywhere in the app today (a search for `schemaVersion` or `migrat` finds nothing). That has to exist **before the first update ships**, or the first migration has nothing to compare against.

### 7.2 Versioned schemas

- One integer, `data.schemaVersion`, stored in its own small file `files/schema.json` (not in prefs, so a prefs migration can't lose it).
- A `Migrations` list in `:core:data`: `Migration(from, to, run(ctx))`, each small and idempotent. `Migrations.run(ctx)` is called first thing in `VesperApp.onCreate`, before features install, and writes the new version only after all steps succeeded.
- Version 1 is "whatever 0.1.0 wrote today" (no file means version 1). The first release with the updater writes `schema.json = 1` without changing data.
- Each feature that owns data (alarms, profiles, people, photos) owns its own migration steps in its module and registers them (`host.registerMigration(...)`, a new small contract; request it in `docs/contract-requests.md`).

### 7.3 Rules that make rollback safe

1. **Additive only inside a release train:** new keys and new fields; never rename or reuse a key; readers ignore unknown keys. Then the previous version still works on the new data.
2. A destructive change (rename, delete, format change) is a two-step: release N writes **both** old and new forms, release N+1 drops the old one. N+1's manifest sets `minVersionCode = N`, and `noRollbackBelow` (6.3) when it can't go back.
3. Before any migration that changes existing data, copy the affected files to `files/backup-schema-<from>/` (small: prefs XML, alarm and profile files; not photos or models). Keep one backup. Delete it when the version is confirmed healthy plus 7 days.
4. A migration that throws leaves the data as it was (the backup is restored) and the app starts in a safe mode with defaults for the failed part, writes a `CrashLog` entry and counts as an unhealthy launch (so rollback can trigger).
5. Never touch secrets or face templates in a migration unless it's the point of the release.

### 7.4 Tests

JVM unit tests in `:core:data` (the architecture doc's `testDebugUnitTest`; `org.json` is stubbed on the JVM, so add `testImplementation(libs.json)`):
- **Golden fixtures:** `src/test/resources/schema/v1/…`, `v2/…`: real files from each released version. Test: migrate each to current; assert values.
- **Chain test:** v1 to latest in one run equals stepping one version at a time.
- **Idempotent:** run migrations twice; data unchanged the second time.
- **Forward compat:** the *previous* release's reader loads the *new* writer's output (kept as a fixture produced by a test in the new version; guards rule 7.3.1).
- **Failure test:** a throwing step restores the backup.
- The release script (4.5) runs them and refuses to publish on failure.

## 8. Asset updates (voice models, later face models)

Today: `fetch-voice.sh` downloads sherpa-onnx's release archives (bz2 tarballs) with SHA-256 pinned in the script, and `push-voice-models.sh` pushes the unpacked models over adb to `/sdcard/Android/data/app.vesper/files/voice/{kws,stt,tts}`. The Echo has to be at a Mac. Plan: the app does this itself.

- **Format:** per-file downloads, not archives. Android's JDK has no bzip2 and the app has no AndroidX/commons-compress, so the release script unpacks upstream tarballs on the Mac and uploads the individual files (or a `.zip`) to a GitHub release `assets-voice-<n>`, with their SHA-256 in the manifest's `assets.voice.files[]` (path, url, size, sha256). Licences allow it (Apache-2.0 for sherpa-onnx models; check the Piper voice `MODEL_CARD` first; if redistribution is unclear, link the upstream URL and unpack on the Mac only for the one-time push).
- **Pinned hashes:** same idea as `fetch-voice.sh`: the manifest is the pin. The app refuses a file whose hash differs, and a manifest entry without a hash is invalid. The release script computes them from the files it uploads.
- **Version:** `assets.version` is an integer. The installed set is recorded in `files/voice/.version`. `assets.minAppVersionCode` says which app can use them (a new model layout can need new code).
- **Atomic swap:** download into `files/voice.staging/<version>/`, verify every file, then rename to `files/voice/` while keeping the old directory as `voice.prev` until the next confirmed assistant start. If the new models fail to load (STT init error), swap back. The assistant already reports "voice isn't installed" when files are missing; reuse that check as the health check.
- **Rules:** Wi-Fi only, 02:00 to 05:00 window, throttled, resumable per file, not while the assistant is listening or speaking, not when free `/sdcard` space is less than 2× the new set plus 200 MB. Show the status on the settings page ("Voice models 2 of 2 current, 78 MB").
- **When:** only when the manifest says so; models rarely change. Most releases have the same `assets.version`.
- **Face models:** keep in the APK for now (`noCompress += tflite` so they're mmapped, the architecture rule). Moving them out would need the same mechanism plus loading from a file path; only worth it if the APK passes ~50 MB (open question 3). Profiles store embeddings; a **new embedding model invalidates stored face templates**, so any face model change is a data migration too (re-enroll prompt) and goes through 7.

## 9. Settings page

New section **Software update** in `:feature:update`, registered like any other (`host.registerSettings(SettingsSection(...))`), so `:feature:settings` needs no change except two routes:

- Status line: version name and code, channel (stable/beta), last check, state (`Up to date`, `0.4 downloaded, installs tonight`, ...), next window.
- Settings: auto-update (on/off), channel (stable/beta), install window, "Only install when idle" (on, locked on for alarms).
- Buttons (`SettingType.ACTION`, or `POST /api/action/...`): Check now, Download, Install now, Roll back to previous, Upload APK.
- Release notes of the available version. History of the last 5 updates and rollbacks (from `state` and `CrashLog`).
- The device's update-related facts for support: signer hash, ROM build, `systemRevision` present/needed, free space.
- Live updates over the existing `/api/events` SSE stream (download progress).

The Echo's own UI needs nothing else: the island and banners cover it (5.5).

## 10. Privacy

- The only network traffic added: `GET update-<channel>.json` (a fixed URL) and, when there's a newer version, the APK and asset files from `github.com` and `*.githubusercontent.com`.
- What the request carries: the URL, a generic `User-Agent: EchoHome-updater` **without** the version (the version stays on the device; the manifest holds all releases and the device decides), and `If-None-Match`. No device ID, no serial, no installId, no Wi-Fi name, no account. GitHub sees the Echo's IP like any download.
- No telemetry, analytics or crash upload. The `CrashLog` stays on the device and is shown on the settings page. Rollout buckets are computed locally.
- A setting to turn off the check entirely (`updates.channel = off`). With it off, no request is ever made.
- The update-check code is the only place with the GitHub host list, so it's easy to audit. README gets one more line under "Online services".

## 11. Phased implementation plan

Rough effort is one person (an agent session counts as ~half a day).

| # | Milestone | Effort | Needs the user |
|---|---|---|---|
| 0 | **Decisions and device checks.** Answer the open questions that are quick to test (5, 6, 7, 8, 9): key rotation accepted, HOME survives update, black gap length, downgrade flag, alarms after an update. Test with a throwaway second debug-signed build of the same package. | 0.5 day | Echo access, OK to install test builds |
| 1 | **Signing.** Generate the release key and back it up. Add `keystore.properties`, make release fail without it, sign debug with the same key when present. Do the one-time migration (3.3 A or B) and record it in `docs/status/`. | 0.5 day | Choose where the key lives; be at the Echo; approve a full backup, and for option B a `/system` step |
| 2 | **Versioning and migrations.** `versionCode` rule, `schema.json`, `Migrations` runner, golden fixtures for v1, tests. Settings DB backup. No updater yet. | 1 day | None |
| 3 | **Release pipeline.** `echo-release.sh`: build, verify cert, tests, manifest, `gh release create`, `channels` upload. First release 0.2.0 to the beta channel. | 1 day | User runs it (nothing is pushed by agents); confirm repo and release tags |
| 4 | **Allow-list and permission.** Add `INSTALL_PACKAGES` (and anything else) to the XML. Run `echo-system-install.sh` with a backup. Verify. | 0.5 day | **Approval for the `/system` change, a reboot, a complete backup first** |
| 5 | **`:feature:update` core.** Manifest fetch, download with resume, verification, state file, install gate, session install, `UpdateReceiver`, `UpdateGuard` launch counter and health check, island and banner. Manual "install" from a debug action. | 3 days | Echo for testing (device lock) |
| 6 | **Rollback and safety.** Previous APK retention, auto rollback (previous APK, then `/system` copy), watchdog hook, crash-loop tests with a deliberately broken test release (a `crashOnStart` build flag, never in a published release). Optional init guard `device/echohome.rc`. | 2 days (+1 for the guard) | The guard is a `/system` change: approval |
| 7 | **Settings page and manual path.** Section, routes, upload, check now, rollback button, live progress. | 1.5 days | None |
| 8 | **Asset updater** for voice models (mirror release, per-file manifest, atomic swap, rollback by health check). | 2 days | Licence check of the Piper voice |
| 9 | **Staged rollout and docs.** Rollout buckets, beta to stable promotion, README/NOTES sections, recovery runbook. A full dry run: stable user on old version updates two versions, with one rollback. | 1 day | None |

Total about 12 to 14 days of work, mostly phase 5 and 6. Phases 1 and 2 are useful on their own (a real key and migrations help even if the updater never ships), and 1 to 3 give a Mac-based release flow that already improves on today.

## 12. Needs the user's approval

- Generating and storing the release key, and where the backup goes (the key can't be recovered).
- The key migration (3.3): a full partition backup first, and for option B a `/system` change and reboot.
- Adding `INSTALL_PACKAGES` to `/system/etc/permissions/privapp-permissions-app.vesper.xml`: a `/system` write, a remount, a reboot. Not done by any agent.
- Optional init guard `device/echohome.rc`, same reasons.
- Refreshing the `/system` APK copy when needed.
- Publishing anything on GitHub (releases, the `channels` release): the user runs `echo-release.sh`.
- Installing test builds on the Echo (device lock, one agent at a time).
- Any ROM change: out of scope, always manual.

## 13. Open questions

1. Release key type and size: RSA 4096 or EC P-256? RSA is the safe default for old tools.
2. Should the beta channel exist at all with one device? It's cheap; keep it for the dev Echo.
3. APK size: measure the release APK (voice libs + two onnx/tflite libs + models + fonts). If the APK is above ~50 MB, move face models out; download time and the 3× free-space rule matter on 8 GB flash.
4. Is `/data` free space on the Echo enough for 3× APK? Check `df /data`.
5. How does the app know the `/system` revision? A marker file written by `echo-system-install.sh` (`/system/etc/echohome-system-revision`) is simplest. Needs the script change (a `/system` write on the next run, not now).
6. Does this ROM's PackageManager accept a v3 lineage update over a debug-signed system app copy? Test in phase 0 on a throwaway build.
7. Does the preferred HOME activity survive a self-update? If not, which permission re-sets it from the app, and is it privileged (allow-list)?
8. Real length of the black gap and dexopt time on the A53, and RAM during install.
9. Does `INSTALL_ALLOW_DOWNGRADE` through `SessionParams` work for a non-debuggable app on API 30 with INSTALL_PACKAGES, or is `pm uninstall-system-updates` the only way back?
10. SELinux on this userdebug ROM: enforcing or permissive? Decides whether the init guard needs a policy change (which would be a bigger `/system` change; if so, drop the guard).
11. Do alarms (`AlarmManager`) survive a package replace on this ROM, or must `UpdateReceiver` re-arm them? (Plan assumes it must.)
12. Piper voice licence: can the files be mirrored on a GitHub release?
13. Should a stable user ever get a prompt on the Echo screen ("Update available") or only the settings page? Plan: settings page only, island only during install.
14. Hosting: GitHub is simple but rate limits and policies can change. A fallback `manifestUrl` setting (hidden) makes it possible to point at another host later.
