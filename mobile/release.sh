#!/usr/bin/env bash
# Builds the release APKs (one per CPU: arm64-v8a, armeabi-v7a, x86_64, x86) and stores them under ../releases/ as
# arthatis-wallet-v<versionName>-<abi>.apk (+ .sha256 each).
set -euo pipefail
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
mkdir -p ../releases
for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
  NAME="arthatis-wallet-v${VERSION}-${ABI}.apk"
  cp "app/build/outputs/apk/release/app-${ABI}-release.apk" "../releases/${NAME}"
  (cd ../releases && sha256sum "${NAME}" > "${NAME}.sha256")
  echo "OK: ../releases/${NAME}"
done
