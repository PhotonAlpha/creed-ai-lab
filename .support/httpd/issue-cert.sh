#!/usr/bin/env bash
set -euo pipefail
# Issues the httpd server identity (creed-httpd) from the EXISTING Creed intermediate CA.
#
# Why not just rerun .support/scripts/CA-Generation.sh: that script mints a brand-new root and
# intermediate every time, which silently invalidates every keystore already handed to the other
# services. This signs one more leaf with the CA already in .support/scripts/pki.
#
# httpd reads PEM, not PKCS12, so the useful outputs are creed-httpd.crt / creed-httpd.key (plus the
# existing ca-chain.crt). The .p12 is produced as well, to match every other identity in the directory.
#
# SAN carries IP:127.0.0.1 on top of what CA-Generation.sh gives a leaf, because the node dials the
# MCMP listener by address (creed.mod-cluster.proxies=127.0.0.1:6666).
#
#   .support/httpd/issue-cert.sh            # idempotent: does nothing if the cert exists
#   FORCE=1 .support/httpd/issue-cert.sh    # re-issue

PKI="$(cd "$(dirname "$0")/../scripts/pki" && pwd)"
NAME=creed-httpd
STOREPASS="${STOREPASS:-changeit}"
DAYS_LEAF="${DAYS_LEAF:-825}"
SUBJ_BASE="${SUBJ_BASE:-/C=SG/O=Creed/OU=Platform}"

cd "$PKI"
for f in creed-CA-Public-RSA.crt creed-CA-Public-RSA.key ca-chain.crt; do
  [[ -f "$f" ]] || { echo "missing $PKI/$f — run .support/scripts/CA-Generation.sh first" >&2; exit 1; }
done
if [[ -f "${NAME}.crt" && -z "${FORCE:-}" ]]; then
  echo "${NAME}.crt already exists (FORCE=1 to re-issue)"; exit 0
fi

EXT="$(mktemp)"; trap 'rm -f "$EXT" "${NAME}.csr"' EXIT
printf "%s\n" \
  "basicConstraints=critical,CA:FALSE" \
  "keyUsage=critical,digitalSignature,keyEncipherment" \
  "extendedKeyUsage=serverAuth" \
  "subjectKeyIdentifier=hash" \
  "authorityKeyIdentifier=keyid:always" \
  "subjectAltName=DNS:${NAME},DNS:localhost,IP:127.0.0.1" > "$EXT"

openssl genrsa -out "${NAME}.key" 2048
openssl req -new -sha256 -key "${NAME}.key" -subj "${SUBJ_BASE}/CN=${NAME}" -out "${NAME}.csr"
openssl x509 -req -sha256 -days "$DAYS_LEAF" -in "${NAME}.csr" \
  -CA creed-CA-Public-RSA.crt -CAkey creed-CA-Public-RSA.key -CAcreateserial \
  -extfile "$EXT" -out "${NAME}.crt"
openssl pkcs12 -export -inkey "${NAME}.key" -in "${NAME}.crt" -certfile ca-chain.crt \
  -name "${NAME}" -passout pass:"${STOREPASS}" -out "${NAME}-keystore.p12"
# httpd runs as root inside the container and reads the key through a bind mount.
chmod 644 "${NAME}.key"
openssl verify -CAfile ca-chain.crt "${NAME}.crt"
