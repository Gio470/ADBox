#!/usr/bin/env bash
set -euo pipefail
version='3.3.4'
digest='8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e'
destination='app/src/main/assets/adbox-screen-server.jar'
mkdir -p app/src/main/assets
if [[ -f "$destination" ]] && [[ "$(sha256sum "$destination" | cut -d ' ' -f 1)" == "$digest" ]]; then
  echo 'Pinned scrcpy screen server verified.'
  exit 0
fi
temporary="${destination}.download"
trap 'rm -f "$temporary"' EXIT
curl --fail --location --retry 3 --max-time 60 "https://github.com/Genymobile/scrcpy/releases/download/v${version}/scrcpy-server-v${version}" --output "$temporary"
actual=$(sha256sum "$temporary" | cut -d ' ' -f 1)
[[ "$actual" == "$digest" ]] || { echo 'Screen server checksum mismatch.' >&2; exit 1; }
mv "$temporary" "$destination"
echo 'Pinned scrcpy screen server verified.'
