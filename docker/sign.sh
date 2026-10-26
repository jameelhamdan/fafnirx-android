#!/bin/sh
# Signs an APK with a self-signed key. The key is made once and kept in .signing/ (git-ignored), so later builds update over
# earlier ones. CI can provide its own: KEYSTORE (path), KEYSTORE_PASSWORD, KEY_ALIAS.
#   sign.sh <unsigned.apk> <signed.apk>      sign.sh --keygen
set -eu
cd /src
KEYSTORE="${KEYSTORE:-/src/.signing/fafnirx.keystore}"
KEY_ALIAS="${KEY_ALIAS:-fafnirx}"
PASS_FILE=/src/.signing/keystore.pass

if [ ! -f "$KEYSTORE" ]; then
  mkdir -p "$(dirname "$KEYSTORE")"
  if [ -z "${KEYSTORE_PASSWORD:-}" ]; then
    openssl rand -hex 16 > "$PASS_FILE"
    KEYSTORE_PASSWORD="$(cat "$PASS_FILE")"
  fi
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -keypass "$KEYSTORE_PASSWORD" \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=fafnirx-android, O=fafnirx" > /dev/null
  echo "made a new self-signed key: $KEYSTORE (keep it: a build signed with another key cannot update this one)"
fi
if [ -z "${KEYSTORE_PASSWORD:-}" ]; then KEYSTORE_PASSWORD="$(cat "$PASS_FILE")"; fi

if [ "${1:-}" = "--keygen" ]; then
  keytool -list -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASSWORD" -alias "$KEY_ALIAS" | tail -2
  echo "base64 of the keystore (secret ANDROID_KEYSTORE_BASE64):"
  base64 -w0 "$KEYSTORE"
  echo
  echo "password (ANDROID_KEYSTORE_PASSWORD): $KEYSTORE_PASSWORD   alias (ANDROID_KEY_ALIAS): $KEY_ALIAS"
  exit 0
fi

IN="$1"
OUT="$2"
mkdir -p "$(dirname "$OUT")"
ALIGNED="$(mktemp -u).apk"
zipalign -f -p 4 "$IN" "$ALIGNED"
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASSWORD" --ks-key-alias "$KEY_ALIAS" \
  --key-pass "pass:$KEYSTORE_PASSWORD" --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED" "$OUT.idsig"
apksigner verify --print-certs "$OUT" | grep -E "Signer #1 certificate (DN|SHA-256)"
(cd "$(dirname "$OUT")" && sha256sum "$(basename "$OUT")" > "$(basename "$OUT").sha256")
SIZE=$(wc -c < "$OUT")
echo "APK: $OUT ($SIZE bytes)"
MAX="${MAX_APK_BYTES:-4194304}"
if [ "$SIZE" -gt "$MAX" ]; then
  echo "APK is larger than the budget of $MAX bytes" >&2
  exit 1
fi
