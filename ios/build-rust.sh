#!/bin/bash
# Builds the Rust DSP (../dsp) as a static library for the SDK Xcode is
# building against. Invoked from the "Build Rust DSP" run-script phase;
# can also be run by hand: ./build-rust.sh iphoneos|iphonesimulator
#
# Output: ios/build/rust/<platform>/libdeltasleep_dsp.a, picked up via
# LIBRARY_SEARCH_PATHS[sdk=...] in the project.
set -euo pipefail

# Xcode run scripts get a minimal PATH; cargo/rustup live in ~/.cargo/bin.
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DSP_DIR="$SCRIPT_DIR/../dsp"
PLATFORM="${1:-${PLATFORM_NAME:-iphonesimulator}}"
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"

case "$PLATFORM" in
  iphoneos)        TARGETS=(aarch64-apple-ios) ;;
  iphonesimulator) TARGETS=(aarch64-apple-ios-sim) ;;
  *) echo "error: unsupported platform '$PLATFORM'" >&2; exit 1 ;;
esac

# Xcode's environment leaks SDKROOT/LD settings that confuse rustc's own
# host build scripts; cargo resolves the iOS SDK itself via xcrun.
unset SDKROOT LIBRARY_PATH

OUT_DIR="$SCRIPT_DIR/build/rust/$PLATFORM"
mkdir -p "$OUT_DIR"

LIBS=()
for TARGET in "${TARGETS[@]}"; do
  rustup target add "$TARGET" >/dev/null 2>&1 || true
  (cd "$DSP_DIR" && cargo rustc --lib --release --target "$TARGET" --crate-type staticlib)
  LIBS+=("$DSP_DIR/target/$TARGET/release/libdeltasleep_dsp.a")
done

if [ "${#LIBS[@]}" -eq 1 ]; then
  cp "${LIBS[0]}" "$OUT_DIR/libdeltasleep_dsp.a"
else
  lipo -create "${LIBS[@]}" -output "$OUT_DIR/libdeltasleep_dsp.a"
fi
echo "Rust DSP → $OUT_DIR/libdeltasleep_dsp.a"
