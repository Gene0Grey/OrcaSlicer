// The desktop's painting gizmos on the picked object (support, seam, colour / MMU, fuzzy skin),
// with a sphere brush or smart fill on TriangleSelector; brim ears (tap to place, auto at sharp
// corners); and variable layer height (adaptive, smooth, brush along Z, reset).

#include "Session.hpp"

#include "libslic3r/BrimEarsPoint.hpp"
#include "libslic3r/Slicing.hpp"
#include "libslic3r/TriangleSelector.hpp"

using namespace Slic3r;
using json = nlohmann::json;

namespace {

// The stroke in progress: one selector per volume of the picked object.
std::map<int, std::unique_ptr<TriangleSelector>> g_selectors;

FacetsAnnotation &facets(ModelVolume &v, int mode)
{
    switch (mode) {
    case 1: return v.supported_facets;
    case 2: return v.seam_facets;
    case 3: return v.mmu_segmentation_facets;
    default: return v.fuzzy_skin_facets;
    }
}

ModelObject *picked()
{
    Session &s = g_session;
    return s.selected >= 0 && size_t(s.selected) < s.model.objects.size() ? s.model.objects[s.selected] : nullptr;
}

// Paints at a screen point. tool: 0 sphere brush, 1 smart fill (faces within 30 degrees).
bool paint_at(float x, float y, int w, int h, const float *view, const float *proj, int mode, int state, float radius, int tool)
{
    Session &s = g_session;
    ModelObject *o = picked();
    if (!o) return false;
    const RayHit hit = ray_hit(x, y, w, h, view, proj, s.selected);
    if (hit.obj < 0) return false;
    ModelVolume &v = *o->volumes[hit.vol];
    auto &sel = g_selectors[hit.vol];
    if (!sel) {
        sel = std::make_unique<TriangleSelector>(v.mesh());
        sel->deserialize(facets(v, mode).get_data());
    }
    const Transform3d trafo = o->instances.front()->get_matrix() * v.get_matrix();
    const Transform3d inv = trafo.inverse();
    const Vec3f local = (inv * hit.point).cast<float>();
    // Camera position (the eye) in mesh coordinates.
    const Eigen::Matrix4d view_inv = Eigen::Map<const Eigen::Matrix4f>(view).cast<double>().inverse();
    const Vec3f eye = (inv * Vec3d(view_inv(0, 3), view_inv(1, 3), view_inv(2, 3))).cast<float>();
    Transform3d no_translate = trafo;
    no_translate.translation() = Vec3d::Zero();
    if (tool == 1) {
        sel->seed_fill_select_triangles(local, hit.face, no_translate, TriangleSelector::ClippingPlane(), 30.f);
        sel->seed_fill_apply_on_triangles(EnforcerBlockerType(state));
    } else {
        sel->select_patch(hit.face, std::make_unique<TriangleSelector::Sphere>(local, eye, radius, trafo, TriangleSelector::ClippingPlane()),
                          EnforcerBlockerType(state), no_translate, true);
    }
    facets(v, mode).set(*sel);
    ++s.scene_version;
    return true;
}

SlicingParameters slicing_params(const ModelObject &o)
{
    return PrintObject::slicing_parameters(g_session.bundle->full_config(), o, float(o.max_z()), Vec3d::Ones());
}

} // namespace

// Which painting gizmo is open (0 none, 1 support, 2 seam, 3 colour, 4 fuzzy skin, 5 brim ears):
// the renderer shows that paint on the picked object.
JNI(void, paintMode)(JNIEnv *, jobject, jint mode)
{
    std::lock_guard lock(g_session.mutex);
    g_session.paint_mode = mode;
    g_selectors.clear();
    ++g_session.scene_version;
}

// A paint stroke: begin (takes an undo snapshot), move, end. Returns whether the brush hit.
JNI(jboolean, paintStroke)(JNIEnv *env, jobject, jint phase, jfloat x, jfloat y, jint w, jint h, jfloatArray jview, jfloatArray jproj,
                           jint mode, jint state, jfloat radius, jint tool)
{
    float view[16], proj[16];
    env->GetFloatArrayRegion(jview, 0, 16, view);
    env->GetFloatArrayRegion(jproj, 0, 16, proj);
    std::lock_guard lock(g_session.mutex);
    if (phase == 0) { g_selectors.clear(); if (picked()) g_session.snapshot(); }
    if (phase == 2) { g_selectors.clear(); model_changed(); return JNI_TRUE; }
    return paint_at(x, y, w, h, view, proj, mode, state, radius, tool) ? JNI_TRUE : JNI_FALSE;
}

// Clears one kind of paint on the picked object.
JNI(void, paintClear)(JNIEnv *, jobject, jint mode)
{
    std::lock_guard lock(g_session.mutex);
    ModelObject *o = picked();
    if (!o) return;
    g_session.snapshot();
    if (mode == 5) o->brim_points.clear();
    else for (ModelVolume *v : o->volumes) facets(*v, mode).reset();
    model_changed();
}

// Brim ears: add one where tapped (on the object's bottom), or auto at the bottom outline's sharp
// corners. Ears are used with brim type "painted", which this sets on the object.
JNI(jint, brimEar)(JNIEnv *env, jobject, jint cmd, jfloat x, jfloat y, jint w, jint h, jfloatArray jview, jfloatArray jproj, jfloat radius)
{
    float view[16], proj[16];
    env->GetFloatArrayRegion(jview, 0, 16, view);
    env->GetFloatArrayRegion(jproj, 0, 16, proj);
    std::lock_guard lock(g_session.mutex);
    Session &s = g_session;
    ModelObject *o = picked();
    if (!o) return 0;
    const Transform3d inv = o->instances.front()->get_matrix().inverse();
    const double zmin = o->instance_bounding_box(0).min.z();
    std::vector<Vec3d> at;
    if (cmd == 0) {
        const RayHit hit = ray_hit(x, y, w, h, view, proj, s.selected);
        if (hit.obj < 0) return int(o->brim_points.size());
        at.push_back(Vec3d(hit.point.x(), hit.point.y(), zmin));
    } else {
        // Corners of the footprint sharper than 135 degrees.
        const Polygon hull = o->instances.front()->convex_hull_2d();
        const Points &p = hull.points;
        for (size_t i = 0; i < p.size(); ++i) {
            const Vec2d a = unscale(p[(i + p.size() - 1) % p.size()]), b = unscale(p[i]), c = unscale(p[(i + 1) % p.size()]);
            const double ang = std::acos(std::clamp((a - b).normalized().dot((c - b).normalized()), -1., 1.)) * 180. / PI;
            if (ang < 135.) at.push_back(Vec3d(b.x(), b.y(), zmin));
        }
    }
    if (at.empty()) return int(o->brim_points.size());
    s.snapshot();
    for (const Vec3d &p : at) o->brim_points.emplace_back((inv * p).cast<float>(), radius);
    o->config.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btPainted));
    model_changed();
    return int(o->brim_points.size());
}

// Variable layer height of the picked object. cmd: get, adaptive {quality}, smooth {radius,
// keep_min}, adjust {z, delta, band, action: 0 increase, 1 decrease, 2 reduce, 3 smooth}, reset.
// Returns {profile: [z, h, ...], min, max, height} or {"error"}.
JNI(jstring, layerProfile)(JNIEnv *env, jobject, jstring jcmd, jstring jargs)
{
    std::lock_guard lock(g_session.mutex);
    try {
        ModelObject *o = picked();
        if (!o || !g_session.bundle) return to_j(env, json{{"error", "Pick an object first."}}.dump());
        const std::string cmd = jstr(env, jcmd), args = jstr(env, jargs);
        const json a = args.empty() ? json::object() : json::parse(args);
        const SlicingParameters sp = slicing_params(*o);
        std::vector<coordf_t> profile = o->layer_height_profile.get();
        PrintObject::update_layer_height_profile(*o, sp, profile);
        if (cmd != "get") {
            // A brush drag is one undo step: only its first event takes a snapshot.
            if (cmd != "adjust" || a.value("start", false)) g_session.snapshot();
            if (cmd == "adaptive") profile = layer_height_profile_adaptive(sp, *o, float(a.value("quality", 0.5)));
            else if (cmd == "smooth") profile = smooth_height_profile(profile, sp, HeightProfileSmoothingParams(unsigned(a.value("radius", 5)), a.value("keep_min", false)));
            else if (cmd == "adjust")
                adjust_layer_height_profile(*o, sp, profile, a.value("z", 0.), a.value("delta", 0.02), a.value("band", 2.), LayerHeightEditActionType(a.value("action", 0)));
            else if (cmd == "reset") { o->layer_height_profile.clear(); profile.clear(); PrintObject::update_layer_height_profile(*o, sp, profile); }
            if (cmd != "reset") o->layer_height_profile.set(profile);
            model_changed();
        }
        return to_j(env, json{{"profile", profile}, {"min", sp.min_layer_height}, {"max", sp.max_layer_height},
                              {"layer", sp.layer_height}, {"height", sp.object_print_z_height()}}.dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}
