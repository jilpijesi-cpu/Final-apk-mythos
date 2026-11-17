#!/usr/bin/env bash
# Simulasi alur build_apk dengan gh palsu dan Termux tanpa identitas git. Pakai: bash tools/uji-skrip-build/run.sh tools/build-github.sh [nama-artifact]
# Harapan: KITA_BUILD=ok untuk mythos-debug; "APK tidak dihasilkan" untuk nama artifact lain.
# usage: run.sh script.sh [artifact-name-yang-diunggah-workflow]
S="$1"; W=$(mktemp -d); export HOME=$W GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=user.useConfigOnly GIT_CONFIG_VALUE_0=true   # meniru Termux baru: tanpa identitas
export PATH=$(cd "$(dirname "$0")" && pwd)/bin:$PATH FAKE_ARTIFACT="${2:-mythos-debug}"
git init -q --bare $W/remote.git; export FAKE_BARE=$W/remote.git
mkdir -p $W/ws/proj/.github/workflows && cd $W/ws && echo "x" > proj/a.txt && echo "name: b" > proj/.github/workflows/b.yml
bash "$S" proj own repo true tokenpalsu 2>&1 | grep -E "KITA_|GAGAL|gagal|BERBAHAYA|Petunjuk|identitas|scope" ; echo "(exit=${PIPESTATUS[0]})"
