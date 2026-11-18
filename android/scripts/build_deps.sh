#!/usr/bin/env bash
# Cross-compile the libslic3r dependency set for Android with the NDK.
# Usage: build_deps.sh [abi]   (default arm64-v8a). Run on a Linux host (WSL works).
set -euo pipefail

ABI=${1:-arm64-v8a}
API=${ANDROID_API:-29}
TOOLS=${ANDROID_TOOLS:-$HOME/android-tools}
NDK=${ANDROID_NDK:-$TOOLS/android-ndk-r29}
CMAKE=${CMAKE:-$TOOLS/cmake-4.5.0-linux-x86_64/bin/cmake}
OUT=${ORCA_ANDROID_OUT:-$HOME/orca-build}
SRC=$(cd "$(dirname "$0")/../.." && pwd)

export PATH="$TOOLS/bin:$PATH"
# Low-RAM hosts: CGAL/OCCT translation units need ~1-2 GB each.
export CMAKE_BUILD_PARALLEL_LEVEL=${JOBS:-3}

BUILD="$OUT/deps-$ABI"
"$CMAKE" -S "$SRC/deps" -B "$BUILD" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="$API" -DANDROID_STL=c++_static \
    -DDEP_DOWNLOAD_DIR="$OUT/DL_CACHE" \
    -DDESTDIR="$OUT/prefix-$ABI" \
    -DSLIC3R_CAD=OFF -DSLIC3R_OPENVDB=OFF

TARGETS=${TARGETS:-"dep_EXPAT dep_PNG dep_JPEG dep_Boost dep_TBB dep_OpenSSL dep_CURL dep_Cereal dep_Qhull
    dep_GMP dep_MPFR dep_Eigen dep_CGAL dep_NLopt dep_libnoise dep_Draco dep_Assimp dep_OCCT dep_OpenCV"}
for t in $TARGETS; do
    echo "=== $t"
    "$CMAKE" --build "$BUILD" --target "$t"
done
echo "deps installed to $OUT/prefix-$ABI"
