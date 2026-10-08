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

# 3. Find the built APK and rename it to the expected release name
RELEASE_DIR="app/build/outputs/apk/release"
APK_SRC=$(find "${RELEASE_DIR}" -name '*-release.apk' | head -1)

if [ -z "${APK_SRC}" ]; then
  echo "ERROR: No *-release.apk found in ${RELEASE_DIR}"
  ls -la "${RELEASE_DIR}" || true
  exit 1
fi

APK_DST="${RELEASE_DIR}/morphe-manager-${VERSION}.apk"
mv "${APK_SRC}" "${APK_DST}"
echo "Renamed APK to ${APK_DST}"

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

"$APKSIGNER" verify --verbose "$APK_DST"
ACTUAL_CERT=$("$APKSIGNER" verify --print-certs "$APK_DST" |
  sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1 |
  tr '[:upper:]' '[:lower:]' | tr -d ':[:space:]')
EXPECTED_CERT=$(keytool -exportcert -keystore app/keystore.jks \
  -storepass:env KEYSTORE_PASSWORD -alias "$KEYSTORE_ENTRY_ALIAS" |
  openssl dgst -sha256 -binary | od -An -tx1 | tr -d '[:space:]')
if [[ -z "$ACTUAL_CERT" || -z "$EXPECTED_CERT" || "$ACTUAL_CERT" != "$EXPECTED_CERT" ]]; then
  echo "ERROR: APK signer does not match the production keystore" >&2
  exit 1
fi
echo "Verified APK signature and production signing certificate"

# 5. GPG-sign the APK
gpg --armor --detach-sign "${APK_DST}"
echo "Signed ${APK_DST}"

echo "Release v${VERSION} prepared successfully."
