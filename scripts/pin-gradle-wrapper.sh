#!/usr/bin/env bash
# Menambahkan distributionSha256Sum ke gradle/wrapper/gradle-wrapper.properties supaya wrapper menolak distribusi Gradle yang berubah.
# Butuh jaringan + curl + sha256sum. Pakai dari akar proyek:  bash scripts/pin-gradle-wrapper.sh
# Langkah: unduh distribusi dari distributionUrl, bandingkan dengan checksum resmi (<url>.sha256 di services.gradle.org), lalu tulis hasilnya.
set -euo pipefail
cd "$(dirname "$0")/.."
props=gradle/wrapper/gradle-wrapper.properties
url="$(sed -n 's/^distributionUrl=//p' "$props" | sed 's#\\:#:#g')"
[[ "$url" =~ ^https://services\.gradle\.org/distributions/gradle-[0-9.]+-(bin|all)\.zip$ ]] || { echo "distributionUrl tidak dikenali: $url" >&2; exit 1; }
expected="$(curl -fsSL "$url.sha256" | tr -d ' \r\n')"
[[ "$expected" =~ ^[0-9a-f]{64}$ ]] || { echo "checksum resmi tidak valid: $expected" >&2; exit 1; }
tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
curl -fsSL "$url" -o "$tmp"
actual="$(sha256sum "$tmp" | cut -d' ' -f1)"
[ "$actual" = "$expected" ] || { echo "Checksum unduhan ($actual) tidak cocok dengan yang resmi ($expected); properti tidak diubah." >&2; exit 1; }
if grep -q '^distributionSha256Sum=' "$props"; then
  sed -i "s/^distributionSha256Sum=.*/distributionSha256Sum=$actual/" "$props"
else
  printf 'distributionSha256Sum=%s\n' "$actual" >> "$props"
fi
echo "distributionSha256Sum=$actual"
