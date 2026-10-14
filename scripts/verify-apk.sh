#!/usr/bin/env bash
set -euo pipefail
apk="${1:-app/build/outputs/apk/release/app-release-unsigned.apk}"
output_dir="app/build/outputs/apk/release"
[[ -s "$apk" ]]
python3 - "$apk" <<'PYCODE'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    signatures=[name for name in apk.namelist() if name.upper().startswith('META-INF/') and name.upper().endswith(('.RSA','.DSA','.EC','.SF'))]
    if signatures: raise SystemExit('Expected an unsigned APK; JAR signature entries were found.')
    with open(sys.argv[1],'rb') as file:
        file.seek(apk.start_dir-16)
        if file.read(16)==b'APK Sig Block 42': raise SystemExit('Expected an unsigned APK; an APK signing block was found.')
print('Verified the APK has no JAR signatures or APK signing block.')
PYCODE
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk" | tee "$output_dir/badging.txt"
grep -q "application-label:'ADBox'" "$output_dir/badging.txt"
grep -q "package: name='dev.adbox.toolkit' " "$output_dir/badging.txt"

# Inspect the merged, compiled manifest so relative component names resolve to
# the new namespace and no old application package remains in the installed APK.
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump xmltree "$apk" AndroidManifest.xml > "$output_dir/manifest.txt"
for component in ADBoxApplication MainActivity FastbootOperationService; do
  grep -Fq "dev.adbox.toolkit.$component" "$output_dir/manifest.txt"
done
echo 'APK package and application/activity/service components use dev.adbox.toolkit.'

# Android resource linking may shorten filenames, so inspect compiled XML contents.
themed_resource=$(python3 - "$apk" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    candidates=[]
    for name in apk.namelist():
        if name.startswith('res/') and name.endswith('.xml'):
            data=apk.read(name)
            if b'monochrome' in data or 'monochrome'.encode('utf-16le') in data:
                candidates.append(name)
    if len(candidates)!=1:
        raise SystemExit('Expected one packaged monochrome icon resource; found '+str(len(candidates)))
    print(candidates[0])
PY
)
[[ -n "$themed_resource" ]]
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump xmltree "$apk" "$themed_resource" > "$output_dir/themed-icon.txt"
grep -q 'E: adaptive-icon' "$output_dir/themed-icon.txt"
grep -q 'E: monochrome' "$output_dir/themed-icon.txt"
echo 'APK contains the ADBox adaptive icon and Android 13 monochrome layer.'

# Verify the packaged manifest, including merged dependency permissions.
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump permissions "$apk" > "$output_dir/permissions.txt"
cat "$output_dir/permissions.txt"
if grep -Eq 'android[.]permission[.](INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_WIFI_MULTICAST_STATE)' "$output_dir/permissions.txt"; then
  echo 'USB-only APK unexpectedly requests a network permission.' >&2
  exit 1
fi

# Verify that the actual APK contains the pinned helper, not just the build tree.
server_hash=$(unzip -p "$apk" assets/adbox-screen-server.jar | sha256sum | cut -d ' ' -f 1)
[[ "$server_hash" == '8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e' ]]
echo 'APK contains the verified scrcpy 3.3.4 screen server.'
