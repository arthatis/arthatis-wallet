#!/usr/bin/env bash
# Builds the release APKs, one per CPU (arm64-v8a, armeabi-v7a, x86_64, x86), into mobile/dist/ with a SHA256SUMS file.
# Nothing here is committed: publish them as assets of a GitHub Release for the tag, e.g.
#   git tag -a v$VERSION -m "v$VERSION" && git push origin v$VERSION
#   gh release create v$VERSION --prerelease --title "v$VERSION" --notes-file <notes> mobile/dist/*
set -euo pipefail
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease
rm -rf dist && mkdir -p dist
for ABI in arm64-v8a armeabi-v7a x86_64 x86; do
  cp "app/build/outputs/apk/release/app-${ABI}-release.apk" "dist/arthatis-wallet-v${VERSION}-${ABI}.apk"
done
(cd dist && sha256sum arthatis-wallet-v${VERSION}-*.apk > SHA256SUMS)
echo "OK: mobile/dist/ (v${VERSION})"
