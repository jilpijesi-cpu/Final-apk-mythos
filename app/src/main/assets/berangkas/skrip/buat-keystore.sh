#!/usr/bin/env bash
# Membuat identitas penandatangan BARU untuk MYTHOS: kita.jks + keystore.properties (sandi acak).
# Pemakaian (di folder proyek, di Termux): bash tools/buat-keystore.sh [folder-proyek] [--tampilkan]
# Kunci dibuat di perangkat ini dan tidak dikirim ke mana pun. Sesudahnya skrip hanya MENCETAK perintah untuk
# menyimpan cadangan dan rahasia GitHub; sandi tidak ditulis ke layar kecuali Anda memintanya (--tampilkan).
set -eu
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -n "${1:-}" ] && [ "${1:-}" != "--tampilkan" ]; then
  cd "$1"
  shift
elif [ -f "$SCRIPT_DIR/../settings.gradle.kts" ] || [ -f "$SCRIPT_DIR/../settings.gradle" ]; then
  cd "$SCRIPT_DIR/.."
fi
command -v keytool >/dev/null 2>&1 || { echo "keytool tidak ada. Termux: pkg install openjdk-17"; exit 1; }
if [ -e kita.jks ] || [ -e keystore.properties ]; then
  echo "kita.jks atau keystore.properties sudah ada. Tidak ditimpa: identitas lama akan hilang dan upgrade tidak bisa dipasang."
  echo "Pindahkan dulu bila Anda sungguh ingin identitas baru lagi."
  exit 1
fi
umask 077
PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
[ "${#PASS}" -ge 20 ] || { echo "gagal membuat sandi acak"; exit 1; }
KEYSTORE_PASS="$PASS" keytool -genkeypair -keystore kita.jks -alias kita -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass:env KEYSTORE_PASS -keypass:env KEYSTORE_PASS -dname "CN=MYTHOS, O=Kita, C=ID" >/dev/null 2>&1 \
  || { rm -f kita.jks; echo "keytool gagal"; exit 1; }
printf 'storeFile=kita.jks\nstorePassword=%s\nkeyAlias=kita\nkeyPassword=%s\n' "$PASS" "$PASS" > keystore.properties
chmod 600 keystore.properties kita.jks 2>/dev/null || true
echo "SELESAI. Dibuat: kita.jks dan keystore.properties (keduanya diabaikan git)."
echo "Sidik jari SHA-256 identitas baru:"
KEYSTORE_PASS="$PASS" keytool -list -keystore kita.jks -storepass:env KEYSTORE_PASS 2>/dev/null | grep -i 'fingerprint' || true
echo
echo "LANGKAH WAJIB:"
echo "1) CADANGKAN kita.jks dan keystore.properties ke tempat aman (mis. salin ke penyimpanan terenkripsi)."
echo "   Bila hilang, versi berikutnya tidak bisa menimpa versi terpasang."
echo "2) Untuk GitHub Actions, isi 2 rahasia repo (Settings > Secrets and variables > Actions):"
echo "   KEYSTORE_B64      = isi keluaran: base64 -w0 kita.jks"
echo "   KEYSTORE_PASSWORD = sandi di keystore.properties (storePassword)"
echo "   (alias selalu 'kita')"
if [ "${1:-}" = "--tampilkan" ]; then echo; echo "storePassword: $PASS"; fi
