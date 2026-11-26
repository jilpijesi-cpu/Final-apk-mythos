#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
pkg update -y
pkg install -y nodejs-lts openssl
ROOT="$HOME/claude-replica-gateway"
mkdir -p "$ROOT/data"
cp -R "$(cd "$(dirname "$0")" && pwd)"/* "$ROOT/"
chmod 700 "$ROOT/data"
if [ ! -f "$ROOT/.env" ]; then
  printf 'VAULT_MASTER_KEY=%s\nADMIN_TOKEN=%s\nHOST=127.0.0.1\nPORT=8787\nVAULT_FILE=%s/data/vault.json\n' "$(openssl rand -hex 32)" "$(openssl rand -hex 32)" "$ROOT" > "$ROOT/.env"
  chmod 600 "$ROOT/.env"
fi
cat > "$ROOT/start.sh" <<'START'
#!/data/data/com.termux/files/usr/bin/bash
set -a
. "$HOME/claude-replica-gateway/.env"
set +a
cd "$HOME/claude-replica-gateway"
exec node server.mjs
START
chmod 700 "$ROOT/start.sh"
echo "Gateway installed at $ROOT"
echo "Run: $ROOT/start.sh"
echo "Admin token (paste into app Settings > AI Providers > Configure):"
grep '^ADMIN_TOKEN=' "$ROOT/.env" | cut -d= -f2-
echo "Keep .env private. Back up the encrypted vault and VAULT_MASTER_KEY separately."
