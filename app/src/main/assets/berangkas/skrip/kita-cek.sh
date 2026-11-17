#!/usr/bin/env bash
# kita-cek.sh - cek kesiapan alat agen AI di Termux.
# HANYA MEMBACA: tidak menulis file apa pun, tidak menyentuh folder kerja (~/MYTHOS).
# Pemakaian:
#   bash kita-cek.sh              -> cek alat Termux ("Aplikasi siap")
#   bash kita-cek.sh <folder>     -> ditambah cek proyek ("Proyek siap")
# Penanda di baris terakhir: KITA_SIAP=ok|belum  dan  KITA_PROYEK=ok|belum|lewati

GAGAL=0      # jumlah syarat wajib yang belum terpenuhi
PERINGATAN=0 # jumlah syarat opsional yang belum terpenuhi

ok()   { echo "✅ $*"; }
bad()  { echo "❌ $*"; GAGAL=$((GAGAL + 1)); }
warn() { echo "⚠️  $*"; PERINGATAN=$((PERINGATAN + 1)); }
info() { echo "   $*"; }

ada() { command -v "$1" >/dev/null 2>&1; }

echo "== Aplikasi siap? (alat di Termux) =="

# 1. Lingkungan Termux
if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ]; then
  ok "Termux terdeteksi"
else
  bad "Ini bukan Termux (variabel PREFIX tidak ada)"
fi

# 2. Alat wajib
for t in git curl; do
  if ada "$t"; then
    ok "$t: $("$t" --version 2>&1 | head -1)"
  else
    bad "$t belum terpasang -> pkg install $t"
  fi
done

# gh (GitHub CLI) dibutuhkan untuk build lewat GitHub Actions
if ada gh; then
  ok "gh: $(gh --version 2>&1 | head -1)"
else
  bad "gh belum terpasang -> pkg install gh"
fi

# Java 17 (untuk Gradle bila build lokal / validasi)
if ada java; then
  jv="$(java -version 2>&1 | head -1)"
  major="$(echo "$jv" | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/')"
  case "$major" in
    ''|*[!0-9]*) warn "Java terpasang tapi versinya tidak terbaca: $jv" ;;
    *) if [ "$major" -ge 17 ]; then ok "java $major"; else warn "java $major terlalu lama, butuh 17 -> pkg install openjdk-17"; fi ;;
  esac
else
  warn "java belum terpasang (perlu untuk build lokal) -> pkg install openjdk-17"
fi

# 3. Alat pendukung (opsional)
for t in node zip unzip jq python; do
  if ada "$t"; then ok "$t tersedia"; else warn "$t belum terpasang -> pkg install $t"; fi
done
if ada ollama; then ok "ollama tersedia (model lokal)"; else info "ollama tidak terpasang (opsional, hanya untuk model lokal)"; fi

# 4. Jembatan Termux (agar aplikasi bisa menjalankan perintah)
props="$HOME/.termux/termux.properties"
if [ -f "$props" ] && grep -Eq '^[[:space:]]*allow-external-apps[[:space:]]*=[[:space:]]*true' "$props"; then
  ok "Izin aplikasi luar (allow-external-apps) aktif"
else
  bad "Izin aplikasi luar belum aktif. Tambahkan 'allow-external-apps = true' di ~/.termux/termux.properties lalu mulai ulang Termux"
fi

# 5. Login GitHub
if ada gh; then
  if [ -n "${GH_TOKEN:-}" ] || [ -n "${GITHUB_TOKEN:-}" ]; then
    ok "Token GitHub tersedia lewat variabel lingkungan"
  elif gh auth status >/dev/null 2>&1; then
    ok "gh sudah login ke GitHub"
  else
    warn "gh belum login. Aplikasi memakai token dari Pengaturan saat build; untuk uji manual: gh auth login"
  fi
fi

# 6. Internet
for u in https://github.com https://openrouter.ai; do
  code="$(curl -s -o /dev/null -m 8 -w '%{http_code}' "$u" 2>/dev/null)"
  case "$code" in
    2*|3*) ok "Bisa menjangkau $u" ;;
    *) warn "Tidak bisa menjangkau $u (kode: ${code:-gagal})" ;;
  esac
done

# 7. Ruang penyimpanan (minimal 2 GB bebas untuk build)
kb="$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2 {print $4}')"
case "$kb" in
  ''|*[!0-9]*) warn "Ruang bebas tidak terbaca" ;;
  *) mb=$((kb / 1024))
     if [ "$mb" -ge 2048 ]; then ok "Ruang bebas: ${mb} MB"; else warn "Ruang bebas tinggal ${mb} MB (disarankan minimal 2048 MB)"; fi ;;
esac

# 8. Folder kerja: hanya dilaporkan, tidak dipakai menilai kesiapan aplikasi
kerja="$HOME/MYTHOS"
if [ -d "$kerja" ]; then
  n="$(ls -A "$kerja" 2>/dev/null | wc -l | tr -d ' ')"
  info "Folder kerja ~/MYTHOS berisi $n item (tidak memengaruhi kesiapan aplikasi)"
else
  info "Folder kerja ~/MYTHOS belum ada (tidak masalah)"
fi

if [ "$GAGAL" -eq 0 ]; then
  echo "KESIMPULAN: Aplikasi SIAP ($PERINGATAN peringatan)."
  APP=ok
else
  echo "KESIMPULAN: Aplikasi BELUM siap ($GAGAL syarat wajib belum terpenuhi, $PERINGATAN peringatan)."
  APP=belum
fi

# ---------- Bagian proyek (hanya bila folder diberikan) ----------
PROYEK=lewati
if [ -n "${1:-}" ]; then
  echo
  echo "== Proyek siap? ($1) =="
  if [ ! -d "$1" ]; then
    echo "❌ Folder proyek tidak ditemukan: $1"
    PROYEK=belum
  else
    pg=0
    [ -f "$1/gradlew" ] && ok "gradlew ada" || { echo "❌ gradlew tidak ada"; pg=1; }
    [ -f "$1/gradlew" ] && [ ! -x "$1/gradlew" ] && { warn "gradlew belum executable -> chmod +x gradlew"; }
    [ -f "$1/settings.gradle.kts" ] || [ -f "$1/settings.gradle" ] && ok "settings.gradle ada" || { echo "❌ settings.gradle tidak ada"; pg=1; }
    [ -d "$1/app" ] && ok "folder app ada" || { echo "❌ folder app tidak ada"; pg=1; }
    if [ -n "$(find "$1/.github/workflows" -maxdepth 1 \( -name '*.yml' -o -name '*.yaml' \) 2>/dev/null | head -1)" ]; then
      ok "workflow CI ada"
    else
      echo "❌ workflow CI (.github/workflows) tidak ada"; pg=1
    fi
    [ -d "$1/.git" ] && ok "repo git ada" || warn "belum ada repo git (dibuat otomatis saat build_apk)"
    if [ "$pg" -eq 0 ]; then PROYEK=ok; else PROYEK=belum; fi
  fi
fi

echo "KITA_SIAP=$APP"
echo "KITA_PROYEK=$PROYEK"
[ "$APP" = ok ]
