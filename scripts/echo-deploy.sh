#!/bin/bash
# Build the minified release, install it on the Echo and make it HOME (holds the device lock).
# For development builds use ./scripts/build-install.sh (debug by default, more options).
exec "$(dirname "$0")/build-install.sh" --release "$@"
