#!/usr/bin/env bash
set -euo pipefail

VERSION="$1"

echo "Preparing release v${VERSION}..."

# 1. Update the version in app/gradle.properties
sed -i "s/^version\s*=.*/version = ${VERSION}/" app/gradle.properties
echo "Updated app/gradle.properties to version ${VERSION}"

# 2. Build the release APK with the correct version baked in.
#    Stop any existing daemon so a fresh one picks up the updated gradle.properties.
chmod +x ./gradlew
./gradlew --stop || true
./gradlew assembleRelease --stacktrace

# 3. Locate the newly built APK. Do not assign its public release name yet.
RELEASE_DIR="app/build/outputs/apk/release"
APK_SRC=$(find "${RELEASE_DIR}" -name '*-release.apk' | head -1)

if [ -z "${APK_SRC}" ]; then
  echo "ERROR: No *-release.apk found in ${RELEASE_DIR}"
  ls -la "${RELEASE_DIR}" || true
  exit 1
fi

# 4. Verify the Android signature against the production keystore before publishing.
#    A release build must never silently fall back to the Android debug key.
for name in KEYSTORE_PASSWORD KEYSTORE_ENTRY_ALIAS KEYSTORE_ENTRY_PASSWORD; do
  if [[ -z "${!name:-}" ]]; then
    echo "ERROR: Missing production signing credential: ${name}" >&2
    exit 1
  fi
done
if [[ ! -s app/keystore.jks ]]; then
  echo "ERROR: Missing or empty production keystore" >&2
  exit 1
fi

ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$ANDROID_SDK_ROOT" || ! -d "$ANDROID_SDK_ROOT/build-tools" ]]; then
  echo "ERROR: Android SDK build-tools not found" >&2
  exit 1
fi
APKSIGNER=$(find "$ANDROID_SDK_ROOT/build-tools" -maxdepth 2 -type f -name apksigner | sort -V | tail -n 1)
if [[ -z "$APKSIGNER" ]]; then
  echo "ERROR: apksigner not found" >&2
  exit 1
fi

"$APKSIGNER" verify --verbose "$APK_SRC"
# Accept the certificate output of both Android SDK apksigner and Termux apksigner.
# Multiple signature schemes can report the same certificate; distinct certificates
# are not accepted. Never concatenate fingerprints into a single digest.
CERT_LINES=$("$APKSIGNER" verify --print-certs "$APK_SRC")
CERTS=$(printf '%s\n' "$CERT_LINES" |
  sed -nE 's/^(Signer #[0-9]+|V[0-9]+(\\.[0-9]+)? Signer): certificate SHA-256 digest: ([[:xdigit:]]{64})$/\\3/p' |
  tr '[:upper:]' '[:lower:]' | sort -u)
ACTUAL_CERT="$CERTS"
EXPECTED_CERT=$(keytool -exportcert -keystore app/keystore.jks \
  -storepass:env KEYSTORE_PASSWORD -alias "$KEYSTORE_ENTRY_ALIAS" |
  openssl dgst -sha256 -binary | od -An -v -tx1 | tr -d '[:space:]')
if [[ ! "$ACTUAL_CERT" =~ ^[0-9a-f]{64}$ || ! "$EXPECTED_CERT" =~ ^[0-9a-f]{64}$ || "$ACTUAL_CERT" != "$EXPECTED_CERT" ]]; then
  echo "ERROR: APK signer does not match the production keystore" >&2
  exit 1
fi
echo "Verified APK signature and production signing certificate"

# 5. Assign the final release filename only after verification.
APK_DST="${RELEASE_DIR}/morphe-manager-${VERSION}.apk"
mv -- "${APK_SRC}" "${APK_DST}"
echo "Renamed verified APK to ${APK_DST}"

# 6. GPG-sign the APK
gpg --armor --detach-sign "${APK_DST}"
echo "Signed ${APK_DST}"

echo "Release v${VERSION} prepared successfully."
