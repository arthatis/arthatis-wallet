#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
cd "$ROOT/mobile"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb tidak ditemukan. Pasang Android platform-tools, lalu coba lagi." >&2
  exit 1
fi

mapfile -t devices < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if ((${#devices[@]} == 0)); then
  echo "Tidak ada device/emulator siap (status 'device')." >&2
  echo "Sambungkan HP (USB debugging) atau jalankan emulator, lalu coba lagi." >&2
  adb devices >&2
  exit 1
fi

echo "Menginstal debug build ke: ${devices[*]}"
exec ./gradlew installDebug
