// The preview's controls and readouts over libvgcode (Scene.cpp owns the viewer): layer and
// move ranges, the tool at the current move, view type, line-type visibility, the colour
// scheme legend, and toolpath export. All on the GL thread.

#include "Session.hpp"

#include "LibVGCode/LibVGCodeWrapper.hpp"

#include <cstdio>
#include <map>
#include <string>

using json = nlohmann::json;

libvgcode::Viewer &preview_viewer();
bool preview_ready();

// Layers and move ranges for the preview sliders. GL thread (the viewer lives there).
JNI(jstring, previewInfo)(JNIEnv *env, jobject)
{
    libvgcode::Viewer &viewer = preview_viewer();
    json out;
    out["loaded"] = preview_ready();
    if (preview_ready()) {
        out["zs"]      = viewer.get_layers_zs();
        const auto lr  = viewer.get_layers_view_range();
        out["layers"]  = {lr[0], lr[1]};
        const auto fr  = viewer.get_view_full_range();
        const auto vr  = viewer.get_view_visible_range();
        out["full"]    = {fr[0], fr[1]};
        const auto er  = viewer.get_view_enabled_range();
        out["enabled"] = {er[0], er[1]};
        out["visible"] = {vr[0], vr[1]};
        json colors = json::object();
        for (libvgcode::EGCodeExtrusionRole r : viewer.get_extrusion_roles()) {
            const auto &c = viewer.get_extrusion_role_color(r);
            const int slic3r_role = int(libvgcode::convert(r));
            char hex[8];
            snprintf(hex, sizeof(hex), "#%02X%02X%02X", c[0], c[1], c[2]);
            colors[std::to_string(slic3r_role)] = {{"color", hex}, {"visible", viewer.is_extrusion_role_visible(r)}};
        }
        out["roles"] = colors;
    }
    return env->NewStringUTF(out.dump().c_str());
}

// The moves slider's state: the top layer's move range and the tool at the current move. GL thread.
JNI(jstring, previewCurrent)(JNIEnv *env, jobject)
{
    libvgcode::Viewer &viewer = preview_viewer();
    json out = json::object();
    if (preview_ready()) {
        const auto er = viewer.get_view_enabled_range();
        const auto vr = viewer.get_view_visible_range();
        const libvgcode::PathVertex &v = viewer.get_current_vertex();
        out = {{"enabled", {er[0], er[1]}}, {"visible", {vr[0], vr[1]}},
               {"x", v.position[0]}, {"y", v.position[1]}, {"z", v.position[2]},
               {"speed", v.feedrate}, {"line", v.gcode_id}, {"width", v.width}, {"height", v.height}};
    }
    return env->NewStringUTF(out.dump().c_str());
}

JNI(void, previewSetLayers)(JNIEnv *, jobject, jint lo, jint hi)
{
    libvgcode::Viewer &viewer = preview_viewer();
    if (preview_ready()) viewer.set_layers_view_range(size_t(lo), size_t(hi));
}

JNI(void, previewSetMoves)(JNIEnv *, jobject, jint lo, jint hi)
{
    libvgcode::Viewer &viewer = preview_viewer();
    if (preview_ready()) viewer.set_view_visible_range(size_t(lo), size_t(hi));
}

JNI(void, previewSetViewType)(JNIEnv *, jobject, jint type)
{
    libvgcode::Viewer &viewer = preview_viewer();
    if (preview_ready()) viewer.set_view_type(libvgcode::EViewType(type));
}

// role: a Slic3r::ExtrusionRole, as in the slice statistics.
JNI(void, previewToggleRole)(JNIEnv *, jobject, jint role)
{
    libvgcode::Viewer &viewer = preview_viewer();
    if (preview_ready()) viewer.toggle_extrusion_role_visibility(libvgcode::convert(Slic3r::ExtrusionRole(role)));
}

JNI(void, previewToggleTravel)(JNIEnv *, jobject)
{
    libvgcode::Viewer &viewer = preview_viewer();
    if (preview_ready()) viewer.toggle_option_visibility(libvgcode::EOptionType::Travels);
}

// The colour scheme's legend: a value range with its colours (speed, height, width, flow, fan,
// temperature, ...), or the filament/tool colours. GL thread.
JNI(jstring, previewLegend)(JNIEnv *env, jobject, jint type)
{
    libvgcode::Viewer &viewer = preview_viewer();
    json out = json::object();
    if (!preview_ready()) return env->NewStringUTF(out.dump().c_str());
    auto hex = [](uint8_t r, uint8_t g, uint8_t b) {
        char h[8];
        snprintf(h, sizeof(h), "#%02X%02X%02X", r, g, b);
        return std::string(h);
    };
    const auto vt = libvgcode::EViewType(type);
    if (vt == libvgcode::EViewType::ColorPrint || vt == libvgcode::EViewType::Tool) {
        json colors = json::array();
        for (const auto &c : vt == libvgcode::EViewType::Tool ? viewer.get_tool_colors() : viewer.get_color_print_colors())
            colors.push_back(hex(c[0], c[1], c[2]));
        out = {{"kind", "list"}, {"colors", colors}};
    } else if (vt != libvgcode::EViewType::Summary && vt != libvgcode::EViewType::FeatureType) {
        const libvgcode::ColorRange &range = viewer.get_color_range(vt);
        json values = json::array(), colors = json::array();
        std::vector<float> v = range.get_values();
        if (v.size() < 2) v = {range.get_range()[0], range.get_range()[1]};
        for (float x : v) {
            const libvgcode::Color c = range.get_color_at(x);
            values.push_back(x);
            colors.push_back(hex(c[0], c[1], c[2]));
        }
        out = {{"kind", "range"}, {"values", values}, {"colors", colors}, {"log", range.get_type() == libvgcode::EColorRangeType::Logarithmic}};
    }
    return env->NewStringUTF(out.dump().c_str());
}

// Writes the visible toolpaths as an OBJ (+ MTL) of square tubes in their preview colours
// (desktop "Export toolpaths as OBJ"). GL thread. Returns false when there is nothing to write.
JNI(jboolean, exportToolpaths)(JNIEnv *env, jobject, jstring jpath)
{
    libvgcode::Viewer &viewer = preview_viewer();
    if (!preview_ready()) return JNI_FALSE;
    const char *c = env->GetStringUTFChars(jpath, nullptr);
    const std::string path(c);
    env->ReleaseStringUTFChars(jpath, c);
    const std::string mtl_path = path.substr(0, path.find_last_of('.')) + ".mtl";
    FILE *obj = fopen(path.c_str(), "w"), *mtl = fopen(mtl_path.c_str(), "w");
    if (!obj || !mtl) { if (obj) fclose(obj); if (mtl) fclose(mtl); return JNI_FALSE; }
    fprintf(obj, "# OrcaSlicer toolpaths\nmtllib %s\n", mtl_path.substr(mtl_path.find_last_of('/') + 1).c_str());
    std::map<uint32_t, int> materials;
    const auto vr = viewer.get_view_visible_range();
    size_t vertices = 0;
    for (size_t i = std::max<size_t>(vr[0], 1); i <= vr[1] && i < viewer.get_vertices_count(); ++i) {
        const libvgcode::PathVertex &a = viewer.get_vertex_at(i - 1), &b = viewer.get_vertex_at(i);
        if (!b.is_extrusion() || !viewer.is_extrusion_role_visible(b.role)) continue;
        const libvgcode::Color col = viewer.get_vertex_color(b);
        const uint32_t key = (uint32_t(col[0]) << 16) | (uint32_t(col[1]) << 8) | col[2];
        if (!materials.count(key)) {
            const int id = int(materials.size());
            materials[key] = id;
            fprintf(mtl, "newmtl m%d\nKd %.3f %.3f %.3f\n", id, col[0] / 255.f, col[1] / 255.f, col[2] / 255.f);
        }
        const Slic3r::Vec3f p0(a.position[0], a.position[1], a.position[2]), p1(b.position[0], b.position[1], b.position[2]);
        Slic3r::Vec3f d = p1 - p0;
        d.z() = 0.f;
        if (d.norm() < 1e-6f) continue;
        const Slic3r::Vec3f side = Slic3r::Vec3f(-d.y(), d.x(), 0.f).normalized() * (0.5f * b.width);
        const Slic3r::Vec3f down(0.f, 0.f, -b.height);
        const Slic3r::Vec3f q[8] = {p0 + side, p0 - side, p0 - side + down, p0 + side + down,
                                    p1 + side, p1 - side, p1 - side + down, p1 + side + down};
        for (const auto &v : q) fprintf(obj, "v %.3f %.3f %.3f\n", v.x(), v.y(), v.z());
        fprintf(obj, "usemtl m%d\n", materials[key]);
        const size_t o = vertices + 1;
        const int f[6][4] = {{0, 4, 5, 1}, {1, 5, 6, 2}, {2, 6, 7, 3}, {3, 7, 4, 0}, {0, 1, 2, 3}, {4, 7, 6, 5}};
        for (const auto &face : f)
            fprintf(obj, "f %zu %zu %zu %zu\n", o + face[0], o + face[1], o + face[2], o + face[3]);
        vertices += 8;
    }
    fclose(obj);
    fclose(mtl);
    return vertices > 0 ? JNI_TRUE : JNI_FALSE;
}
