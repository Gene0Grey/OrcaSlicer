# Android-only targets on top of libslic3r. Added from the root CMakeLists when ANDROID.

# libvgcode draws the G-code preview; its GLES path loads GL functions itself (glad).
set(SLIC3R_OPENGL_ES ON)
add_subdirectory(${CMAKE_SOURCE_DIR}/src/libvgcode ${CMAKE_CURRENT_BINARY_DIR}/libvgcode)
# Use the platform's EGL headers for eglGetProcAddress instead of glad's own egl loader.
target_compile_definitions(libvgcode PRIVATE GLAD_GLES2_USE_SYSTEM_EGL=1)
# glad null-checks eglGetProcAddress, which the system header declares as a function.
target_compile_options(libvgcode PRIVATE -Wno-tautological-pointer-compare)

# liborca_core.so: the JNI library the app loads (OrcaCore.kt).
add_library(orca_core SHARED
    jni/OrcaCore.cpp
    jni/Scene.cpp
    jni/Gizmo.cpp
    jni/Objects.cpp
    jni/Edit.cpp
    jni/Plates.cpp
    jni/Slice.cpp
    jni/Project.cpp
    jni/Presets.cpp
    jni/Tools.cpp
    jni/Preview.cpp
    # Desktop's GCodeProcessorResult -> libvgcode conversion, reused as is.
    ${CMAKE_SOURCE_DIR}/src/slic3r/GUI/LibVGCode/LibVGCodeWrapper.cpp
)
# shim/ first: it supplies the GUI header LibVGCodeWrapper.cpp includes but does not use.
# src/slic3r makes the wrapper's "../../src/libvgcode/..." includes resolve.
target_include_directories(orca_core PRIVATE
    shim
    ${CMAKE_SOURCE_DIR}/src
    ${CMAKE_SOURCE_DIR}/src/slic3r
    ${CMAKE_SOURCE_DIR}/src/slic3r/GUI
)
target_link_libraries(orca_core PRIVATE libslic3r libvgcode nanosvg nlohmann_json cereal::cereal GLESv3 EGL log android)
