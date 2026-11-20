#!/usr/bin/env bash
# Creates the signing key of the app's own core channel (.github/workflows/cores.yml):
# the private key goes into the repository secret CORES_SIGNING_KEY, the public key into
# cores/signing-public.pem (commit it; builds embed it and check every core manifest with it).
# Needs openssl and an authenticated gh. The private key never touches the disk unencrypted
# longer than this script runs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${1:-seinist/ambient-tv}"
[[ -f "$ROOT/cores/signing-public.pem" && "${FORCE:-0}" != "1" ]] && { echo "cores/signing-public.pem exists; FORCE=1 replaces the key." >&2; exit 1; }
umask 077
key="$(mktemp)"
trap 'rm -f "$key"' EXIT
openssl ecparam -name prime256v1 -genkey -noout -out "$key"
openssl ec -in "$key" -pubout -out "$ROOT/cores/signing-public.pem" 2>/dev/null
gh secret set CORES_SIGNING_KEY --repo "$REPO" < "$key"
echo "Secret CORES_SIGNING_KEY set for $REPO; commit cores/signing-public.pem."
