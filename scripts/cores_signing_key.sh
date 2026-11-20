#!/usr/bin/env bash
# Creates the signing key of the app's own core channel (.github/workflows/cores.yml):
# the private key goes into the repository secret CORES_SIGNING_KEY, the public key into
# cores/signing-public.pem (commit it; builds embed it and check every core manifest with it).
# With gh, upload the private key and remove the temporary copy. Without gh or with
# --manual, keep the key in a private temporary directory for uploading in the browser.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${1:-seinist/ambient-tv}"
[[ $# -le 1 || ( $# -eq 2 && "$2" == --manual ) ]] || { echo "Usage: $0 [owner/repo [--manual]]" >&2; exit 2; }
[[ -f "$ROOT/cores/signing-public.pem" && "${FORCE:-0}" != "1" ]] && { echo "cores/signing-public.pem already exists. Set FORCE=1 to replace it." >&2; exit 1; }
manual=0
if [[ "${2:-}" == --manual ]] || ! command -v gh >/dev/null 2>&1; then manual=1; fi

umask 077
key_dir="$(mktemp -d "${TMPDIR:-/tmp}/ambient-tv-cores.XXXXXX")"
key="$key_dir/signing-private.pem"
public_tmp=
keep_private=0
cleanup() {
    if [[ -n "$public_tmp" ]]; then rm -f -- "$public_tmp"; fi
    if [[ "$keep_private" == 0 ]]; then
        rm -f -- "$key"
        rmdir -- "$key_dir"
    fi
}
trap cleanup EXIT

public_tmp="$(mktemp "$ROOT/cores/.signing-public.XXXXXX")"
openssl ecparam -name prime256v1 -genkey -noout -out "$key"
openssl ec -in "$key" -pubout -out "$public_tmp" 2>/dev/null
chmod 644 "$public_tmp"
if [[ "$manual" == 0 ]]; then gh secret set CORES_SIGNING_KEY --repo "$REPO" < "$key"; fi
mv -- "$public_tmp" "$ROOT/cores/signing-public.pem"

if [[ "$manual" == 1 ]]; then
    keep_private=1
    printf 'Private key saved at %s\n' "$key"
    printf 'Open https://github.com/%s/settings/secrets/actions and create the repository secret CORES_SIGNING_KEY. Paste the full private PEM file as the value.\n' "$REPO"
else
    printf 'Secret CORES_SIGNING_KEY set for %s.\n' "$REPO"
fi
printf 'Commit and push cores/signing-public.pem.\n'
