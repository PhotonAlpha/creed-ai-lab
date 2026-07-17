#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# Creed mTLS PKI generator  --  PKCS#12 keystores + truststores
#
# Hierarchy:
#   creed-CA-global-root            self-signed ROOT CA
#     └─ creed-CA-Public-RSA        INTERMEDIATE CA (issues every leaf cert)
#          ├─ creed-gateway            / creed-gateway-CLI
#          ├─ creed-gateway-partner    / creed-gateway-partner-CLI
#          ├─ creed-resource-catalog   / creed-resource-catalog-CLI
#          ├─ creed-resource-order     / creed-resource-order-CLI
#          └─ creed-resource-payment   / creed-resource-payment-CLI
#
# Trust model:  every *-truststore.p12 = { root , intermediate }.
# All leaf certs are signed by creed-CA-Public-RSA, so any service trusts any
# peer in the mesh (CA-anchored mutual trust). The call-graph in the request
# (who calls whom) is satisfied automatically and needs no per-peer scoping.
#
# Per identity we mint TWO certs:
#   <svc>      serverAuth   -> inbound identity (presented when called)
#   <svc>-CLI  clientAuth   -> outbound identity (presented by RestTemplate)
# ============================================================================

# ---- config (override via env) ---------------------------------------------
OUT="${OUT:-./pki}"
DAYS_ROOT="${DAYS_ROOT:-3650}"
DAYS_INT="${DAYS_INT:-1825}"
DAYS_LEAF="${DAYS_LEAF:-825}"        # keep <=825d to satisfy modern TLS limits
CA_KEYSIZE="${CA_KEYSIZE:-4096}"
LEAF_KEYSIZE="${LEAF_KEYSIZE:-2048}"
SUBJ_BASE="${SUBJ_BASE:-/C=SG/O=Creed/OU=Platform}"

# One password here for clarity. In production use a distinct, KMS/Vault-managed
# password per bundle (your AES-GCM master + RSA-OAEP per-bundle scheme).
STOREPASS="${STOREPASS:-changeit}"

mkdir -p "$OUT"; cd "$OUT"

# ---- 1. ROOT CA : creed-CA-global-root -------------------------------------
openssl genrsa -out creed-CA-global-root.key "$CA_KEYSIZE"
openssl req -x509 -new -nodes -sha256 -days "$DAYS_ROOT" \
  -key creed-CA-global-root.key \
  -subj "${SUBJ_BASE}/CN=creed-CA-global-root" \
  -addext "basicConstraints=critical,CA:TRUE" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash" \
  -out creed-CA-global-root.crt

# ---- 2. INTERMEDIATE CA : creed-CA-Public-RSA ------------------------------
openssl genrsa -out creed-CA-Public-RSA.key "$CA_KEYSIZE"
openssl req -new -sha256 \
  -key creed-CA-Public-RSA.key \
  -subj "${SUBJ_BASE}/CN=creed-CA-Public-RSA" \
  -out creed-CA-Public-RSA.csr
openssl x509 -req -sha256 -days "$DAYS_INT" \
  -in creed-CA-Public-RSA.csr \
  -CA creed-CA-global-root.crt -CAkey creed-CA-global-root.key -CAcreateserial \
  -extfile <(printf "%s\n" \
      "basicConstraints=critical,CA:TRUE,pathlen:0" \
      "keyUsage=critical,keyCertSign,cRLSign" \
      "subjectKeyIdentifier=hash" \
      "authorityKeyIdentifier=keyid:always") \
  -out creed-CA-Public-RSA.crt

# chain bundled into every leaf keystore (leaf -> intermediate -> root)
cat creed-CA-Public-RSA.crt creed-CA-global-root.crt > ca-chain.crt

# ---- helpers ----------------------------------------------------------------
# make_keystore <name> <serverAuth|clientAuth>
make_keystore() {
  local name="$1" eku="$2"
  openssl genrsa -out "${name}.key" "$LEAF_KEYSIZE"
  openssl req -new -sha256 -key "${name}.key" \
    -subj "${SUBJ_BASE}/CN=${name}" -out "${name}.csr"
  openssl x509 -req -sha256 -days "$DAYS_LEAF" \
    -in "${name}.csr" \
    -CA creed-CA-Public-RSA.crt -CAkey creed-CA-Public-RSA.key -CAcreateserial \
    -extfile <(printf "%s\n" \
        "basicConstraints=critical,CA:FALSE" \
        "keyUsage=critical,digitalSignature,keyEncipherment" \
        "extendedKeyUsage=${eku}" \
        "subjectKeyIdentifier=hash" \
        "authorityKeyIdentifier=keyid:always" \
        "subjectAltName=DNS:${name},DNS:localhost") \
    -out "${name}.crt"
  openssl pkcs12 -export \
    -inkey "${name}.key" -in "${name}.crt" -certfile ca-chain.crt \
    -name "${name}" -passout pass:"${STOREPASS}" \
    -out "${name}-keystore.p12"
  rm -f "${name}.csr"
}

# make_truststore <name>   -> PKCS12 truststore containing root + intermediate
make_truststore() {
  local name="$1"; rm -f "${name}-truststore.p12"
  keytool -importcert -noprompt -trustcacerts \
    -alias creed-ca-global-root -file creed-CA-global-root.crt \
    -keystore "${name}-truststore.p12" -storetype PKCS12 -storepass "${STOREPASS}"
  keytool -importcert -noprompt -trustcacerts \
    -alias creed-ca-public-rsa -file creed-CA-Public-RSA.crt \
    -keystore "${name}-truststore.p12" -storetype PKCS12 -storepass "${STOREPASS}"
}

# ---- 1b. CA keystores + truststores ----------------------------------------
# CA keystores hold the signing keys -- guard tightly / move to HSM in prod.
openssl pkcs12 -export -inkey creed-CA-global-root.key -in creed-CA-global-root.crt \
  -name creed-CA-global-root -passout pass:"${STOREPASS}" \
  -out creed-CA-global-root-keystore.p12
openssl pkcs12 -export -inkey creed-CA-Public-RSA.key -in creed-CA-Public-RSA.crt \
  -certfile creed-CA-global-root.crt -name creed-CA-Public-RSA \
  -passout pass:"${STOREPASS}" -out creed-CA-Public-RSA-keystore.p12
make_truststore creed-CA-global-root
make_truststore creed-CA-Public-RSA

# ---- 2-5. service identities (server + CLI) --------------------------------
SERVICES=(creed-gateway creed-gateway-partner creed-resource-catalog creed-resource-order creed-resource-payment)
for svc in "${SERVICES[@]}"; do
  make_keystore   "${svc}"      serverAuth
  make_truststore "${svc}"
  make_keystore   "${svc}-CLI"  clientAuth
  make_truststore "${svc}-CLI"
done

echo "== done =="; ls -1 *.p12