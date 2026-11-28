#!/usr/bin/env bash
# F-17: Buat/perbarui Gradle lockfile dan verification-metadata.xml (SHA-256) untuk mengunci seluruh dependency graph.
# Jalankan di mesin build ber-SDK & internet lalu commit hasilnya ke repository.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
bash ./gradlew dependencies --write-locks --no-daemon
bash ./gradlew --write-verification-metadata sha256 testDebugUnitTest assembleDebug assembleRelease -PallowUnsigned=true --no-daemon
echo "Selesai: gradle.lockfile dan gradle/verification-metadata.xml diperbarui."
