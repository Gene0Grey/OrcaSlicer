# Android (NDK) cross build. Configure with the NDK toolchain file, e.g.
#   cmake -S deps -B deps/build-android-arm64 -G Ninja \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=29 -DSLIC3R_CAD=OFF
# then build only the dep_* targets libslic3r needs (no GUI deps exist for Android).

# The autotools recipes (GMP, MPFR) pass --host=${TOOLCHAIN_PREFIX} and CC=${CMAKE_C_COMPILER}.
# The NDK's bare clang needs --target, so hand them the API-level wrapper scripts instead.
set(TOOLCHAIN_PREFIX ${CMAKE_LIBRARY_ARCHITECTURE})
set(CMAKE_C_COMPILER   "${ANDROID_TOOLCHAIN_ROOT}/bin/${TOOLCHAIN_PREFIX}${ANDROID_PLATFORM_LEVEL}-clang")
set(CMAKE_CXX_COMPILER "${ANDROID_TOOLCHAIN_ROOT}/bin/${TOOLCHAIN_PREFIX}${ANDROID_PLATFORM_LEVEL}-clang++")

# Forward the Android toolchain settings to every CMake sub-project.
set(_android_dep_opts
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DANDROID_STL=${ANDROID_STL}
    # The NDK toolchain only searches the sysroot and CMAKE_FIND_ROOT_PATH for packages.
    -DCMAKE_FIND_ROOT_PATH=${DESTDIR}
)

include("deps-linux.cmake")

list(APPEND DEP_CMAKE_OPTS ${_android_dep_opts})
