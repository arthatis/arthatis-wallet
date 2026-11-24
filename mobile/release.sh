#!/usr/bin/env bash
# Builds the release APK and stores it under ../releases/ as arthatis-wallet-v<versionName>.apk (+ .sha256).
set -euo pipefail
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
mkdir -p ../releases
APK="../releases/arthatis-wallet-v${VERSION}.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK"
(cd ../releases && sha256sum "arthatis-wallet-v${VERSION}.apk" > "arthatis-wallet-v${VERSION}.apk.sha256")
echo "OK: $APK"
