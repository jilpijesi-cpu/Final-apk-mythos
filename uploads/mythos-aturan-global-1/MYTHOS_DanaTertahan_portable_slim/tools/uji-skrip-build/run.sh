#!/usr/bin/env bash
# Simulasi alur build_apk dengan gh palsu dan Termux tanpa identitas git. Pakai: bash tools/uji-skrip-build/run.sh tools/build-github.sh [nama-artifact]
# Harapan: KITA_BUILD=ok untuk mythos-debug; "APK tidak dihasilkan" untuk nama artifact lain.
# usage: run.sh script.sh [artifact-name-yang-diunggah-workflow]
set -euo pipefail
S="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
W="$(mktemp -d)"
trap 'rm -rf "$W"' EXIT
export HOME="$W" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=user.useConfigOnly GIT_CONFIG_VALUE_0=true   # meniru Termux baru: tanpa identitas
export PATH="$(cd "$(dirname "$0")" && pwd)/bin:$PATH" FAKE_ARTIFACT="${2:-mythos-debug}"
git init -q --bare "$W/remote.git"
export FAKE_BARE="$W/remote.git"
mkdir -p "$W/ws/proj/.github/workflows"
cd "$W/ws"
echo "x" > proj/a.txt
echo "name: b" > proj/.github/workflows/b.yml
set +e +o pipefail
printf 'tokenpalsu\n' | bash "$S" "$W/ws/proj" own repo true - 2>&1 | grep -E "KITA_|GAGAL|gagal|BERBAHAYA|Petunjuk|identitas|scope"
rc="${PIPESTATUS[1]}"
echo "(exit=${rc})"
