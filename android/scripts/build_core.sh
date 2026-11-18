#!/usr/bin/env bash
# Cross-compile libslic3r and the app's JNI library liborca_core.so (android/native).
# Needs the deps prefix from build_deps.sh. Usage: build_core.sh [abi] [targets...]
set -euo pipefail

ABI=${1:-arm64-v8a}; shift || true
TARGETS=("${@:-orca_core}")
API=${ANDROID_API:-29}
TOOLS=${ANDROID_TOOLS:-$HOME/android-tools}
NDK=${ANDROID_NDK:-$TOOLS/android-ndk-r29}
CMAKE=${CMAKE:-$TOOLS/cmake-4.5.0-linux-x86_64/bin/cmake}
OUT=${ORCA_ANDROID_OUT:-$HOME/orca-build}
SRC=$(cd "$(dirname "$0")/../.." && pwd)
PREFIX="$OUT/prefix-$ABI/usr/local"

export PATH="$TOOLS/bin:$PATH"

BUILD="$OUT/core-$ABI"
# OpenCV's Android install layout keeps its package config under sdk/native/jni.
OPENCV_CONFIG=$(find "$PREFIX" -name OpenCVConfig.cmake | awk '{ print length, $0 }' | sort -n | head -1 | cut -d' ' -f2-)
"$CMAKE" -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="$API" -DANDROID_STL=c++_shared \
    -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
    -DOpenCV_DIR="$(dirname "$OPENCV_CONFIG")" \
    -DSLIC3R_STATIC=ON -DSLIC3R_PCH=OFF -DSLIC3R_ENC_CHECK=OFF -DSLIC3R_CAD=OFF -DSLIC3R_OPENVDB=OFF \
    -DBUILD_TESTS="${BUILD_TESTS:-OFF}"

# Low-RAM hosts: big libslic3r translation units need 1-2 GB each.
"$CMAKE" --build "$BUILD" --target "${TARGETS[@]}" -j "${JOBS:-3}" -- -k 0 # report every compile error in one pass
