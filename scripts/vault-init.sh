#!/usr/bin/env sh
#
# Auto-initialise + auto-unseal the persistent Vault. Run as the `vault-init` one-shot in
# docker-compose.yml on every `up`, so the stack is turnkey (no manual unseal step) while
# Vault stays persistent (file storage). Idempotent:
#   - first ever run : `operator init` (1 key share), save the unseal key + root token to
#                      the shared `vaultinit` volume, then unseal.
#   - every later run: read the saved key and unseal (Vault comes up sealed after a restart).
#
# Security note: the unseal key is stored next to the data (on-host volume), so "sealed at
# rest" protection is limited — fine for local/turnkey installs. For production, switch to
# auto-unseal backed by a KMS / Transit (see GETTING_STARTED.md "Hardening Vault").
set -eu

export VAULT_ADDR="${VAULT_ADDR:-http://vault:8200}"
KEYFILE="${VAULT_INIT_FILE:-/vault/init/vault-init.json}"
mkdir -p "$(dirname "$KEYFILE")"

echo "[vault-init] waiting for Vault at ${VAULT_ADDR} ..."
# `vault status` exits 2 when sealed/uninitialised but reachable — that's success for us.
until vault status >/dev/null 2>&1 || [ "$?" -eq 2 ]; do sleep 2; done

if vault status -format=json 2>/dev/null | grep -q '"initialized": *true'; then
  echo "[vault-init] Vault already initialised."
  if [ ! -f "$KEYFILE" ]; then
    echo "[vault-init] ERROR: Vault is initialised but $KEYFILE is missing — cannot unseal."
    echo "[vault-init]        (wipe with 'compose down -v' to re-init, or restore the key file.)"
    exit 1
  fi
else
  echo "[vault-init] initialising Vault (key-shares=1, key-threshold=1) ..."
  vault operator init -key-shares=1 -key-threshold=1 -format=json > "$KEYFILE"
  chmod 600 "$KEYFILE" 2>/dev/null || true
  ROOT_TOKEN=$(tr -d '\n' < "$KEYFILE" | sed -n 's/.*"root_token": *"\([^"]*\)".*/\1/p')
  echo "[vault-init] ============================================================"
  echo "[vault-init]  Vault initialised. Root token (saved in the vaultinit volume):"
  echo "[vault-init]    ${ROOT_TOKEN}"
  echo "[vault-init]  Use it for the Vault UI (http://localhost:8200) or to manage secrets."
  echo "[vault-init] ============================================================"
fi

if vault status -format=json 2>/dev/null | grep -q '"sealed": *true'; then
  UNSEAL_KEY=$(tr -d '\n' < "$KEYFILE" | sed -n 's/.*"unseal_keys_b64": *\[ *"\([^"]*\)".*/\1/p')
  if [ -z "$UNSEAL_KEY" ]; then
    echo "[vault-init] ERROR: sealed but no unseal key found in $KEYFILE"; exit 1
  fi
  echo "[vault-init] unsealing ..."
  vault operator unseal "$UNSEAL_KEY" >/dev/null
fi

echo "[vault-init] Vault is initialised and unsealed."
