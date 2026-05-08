#!/usr/bin/env bash
set -euo pipefail

# Development phone serial pinned for this branch/workstream.
DEV_PHONE_SERIAL="ZT4229JR78"

if [[ "${1:-}" == "--serial" ]]; then
  echo "${DEV_PHONE_SERIAL}"
  exit 0
fi

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <adb args...>"
  echo "Example: $0 install -r app/build/outputs/apk/civ/debug/<apk>"
  exit 1
fi

exec adb -s "${DEV_PHONE_SERIAL}" "$@"
