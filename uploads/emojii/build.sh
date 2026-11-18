#!/usr/bin/env bash
# Bootstrap Android SDK components (if missing), then build the debug APK.
# Usage: ./build.sh [Gradle tasks...]
# Example: ./build.sh testDebugUnitTest assembleDebug
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "build.sh saat ini mendukung Linux. Untuk Termux, gunakan: bash build-termux.sh" >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  echo "Java tidak ditemukan. Pasang JDK 17 atau lebih baru, lalu jalankan ulang." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  echo "curl dan unzip diperlukan untuk menyiapkan Android SDK." >&2
  exit 1
fi

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
TOOLS="$SDK/cmdline-tools/latest/bin"
mkdir -p "$SDK/cmdline-tools"

if [[ ! -x "$TOOLS/sdkmanager" ]]; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  echo "Mengunduh Android command-line tools..."
  curl -fL --retry 3 \
    https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    -o "$TMP/commandlinetools.zip"
  mkdir -p "$TMP/unpacked"
  unzip -q "$TMP/commandlinetools.zip" -d "$TMP/unpacked"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$TMP/unpacked/cmdline-tools" "$SDK/cmdline-tools/latest"
  trap - EXIT
  rm -rf "$TMP"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$TOOLS:$SDK/platform-tools:$PATH"

# Prompt the user to review and accept Google's SDK licenses; do not accept on their behalf.
"$TOOLS/sdkmanager" --sdk_root="$SDK" --licenses
"$TOOLS/sdkmanager" --sdk_root="$SDK" \
  "platform-tools" "platforms;android-34" "build-tools;34.0.0"
printf 'sdk.dir=%s\n' "$SDK" > local.properties

if [[ $# -eq 0 ]]; then
  set -- assembleDebug
fi
./gradlew "$@"
echo "\nSelesai. Periksa folder app/build/outputs/apk/debug/ untuk APK debug."
