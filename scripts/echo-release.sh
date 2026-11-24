#!/bin/bash
# Prepare an EchoHome release for GitHub Releases (devmello/EchoHome); with --publish, publish it.
# The Echo's updater (:feature:update) installs it: docs/status/update.md.
#
#   ./scripts/echo-release.sh              dry run (default): build, verify, write everything to
#                                          android/build/release/<tag>/ and print what would be published
#   ./scripts/echo-release.sh --publish    the same, then `gh release create` after a confirmation
#
# Options:
#   --yes               with --publish: don't ask
#   --notes FILE        release notes (Markdown) instead of the commit log since the last v* tag
#   --out DIR           output directory
# Test-only (refused with --publish): --version-code N, --debug (debug build),
#   --feed-base URL     latest.json points the APK at URL/<apk> (a fake feed served from this Mac)
#
# Versions: versionCode = commit count of HEAD (always grows on main), versionName = <base>.<code>,
# tag v<versionName>. Built and signed on this Mac: release builds use this Mac's debug keystore,
# the same key as the app on the Echo (Android only accepts updates signed with the installed key),
# so a release can't be built anywhere else. Nothing is pushed: the commit must already be on GitHub.
set -euo pipefail
. "$(dirname "$0")/lib.sh"
REPO=devmello/EchoHome
MODE=dry-run; YES=0; NOTES_FILE=""; OUT=""; VC=""; VARIANT=release; FEED_BASE=""
while [ $# -gt 0 ]; do
  case $1 in
    --dry-run) MODE=dry-run ;;
    --publish) MODE=publish ;;
    --yes) YES=1 ;;
    --notes) NOTES_FILE=$2; shift ;;
    --out) OUT=$2; shift ;;
    --version-code) VC=$2; shift ;;
    --debug) VARIANT=debug ;;
    --feed-base) FEED_BASE=${2%/}; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
die() { echo "ABORT: $*" >&2; exit 1; }
warn() { echo "warning: $*" >&2; }

cd "$CHECKOUT"
if [ "$MODE" = publish ]; then
  [ -z "$VC$FEED_BASE" ] && [ "$VARIANT" = release ] || die "--version-code, --debug and --feed-base are for test feeds only"
fi
COMMIT=$(git rev-parse HEAD)
VC=${VC:-$(git rev-list --count HEAD)}
BASE=$(sed -n 's/^val versionBase = "\(.*\)"$/\1/p' android/app/build.gradle.kts)
[ -n "$BASE" ] || die "versionBase not found in android/app/build.gradle.kts"
VN="$BASE.$VC"
TAG="v$VN"
APK_NAME="EchoHome-$VN.apk"
OUT=${OUT:-$CHECKOUT/android/build/release/$TAG}

# --- checks
# Tracked changes anywhere, or untracked files where the build reads them (android/, device/, scripts/).
DIRTY=$( { git status --porcelain --untracked-files=no; git status --porcelain -- android device scripts | grep "^??"; } )
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$MODE" = publish ]; then
  [ -z "$DIRTY" ] || die "uncommitted changes (a release is built from a clean commit)"
  [ "$BRANCH" = main ] || die "on '$BRANCH', not main"
  git fetch -q origin main || die "git fetch failed"
  git merge-base --is-ancestor "$COMMIT" origin/main || die "$COMMIT isn't on GitHub's main yet: push it first"
  git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG exists locally"
  [ -z "$(git ls-remote --tags origin "refs/tags/$TAG")" ] || die "tag $TAG exists on GitHub"
  gh auth status >/dev/null 2>&1 || die "gh isn't logged in (gh auth login)"
else
  [ -z "$DIRTY" ] || warn "uncommitted changes: this dry run builds them, a publish would refuse"
  [ "$BRANCH" = main ] || warn "on '$BRANCH', not main"
fi
# An APK without the voice libs would replace a working assistant on the Echo.
for lib in feature/assistant/src/main/jniLibs/armeabi-v7a/libsherpa-onnx-jni.so feature/assistant/src/main/jniLibs/armeabi-v7a/libonnxruntime.so \
           feature/airplay/src/main/jniLibs/armeabi-v7a/libvesper_airplay.so feature/spotify/src/main/jniLibs/armeabi-v7a/libvesper_spotify.so; do
  if [ ! -f "android/$lib" ]; then
    [ "$MODE" = publish ] && die "missing android/$lib (./scripts/fetch-voice.sh --libs)"
    warn "missing android/$lib"
  fi
done

# --- build
TASK=assembleRelease; [ "$VARIANT" = debug ] && TASK=assembleDebug
echo "build: $TAG (versionCode $VC) from $COMMIT ($VARIANT)"
(cd android && ./gradlew -q :feature:update:testDebugUnitTest ":app:$TASK" -PvesperVersionCode="$VC" -PvesperVersionName="$VN") \
  || die "build or unit tests failed"
BUILT="$CHECKOUT/android/app/build/outputs/apk/$VARIANT/app-$VARIANT.apk"
[ -f "$BUILT" ] || die "no APK at $BUILT"

# --- verify
BT=$(ls -d "$ANDROID_HOME"/build-tools/* | tail -1)
BADGING=$("$BT/aapt2" dump badging "$BUILT" | sed -n 1p)
echo "$BADGING" | grep -q "name='app.vesper' versionCode='$VC' versionName='$VN'" || die "APK says: $BADGING"
CERT=$("$BT/apksigner" verify --print-certs "$BUILT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | sed -n 1p)
[ -n "$CERT" ] || die "apksigner found no signer"
KEY=$(keytool -list -v -keystore "$HOME/.android/debug.keystore" -storepass android -alias androiddebugkey 2>/dev/null \
  | sed -n 's/^[[:space:]]*SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f')
[ "$CERT" = "$KEY" ] || die "APK signer $CERT isn't this Mac's debug key ($KEY), which the Echo's app is signed with"

# --- assets
mkdir -p "$OUT"
cp "$BUILT" "$OUT/$APK_NAME"
SHA=$(shasum -a 256 "$OUT/$APK_NAME" | cut -d' ' -f1)
SIZE=$(stat -f %z "$OUT/$APK_NAME")
echo "$SHA  $APK_NAME" > "$OUT/$APK_NAME.sha256"

if [ -n "$NOTES_FILE" ]; then
  NOTES=$(cat "$NOTES_FILE")
else
  LAST=$(git describe --tags --abbrev=0 --match 'v[0-9]*' 2>/dev/null || true)
  RANGE=${LAST:+$LAST..}HEAD
  TOTAL=$(git rev-list --count --no-merges "$RANGE")
  NOTES="Changes${LAST:+ since $LAST}:"$'\n'"$(git log --no-merges --pretty='- %s' "$RANGE" | sed -n '1,40p')"
  [ "$TOTAL" -gt 40 ] && NOTES="$NOTES"$'\n'"- … and $((TOTAL - 40)) more"
fi
META="{\"versionCode\":$VC,\"versionName\":\"$VN\",\"apk\":\"$APK_NAME\",\"sha256\":\"$SHA\",\"size\":$SIZE,\"minSdk\":30,\"commit\":\"$COMMIT\"}"
# The updater reads the comment (ReleaseParser); people read the rest.
cat > "$OUT/body.md" <<EOF
$NOTES

SHA-256 ($APK_NAME): \`$SHA\`

<!-- echohome-update $META -->
EOF
# What GitHub's GET /repos/$REPO/releases/latest will answer (for a fake feed: --feed-base).
URL_BASE=${FEED_BASE:-https://github.com/$REPO/releases/download/$TAG}
python3 -I - "$OUT/body.md" "$TAG" "$APK_NAME" "$SIZE" "$URL_BASE/$APK_NAME" > "$OUT/latest.json" <<'EOF'
import json, sys
body, tag, name, size, url = sys.argv[1:]
print(json.dumps({"tag_name": tag, "name": "EchoHome " + tag[1:], "draft": False, "prerelease": False,
                  "body": open(body).read(), "assets": [{"name": name, "size": int(size), "browser_download_url": url}]}, indent=1))
EOF

GH=(gh release create "$TAG" "$OUT/$APK_NAME" "$OUT/$APK_NAME.sha256" --repo "$REPO" --target "$COMMIT"
    --title "EchoHome $VN" --notes-file "$OUT/body.md" --latest)
echo
echo "=== release $TAG"
echo "commit:   $COMMIT ($BRANCH)"
echo "apk:      $OUT/$APK_NAME ($SIZE bytes, versionCode $VC)"
echo "sha256:   $SHA"
echo "signer:   $CERT"
echo "assets:   $APK_NAME, $APK_NAME.sha256"
echo "--- body.md"
cat "$OUT/body.md"
echo "---"
printf 'command: '; printf '%q ' "${GH[@]}"; echo
if [ "$MODE" != publish ]; then
  echo "(dry run: nothing published. Publish with: ./scripts/echo-release.sh --publish)"
  exit 0
fi
if [ "$YES" != 1 ]; then
  read -r -p "Publish $TAG to github.com/$REPO? [y/N] " ok
  [ "$ok" = y ] || [ "$ok" = Y ] || { echo "not published"; exit 1; }
fi
"${GH[@]}"
echo "published: https://github.com/$REPO/releases/tag/$TAG"
