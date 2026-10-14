#!/usr/bin/env bash
# Sign the verified unsigned release APK with the ADBox release key.
# Expects RELEASE_KEYSTORE (base64 PKCS12) and RELEASE_PASSWORD in the environment.
set -euo pipefail
: "${RELEASE_KEYSTORE:?Set the RELEASE_KEYSTORE secret}" "${RELEASE_PASSWORD:?Set the RELEASE_PASSWORD secret}"
tools="$ANDROID_HOME/build-tools/35.0.0"
output_dir="app/build/outputs/apk/release"
release_dir="$PWD/.adbox-signing"
trap 'rm -rf "$release_dir" "$output_dir/adbox-aligned.apk"' EXIT
mkdir -p "$release_dir" && chmod 700 "$release_dir"
printf '%s' "$RELEASE_KEYSTORE" | base64 -d > "$release_dir/release.jks"
printf '%s' "$RELEASE_PASSWORD" > "$release_dir/password"
chmod 600 "$release_dir"/*

"$tools/zipalign" -P 16 -f 4 "$output_dir/app-release-unsigned.apk" "$output_dir/adbox-aligned.apk"
"$tools/apksigner" sign --ks "$release_dir/release.jks" --ks-key-alias adbox-release --ks-pass "file:$release_dir/password" \
  --out "$output_dir/adbox-release.apk" "$output_dir/adbox-aligned.apk"
"$tools/apksigner" verify --min-sdk-version 26 "$output_dir/adbox-release.apk"

echo 'APK is signed with the ADBox release key.'
