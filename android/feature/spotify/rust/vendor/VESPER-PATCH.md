# Vendored librespot crates (EchoHome patches)

`librespot-core` and `librespot-discovery` are the crates.io **0.8.0** releases (librespot commit
`d36f9f1907e8`, MIT, see `LICENSE`), used through `[patch.crates-io]` in `../Cargo.toml`. Every
change is marked `VESPER PATCH` in the source. The other librespot crates (connect, playback,
metadata, audio, protocol, oauth) come unchanged from crates.io.

## librespot-discovery

- `src/server.rs`: `getInfo` reports ZeroConf `version` **2.12.0** instead of 2.9.0. Current
  Spotify apps (iOS 9.1, Windows) request 2.12.0 and send an empty `blob` / `clientKey` to a device
  that reports 2.9.0, so they can't log in. Upstream PR librespot-org/librespot#1847 (open, Nov 2026).
- `src/server.rs`: an `addUser` blob shorter than 36 bytes (IV + checksum) is rejected instead of
  panicking on the slice (upstream fixed the same on `dev` after 0.8.0).
- `src/lib.rs`: `launch_external`, a zeroconf "backend" that advertises nothing, so Android's
  NsdManager can do it (like `:feature:airplay`); `DNS_SD_SERVICE_TYPE` / `TXT_RECORDS`; and
  `Discovery::port()` (the server's port when the builder asked for 0).

## librespot-core

- `src/session.rs`: a non-Premium account ends the session (`Session::shutdown`) instead of
  `std::process::exit(1)`, which would kill the whole HOME app (and, with cached credentials,
  crash-loop it). The account type stays readable via `get_user_attribute("type")`. Upstream PR
  librespot-org/librespot#1855 (open, Oct 2026).
- `build.rs` / `Cargo.toml`: no `vergen-gitcl` build step (it no longer builds against today's
  vergen-lib, and would stamp EchoHome's git sha). The v0.8.0 version values are set directly.

## Updating

Copy the new release's `core/` and `discovery/` crates over these directories, re-apply the
patches above (search for `VESPER PATCH`), drop any that upstream merged, then run
`../build.sh` (it runs `cargo test`, including the zeroconf getInfo test).
