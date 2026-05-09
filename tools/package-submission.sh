#!/usr/bin/env bash
#
# TPP-style source archive — same layout as UV-PRO 1.7.0 submission:
#   - Zip name:  UV-PRO-<version>-ATAK-<atak>-source.zip
#   - Single root folder inside zip:  UV-PRO-<version>/
#
# Outputs default to TAK Submissions under ATAK Plugins ($HOME/Documents/ATAK/...).
# Override: PLUGINS_DIR=/path ./tools/package-submission.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="$(sed -n 's/.*ext\.PLUGIN_VERSION *= *"\([^"]*\)".*/\1/p' build.gradle | head -1)"
ATAK_VER="$(sed -n 's/.*ext\.ATAK_VERSION *= *"\([^"]*\)".*/\1/p' build.gradle | head -1)"
if [[ -z "${VERSION}" || -z "${ATAK_VER}" ]]; then
  echo "Could not read PLUGIN_VERSION / ATAK_VERSION from build.gradle" >&2
  exit 1
fi

TPP_ROOT="${TPP_ROOT:-UV-PRO-${VERSION}}"
SHA="$(git rev-parse --short HEAD)"
FULLSHA="$(git rev-parse HEAD)"
STAMP="$(date -u +%Y%m%dT%H%MZ)"

PLUGINS_DIR="${PLUGINS_DIR:-${HOME}/Documents/ATAK/Plugins/TAK Submissions}"
mkdir -p "${PLUGINS_DIR}"

SOURCE_ZIP="UV-PRO-${VERSION}-ATAK-${ATAK_VER}-source.zip"
SOURCE_PATH="${PLUGINS_DIR}/${SOURCE_ZIP}"

git archive --format=zip --prefix="${TPP_ROOT}/" -o "${SOURCE_PATH}" HEAD

APK=""
shopt -s nullglob
for f in "${ROOT}"/app/build/outputs/apk/civ/release/ATAK-Plugin-UVPro-"${VERSION}"-*-civ-release.apk; do
  APK="$f"
  break
done
shopt -u nullglob

APK_NAME=""
if [[ -n "${APK}" && -f "${APK}" ]]; then
  APK_NAME="$(basename "${APK}")"
  cp -f "${APK}" "${PLUGINS_DIR}/${APK_NAME}"
fi

MANIFEST="${PLUGINS_DIR}/UV-PRO-${VERSION}-submission-MANIFEST.txt"
cat > "${MANIFEST}" << EOF
UV-PRO ${VERSION} submission pack (local)
ATAK target: ${ATAK_VER}
Git: ${FULLSHA} (${SHA})
UTC: ${STAMP}
Output directory: ${PLUGINS_DIR}/

  - ${SOURCE_ZIP}
      TPP source archive; root folder ${TPP_ROOT}/
EOF
if [[ -n "${APK_NAME}" ]]; then
  cat >> "${MANIFEST}" << EOF
  - ${APK_NAME}
      Local assembleCivRelease (not TPC-signed). Replace with TPC APK when returned.
EOF
else
  cat >> "${MANIFEST}" << EOF
  - (no APK — run ./gradlew assembleCivRelease first)
EOF
fi

echo "Wrote:"
echo "  ${SOURCE_PATH}"
[[ -n "${APK_NAME}" ]] && echo "  ${PLUGINS_DIR}/${APK_NAME}"
echo "  ${MANIFEST}"
ls -la "${SOURCE_PATH}" "${PLUGINS_DIR}/${APK_NAME}" 2>/dev/null || ls -la "${SOURCE_PATH}"
