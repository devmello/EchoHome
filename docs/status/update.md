# Status: updates (`:feature:update`)

Nov 24, 2026. Branch `worktree-agent-afef6fde34e1d1f86`, from main `6a8e877`. Over-the-network updates from GitHub Releases (`devmello/EchoHome`, public), so a new version reaches the Echo without adb. The longer plan is `docs/software-updates.md`; this is the first, smaller version of it.

## How it works

**Versions** (`android/app/build.gradle.kts`): `versionCode` = `git rev-list --count HEAD` (grows with every commit on main), override `-PvesperVersionCode=N`; `versionName` = `0.2.<code>` for releases, `0.2.<code>-dev` for local builds (override `-PvesperVersionName`). Without git: 1.

**Publishing** (`scripts/echo-release.sh`, on this Mac only; release builds are signed with this Mac's debug keystore, the same key as the app on the Echo):
- Default `--dry-run`: runs the updater's unit tests, builds the release APK with the derived version, checks package / versionCode / versionName with `aapt2`, checks with `apksigner` that the signer is this Mac's debug key, and writes to `android/build/release/<tag>/`: `EchoHome-<v>.apk`, `EchoHome-<v>.apk.sha256`, `body.md` (notes from the commit log since the last `v*` tag, or `--notes FILE`; the SHA-256 line; a hidden `<!-- echohome-update {...} -->` comment with versionCode, versionName, apk name, sha256, size, commit) and `latest.json` (what GitHub's API will answer). Prints the exact `gh release create` command.
- `--publish`: also requires a clean tree on `main`, the commit already on `origin/main` (the script never pushes), no existing tag, voice libs present, `gh` logged in; asks `[y/N]` (`--yes` skips), then `gh release create v<v> <apk> <apk>.sha256 --target <commit> --latest`. **Not run by me: publishing is the user's call.**
- Test-only flags (refused with `--publish`): `--version-code N`, `--debug`, `--feed-base URL`, `--out DIR`.

**On the Echo** (`android/feature/update`, package `app.vesper.feature.update`):
- `UpdateManager`: once a day at 02:30 + 0–29 min (fixed per install), or 3 min after start if the last check is over 26 h old, `GET https://api.github.com/repos/devmello/EchoHome/releases/latest` with `If-None-Match` (304s don't count against the 60/h limit). Generic `User-Agent: EchoHome-updater`, no device data. HTTPS only, and every hop (redirects too) must be `api.github.com`, `github.com` or `*.githubusercontent.com` (`GitHubFetcher`).
- `ReleaseParser`: reads the comment block, finds the named asset, cross-checks size and GitHub's own `digest: sha256:…` when present. Drafts / prereleases are refused. Notes = the body without the comment and the checksum line, max 1200 chars.
- If its versionCode is higher, not skipped, not a refused checksum, and auto-install is on (or the user tapped Install now): free-space check (3 × APK + 64 MB), download to `files/updates/download-<code>-<sha>.apk.part` (resumes with `Range`, starts over on a 200), SHA-256, then `ApkCheck`: `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)` → package `app.vesper`, versionCode equals the release's, signing certificate SHA-256 set equals the installed app's. A refusal deletes the file and remembers the checksum (not downloaded again automatically). Verified → `files/updates/ready.apk`.
- Install (`Installer`, `PackageInstaller` session, `INSTALL_PACKAGES`): re-hashes and re-checks the file, copies the installed `base.apk` to `files/updates/previous.apk` (the rollback target, with its SHA-256), writes `pending` to the state file, commits. The system replaces the process; `UpdateReceiver` gets `MY_PACKAGE_REPLACED` in the new version and starts `MainActivity` if the screen is on.
- When: **automatically** only inside 03:00–05:00 local, no alarm ringing / snoozed / in sunrise and none due within 90 min, no call / doorbell / ringing timer / reminder / assistant on the island, nothing playing in `services.media` (sleep sounds count), no running timer, nobody in the room (`presence == PRESENT` blocks; unknown counts as empty) (`QuietWindow`). An `AlarmManager` RTC_WAKEUP alarm (`setWindow`, 5 min) wakes it at the next check or every 15 min in the window while an update waits (`Schedule.nextWake`). **"Install now"** checks first if needed, then installs right away unless an alarm is ringing or a call is on.
- State: `files/updates/state.json` (`UpdateState`, atomic write + fsync). Fields are only ever added, so an older version after a rollback still reads the skip list.
- `UpdateGuard` (crash-loop safety net), called by `VesperApp.onCreate` right after the watchdog and before fonts, services and features:
  - Confirms a pending install (`pending.to == installed`): arms the guard for that version for 24 h.
  - Each start of the armed version records its start; a start that stays up 2 min clears it. A start that finds the previous one unconfirmed counts as failed if `ActivityManager.getHistoricalProcessExitReasons` says the previous process could have crashed (crash, native crash, ANR, signal, exit-self, low memory, init failure, unknown; not force-stop, install or permission change).
  - **3 failed starts** → marks the version skipped, writes `pending` (rollback), installs `previous.apk` with a downgrade (`SessionParams.setRequestDowngrade` + `INSTALL_REQUEST_DOWNGRADE|INSTALL_ALLOW_DOWNGRADE` via reflection; allowed because the ROM is `ro.debuggable=1` and the app is a system app with `usesNonSdkApi`), and the app starts in **safe mode**: services but no features, one screen with "EchoHome X didn't start properly. Going back to Y…" (`SafeModeView` in `:app`). A restart during the rollback tries again; if it fails, `rollbackFailed` makes every start of that version a safe-mode screen with the recovery commands instead of a crash loop.
  - Skipped versions are never installed automatically (Install now still can; a newer versionCode is offered normally).
- Settings page, section **Updates** (order 95): "Check now" (its help is the status: installed version and code, last check time and result, what it's doing now, last event), "Install now" (its help: the available version, notes, whether it was skipped), "Install automatically" (`update.auto`, on), "Check for updates" (`update.enabled`, on, Advanced; off = no requests). The section is re-registered when the status text changes, so open pages refresh live (`schema` SSE). `.help` got `white-space: pre-line` in `app.css` for the multi-line status.
- After an "Install now" update a banner says "Updated to <version>" with the first line of the notes.
- **Debug builds only** (`src/debug/DebugFeed.kt`; `src/release` has a stub, so release builds can't use any of it): launch extras `--es updateFeed http://<mac>:8765/v11/latest.json` (a test feed; `-` clears), `--ez updateCheck true`, `--ez updateInstall true`. Plain HTTP goes only to that feed's host:port, through a 60-line socket client; the network security config stays HTTPS-only for everything else.

## The /system step (done Nov 24, 00:03, user-approved)

- `device/privapp-permissions-app.vesper.xml`: added `android.permission.INSTALL_PACKAGES`.
- An updated system app only gets a privileged permission that its `/system` copy also requests, so the `/system` APK was refreshed too. `scripts/echo-system-install.sh` changes: it now pushes the device files of the checkout it runs from (`$CHECKOUT/device/…`, before: always the main checkout's), and builds the `/system` copy with **versionCode 1** (`0.2-system`) and refuses another versionCode, because Android drops an update at boot whose versionCode is lower than the `/system` copy's. Its verify step prints the `INSTALL_PACKAGES` grant.
- Ran `./scripts/echo-system-install.sh` (backup `backups/20261122-052747` present; the script's privileged-permission check passed; remount, push APK + XML + gating.kl + echohome.rc, reboot). After the reboot: `dumpsys package app.vesper` → `android.permission.INSTALL_PACKAGES: granted=true`, `/system/priv-app/Vesper` versionCode 1 PRIVILEGED with the `/data` update on top, HOME resolves to `app.vesper/.MainActivity` and it was resumed, `gating.kl` in use, `logcat -b crash` empty, SystemUI up (same pid a minute later).

## End-to-end tests on the Echo

Fake releases built with `echo-release.sh --debug --version-code N --feed-base http://192.168.10.65:8765/…` (so the real release body format was parsed), served by `python3 -m http.server` from a scratch directory, picked with `--es updateFeed`.

All on Nov 24, 00:13–00:30, debug builds (they carry the test-feed override), installed over the `/system` copy with `INSTALL_PACKAGES` granted:

| # | Test | Result |
|---|---|---|
| 1 | v10 installed, feed v11, "Install now" (`--ez updateInstall true`) | **Pass.** Checked, downloaded 46 MB, verified, installed: versionCode 11 about 10 s after the tap. `MainActivity` resumed in a new task, HOME still resolves to `app.vesper/.MainActivity`. The new process confirmed the install, armed the guard ("watching 11"), got `INSTALL_SUCCEEDED`. `previous.apk` = the v10 base.apk with its SHA-256. |
| 2 | Same version (feed v11 on v11) | **Pass.** "Up to date", nothing downloaded; Install now: still 11. |
| 3 | Older version (feed v10 on v11) | **Pass.** "Up to date"; Install now: still 11. |
| 4 | Bad checksum (v12 with a wrong sha256 in the release) | **Pass.** Downloaded, "Refused 0.2.12: checksum doesn't match", file deleted, checksum remembered; Install now: still 11. |
| 5 | Wrong signature (v12 re-signed with a throwaway key, correct checksum) | **Pass.** "Refused 0.2.12: The APK is signed with a different key"; Install now: still 11. |
| 6 | Rollback: v13 throws in `VesperApp.onCreate` right after the guard (a temporary edit, built into a scratch APK and reverted, never committed) | **Pass.** 11 → 13 by Install now; 13 crashed on every start (Android restarted HOME within a second each time; exit reason 2 = signalled, counted); 4th start: "13 didn't stay up 3 times: rolling back to 11", safe-mode screen, downgrade session committed, 11 back about 6 s after 13 first started, HOME resumed. State: skipped [13], guard disarmed. A new check with 13 still published: "0.2.13 is available (skipped after a rollback…)", not downloaded. **The downgrade through `PackageInstaller` works on this ROM.** |
| 7 | Automatic install (no tap): test build w20/w21 whose window was 00:00–23:59 (temporary edit, reverted), "Check now" only | **Pass.** Check → download → verify → `QuietWindow.decide` on the real room state (nothing playing, nobody seen, no alarm within 90 min) → installed by itself: 21 about 6 s later, HOME resumed. |

Then back to a release build: `--es updateFeed -` (override cleared), `files/updates` deleted, release APK installed with `-d`.

**Release build against the real GitHub** (00:47–00:50): release build of this branch, versionCode 1, `0.2-preview` (versionCode 1 on purpose, so other agents' older builds still install over it and the first published release counts as newer), `INSTALL_PACKAGES: granted=true`, HOME resumed and resolves to us, `logcat -b crash` empty. Its first check ran 3 min after start: `check (manual=false): No release published yet` (GitHub's 404, over HTTPS with the host allow-list); next wake 02:43 (02:30 + this install's jitter).

## Unit tests

`./gradlew :feature:update:testDebugUnitTest`: 34 tests. `ReleaseParserTest` (the script's format, case, missing info / versionCode / sha / asset, size and digest mismatch, prerelease, non-JSON, notes cleaning and cut, JSON round trip), `VersionPolicyTest` (higher / same / older, skipped vs manual), `QuietWindowTest` (window edges, alarm gap, room / media / island / timer, manual rules, a window across midnight), `ScheduleTest` (daily check + jitter, overdue, retries in the window, DST), `CrashLoopTest` (3 crashed starts → rollback, a stable start clears, non-crash exits don't count, another version / expiry disarm, exit reasons), `UpdateStateTest` (round trip, history cap).

## Shared files touched

`android/settings.gradle.kts` and `android/app/build.gradle.kts` (module, versions), `VesperApp.kt` (guard call, safe mode, `UpdateFeature` before `SettingsFeature`), new `SafeModeView.kt`, `feature/settings/.../app.css` (`.help` pre-line), `device/privapp-permissions-app.vesper.xml`, `scripts/echo-system-install.sh`, `scripts/build-install.sh` (`install -r -d`), new `scripts/echo-release.sh`, `docs/architecture.md`, `docs/software-updates.md` (status line).

## Left / not verified

- **First real release, Nov 24 00:57:** `v0.2.273` published with `echo-release.sh --publish` (three script fixes on the way: untracked files outside the build no longer count as dirty, a `grep` with no match no longer aborts the script, and `gh` is checked with `gh api user` because a stale second login made `gh auth status` fail). On the Echo (release 0.2.270): "Check now" from the settings API downloaded 0.2.273 from GitHub (redirect to `*.githubusercontent.com` followed), verified it and waited for the night window; "Install now" installed it, EchoHome came back resumed as HOME, no crash. Still untested against GitHub: the ETag/304 answer and the `digest` field.
- The real 03:00 automatic install wasn't watched (the decision ran on the device with a test build whose window was all day, see above).
- Downgrade on rollback was verified with debug builds; release builds rely on the same `ro.debuggable=1` rule (not separately tested).
- A crash before `Application.onCreate` (static init, a dex that fails to load) or a native crash that makes Android give up on HOME isn't caught: nothing of ours runs. Recovery from the Mac: `./scripts/build-install.sh --release` or `adb shell pm uninstall-system-updates app.vesper` (back to the `/system` copy). The init-guard idea is in `docs/software-updates.md` §6.4.
- The signing key is still this Mac's debug key (see `docs/software-updates.md` §3 for why a real release key matters before the repo has forks or other users).
- No resumable-download test against a server that supports `Range` (`http.server` doesn't; the restart-from-zero path is what ran).
- Other agents install over the Echo all the time, so it runs whichever build came last; the updater only lives in builds from this branch on.
- A check right after an overdue start (e.g. 00:50) doesn't repeat at that night's 02:30 + jitter tick (a tick only checks when the last check is over 20 h old), so the first daily check is the next night.
- Every commit bumps versionCode, so another agent's branch that is behind is a downgrade: `build-install.sh` now uses `-d`; branches made before this one still use `install -r` and fail with `INSTALL_FAILED_VERSION_DOWNGRADE` while a newer build is on the Echo (`adb install -r -d` by hand, or rebase).
