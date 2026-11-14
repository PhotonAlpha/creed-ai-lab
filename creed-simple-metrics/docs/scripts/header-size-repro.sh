#!/usr/bin/env bash
# Reproduces Tomcat's "no room available in the buffer" (HeadersTooLargeException) on a running creed-simple-metrics:
# the Camel REST routes echo request headers into the response, so a request header that fits the request limit can
# overflow the response limit. See docs/http-header-size.md.
#
#   docs/scripts/header-size-repro.sh [base-url] [sizes...]
#   docs/scripts/header-size-repro.sh https://localhost:8096/simple 7980 8000 8100 12000
#
# Reading the result: 200 = fits both ways; 500 with a tiny body = the response overflowed (server log:
# HeadersTooLargeException); 400 "Request header is too large" = the request itself was over the request limit.
set -euo pipefail
BASE="${1:-https://localhost:8096/simple}"
shift || true
SIZES=("${@:-7980 8000 8100 12000 16000}")
read -r -a SIZES <<<"${SIZES[*]}"

printf '%-14s %-6s %-18s %s\n' "X-Trace-Note" "HTTP" "response headers" "echoed"
for n in "${SIZES[@]}"; do
  note=$(head -c "$n" /dev/zero | tr '\0' 'x')
  headers=$(mktemp)
  # -k: the local PKI's CA is not in the system trust store
  result=$(curl -sk -o /dev/null -D "$headers" -w '%{http_code} %{size_header}' -H "X-Trace-Note: $note" \
    "$BASE/camel/api/hello")
  printf '%-14s %-6s %-18s %s\n' "$n B" "${result% *}" "${result#* } B" \
    "$(grep -qi '^x-trace-note' "$headers" && echo yes || echo no)"
  rm -f "$headers"
done
