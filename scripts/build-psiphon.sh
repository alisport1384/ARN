#!/usr/bin/env bash
#
# Installs the psiphon-tunnel-core console client the Aether ENGINE spawns
# (AETHER_PSIPHON=chain) into app/src/main/jniLibs/<abi>/libpsiphon.so.
#
# Same packaging mechanism as libaether.so / libxray.so: a native EXECUTABLE
# shipped beside the JNI libraries. useLegacyPackaging=true guarantees it is
# extracted to ApplicationInfo.nativeLibraryDir with the exec bit set, which is
# where AetherProcess points the engine's AETHER_PSIPHON_BIN at.
#
# Built by the vendored engine's own native/aether/psiphon-build.sh, which owns
# the pinned upstream commit and the build flags - this script only decides WHICH
# ABIs to build and where the result is installed.
#
# The target OS is ANDROID, not linux, and that is the whole ballgame: Android
# 5.0+ refuses to start a non-PIE executable, and it refuses it before the
# process produces any output, so the engine only ever saw "psiphon stopped
# before it was ready" for a binary that was in fact perfect on a desktop.
# GOOS=android gives a PIE ET_DYN against bionic (see the long note in
# psiphon-build.sh). It needs cgo, so the NDK's clang is required here too -
# the same NDK the Rust and Xray cores already use.
#
# Requires: Go (>= the toolchain the psiphon module pins) on PATH, and the
# Android NDK (ANDROID_NDK_HOME, or one of the usual SDK locations).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
AETHER_SRC="${PROJECT_DIR}/native/aether"
JNI_DIR="${PROJECT_DIR}/app/src/main/jniLibs"

# The engine's own build script: it pins the CluvexStudio fork, the branch and
# the commit, so there is exactly one place where a psiphon upgrade happens.
BUILDER="${AETHER_SRC}/psiphon-build.sh"
if [ ! -f "${BUILDER}" ]; then
  echo "ERROR: ${BUILDER} not found (is the vendored aether core present?)" >&2
  exit 1
fi
if ! command -v go >/dev/null; then
  echo "ERROR: building libpsiphon.so needs a Go toolchain on PATH." >&2
  exit 1
fi

build_abi() {
  local abi="$1" goarch="$2" goarm="$3"
  local out="${JNI_DIR}/${abi}/libpsiphon.so"
  local tmp
  tmp="$(mktemp -d)"
  echo "==> [psiphon] building psiphon-tunnel-core for ${abi} (android/${goarch}${goarm:+ v${goarm}})"
  if [ -n "${goarm}" ]; then
    bash "${BUILDER}" android "${goarch}" "${tmp}" "${goarm}"
  else
    bash "${BUILDER}" android "${goarch}" "${tmp}"
  fi
  mkdir -p "${JNI_DIR}/${abi}"
  cp "${tmp}/psiphon-tunnel-core" "${out}"
  chmod 755 "${out}"
  rm -rf "${tmp}"
  if [ ! -s "${out}" ]; then
    echo "ERROR: [${abi}] no libpsiphon.so at ${out}" >&2
    exit 1
  fi
  echo "    [${abi}] $(du -h "${out}" | cut -f1) -> ${out}"
}

# The same two ABIs as build-natives.sh / build-xray.sh.
build_abi "arm64-v8a"   "arm64" ""
build_abi "armeabi-v7a" "arm"   "7"

echo "==> psiphon console client installed under ${JNI_DIR}"
