#!/usr/bin/env bash
# kita-self.sh: audit diri, baseline, diff, restore, dan bump versi untuk upgrade diri MYTHOS.
# Pemakaian: bash kita-self.sh <audit|baseline|diff|restore|bump> <folder-proyek> [applicationId-yang-diharapkan | versionCode] [versionName]
# Keluaran berupa baris penanda KITA_* yang dibaca SelfAudit.kt (parseSelfOutput). Skrip ini dikirim dari aplikasi
# setiap kali dipakai (bukan dibaca dari folder proyek), jadi agen tidak bisa mengubahnya diam-diam.
# audit dan diff hanya MEMBACA. baseline menulis ke <folder>/.kita-upgrade/. restore menimpa kode dengan cadangan. bump mengubah versi.
# Nilai rahasia TIDAK pernah dicetak: temuan kunci hanya menyebut berkas:baris.
set -u
MODE="${1:-}"
DIR="${2:-.}"
ARG="${3:-}"
ARG2="${4:-}"
cd "$DIR" 2>/dev/null || { echo "KITA_ERR|folder tidak ditemukan: $DIR"; exit 2; }
UP=".kita-upgrade"
TMPF="$(mktemp 2>/dev/null || echo /tmp/kita-self.$$)"
trap 'rm -f "$TMPF"' EXIT

# ---------------------------------------------------------------- pembantu
# Catatan penting: nama paket aplikasi adalah com.build.mythos (path: com/build/mythos).
# Jangan pernah memakai '-name build', '--exclude-dir=build', atau '--exclude=*/build' karena akan memangkas source utama!
list_files() {
  find . \( -path ./build -o -path ./app/build -o -name .git -o -name .gradle -o -name .kita-upgrade -o -name node_modules -o -name .idea \) -prune -o \
    -type f ! -name local.properties ! -name keystore.properties ! -name '*.jks' ! -name '*.keystore' \
    ! -name '*.apk' ! -name '*.log' ! -name '.kita-*' -print 2>/dev/null | sed 's|^\./||' | LC_ALL=C sort
}
hash_files() { list_files | tr '\n' '\0' | xargs -0 sha256sum 2>/dev/null; }
# $1 = berkas hash baseline, $2 = berkas hash sekarang. Format baris: 64 hex + 2 spasi + path.
diff_files() {
  awk 'NR==FNR { b[substr($0, 67)] = substr($0, 1, 64); next }
       { p = substr($0, 67); c[p] = substr($0, 1, 64) }
       END {
         for (p in c) { if (!(p in b)) print "A|" p; else if (b[p] != c[p]) print "M|" p }
         for (p in b) if (!(p in c)) print "D|" p
       }' "$1" "$2" | LC_ALL=C sort
}
is_git() { command -v git >/dev/null 2>&1 && git rev-parse --is-inside-work-tree >/dev/null 2>&1; }
count_lines() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | wc -l | tr -d ' '; fi; }
app_gradle() { grep -rl --include=build.gradle.kts --include=build.gradle 'versionCode' . 2>/dev/null | grep -vE '^\./(app/)?build/' | head -1; }
find_src() { # $1 = pola path, $2 = pola nama
  find . \( -path ./build -o -path ./app/build -o -name .git -o -name .gradle -o -name .kita-upgrade \) -prune -o -type f -path "$1" -name "$2" -print 2>/dev/null | LC_ALL=C sort
}
emit() { # sev id lokasi pesan
  local m l="${3#./}"
  m="$(printf '%s' "$4" | tr '|\r\n' '/  ' | cut -c1-220)"
  printf 'KITA_AUDIT|%s|%s|%s|%s\n' "$1" "$2" "$l" "$m"
}
meta() { printf 'KITA_META|%s|%s\n' "$1" "$2"; }
progress() { printf 'KITA_PROGRESS|%s|%s\n' "$(date +%H:%M:%S 2>/dev/null)" "$1"; } # tampil live di kartu langkah; parser mengabaikannya
manifest_file() { find_src '*/src/main/*' 'AndroidManifest.xml' | head -1; }
perms() {
  local m; m="$(manifest_file)"
  [ -n "$m" ] || return 0
  tr '\n' ' ' < "$m" | grep -oE '<uses-permission[^>]*>' | grep -oE 'android:name="[^"]+"' | sed 's/^android:name="//; s/"$//' | LC_ALL=C sort -u
}
deps() {
  find . \( -path ./build -o -path ./app/build -o -name .git -o -name .gradle -o -name .kita-upgrade \) -prune -o -type f \( -name '*.gradle.kts' -o -name '*.gradle' \) -print 2>/dev/null | while IFS= read -r f; do
    grep -hE '^[[:space:]]*(implementation|api|kapt|ksp|classpath|testImplementation|androidTestImplementation|debugImplementation)\b' "$f" | grep -oE '"[^"]+"' | tr -d '"' | grep ':'
    grep -hoE 'id\("[^"]+"\)[[:space:]]+version[[:space:]]+"[^"]+"' "$f" | sed -E 's/id\("([^"]+)"\)[[:space:]]+version[[:space:]]+"([^"]+)"/plugin:\1:\2/'
  done | LC_ALL=C sort -u
}
test_count() {
  find_src '*/src/test/*' '*.kt' | tr '\n' '\0' | xargs -0 cat 2>/dev/null | grep -c '@Test'
}

# ---------------------------------------------------------------- audit
do_audit() {
  local EXPECT="$1" APPG VC VN APPID MAIN_KT TEST_KT N_MAIN N_TEST N_TESTS MAN entry name pat loc f b gaps n_gap TEST_WORDS names

  progress "0/8 membaca metadata proyek"
  APPG="$(app_gradle)"
  VC="$(grep -hoE 'versionCode[ =]+[0-9]+' "$APPG" 2>/dev/null | head -1 | grep -oE '[0-9]+$')"
  VN="$(grep -hoE 'versionName[ =]+"[^"]*"' "$APPG" 2>/dev/null | head -1 | sed 's/^[^"]*"//; s/"$//')"
  APPID="$(grep -hoE 'applicationId[ =]+"[^"]*"' "$APPG" 2>/dev/null | head -1 | sed 's/^[^"]*"//; s/"$//')"
  MAIN_KT="$(find_src '*/src/main/*' '*.kt')"
  TEST_KT="$(find_src '*/src/test/*' '*.kt'; find_src '*/src/androidTest/*' '*.kt')"
  TEST_KT="$(printf '%s\n' "$TEST_KT" | sed '/^$/d')"
  N_MAIN="$(count_lines "$MAIN_KT")"
  N_TEST="$(count_lines "$TEST_KT")"
  N_TESTS="$(test_count)"
  meta app_gradle "${APPG#./}"
  meta version_code "${VC:-}"
  meta version_name "${VN:-}"
  meta app_id "${APPID:-}"
  meta compile_sdk "$(grep -hoE 'compileSdk[ =]+[0-9]+' "$APPG" 2>/dev/null | head -1 | grep -oE '[0-9]+$')"
  meta target_sdk "$(grep -hoE 'targetSdk[ =]+[0-9]+' "$APPG" 2>/dev/null | head -1 | grep -oE '[0-9]+$')"
  meta kt_main "$N_MAIN"
  meta kt_test "$N_TEST"
  meta tests "${N_TESTS:-0}"
  # APK harus membawa source dirinya (tugas Gradle packSource -> assets/mythos-source.zip); tanpa ini upgrade tidak menyegarkan source.
  if grep -q 'packSource' "$APPG" 2>/dev/null && grep -q 'mythos-source.zip' "$APPG" 2>/dev/null; then meta packs_source yes; else meta packs_source no; fi
  if is_git; then
    meta git yes
    meta git_head "$(git rev-parse --short HEAD 2>/dev/null)"
    meta git_branch "$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"
    meta git_dirty "$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
  else
    meta git no
  fi
  [ -n "$APPG" ] || emit high S07 - "build.gradle aplikasi (berisi versionCode) tidak ditemukan: ini bukan proyek aplikasi Android"
  [ -n "$VC" ] || emit med S07 "${APPG#./}" "versionCode tidak terbaca"

  progress "1/8 memindai rahasia di kode"
  # S01 rahasia di kode: hanya lokasi yang dicetak
  local SECRETS=(
    'kunci Google API~AIza[0-9A-Za-z_-]{35}'
    'kunci Anthropic~sk-ant-[A-Za-z0-9_-]{20,}'
    'kunci gaya OpenAI~sk-[A-Za-z0-9]{32,}'
    'token GitHub~(ghp|gho|ghs)_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}'
    'token Slack~xox[baprs]-[A-Za-z0-9-]{10,}'
    'kunci privat PEM~-----BEGIN [A-Z ]*PRIVATE KEY-----'
  )
  for entry in "${SECRETS[@]}"; do
    name="${entry%%~*}"; pat="${entry#*~}"
    grep -rnIoE "$pat" . --exclude-dir=.git --exclude-dir=outputs --exclude-dir=intermediates --exclude-dir=generated --exclude-dir=tmp --exclude-dir=.gradle --exclude-dir=.kita-upgrade \
      --exclude-dir=node_modules --exclude=kita-self.sh 2>/dev/null | grep -vE '^\./(app/)?build/' | cut -d: -f1,2 | head -5 | while IFS= read -r loc; do
      case "$loc" in
        */src/test/*) emit low S01 "$loc" "pola $name di berkas tes (kemungkinan contoh palsu; nilai tidak ditampilkan)" ;;
        *) emit high S01 "$loc" "pola $name terdeteksi di berkas (nilai tidak ditampilkan)" ;;
      esac
    done
  done

  progress "2/8 memeriksa berkas kunci penandatanganan"
  # S02 berkas kunci penandatanganan
  if is_git; then
    git ls-files 2>/dev/null | grep -E '(\.jks|\.keystore|\.p12)$|(^|/)keystore\.properties$' | head -5 | while IFS= read -r f; do
      emit high S02 "$f" "berkas kunci penandatanganan ikut terlacak git"
    done
  else
    find . \( -path ./build -o -path ./app/build -o -name .git -o -name .gradle \) -prune -o -type f \( -name '*.jks' -o -name '*.keystore' -o -name '*.p12' -o -name keystore.properties \) -print 2>/dev/null | head -3 | while IFS= read -r f; do
      grep -qE 'keystore|\.jks' .gitignore 2>/dev/null || emit med S02 "$f" "berkas kunci ada dan .gitignore tidak menyebut keystore"
    done
  fi

  progress "3/8 memeriksa manifest dan izin"
  # S03 manifest
  MAN="$(manifest_file)"
  if [ -n "$MAN" ]; then
    grep -q 'android:debuggable="true"' "$MAN" && emit high S03 "${MAN#./}" "android:debuggable=true"
    grep -q 'usesCleartextTraffic="true"' "$MAN" && emit med S03 "${MAN#./}" "usesCleartextTraffic=true: HTTP polos diizinkan global"
    grep -q 'allowBackup="false"' "$MAN" || emit low S03 "${MAN#./}" "allowBackup tidak dimatikan: data aplikasi ikut cadangan"
    if tr '\n' ' ' < "$MAN" | grep -oE '<provider[^>]*>' | grep -q 'exported="true"'; then
      emit high S03 "${MAN#./}" "ContentProvider exported=true: aplikasi lain bisa membaca isinya"
    fi
    emit info S03 "${MAN#./}" "$(grep -c 'android:exported="true"' "$MAN") komponen exported=true"
  else
    emit med S03 - "AndroidManifest.xml tidak ditemukan"
  fi
  perms | sed 's/^/KITA_PERM|/'
  deps | sed 's/^/KITA_DEP|/'

  progress "4/8 memeriksa build dan CI"
  # S07 build dan CI
  if [ -f gradlew ]; then
    [ -x gradlew ] || emit med S07 gradlew "gradlew tidak executable (chmod +x gradlew)"
    [ -f gradle/wrapper/gradle-wrapper.jar ] || emit med S07 gradle/wrapper "gradle-wrapper.jar tidak ada"
  else
    emit high S07 - "gradlew tidak ada: proyek tidak punya Gradle wrapper"
  fi
  ls .github/workflows/*.yml >/dev/null 2>&1 || emit low S07 .github/workflows "tidak ada workflow CI: build resmi tidak terverifikasi di luar HP"

  progress "5/8 memeriksa fungsi pengaman"
  # S08 penjaga diri: fungsi pengaman harus tetap ada (hanya bila ini kode aplikasi yang diharapkan)
  if [ -n "$EXPECT" ] && [ "$APPID" = "$EXPECT" ]; then
    local CANARY=(
      'CommandPolicy.kt~fun assessCommand'
      'CommandPolicy.kt~fun bypassesApproval'
      'CommandPolicy.kt~fun needsExtraCare'
      'AgentGuard.kt~fun verificationNudge'
      'ToolExecution.kt~fun MainActivity.confirm'
      'Secret.kt~fun encrypt'
      'ApkInstall.kt~fun MainActivity.installApk'
      'SelfUpgrade.kt~fun evaluateUpgrade'
      'ApkVerify.kt~fun evaluateApk'
    )
    for entry in "${CANARY[@]}"; do
      name="${entry%%~*}"; pat="${entry#*~}"
      f="$(find_src '*/src/main/*' "$name" | head -1)"
      if [ -z "$f" ]; then emit high S08 "$name" "berkas pengaman hilang: $name"
      elif ! grep -qF "$pat" "$f"; then emit high S08 "${f#./}" "fungsi pengaman hilang: $pat"
      fi
    done
  fi
  grep -rnE 'getBoolean\("autonomous", *true' --include='*.kt' --exclude-dir=test --exclude-dir=androidTest --exclude-dir=outputs --exclude-dir=intermediates . 2>/dev/null | grep -vE '^\./(app/)?build/' | cut -d: -f1,2 | head -3 | while IFS= read -r loc; do
    emit high S08 "$loc" "default mode otonom menjadi true (regresi temuan H-01)"
  done

  progress "6/8 mencari pola kode berisiko"
  # S09 pola kode berisiko
  grep -rnE 'MODE_WORLD_(READABLE|WRITEABLE)|addJavascriptInterface' --include='*.kt' --exclude-dir=test --exclude-dir=outputs --exclude-dir=intermediates . 2>/dev/null | grep -vE '^\./(app/)?build/' | cut -d: -f1,2 | head -3 | while IFS= read -r loc; do
    emit high S09 "$loc" "pola berbahaya (MODE_WORLD_* atau addJavascriptInterface)"
  done
  grep -rnE 'setJavaScriptEnabled\(true' --include='*.kt' --exclude-dir=test --exclude-dir=outputs --exclude-dir=intermediates . 2>/dev/null | grep -vE '^\./(app/)?build/' | cut -d: -f1,2 | head -3 | while IFS= read -r loc; do
    emit med S09 "$loc" "JavaScript diaktifkan di WebView"
  done
  grep -rnE '"http://[A-Za-z0-9]' --include='*.kt' --exclude-dir=test --exclude-dir=outputs --exclude-dir=intermediates . 2>/dev/null \
    | grep -vE '^\./(app/)?build/|localhost|127\.0\.0\.1|10\.0\.2\.2|schemas\.android|w3\.org' | cut -d: -f1,2 | head -5 | while IFS= read -r loc; do
    emit med S09 "$loc" "URL http:// tanpa TLS"
  done
  grep -rniE 'Log\.[a-z]\([^)]*(api_?key|token|secret|password)' --include='*.kt' --exclude-dir=test --exclude-dir=outputs --exclude-dir=intermediates . 2>/dev/null | grep -vE '^\./(app/)?build/' | cut -d: -f1,2 | head -3 | while IFS= read -r loc; do
    emit med S09 "$loc" "log mungkin memuat rahasia"
  done

  progress "7/8 memeriksa cakupan tes"
  # S04 tes: berkas logika murni (tanpa import android.*) yang tidak satu pun deklarasi tingkat atasnya dirujuk tes
  if [ -z "$TEST_KT" ]; then
    emit high S04 - "tidak ada berkas tes sama sekali"
  else
    TEST_WORDS="$(printf '%s\n' "$TEST_KT" | tr '\n' '\0' | xargs -0 cat 2>/dev/null | grep -oE '[A-Za-z_][A-Za-z0-9_]{3,}' | LC_ALL=C sort -u)"
    gaps=""; n_gap=0
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      grep -q 'import android\.' "$f" && continue
      b="$(basename "$f" .kt)"
      names="$(grep -hoE '^(internal |public )?(suspend )?(data |enum |sealed )?(fun|class|object|interface) +(<[^>]*> *)?([A-Za-z0-9_]+\.)?[A-Za-z0-9_]+' "$f" | sed -E 's/.* //; s/.*\.//' | awk 'length($0) >= 4')"
      names="$(printf '%s\n%s\n' "$b" "$names")"
      printf '%s\n' "$names" | grep -qxFf <(printf '%s\n' "$TEST_WORDS") && continue
      n_gap=$((n_gap + 1))
      [ "$n_gap" -le 10 ] && gaps="$gaps$b, "
    done <<EOF
$MAIN_KT
EOF
    [ "$n_gap" -gt 0 ] && emit low S04 - "$n_gap berkas logika murni tanpa rujukan di tes: ${gaps%, }"
  fi

  progress "8/8 menghitung ukuran dan utang teknis"
  # S05 ukuran, S06 utang teknis
  printf '%s\n' "$MAIN_KT" | tr '\n' '\0' | xargs -0 wc -l 2>/dev/null | grep -v ' total$' | sort -rn | awk '$1 > 800' | head -4 | while read -r n f; do
    emit low S05 "${f#./}" "berkas $n baris (mudah rapuh saat diubah agen; pecah bila memungkinkan)"
  done
  local n_sleep n_todo
  n_sleep="$(printf '%s\n' "$MAIN_KT" | tr '\n' '\0' | xargs -0 cat 2>/dev/null | grep -c 'Thread\.sleep(')"
  n_todo="$(printf '%s\n' "$MAIN_KT" | tr '\n' '\0' | xargs -0 cat 2>/dev/null | grep -cE 'TODO|FIXME|HACK')"
  [ "${n_sleep:-0}" -gt 0 ] && emit low S06 - "$n_sleep pemakaian Thread.sleep di kode utama"
  [ "${n_todo:-0}" -gt 0 ] && emit low S06 - "$n_todo penanda TODO/FIXME/HACK"
  echo "KITA_AUDIT_DONE"
}

# ---------------------------------------------------------------- baseline / diff / restore / bump
do_baseline() {
  local ts snap
  mkdir -p "$UP" || { echo "KITA_ERR|tidak bisa membuat $UP"; exit 2; }
  ts="$(date +%Y%m%d-%H%M%S)"
  snap="snap-$ts.tgz"
  hash_files > "$UP/baseline.files.tmp"
  [ -s "$UP/baseline.files.tmp" ] || { echo "KITA_ERR|tidak ada berkas sumber untuk dicatat"; exit 2; }
  do_audit "$ARG" > "$UP/baseline.audit.tmp"
  tar czf "$UP/$snap" --exclude=./.git --exclude=./.gradle --exclude=./.kita-upgrade --exclude=./build --exclude=./app/build \
    --exclude=./local.properties --exclude=./keystore.properties --exclude='*.jks' --exclude='*.keystore' --exclude='*.apk' . 2>/dev/null
  tar tzf "$UP/$snap" >/dev/null 2>&1 || { rm -f "$UP/$snap"; echo "KITA_ERR|cadangan kode gagal dibuat atau rusak (cek ruang penyimpanan)"; exit 2; }
  mv "$UP/baseline.files.tmp" "$UP/baseline.files"
  mv "$UP/baseline.audit.tmp" "$UP/baseline.audit"
  printf '%s' "$snap" > "$UP/baseline.snap"
  ls -1t "$UP"/snap-*.tgz 2>/dev/null | tail -n +4 | tr '\n' '\0' | xargs -0 rm -f 2>/dev/null
  echo "KITA_BASELINE=ok"
  echo "KITA_SNAPSHOT=$snap"
  echo "KITA_BASEHASH=$(cat "$UP/baseline.files" "$UP/baseline.audit" | sha256sum | cut -d' ' -f1)"
  cat "$UP/baseline.audit"
}

newer_than() { # $1 = berkas acuan; cetak yes bila ada berkas sumber yang lebih baru
  local f
  while IFS= read -r f; do
    if [ "$f" -nt "$1" ]; then echo yes; return; fi
  done < <(list_files)
  echo no
}

do_diff() {
  [ -f "$UP/baseline.files" ] && [ -f "$UP/baseline.audit" ] || { echo "KITA_ERR|baseline belum ada: jalankan self_upgrade tahap=mulai dulu"; exit 3; }
  echo "KITA_BASEHASH=$(cat "$UP/baseline.files" "$UP/baseline.audit" | sha256sum | cut -d' ' -f1)"
  hash_files > "$TMPF"
  diff_files "$UP/baseline.files" "$TMPF" | sed 's/^/KITA_CHG|/'
  do_audit "$ARG"
  sed 's/^KITA_/KITA_BASE_/' "$UP/baseline.audit"
  local line apk st R newest ran bad
  line="$(find . -path '*/build/outputs/apk/*' -name '*.apk' -not -path './.git/*' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -1)"
  if [ -n "$line" ]; then
    apk="${line#* }"
    st=fresh; [ "$(newer_than "$apk")" = yes ] && st=stale
    echo "KITA_APK|$(pwd)/${apk#./}|$st"
  else
    echo "KITA_APK|none|none"
  fi
  R="$(find . -path '*/build/test-results/*' -name 'TEST-*.xml' 2>/dev/null)"
  if [ -z "$R" ]; then
    echo "KITA_TESTS|none|0|0"
  else
    ran="$(printf '%s\n' "$R" | tr '\n' '\0' | xargs -0 grep -ho 'tests="[0-9]*"' 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')"
    bad="$(printf '%s\n' "$R" | tr '\n' '\0' | xargs -0 grep -hoE '(failures|errors)="[0-9]+"' 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')"
    newest="$(printf '%s\n' "$R" | tr '\n' '\0' | xargs -0 ls -t 2>/dev/null | head -1)"
    st=fresh; [ "$(newer_than "$newest")" = yes ] && st=stale
    echo "KITA_TESTS|$st|$ran|$bad"
  fi
  echo "KITA_DIFF=ok"
}

do_restore() {
  local snap removed remaining
  [ -f "$UP/baseline.snap" ] || { echo "KITA_ERR|tidak ada cadangan (baseline.snap)"; exit 3; }
  snap="$UP/$(cat "$UP/baseline.snap")"
  [ -f "$snap" ] || { echo "KITA_ERR|berkas cadangan hilang: $snap"; exit 3; }
  tar tzf "$snap" >/dev/null 2>&1 || { echo "KITA_ERR|berkas cadangan rusak: $snap"; exit 3; }
  hash_files > "$TMPF"
  removed=0
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    rm -f -- "$f" && removed=$((removed + 1))
  done < <(diff_files "$UP/baseline.files" "$TMPF" | grep '^A|' | cut -d'|' -f2-)
  # -m: waktu ubah = sekarang, jadi APK lama otomatis dianggap kedaluwarsa dan build ulang wajib
  tar xzmf "$snap" -C . 2>/dev/null || { echo "KITA_ERR|gagal mengekstrak cadangan"; exit 3; }
  hash_files > "$TMPF"
  remaining="$(diff_files "$UP/baseline.files" "$TMPF" | wc -l | tr -d ' ')"
  if [ "$remaining" = 0 ]; then echo "KITA_RESTORE=ok|$removed"; else echo "KITA_RESTORE=partial|$remaining"; fi
}

do_bump() {
  local g
  g="$(app_gradle)"
  [ -n "$g" ] || { echo "KITA_ERR|build.gradle aplikasi tidak ditemukan"; exit 2; }
  case "$ARG" in ''|*[!0-9]*) echo "KITA_ERR|versionCode tidak valid"; exit 2 ;; esac
  sed -i -E "s/(versionCode[ =]+)[0-9]+/\1${ARG}/" "$g"
  if [ -n "$ARG2" ]; then
    case "$ARG2" in *[!A-Za-z0-9._-]*) echo "KITA_ERR|versionName tidak valid"; exit 2 ;; esac
    sed -i -E "s/(versionName[ =]+\")[^\"]*\"/\1${ARG2}\"/" "$g"
  fi
  echo "KITA_BUMP=ok|${g#./}|$(grep -hoE 'versionCode[ =]+[0-9]+' "$g" | head -1)"
}

case "$MODE" in
  audit) do_audit "$ARG" ;;
  baseline) do_baseline ;;
  diff) do_diff ;;
  restore) do_restore ;;
  bump) do_bump ;;
  *) echo "KITA_ERR|mode tidak dikenal: $MODE"; exit 2 ;;
esac
