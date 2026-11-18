#pragma once

#include "Session.hpp"

#include <array>
#include <vector>

// World-space line segments (pairs of xyz points) of one gizmo handle, in one colour.
struct GizmoLines
{
    std::vector<float>   xyz;
    std::array<float, 4> color;
};

// The active gizmo's handles for the selected object; empty when off. Caller holds the session mutex.
std::vector<GizmoLines> gizmo_geometry();
int gizmo_mode();
