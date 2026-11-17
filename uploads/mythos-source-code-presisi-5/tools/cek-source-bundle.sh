#!/usr/bin/env bash
# Periksa bahwa assets/mythos-source.zip di dalam APK TIDAK membawa rahasia (nama berkas maupun isi). Gagal tertutup.
# Pemakaian: bash tools/cek-source-bundle.sh <path-apk>
# Dipakai CI (build-apk.yml) bersama fixture rahasia palsu; juga aman dijalankan manual.
set -euo pipefail
APK="${1:?pemakaian: cek-source-bundle.sh <path-apk>}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
unzip -p "$APK" assets/mythos-source.zip > "$T/src.zip" 2>/dev/null || { echo "::error::APK tidak membawa assets/mythos-source.zip"; exit 1; }
mkdir "$T/x"; unzip -q "$T/src.zip" -d "$T/x"

is_secret_path() {
  local b="${1##*/}"
  case "$1" in
    .ssh/*|*/.ssh/*|.aws/*|*/.aws/*|.gnupg/*|*/.gnupg/*|.kita-secrets/*|*/.kita-secrets/*|.config/gh/*|*/.config/gh/*|keystore/*|*/keystore/*) return 0 ;;
  esac
  case "$b" in
    keystore.properties|local.properties|.kita-build.sh|*.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*.ppk|*.gpg) return 0 ;;
    .env|.env.*|*.env) return 0 ;;
    id_rsa|id_rsa.*|id_dsa|id_dsa.*|id_ecdsa|id_ecdsa.*|id_ed25519|id_ed25519.*) return 0 ;;
    .netrc|_netrc|.npmrc|.pypirc|.git-credentials|credentials|credentials.json|*-credentials.json|*_credentials.json) return 0 ;;
    service-account*.json|service_account*.json|oauth_creds.json) return 0 ;;
  esac
  return 1
}

BAD=0
while IFS= read -r -d '' f; do
  rel="${f#"$T/x/"}"
  if is_secret_path "$rel"; then echo "::error::bundle membawa berkas bernama rahasia: $rel"; BAD=1; fi
done < <(find "$T/x" -type f -print0)

RE='-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|gho_[A-Za-z0-9]{36}|ghs_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{50,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-ant-[A-Za-z0-9_-]{20,}|sk-[A-Za-z0-9]{40,}'
HITS="$(grep -rIlE -e "$RE" "$T/x" 2>/dev/null || true)"
if [ -n "$HITS" ]; then echo "::error::bundle membawa isi mirip kunci/token (isi tidak dicetak):"; printf '%s\n' "$HITS" | sed "s#$T/x/##" | head -n 20; BAD=1; fi

# Penanda fixture (dirakit agar teks penandanya sendiri tidak cocok saat skrip ini ikut terbundel).
MARK="MYTHOS_FIXTURE_$(printf 'NAME_ONLY')"
if grep -rIlF -e "$MARK" "$T/x" >/dev/null 2>&1; then echo "::error::isi fixture rahasia palsu ikut terbundel"; BAD=1; fi

[ "$BAD" = 0 ] || exit 1
echo "Bundle source bersih: tidak ada berkas atau isi mirip rahasia ($(find "$T/x" -type f | wc -l | tr -d ' ') berkas diperiksa)."
