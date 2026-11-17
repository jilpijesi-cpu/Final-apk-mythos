#!/usr/bin/env bash
# Build APK lewat GitHub Actions untuk tool build_apk (MYTHOS): git push, tunggu workflow, unduh APK.
# Pemakaian: bash build-github.sh [folder-proyek]
# Autentikasi memakai token GitHub dari Settings (GH_TOKEN) atau sesi gh auth milik Termux.
# Penanda di baris-baris terakhir keluaran: KITA_BUILD=ok|artifact-only|gagal, KITA_APK=<path>, KITA_ARTIFACT, KITA_WORKFLOW, KITA_TESTS.
set -u
fail() { echo "[build_apk] GAGAL: $*"; echo "KITA_BUILD=gagal"; exit 1; }
say() { echo "[build_apk] $*"; }

cd "${1:-.}" 2>/dev/null || fail "folder proyek tidak ditemukan: ${1:-.}"
MYTHOS_GH_OWNER="${2:-${MYTHOS_GH_OWNER:-}}"
MYTHOS_GH_REPO="${3:-${MYTHOS_GH_REPO:-}}"
MYTHOS_GH_PRIVATE="${4:-${MYTHOS_GH_PRIVATE:-true}}"
MYTHOS_GH_TOKEN="${5:-${MYTHOS_GH_TOKEN:-}}"
if [ "$MYTHOS_GH_TOKEN" = "-" ]; then IFS= read -r MYTHOS_GH_TOKEN || MYTHOS_GH_TOKEN=""; fi
if [ -n "$MYTHOS_GH_TOKEN" ]; then export GH_TOKEN="$MYTHOS_GH_TOKEN"; fi
for t in git gh; do
  command -v "$t" >/dev/null 2>&1 && continue
  say "Memasang $t..."
  if [ -n "${PREFIX:-}" ] && command -v pkg >/dev/null 2>&1; then pkg install -y "$t" >/dev/null 2>&1
  else apt-get install -y --no-install-recommends "$t" >/dev/null 2>&1; fi
  command -v "$t" >/dev/null 2>&1 || fail "tidak bisa memasang $t"
done
# Jelaskan galat gh/GitHub (401/403) dalam bahasa yang bisa ditindaklanjuti. Token tidak pernah dicetak.
explain_gh_error() {
  case "$1" in
    *"HTTP 401"*|*"Bad credentials"*) echo "GitHub menolak token (HTTP 401): token salah, dicabut, atau kedaluwarsa. Buat token baru lalu isi di Pengaturan." ;;
    *[Rr]ate*limit*) echo "GitHub membatasi laju API (HTTP 403 rate limit): tunggu beberapa menit lalu panggil build_apk lagi." ;;
    *SAML*|*saml*) echo "GitHub menolak token (HTTP 403): organisasi memakai SAML SSO. Otorisasi token ke organisasi di pengaturan token GitHub." ;;
    *"HTTP 403"*|*"Resource not accessible"*) echo "GitHub menolak izin token (HTTP 403): token valid tetapi izinnya kurang. Butuh scope repo dan workflow (token klasik), atau akses Contents, Actions, dan Workflows baca/tulis ke repo ini (fine-grained)." ;;
  esac
}
if [ -n "$MYTHOS_GH_TOKEN" ]; then
  AUTH_OUT="$(gh api -i user 2>&1)"
  AUTH_CODE="$(printf '%s\n' "$AUTH_OUT" | sed -n '1s/^HTTP\/[0-9.]* \([0-9][0-9]*\).*/\1/p')"
  if [ "$AUTH_CODE" != 200 ]; then
    AUTH_WHY="$(explain_gh_error "$AUTH_OUT")"
    AUTH_SCOPES="$(printf '%s\n' "$AUTH_OUT" | grep -i '^x-oauth-scopes:' | head -n 1 | cut -d: -f2- | tr -d '\r ')"
    if [ -z "$AUTH_CODE" ]; then AUTH_WHY="tidak bisa menghubungi api.github.com (cek koneksi internet)"; fi
    [ -n "$AUTH_WHY" ] || AUTH_WHY="GitHub menjawab HTTP ${AUTH_CODE} untuk token dari Pengaturan"
    [ -z "$AUTH_SCOPES" ] || AUTH_WHY="$AUTH_WHY (scope token saat ini: $AUTH_SCOPES)"
    fail "$AUTH_WHY"
  fi
  # Token klasik tanpa scope workflow ditolak GitHub saat memperbarui .github/workflows. Token fine-grained tidak punya header ini.
  AUTH_SCOPES="$(printf '%s\n' "$AUTH_OUT" | grep -i '^x-oauth-scopes:' | head -n 1 | cut -d: -f2- | tr -d '\r ')"
  if [ -n "$AUTH_SCOPES" ] && [ -d .github/workflows ]; then
    case ",$AUTH_SCOPES," in *,workflow,*) ;; *) say "PERINGATAN: token klasik tidak punya scope workflow (scope: $AUTH_SCOPES). Push berkas .github/workflows akan ditolak GitHub; tambahkan scope workflow di pengaturan token." ;; esac
  fi
else
  gh auth status >/dev/null 2>&1 || fail "Autentikasi GitHub belum tersedia. Isi Token GitHub di Settings atau jalankan gh auth login di Termux"
fi

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || git init -q || fail "git init gagal"
# Termux baru tidak punya identitas git: commit gagal dan galatnya tersembunyi. Atur identitas LOKAL (hanya repo ini) bila belum ada.
[ -n "$(git config user.name 2>/dev/null)" ] || git config user.name "MYTHOS Agent"
[ -n "$(git config user.email 2>/dev/null)" ] || git config user.email "mythos-agent@users.noreply.github.com"
[ -f .gitignore ] || printf '%s\n' build/ .gradle/ local.properties '*.apk' keystore.properties '*.jks' '*.keystore' '*.p12' '*.pfx' '*.pem' '*.key' '.env' '.env.*' '.ssh/' '.aws/' '.gnupg/' '.kita-*' > .gitignore

# Penjaga rahasia (GAGAL TERTUTUP): kunci, token, dan kredensial tidak boleh ikut commit/push.
# 1) Berkas bernama rahasia dikeluarkan dari staging; bila sudah terlanjur dilacak git (ada di HEAD) build dihentikan.
# 2) Isi semua berkas yang akan dikirim dipindai pola kunci privat/token; bila ada yang cocok build dihentikan.
is_secret_path() {
  local b="${1##*/}"
  case "$1" in
    .ssh/*|*/.ssh/*|.aws/*|*/.aws/*|.gnupg/*|*/.gnupg/*|.kita-secrets/*|*/.kita-secrets/*|.config/gh/*|*/.config/gh/*|keystore/*|*/keystore/*) return 0 ;;
  esac
  case "$b" in
    .env.example|.env.sample|.env.template) return 1 ;;
    keystore.properties|local.properties|.kita-build.sh|*.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*.ppk|*.gpg) return 0 ;;
    .env|.env.*|*.env) return 0 ;;
    id_rsa|id_rsa.*|id_dsa|id_dsa.*|id_ecdsa|id_ecdsa.*|id_ed25519|id_ed25519.*) return 0 ;;
    .netrc|_netrc|.npmrc|.pypirc|.git-credentials|credentials|credentials.json|*-credentials.json|*_credentials.json) return 0 ;;
    service-account*.json|service_account*.json|oauth_creds.json|auth.json) return 0 ;;
  esac
  return 1
}
git add -A
SECRET_BAD=0
while IFS= read -r -d '' f; do
  is_secret_path "$f" || continue
  if git cat-file -e "HEAD:$f" 2>/dev/null; then
    say "BERBAHAYA: berkas rahasia sudah dilacak git: $f (hapus dari repo dengan git rm --cached, lalu putar ulang kuncinya)"
    SECRET_BAD=1
  else
    git rm -q --cached -f -- "$f" >/dev/null 2>&1 || true
    say "tidak dikirim (berkas rahasia): $f"
  fi
done < <(git ls-files -z --cached)
SECRET_RE='-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{36}|ghs_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{40,}'
SECRET_FILES="$(git grep --cached -I -l -E -e "$SECRET_RE" 2>/dev/null || true)"
if [ -n "$SECRET_FILES" ]; then
  say "BERBAHAYA: pola kunci privat atau token terdeteksi di berkas berikut (isinya tidak dicetak):"
  printf '%s\n' "$SECRET_FILES" | head -n 20
  SECRET_BAD=1
fi
if [ "$SECRET_BAD" != 0 ]; then
  git rm -r -q --cached -f . >/dev/null 2>&1 || true
  fail "push dibatalkan: ada rahasia di proyek. Keluarkan atau hapus berkas itu (dan putar ulang kuncinya bila pernah terkirim), lalu ulangi."
fi
say "berkas yang akan dikirim: $(git diff --cached --name-only | wc -l | tr -d ' ') berubah dari total $(git ls-files | wc -l | tr -d ' ')"
git diff --cached --name-status | head -n 50
if ! git diff --cached --quiet 2>/dev/null; then
  COMMIT_ERR="$(git commit -qm "build dari agen" 2>&1)" || fail "git commit gagal: $(printf '%s' "$COMMIT_ERR" | head -n 3 | tr '\n' ' ')"
fi
git rev-parse --verify -q HEAD >/dev/null 2>&1 || fail "repo belum punya commit (tidak ada berkas yang bisa dikirim). Pastikan folder proyek berisi berkas."
gh auth setup-git >/dev/null 2>&1 || true
# Jalankan push, cetak galat aslinya (token disamarkan) dan beri petunjuk untuk penolakan scope workflow.
do_push() {
  local out
  out="$("$@" 2>&1)" && return 0
  printf '%s\n' "$out" | sed -E 's/(ghp_|gho_|ghs_|github_pat_)[A-Za-z0-9_]+/[token disembunyikan]/g' | tail -n 8
  case "$out" in
    *workflow*scope*) say "Petunjuk: token tidak punya scope workflow. Buat token klasik dengan scope repo + workflow, atau token fine-grained dengan Workflows: baca/tulis." ;;
  esac
  return 1
}
if git remote get-url origin >/dev/null 2>&1; then
  do_push git push -q -u origin HEAD || fail "git push gagal (cek koneksi dan izin tulis ke repo)"
else
  [ -n "${MYTHOS_GH_OWNER:-}" ] && [ -n "${MYTHOS_GH_REPO:-}" ] || fail "Link repository GitHub belum diisi di Pengaturan"
  if gh repo view "$MYTHOS_GH_OWNER/$MYTHOS_GH_REPO" >/dev/null 2>&1; then
    git remote add origin "https://github.com/$MYTHOS_GH_OWNER/$MYTHOS_GH_REPO.git"
    do_push git push -q -u origin HEAD || fail "git push ke $MYTHOS_GH_OWNER/$MYTHOS_GH_REPO gagal (cek izin token; repo yang sudah berisi proyek lain juga bisa menolak)"
  else
  VIS="${MYTHOS_GH_PRIVATE:-true}"
  if [ "$VIS" = "true" ]; then VIS_FLAG="--private"; else VIS_FLAG="--public"; fi
  ERR="$(mktemp)"
  if ! gh repo create "$MYTHOS_GH_OWNER/$MYTHOS_GH_REPO" $VIS_FLAG --source . --remote origin --push > /dev/null 2>"$ERR"; then
    WHY="$(tr '\n' ' ' < "$ERR" | sed -E 's/(ghp_|github_pat_)[A-Za-z0-9_]+/[token disembunyikan]/g' | cut -c1-300)"
    rm -f "$ERR"
    fail "gagal membuat repository GitHub baru: $WHY"
  fi
  rm -f "$ERR"
  fi
fi
SHA="$(git rev-parse HEAD)"
say "terkirim: ${SHA:0:7}; menunggu workflow GitHub Actions..."

ID=""; LISTERR=""
for _ in $(seq 1 24); do
  R="$(gh run list --commit "$SHA" --limit 1 --json databaseId --jq '.[0].databaseId' 2>&1)"
  case "$R" in
    ''|*[!0-9]*) [ -z "$R" ] || LISTERR="$R" ;;
    *) ID="$R" ;;
  esac
  [ -n "$ID" ] && break
  # 401/403 tidak akan sembuh dengan menunggu: berhenti dan jelaskan penyebabnya.
  [ -z "$(explain_gh_error "$LISTERR")" ] || fail "$(explain_gh_error "$LISTERR")"
  sleep 5
done
[ -n "$ID" ] || fail "workflow tidak muncul. Cek .github/workflows/build-apk.yml dan tab Actions di GitHub"

# Ambil log langkah yang gagal. Koneksi HP bisa putus sesaat: coba ulang 3 kali, lalu cadangan
# (nama langkah yang gagal + tautan), supaya penyebab kegagalan tidak hilang.
faillog() {
  local n="$1" t out
  for t in 1 2 3; do
    if out="$(gh run view "$ID" --log-failed 2>&1)" && [ -n "$out" ]; then printf '%s\n' "$out" | tail -n "$n"; return 0; fi
    sleep 3
  done
  say "Log lengkap tidak bisa diambil (koneksi putus?). Langkah yang gagal:"
  gh run view "$ID" --json jobs --jq '.jobs[].steps[] | select(.conclusion=="failure") | "- " + .name' 2>&1 | head -n 10
  say "Buka di browser: $(gh run view "$ID" --json url --jq .url 2>/dev/null)"
}

BAD=0
while :; do
  if S="$(gh run view "$ID" --json status,conclusion --jq '.status + " " + .conclusion' 2>&1)"; then BAD=0
  else
    BAD=$((BAD+1))
    [ -z "$(explain_gh_error "$S")" ] || fail "$(explain_gh_error "$S")"
    [ "$BAD" -lt 6 ] || fail "tidak bisa membaca status workflow berulang kali (cek koneksi internet)"
    S=""
  fi
  say "run $ID: ${S:-tidak diketahui}"
  case "$S" in completed*) break ;; esac
  sleep 20
done

OUT=app/build/outputs/apk/debug
rm -rf "$OUT"; mkdir -p "$OUT"
GOT=0
DLERR=""
for _ in 1 2 3; do
  if DLERR="$(gh run download "$ID" -n mythos-debug -D "$OUT" 2>&1)" && ls "$OUT"/*.apk >/dev/null 2>&1; then GOT=1; break; fi
  sleep 3
done
if [ "$GOT" = 1 ]; then
  # Tiga status DIPISAH: artifact APK ada, workflow selesai, tes unit lulus. "ok/siap" hanya bila workflow sukses
  # (berarti semua langkah wajib, termasuk tes unit, lulus). Selain itu APK hanya "dibuat", tidak siap dipasang.
  WF="${S#completed }"
  if [ "$WF" = success ]; then TESTS=passed
  else
    TC="$(gh run view "$ID" --json jobs --jq '.jobs[].steps[] | select(.name=="Jalankan tes unit") | .conclusion' 2>/dev/null | head -n 1)"
    case "$TC" in failure) TESTS=failed ;; cancelled) TESTS=cancelled ;; skipped|"") TESTS=unknown ;; *) TESTS="$TC" ;; esac
  fi
  echo "KITA_APK=$(pwd)/$(ls "$OUT"/*.apk | head -n 1)"
  echo "KITA_ARTIFACT=available"
  echo "KITA_WORKFLOW=$WF"
  echo "KITA_TESTS=$TESTS"
  if [ "$WF" = success ] && [ "$TESTS" = passed ]; then
    echo "KITA_BUILD=ok"
  else
    say "APK dibuat TETAPI quality gate tidak lulus (workflow: $WF, tes unit: $TESTS). APK ini TIDAK siap dipakai. Log langkah yang gagal:"
    faillog 40
    echo "KITA_BUILD=artifact-only"
  fi
else
  DLWHY="$(explain_gh_error "$DLERR")"
  [ -z "$DLWHY" ] || say "$DLWHY"
  say "Log langkah yang gagal:"
  faillog 60
  fail "APK tidak dihasilkan"
fi
