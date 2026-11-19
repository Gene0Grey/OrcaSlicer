// GLES 3.0 renderer for the app's 3D view: the plate (bed, grid, axes) and its objects in the
// Prepare view, the libvgcode toolpaths in the Preview view. Runs on the GLSurfaceView thread.
// Camera matrices come from Kotlin (column-major, like android.opengl.Matrix and libvgcode).

#include "Gizmo.hpp"
#include "Session.hpp"

#include "LibVGCode/LibVGCodeWrapper.hpp"

#include <GLES3/gl3.h>
#include <nlohmann/json.hpp>

#include <cmath>
#include <string>
#include <vector>

using json = nlohmann::json;

namespace {

struct Program
{
    GLuint id = 0;
    GLint  mvp = -1, view = -1, color = -1;
};

struct Mesh
{
    GLuint  vbo   = 0;
    GLsizei count = 0;
};

GLuint compile(GLenum type, const char *src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    return s;
}

Program make_program(const char *vs, const char *fs)
{
    Program p;
    p.id = glCreateProgram();
    glAttachShader(p.id, compile(GL_VERTEX_SHADER, vs));
    glAttachShader(p.id, compile(GL_FRAGMENT_SHADER, fs));
    glBindAttribLocation(p.id, 0, "a_pos");
    glBindAttribLocation(p.id, 1, "a_nrm");
    glLinkProgram(p.id);
    p.mvp   = glGetUniformLocation(p.id, "u_mvp");
    p.view  = glGetUniformLocation(p.id, "u_view");
    p.color = glGetUniformLocation(p.id, "u_color");
    return p;
}

const char *MESH_VS = R"(#version 300 es
in vec3 a_pos; in vec3 a_nrm;
uniform mat4 u_mvp; uniform mat4 u_view;
out vec3 v_n;
void main() { gl_Position = u_mvp * vec4(a_pos, 1.0); v_n = mat3(u_view) * a_nrm; })";

// Two directional lights in eye space, the same set-up the desktop gouraud_light shader uses.
const char *MESH_FS = R"(#version 300 es
precision mediump float;
in vec3 v_n; uniform vec4 u_color; out vec4 frag;
void main() {
    vec3 n = normalize(v_n);
    float l = 0.3 + 0.6 * max(dot(n, normalize(vec3(-0.457, 0.457, 0.762))), 0.0)
                  + 0.3 * max(dot(n, normalize(vec3(0.6, 0.6, 1.0))), 0.0);
    frag = vec4(u_color.rgb * min(l, 1.2), u_color.a);
})";

const char *FLAT_VS = R"(#version 300 es
in vec3 a_pos; uniform mat4 u_mvp;
void main() { gl_Position = u_mvp * vec4(a_pos, 1.0); })";

const char *FLAT_FS = R"(#version 300 es
precision mediump float;
uniform vec4 u_color; out vec4 frag;
void main() { frag = u_color; })";

struct GLState
{
    bool                ready = false;
    Program             mesh, flat;
    Mesh                bed_fill, bed_grid, bed_border, axes[3];
    // One draw item per object volume: solid parts opaque, modifiers / negative parts see-through.
    struct Item { Mesh mesh; std::array<float, 4> color; };
    std::vector<Item>   items;
    uint64_t            scene_version   = 0;
    uint64_t            preview_version = 0;
    libvgcode::Viewer   viewer;
    bool                viewer_ready  = false;
    bool                preview_loaded = false;
    int                 width = 1, height = 1;
} gl;

Mesh upload(const std::vector<float> &data, int floats_per_vertex)
{
    Mesh m;
    glGenBuffers(1, &m.vbo);
    glBindBuffer(GL_ARRAY_BUFFER, m.vbo);
    glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(data.size() * sizeof(float)), data.data(), GL_STATIC_DRAW);
    m.count = GLsizei(data.size() / floats_per_vertex);
    return m;
}

void release(Mesh &m)
{
    if (m.vbo) glDeleteBuffers(1, &m.vbo);
    m = {};
}

std::array<float, 4> parse_color(const std::string &hex, float alpha = 1.f)
{
    unsigned v = 0x26A69A;
    if (hex.size() >= 7 && hex[0] == '#')
        v = unsigned(std::stoul(hex.substr(1, 6), nullptr, 16));
    return {((v >> 16) & 255) / 255.f, ((v >> 8) & 255) / 255.f, (v & 255) / 255.f, alpha};
}

// Rebuilds bed and object buffers from the session. Caller holds the session mutex.
void rebuild_scene()
{
    release(gl.bed_fill); release(gl.bed_grid); release(gl.bed_border);
    for (Mesh &a : gl.axes) release(a);
    for (auto &it : gl.items) release(it.mesh);
    gl.items.clear();
    if (!g_session.bundle) return;

    const auto *pa = g_session.bundle->printers.get_edited_preset().config.option<Slic3r::ConfigOptionPoints>("printable_area");
    if (pa && pa->values.size() >= 3) {
        std::vector<float> fill, border;
        const Slic3r::Vec2d c0 = pa->values.front();
        Slic3r::BoundingBoxf bb;
        for (size_t i = 0; i < pa->values.size(); ++i) {
            const Slic3r::Vec2d a = pa->values[i], b = pa->values[(i + 1) % pa->values.size()];
            bb.merge(a);
            if (i > 0 && i + 1 < pa->values.size())
                fill.insert(fill.end(), {float(c0.x()), float(c0.y()), -0.02f, float(a.x()), float(a.y()), -0.02f, float(b.x()), float(b.y()), -0.02f});
            border.insert(border.end(), {float(a.x()), float(a.y()), 0.f, float(b.x()), float(b.y()), 0.f});
        }
        gl.bed_fill   = upload(fill, 3);
        gl.bed_border = upload(border, 3);
        std::vector<float> grid;
        for (double x = std::ceil(bb.min.x() / 10.) * 10.; x <= bb.max.x(); x += 10.)
            grid.insert(grid.end(), {float(x), float(bb.min.y()), 0.f, float(x), float(bb.max.y()), 0.f});
        for (double y = std::ceil(bb.min.y() / 10.) * 10.; y <= bb.max.y(); y += 10.)
            grid.insert(grid.end(), {float(bb.min.x()), float(y), 0.f, float(bb.max.x()), float(y), 0.f});
        gl.bed_grid = upload(grid, 3);
        const float len = float(std::max(bb.size().x(), bb.size().y()) * 0.15);
        gl.axes[0] = upload({0, 0, 0, len, 0, 0}, 3);
        gl.axes[1] = upload({0, 0, 0, 0, len, 0}, 3);
        gl.axes[2] = upload({0, 0, 0, 0, 0, len}, 3);
    }

    const auto *colors = g_session.bundle->project_config.option<Slic3r::ConfigOptionStrings>("filament_colour");
    auto filament_color = [colors](int ext) {
        const size_t i = size_t(std::max(1, ext) - 1);
        return parse_color(colors && i < colors->values.size() ? colors->values[i] : "#26A69A");
    };
    for (size_t oi = 0; oi < g_session.model.objects.size(); ++oi) {
        const Slic3r::ModelObject *o = g_session.model.objects[oi];
        const int obj_ext = o->config.has("extruder") ? o->config.opt_int("extruder") : 0;
        const bool outside = g_session.outside(*o), picked = g_session.picked.count(int(oi)) > 0;
        const bool printable = o->instances.empty() || o->instances.front()->printable;
        for (const Slic3r::ModelVolume *v : o->volumes) {
            std::vector<float> data;
            for (const Slic3r::ModelInstance *inst : o->instances) {
                const Slic3r::Transform3d t = inst->get_matrix() * v->get_matrix();
                const indexed_triangle_set &its = v->mesh().its;
                for (const auto &f : its.indices) {
                    const Slic3r::Vec3d p0 = t * its.vertices[f[0]].cast<double>();
                    const Slic3r::Vec3d p1 = t * its.vertices[f[1]].cast<double>();
                    const Slic3r::Vec3d p2 = t * its.vertices[f[2]].cast<double>();
                    const Slic3r::Vec3d n = (p1 - p0).cross(p2 - p0).normalized();
                    for (const Slic3r::Vec3d &p : {p0, p1, p2})
                        data.insert(data.end(), {float(p.x()), float(p.y()), float(p.z()), float(n.x()), float(n.y()), float(n.z())});
                }
            }
            std::array<float, 4> rgba;
            switch (v->type()) {
            case Slic3r::ModelVolumeType::NEGATIVE_VOLUME:    rgba = {0.55f, 0.57f, 0.60f, 0.40f}; break;
            case Slic3r::ModelVolumeType::PARAMETER_MODIFIER: rgba = {1.00f, 0.69f, 0.00f, 0.35f}; break; // board amber
            case Slic3r::ModelVolumeType::SUPPORT_BLOCKER:    rgba = {0.90f, 0.28f, 0.30f, 0.35f}; break;
            case Slic3r::ModelVolumeType::SUPPORT_ENFORCER:   rgba = {0.30f, 0.55f, 0.95f, 0.35f}; break;
            default: {
                const int ext = v->config.has("extruder") && v->config.opt_int("extruder") > 0 ? v->config.opt_int("extruder") : obj_ext;
                rgba = filament_color(ext);
                if (!printable) rgba = {0.36f, 0.37f, 0.39f, 1.f};
                if (outside)        // off the plate: board "cancelled" red
                    rgba = {0.90f, 0.28f, 0.30f, 1.f};
                else if (picked)    // picked: half way to Orca teal, lifted
                    rgba = {rgba[0] * 0.5f + 0.06f, rgba[1] * 0.5f + 0.38f, rgba[2] * 0.5f + 0.34f, 1.f};
            }
            }
            gl.items.push_back({upload(data, 6), rgba});
        }
    }
}

void load_preview()
{
    gl.preview_loaded = false;
    if (!gl.viewer_ready || !g_session.sliced || !g_session.result) return;
    std::vector<std::string> colors;
    if (const auto *c = g_session.bundle->project_config.option<Slic3r::ConfigOptionStrings>("filament_colour"))
        colors = c->values;
    if (colors.empty()) colors.push_back("#26A69A");
    libvgcode::GCodeInputData data = libvgcode::convert(*g_session.result, colors, colors, gl.viewer);
    gl.viewer.load(std::move(data));
    // As on the desktop: the moves slider walks the top layer only.
    if (!gl.viewer.is_top_layer_only_view_range()) gl.viewer.toggle_top_layer_only_view_range();
    gl.preview_loaded = true;
}

void draw_lines(const Mesh &m, const float *mvp, std::array<float, 4> color)
{
    if (!m.count) return;
    glUniformMatrix4fv(gl.flat.mvp, 1, GL_FALSE, mvp);
    glUniform4fv(gl.flat.color, 1, color.data());
    glBindBuffer(GL_ARRAY_BUFFER, m.vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_LINES, 0, m.count);
}

void mul(const float *a, const float *b, float *out) // column-major a*b
{
    for (int c = 0; c < 4; ++c)
        for (int r = 0; r < 4; ++r) {
            float s = 0;
            for (int k = 0; k < 4; ++k) s += a[k * 4 + r] * b[c * 4 + k];
            out[c * 4 + r] = s;
        }
}

} // namespace


JNI(void, glInit)(JNIEnv *, jobject)
{
    // A new EGL context (first start or after the surface was lost): old GL names are gone.
    if (gl.viewer_ready) gl.viewer.shutdown();
    gl.items.clear();
    gl.bed_fill = gl.bed_grid = gl.bed_border = Mesh();
    for (Mesh &a : gl.axes) a = Mesh();
    gl.scene_version = gl.preview_version = 0;
    gl.preview_loaded = false;
    gl.mesh = make_program(MESH_VS, MESH_FS);
    gl.flat = make_program(FLAT_VS, FLAT_FS);
    try {
        gl.viewer.init(reinterpret_cast<const char *>(glGetString(GL_VERSION)));
        gl.viewer_ready = true;
    } catch (const std::exception &) {
        gl.viewer_ready = false;
    }
    gl.ready = true;
}

JNI(void, glResize)(JNIEnv *, jobject, jint w, jint h)
{
    gl.width = w; gl.height = h;
    glViewport(0, 0, w, h);
}

// mode: 0 prepare, 1 preview. bg: rgb.
JNI(void, glRender)(JNIEnv *env, jobject, jint mode, jfloatArray jview, jfloatArray jproj, jfloatArray jbg)
{
    float view[16], proj[16], bg[3], mvp[16];
    env->GetFloatArrayRegion(jview, 0, 16, view);
    env->GetFloatArrayRegion(jproj, 0, 16, proj);
    env->GetFloatArrayRegion(jbg, 0, 3, bg);
    mul(proj, view, mvp);
    std::vector<GizmoLines> gizmo;
    {
        std::lock_guard lock(g_session.mutex);
        if (mode == 0) gizmo = gizmo_geometry();
        if (gl.scene_version != g_session.scene_version) {
            rebuild_scene();
            gl.scene_version = g_session.scene_version;
        }
        if (mode == 1 && gl.preview_version != g_session.preview_version) {
            load_preview();
            gl.preview_version = g_session.preview_version;
        }
    }

    glClearColor(bg[0], bg[1], bg[2], 1.f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    // Plate
    glUseProgram(gl.flat.id);
    if (gl.bed_fill.count) {
        glUniformMatrix4fv(gl.flat.mvp, 1, GL_FALSE, mvp);
        const std::array<float, 4> fill{0.165f, 0.176f, 0.192f, 1.f}; // brushed steel
        glUniform4fv(gl.flat.color, 1, fill.data());
        glBindBuffer(GL_ARRAY_BUFFER, gl.bed_fill.vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 0, nullptr);
        glDrawArrays(GL_TRIANGLES, 0, gl.bed_fill.count);
    }
    draw_lines(gl.bed_grid, mvp, {0.26f, 0.28f, 0.30f, 1.f});
    glLineWidth(3.f);
    draw_lines(gl.bed_border, mvp, {0.0f, 0.59f, 0.53f, 1.f}); // Orca teal plate edge
    glLineWidth(3.f);
    draw_lines(gl.axes[0], mvp, {0.85f, 0.15f, 0.15f, 1.f});
    draw_lines(gl.axes[1], mvp, {0.15f, 0.75f, 0.15f, 1.f});
    draw_lines(gl.axes[2], mvp, {0.15f, 0.25f, 0.9f, 1.f});
    glLineWidth(1.f);
    glDisableVertexAttribArray(0);

    if (mode == 0) {
        glUseProgram(gl.mesh.id);
        glUniformMatrix4fv(gl.mesh.mvp, 1, GL_FALSE, mvp);
        glUniformMatrix4fv(gl.mesh.view, 1, GL_FALSE, view);
        // Solid parts first, then the see-through volumes without writing depth.
        for (int pass = 0; pass < 2; ++pass) {
            glDepthMask(pass == 0 ? GL_TRUE : GL_FALSE);
            for (const auto &it : gl.items) {
                if (!it.mesh.count || (it.color[3] < 1.f) != (pass == 1)) continue;
                glUniform4fv(gl.mesh.color, 1, it.color.data());
                glBindBuffer(GL_ARRAY_BUFFER, it.mesh.vbo);
                glEnableVertexAttribArray(0);
                glEnableVertexAttribArray(1);
                glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, 6 * sizeof(float), nullptr);
                glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, 6 * sizeof(float), reinterpret_cast<void *>(3 * sizeof(float)));
                glDrawArrays(GL_TRIANGLES, 0, it.mesh.count);
            }
        }
        glDepthMask(GL_TRUE);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        // Gizmo handles on top of everything, thick for touch.
        if (!gizmo.empty()) {
            glDisable(GL_DEPTH_TEST);
            glUseProgram(gl.flat.id);
            glLineWidth(6.f);
            for (const GizmoLines &g : gizmo) {
                Mesh m = upload(g.xyz, 3);
                draw_lines(m, mvp, g.color);
                release(m);
            }
            glLineWidth(1.f);
            glDisableVertexAttribArray(0);
            glEnable(GL_DEPTH_TEST);
        }
    } else if (gl.preview_loaded) {
        libvgcode::Mat4x4 v, p;
        std::copy(view, view + 16, v.begin());
        std::copy(proj, proj + 16, p.begin());
        gl.viewer.render(v, p);
        if (gl.viewer.has_translucent_toolpaths())
            gl.viewer.render_translucent(v, p);
    }
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glUseProgram(0);
}

// Index of the object under a screen point, or -1. Runs on the caller's thread.
JNI(jint, pick)(JNIEnv *env, jobject, jfloat x, jfloat y, jint w, jint h, jfloatArray jview, jfloatArray jproj)
{
    float view[16], proj[16], vp[16];
    env->GetFloatArrayRegion(jview, 0, 16, view);
    env->GetFloatArrayRegion(jproj, 0, 16, proj);
    mul(proj, view, vp);
    const Eigen::Matrix4d inv = Eigen::Map<Eigen::Matrix4f>(vp).cast<double>().inverse();
    const double nx = 2. * x / w - 1., ny = 1. - 2. * y / h;
    Eigen::Vector4d a = inv * Eigen::Vector4d(nx, ny, -1., 1.), b = inv * Eigen::Vector4d(nx, ny, 1., 1.);
    const Slic3r::Vec3d o = a.head<3>() / a.w(), d = (b.head<3>() / b.w() - o).normalized();

    std::lock_guard lock(g_session.mutex);
    int best = -1;
    double best_t = 1e30;
    for (size_t oi = 0; oi < g_session.model.objects.size(); ++oi) {
        const Slic3r::ModelObject *obj = g_session.model.objects[oi];
        for (const Slic3r::ModelInstance *inst : obj->instances)
            for (const Slic3r::ModelVolume *v : obj->volumes) {
                if (!v->is_model_part()) continue;
                const Slic3r::Transform3d t = inst->get_matrix() * v->get_matrix();
                const auto &its = v->mesh().its;
                for (const auto &f : its.indices) {
                    // Möller–Trumbore
                    const Slic3r::Vec3d p0 = t * its.vertices[f[0]].cast<double>(), e1 = t * its.vertices[f[1]].cast<double>() - p0,
                                        e2 = t * its.vertices[f[2]].cast<double>() - p0;
                    const Slic3r::Vec3d pv = d.cross(e2);
                    const double det = e1.dot(pv);
                    if (std::abs(det) < 1e-12) continue;
                    const Slic3r::Vec3d tv = o - p0;
                    const double u = tv.dot(pv) / det;
                    if (u < 0. || u > 1.) continue;
                    const Slic3r::Vec3d qv = tv.cross(e1);
                    const double vv = d.dot(qv) / det;
                    if (vv < 0. || u + vv > 1.) continue;
                    const double dist = e2.dot(qv) / det;
                    if (dist > 0. && dist < best_t) { best_t = dist; best = int(oi); }
                }
            }
    }
    return best;
}

// Layers and move ranges for the preview sliders. GL thread (the viewer lives there).
JNI(jstring, previewInfo)(JNIEnv *env, jobject)
{
    json out;
    out["loaded"] = gl.preview_loaded;
    if (gl.preview_loaded) {
        out["zs"]      = gl.viewer.get_layers_zs();
        const auto lr  = gl.viewer.get_layers_view_range();
        out["layers"]  = {lr[0], lr[1]};
        const auto fr  = gl.viewer.get_view_full_range();
        const auto vr  = gl.viewer.get_view_visible_range();
        out["full"]    = {fr[0], fr[1]};
        const auto er  = gl.viewer.get_view_enabled_range();
        out["enabled"] = {er[0], er[1]};
        out["visible"] = {vr[0], vr[1]};
        json colors = json::object();
        for (libvgcode::EGCodeExtrusionRole r : gl.viewer.get_extrusion_roles()) {
            const auto &c = gl.viewer.get_extrusion_role_color(r);
            const int slic3r_role = int(libvgcode::convert(r));
            char hex[8];
            snprintf(hex, sizeof(hex), "#%02X%02X%02X", c[0], c[1], c[2]);
            colors[std::to_string(slic3r_role)] = {{"color", hex}, {"visible", gl.viewer.is_extrusion_role_visible(r)}};
        }
        out["roles"] = colors;
    }
    return env->NewStringUTF(out.dump().c_str());
}

// The moves slider's state: the top layer's move range and the tool at the current move. GL thread.
JNI(jstring, previewCurrent)(JNIEnv *env, jobject)
{
    json out = json::object();
    if (gl.preview_loaded) {
        const auto er = gl.viewer.get_view_enabled_range();
        const auto vr = gl.viewer.get_view_visible_range();
        const libvgcode::PathVertex &v = gl.viewer.get_current_vertex();
        out = {{"enabled", {er[0], er[1]}}, {"visible", {vr[0], vr[1]}},
               {"x", v.position[0]}, {"y", v.position[1]}, {"z", v.position[2]},
               {"speed", v.feedrate}, {"line", v.gcode_id}, {"width", v.width}, {"height", v.height}};
    }
    return env->NewStringUTF(out.dump().c_str());
}

JNI(void, previewSetLayers)(JNIEnv *, jobject, jint lo, jint hi)
{
    if (gl.preview_loaded) gl.viewer.set_layers_view_range(size_t(lo), size_t(hi));
}

JNI(void, previewSetMoves)(JNIEnv *, jobject, jint lo, jint hi)
{
    if (gl.preview_loaded) gl.viewer.set_view_visible_range(size_t(lo), size_t(hi));
}

JNI(void, previewSetViewType)(JNIEnv *, jobject, jint type)
{
    if (gl.preview_loaded) gl.viewer.set_view_type(libvgcode::EViewType(type));
}

// role: a Slic3r::ExtrusionRole, as in the slice statistics.
JNI(void, previewToggleRole)(JNIEnv *, jobject, jint role)
{
    if (gl.preview_loaded) gl.viewer.toggle_extrusion_role_visibility(libvgcode::convert(Slic3r::ExtrusionRole(role)));
}

JNI(void, previewToggleTravel)(JNIEnv *, jobject)
{
    if (gl.preview_loaded) gl.viewer.toggle_option_visibility(libvgcode::EOptionType::Travels);
}
