#!/usr/bin/env bash
# Builds android/keystore/airwhispers-release.p12 from the PEM identity shipped
# in this repository (or from your own PEM files).
#
# Java's PKCS#12 reader refuses the PBES2/AES-256 bags that OpenSSL 3 writes by
# default, so we prefer the legacy (SHA1/3DES) encoding and verify with keytool
# when one is available.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_DIR="$DIR/keystore"
KEY="${KEYSTORE_DIR}/airwhispers-release.key.pem"
CERT="${KEYSTORE_DIR}/airwhispers-release.cert.pem"
OUT="${KEYSTORE_DIR}/airwhispers-release.p12"
PASS="${AIRWHISPERS_STORE_PASSWORD:-airwhispers}"
ALIAS="${AIRWHISPERS_KEY_ALIAS:-airwhispers}"

if [[ ! -f "$KEY" || ! -f "$CERT" ]]; then
  echo "make-keystore: no PEM identity found in $KEYSTORE_DIR, skipping." >&2
  exit 0
fi

readable_by_java() {
  command -v keytool >/dev/null 2>&1 || return 1
  keytool -list -keystore "$1" -storetype PKCS12 -storepass "$PASS" >/dev/null 2>&1
}

generate() {
  local extra=("$@")
  openssl pkcs12 -export \
    -in "$CERT" -inkey "$KEY" \
    -name "$ALIAS" -out "$OUT" -passout "pass:$PASS" \
    "${extra[@]}" >/dev/null 2>&1
}

rm -f "$OUT"
if generate -legacy && readable_by_java "$OUT"; then
  echo "make-keystore: wrote $OUT (legacy PKCS#12 encoding, verified with keytool)."
  exit 0
fi
if generate -certpbe PBE-SHA1-3DES -keypbe PBE-SHA1-3DES -macalg sha1 && readable_by_java "$OUT"; then
  echo "make-keystore: wrote $OUT (SHA1/3DES PBE, verified with keytool)."
  exit 0
fi
if generate; then
  echo "make-keystore: wrote $OUT (OpenSSL default encoding)."
  if ! readable_by_java "$OUT"; then
    echo "make-keystore: WARNING — keytool could not read $OUT; Gradle will fall back to debug signing." >&2
  fi
  exit 0
fi

echo "make-keystore: could not build a keystore, release builds will use the debug key." >&2
exit 0
