#!/bin/bash
# Rebuilds libvesper_spotify.so (armeabi-v7a only) into ../src/main/jniLibs.
# Needs: rustup target armv7-linux-androideabi, cargo-ndk, an NDK (ANDROID_NDK_HOME or $ANDROID_HOME/ndk/<ver>).
# Crates come from crates.io at the versions in Cargo.lock (--locked); librespot-core and
# librespot-discovery are the vendored, patched copies in vendor/ (see vendor/VESPER-PATCH.md).
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
  export ANDROID_NDK_HOME
fi
[ -d "${ANDROID_NDK_HOME:-}" ] || { echo "No NDK: sdkmanager --sdk_root=\$ANDROID_HOME 'ndk;29.0.14206865'" >&2; exit 1; }
cargo test --locked --quiet
cargo ndk -t armeabi-v7a -P 30 -o ../src/main/jniLibs build --locked --release
ls -l ../src/main/jniLibs/armeabi-v7a/
