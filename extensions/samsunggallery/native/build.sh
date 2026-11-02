#!/usr/bin/env bash
# Builds libgalleryocr.so (galleryocr.cpp + the ncnn release, statically) for Samsung Gallery's "Extract text" and
# puts it where the patch picks it up: patches/src/main/resources/samsunggallery/lib/<abi>/.
#
#   ANDROID_NDK=~/android-sdk/ndk/27.2.12479018 extensions/samsunggallery/native/build.sh [abi ...]
#
# Default ABI: arm64-v8a (Gallery itself is 64-bit ARM only). x86_64 builds for emulator tests only.
# ncnn is BSD-3-Clause: its license ships next to the library (resources/samsunggallery/lib/LICENSE-ncnn.txt).
# NDK 27.2.12479018 was used; the result is stripped and has no build id.
set -euo pipefail

NCNN_VERSION=20250916
NCNN_SHA256=29bee787a120f2410708bca4e9450975ce084861ff92d9e79d0e39c92291248e
API=30

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
ndk="${ANDROID_NDK:?set ANDROID_NDK to the NDK (27.2.12479018 was used)}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
work="$here/build"
abis=("$@")
[ ${#abis[@]} -eq 0 ] && abis=(arm64-v8a)

mkdir -p "$work"
zip="$work/ncnn-$NCNN_VERSION-android.zip"
if [ ! -f "$zip" ]; then
    curl -fL -o "$zip" "https://github.com/Tencent/ncnn/releases/download/$NCNN_VERSION/ncnn-$NCNN_VERSION-android.zip"
fi
echo "$NCNN_SHA256  $zip" | sha256sum -c -
[ -d "$work/ncnn-$NCNN_VERSION-android" ] || unzip -q "$zip" -d "$work"

for abi in "${abis[@]}"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android$API ;;
        x86_64) target=x86_64-linux-android$API ;;
        *) echo "unsupported ABI $abi" >&2; exit 1 ;;
    esac
    ncnn="$work/ncnn-$NCNN_VERSION-android/$abi"
    out="$root/patches/src/main/resources/samsunggallery/lib/$abi"
    # Emulator tests only, never shipped.
    [ "$abi" = x86_64 ] && out="$work/$abi"
    mkdir -p "$out"
    "$toolchain/$target-clang++" -std=c++17 -O2 -fPIC -shared -fno-rtti -fno-exceptions \
        -ffunction-sections -fdata-sections -fvisibility=hidden \
        -I"$ncnn/include/ncnn" "$here/galleryocr.cpp" -L"$ncnn/lib" -lncnn \
        -fopenmp -static-openmp -static-libstdc++ -ljnigraphics -llog -landroid \
        -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -Wl,--build-id=none \
        -o "$out/libgalleryocr.so"
    "$toolchain/llvm-strip" --strip-unneeded "$out/libgalleryocr.so"
    ls -l "$out/libgalleryocr.so"
done
