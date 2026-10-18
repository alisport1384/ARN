#!/usr/bin/env bash
set -euo pipefail

goos="${1:-}"
goarch="${2:-}"
out="${3:-}"
goarm="${4:-}"

if [ -z "$goos" ] || [ -z "$goarch" ] || [ -z "$out" ]; then
    echo "usage: psiphon-build.sh <goos> <goarch> <outdir> [goarm]" >&2
    exit 2
fi

repo="https://github.com/CluvexStudio/psiphon-tunnel-core.git"
branch="shirokhorshid"
commit="83aa73b9b982e7421e00117f5b0c5aceb5dda452"

if ! command -v go >/dev/null 2>&1; then
    echo "psiphon-build: no go toolchain on PATH" >&2
    exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

src="$work/psiphon"
if ! git -c core.longpaths=true clone --quiet --depth 50 --branch "$branch" "$repo" "$src"; then
    echo "psiphon-build: cannot reach github" >&2
    exit 1
fi

git -C "$src" checkout --quiet "$commit" 2>/dev/null ||
    echo "psiphon-build: pinned commit is gone, using $branch as it stands" >&2

ext=""
if [ "$goos" = "windows" ]; then
    ext=".exe"
fi

mkdir -p "$out"

# ---------------------------------------------------------------------------
# How the client is built, per target OS.
#
# ANDROID is not "just another linux": since Android 5.0 the platform refuses
# to start an executable that is not position independent, and it refuses it
# silently from the outside - the kernel/linker kills the process before its
# first line of output. Everything downstream of that looks like a network
# problem ("the port never opened"), which is exactly how this bug hid for two
# rounds. GOOS=linux with CGO_ENABLED=0 produces a *static ET_EXEC*, the one
# shape Android will not run; GOOS=android produces a PIE ET_DYN against
# bionic, which is what every NDK executable is.
#
# GOOS=android needs cgo (the fork's github.com/wlynxg/anet is the Android net
# backend), so the NDK's clang is the C compiler - it is already on PATH in CI
# for the Rust/Xray cores. Go 1.23+ also refuses the //go:linkname anet uses to
# reach net.zoneCache; -checklinkname=0 turns that policy check off while the
# symbol still resolves to the very same stdlib variable (verified in the
# linked binary: anet.zoneCache -> net.zoneCache, anet.zoneCacheX ->
# golang.org/x/net/internal/socket.zoneCache).
#
# DESKTOP targets keep the plain static build: no NDK, no interpreter.
# ---------------------------------------------------------------------------
cc=""
ldflags="-s -w"
cgo=0
if [ "$goos" = "android" ]; then
    cgo=1
    api="${ANDROID_API:-24}"
    ndk="${ANDROID_NDK_HOME:-}"
    if [ -z "$ndk" ] || [ ! -d "$ndk/toolchains/llvm/prebuilt" ]; then
        ndk=""
        for candidate in "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/nonexistent}}/ndk"/*/ \
                         "$HOME/Android/Sdk/ndk"/*/ \
                         "$HOME/Library/Android/sdk/ndk"/*/ \
                         /usr/lib/android-ndk /opt/android-ndk; do
            if [ -d "$candidate/toolchains/llvm/prebuilt" ]; then
                ndk="${candidate%/}"
                break
            fi
        done
    fi
    if [ -z "$ndk" ]; then
        echo "psiphon-build: GOOS=android needs the Android NDK (set ANDROID_NDK_HOME)" >&2
        exit 1
    fi
    llvm="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
    case "$goarch" in
        arm64) cc="$llvm/aarch64-linux-android${api}-clang" ;;
        arm)   cc="$llvm/armv7a-linux-androideabi${api}-clang" ;;
        *)
            echo "psiphon-build: no NDK clang wrapper for android/$goarch" >&2
            exit 1
            ;;
    esac
    if [ ! -x "$cc" ]; then
        echo "psiphon-build: $cc is missing (NDK at $ndk)" >&2
        exit 1
    fi
    ldflags="-s -w -checklinkname=0"
    echo "psiphon-build: android/$goarch -> PIE, cgo via $cc"
fi

build_env=(CGO_ENABLED="$cgo" GOOS="$goos" GOARCH="$goarch" GOARM="$goarm" GOFLAGS=-mod=vendor)
if [ -n "$cc" ]; then
    build_env+=(CC="$cc")
fi

(
    cd "$src"
    env "${build_env[@]}" \
        go build -trimpath -ldflags "$ldflags" -o "$out/psiphon-tunnel-core$ext" ./ConsoleClient
)

# The whole point of the Android branch is the ELF type, so state it rather
# than leave it to be discovered on a device: ET_DYN, never ET_EXEC.
if [ "$goos" = "android" ] && command -v readelf >/dev/null 2>&1; then
    if ! readelf -h "$out/psiphon-tunnel-core$ext" 2>/dev/null | grep -q "DYN"; then
        echo "psiphon-build: $out/psiphon-tunnel-core$ext is not a PIE (ET_DYN) - Android will refuse to run it" >&2
        exit 1
    fi
fi

echo "psiphon-build: $goos/$goarch ->"
ls -l "$out/psiphon-tunnel-core$ext"
