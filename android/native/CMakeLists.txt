# Android-only targets on top of libslic3r. Added from the root CMakeLists when ANDROID.
add_executable(orca_android_slice orca_android_slice.cpp)
target_link_libraries(orca_android_slice libslic3r nanosvg cereal::cereal Threads::Threads log)
