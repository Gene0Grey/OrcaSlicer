#pragma once
// Stand-in for the desktop GUI header, which pulls in wxWidgets and desktop OpenGL.
// LibVGCodeWrapper.cpp needs only adjust_color_for_rendering(), copied from 3DScene.cpp.

#include "libslic3r/Color.hpp"

#include <algorithm>

inline Slic3r::ColorRGBA adjust_color_for_rendering(const Slic3r::ColorRGBA &colors)
{
    constexpr float FullyTransparentMaterialThreshold = 0.1f;
    constexpr float FULL_BLACK_THRESHOLD              = 0.2f;
    const float alpha = std::max(colors.a(), FullyTransparentMaterialThreshold);
    if (colors.r() < FULL_BLACK_THRESHOLD && colors.g() < FULL_BLACK_THRESHOLD && colors.b() < FULL_BLACK_THRESHOLD) {
        const float grey = FULL_BLACK_THRESHOLD + 0.3f * (1.0f - alpha);
        return {grey, grey, grey, alpha};
    }
    return {colors.r(), colors.g(), colors.b(), alpha};
}
