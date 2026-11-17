#!/usr/bin/env bash
# Pembersih folder kerja MYTHOS. Default: HANYA MENAMPILKAN daftar (dry-run).
# Hapus sungguhan: bash bersihkan-mythos.sh --hapus
# Tidak pernah menyentuh: reference/mythos-*, reference/automatic-couscous-2, reference/alat, projects/aktif, projects/arsip, logs.
set -u
M="${MYTHOS_ROOT:-$HOME/.mythos}"
[ -d "$M" ] || M="$HOME/MYTHOS"
HAPUS=0; [ "${1:-}" = "--hapus" ] && HAPUS=1
[ -d "$M" ] || { echo "Folder tidak ada: $M"; exit 1; }

# Syarat aman: referensi baru harus sudah terpasang (versionCode >= 19) sebelum salinan lama dihapus.
REF_GRADLE="$(ls -1t "$M"/reference/mythos-*/app/build.gradle.kts "$M/reference/automatic-couscous-2/app/build.gradle.kts" 2>/dev/null | head -1)"
VC="$(grep -hoE 'versionCode *= *[0-9]+' "${REF_GRADLE:-/dev/null}" 2>/dev/null | grep -oE '[0-9]+$' | head -1)"
echo "versionCode referensi saat ini: ${VC:-tidak ada}"

C=()
# 1. Salinan referensi lama (hanya bila referensi baru sudah terpasang)
if [ "${VC:-0}" -ge 19 ]; then
  for d in "$M"/reference/MYTHOS-v1-lama-*; do [ -e "$d" ] && C+=("$d"); done
else
  echo "Lewati salinan lama: referensi baru (>=19) belum terpasang."
fi
# 2. Lampiran lebih dari 7 hari
while IFS= read -r f; do C+=("$f"); done < <(find "$M/attachments" "$M/chats" -path '*/attachments/*' -type f -mtime +7 2>/dev/null)
# 3. Cadangan undo lebih dari 30 hari
while IFS= read -r f; do C+=("$f"); done < <(find "$M/backups" "$M/chats" -path '*/backups/*' -type f -mtime +30 2>/dev/null)
# 4. File tmp sisa tampilan live (lebih dari 1 hari)
while IFS= read -r f; do C+=("$f"); done < <(find "${TMPDIR:-${PREFIX:-/data/data/com.termux/files/usr}/tmp}" -maxdepth 1 -name 'kita_live_*' -mtime +1 2>/dev/null)

if [ ${#C[@]} -eq 0 ]; then echo "Tidak ada yang perlu dibersihkan."; exit 0; fi
echo "Kandidat (${#C[@]}):"
for x in "${C[@]}"; do printf '  %s (%s)\n' "$x" "$(du -sh "$x" 2>/dev/null | cut -f1)"; done
if [ $HAPUS -eq 1 ]; then
  for x in "${C[@]}"; do chmod -R u+w "$x" 2>/dev/null; rm -rf -- "$x"; done
  echo "Selesai dihapus."
else
  echo "Ini hanya daftar. Untuk menghapus: bash bersihkan-mythos.sh --hapus"
fi
