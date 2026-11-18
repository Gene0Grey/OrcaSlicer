// Touch gizmos for the selected object, the mobile take on the desktop's GLGizmoMove/Rotate/Scale:
// big screen-space hit areas, drag the object body to slide it on the plate, drag an arrow to move
// along an axis, a ring to rotate about it, a corner to scale uniformly.
// Every drag rebuilds the instance transform as delta * (transform at touch down).

#include "Gizmo.hpp"

#include "libslic3r/Geometry.hpp"

#include <atomic>
#include <cmath>

using namespace Slic3r;

namespace {

enum Handle { None = 0, AxisX = 1, AxisY = 2, AxisZ = 3, Plane = 4, Uniform = 5 };

std::atomic<int> g_mode{0};
std::atomic<int> g_active{None};

struct Drag
{
    int         handle = None;
    int         object = -1;
    Transform3d start;
    Vec3d       center, ref;
    double      ref_t = 0., ref_dist = 1.;
} g_drag;

struct Camera
{
    Eigen::Matrix4d vp;
    int             w = 1, h = 1;

    Vec2d project(const Vec3d &p) const
    {
        const Eigen::Vector4d c = vp * Eigen::Vector4d(p.x(), p.y(), p.z(), 1.);
        return {(c.x() / c.w() + 1.) * 0.5 * w, (1. - c.y() / c.w()) * 0.5 * h};
    }
    void ray(double x, double y, Vec3d &o, Vec3d &d) const
    {
        const Eigen::Matrix4d inv = vp.inverse();
        const double nx = 2. * x / w - 1., ny = 1. - 2. * y / h;
        const Eigen::Vector4d a = inv * Eigen::Vector4d(nx, ny, -1., 1.), b = inv * Eigen::Vector4d(nx, ny, 1., 1.);
        o = a.head<3>() / a.w();
        d = (b.head<3>() / b.w() - o).normalized();
    }
};

Camera camera(JNIEnv *env, jint w, jint h, jfloatArray jview, jfloatArray jproj)
{
    float view[16], proj[16];
    env->GetFloatArrayRegion(jview, 0, 16, view);
    env->GetFloatArrayRegion(jproj, 0, 16, proj);
    Camera c;
    c.vp = (Eigen::Map<Eigen::Matrix4f>(proj) * Eigen::Map<Eigen::Matrix4f>(view)).cast<double>();
    c.w = w; c.h = h;
    return c;
}

const Vec3d AXES[3] = {Vec3d::UnitX(), Vec3d::UnitY(), Vec3d::UnitZ()};

ModelInstance *selected_instance()
{
    const int i = g_session.selected;
    if (i < 0 || size_t(i) >= g_session.model.objects.size() || g_session.model.objects[i]->instances.empty()) return nullptr;
    return g_session.model.objects[i]->instances.front();
}

BoundingBoxf3 selected_box() { return g_session.model.objects[g_session.selected]->instance_bounding_box(0); }
double radius(const BoundingBoxf3 &bb) { return bb.size().maxCoeff() * 0.5 + 12.; }

std::vector<Vec3d> ring(const Vec3d &c, const Vec3d &axis, double r)
{
    const Vec3d u = axis.unitOrthogonal(), v = axis.cross(u);
    std::vector<Vec3d> pts;
    for (int i = 0; i <= 64; ++i) {
        const double a = 2. * M_PI * i / 64.;
        pts.push_back(c + r * (std::cos(a) * u + std::sin(a) * v));
    }
    return pts;
}

std::vector<Vec3d> corners(const BoundingBoxf3 &bb)
{
    std::vector<Vec3d> out;
    for (int i = 0; i < 8; ++i)
        out.emplace_back(i & 1 ? bb.max.x() : bb.min.x(), i & 2 ? bb.max.y() : bb.min.y(), i & 4 ? bb.max.z() : bb.min.z());
    return out;
}

double seg_dist(const Vec2d &p, const Vec2d &a, const Vec2d &b)
{
    const Vec2d ab = b - a;
    const double t = std::clamp((p - a).dot(ab) / std::max(1e-9, ab.squaredNorm()), 0., 1.);
    return (a + t * ab - p).norm();
}

// Parameter along the line c + t*a closest to the ray o + s*d (unit vectors).
bool closest_on_axis(const Vec3d &o, const Vec3d &d, const Vec3d &c, const Vec3d &a, double &t)
{
    const Vec3d w = o - c;
    const double b = a.dot(d), denom = 1. - b * b;
    if (std::abs(denom) < 1e-6) return false;
    t = (w.dot(a) - b * w.dot(d)) / denom;
    return true;
}

bool hit_plane(const Vec3d &o, const Vec3d &d, const Vec3d &c, const Vec3d &n, Vec3d &p)
{
    const double den = d.dot(n);
    if (std::abs(den) < 1e-9) return false;
    p = o + d * ((c - o).dot(n) / den);
    return true;
}

bool ray_hits_selected(const Vec3d &o, const Vec3d &d)
{
    const ModelObject *obj = g_session.model.objects[g_session.selected];
    for (const ModelInstance *inst : obj->instances)
        for (const ModelVolume *v : obj->volumes) {
            if (!v->is_model_part()) continue;
            const Transform3d t = inst->get_matrix() * v->get_matrix();
            const auto &its = v->mesh().its;
            for (const auto &f : its.indices) {
                const Vec3d p0 = t * its.vertices[f[0]].cast<double>(), e1 = t * its.vertices[f[1]].cast<double>() - p0,
                            e2 = t * its.vertices[f[2]].cast<double>() - p0, pv = d.cross(e2);
                const double det = e1.dot(pv);
                if (std::abs(det) < 1e-12) continue;
                const Vec3d tv = o - p0;
                const double u = tv.dot(pv) / det;
                if (u < 0. || u > 1.) continue;
                const Vec3d qv = tv.cross(e1);
                const double vv = d.dot(qv) / det;
                if (vv >= 0. && u + vv <= 1. && e2.dot(qv) / det > 0.) return true;
            }
        }
    return false;
}

} // namespace

int gizmo_mode() { return g_mode; }

std::vector<GizmoLines> gizmo_geometry()
{
    std::vector<GizmoLines> out;
    if (g_mode == 0 || selected_instance() == nullptr) return out;
    const BoundingBoxf3 bb = selected_box();
    const Vec3d c = bb.center();
    const double r = radius(bb);
    const std::array<float, 4> colors[3] = {{0.93f, 0.23f, 0.23f, 1.f}, {0.25f, 0.8f, 0.3f, 1.f}, {0.25f, 0.5f, 1.f, 1.f}};
    const std::array<float, 4> active{1.f, 0.85f, 0.1f, 1.f};
    auto push = [](std::vector<float> &v, const Vec3d &a, const Vec3d &b) {
        v.insert(v.end(), {float(a.x()), float(a.y()), float(a.z()), float(b.x()), float(b.y()), float(b.z())});
    };
    if (g_mode == 1) {
        for (int i = 0; i < 3; ++i) {
            GizmoLines l{{}, g_active == i + 1 ? active : colors[i]};
            const Vec3d tip = c + AXES[i] * r, side = AXES[(i + 1) % 3] * (r * 0.08);
            push(l.xyz, c, tip);
            push(l.xyz, tip, tip - AXES[i] * (r * 0.15) + side);
            push(l.xyz, tip, tip - AXES[i] * (r * 0.15) - side);
            out.push_back(std::move(l));
        }
    } else if (g_mode == 2) {
        for (int i = 0; i < 3; ++i) {
            GizmoLines l{{}, g_active == i + 1 ? active : colors[i]};
            const auto pts = ring(c, AXES[i], r);
            for (size_t k = 1; k < pts.size(); ++k) push(l.xyz, pts[k - 1], pts[k]);
            out.push_back(std::move(l));
        }
    } else if (g_mode == 3) {
        GizmoLines l{{}, g_active == Uniform ? active : std::array<float, 4>{1.f, 0.6f, 0.1f, 1.f}};
        const double k = r * 0.15;
        for (const Vec3d &p : corners(bb))
            for (int i = 0; i < 3; ++i) {
                const Vec3d dir = AXES[i] * ((p[i] > c[i]) ? -k : k);
                push(l.xyz, p, p + dir);
            }
        out.push_back(std::move(l));
    }
    return out;
}

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_dev_orcaslicer_android_OrcaCore_##name

// 0 off, 1 move, 2 rotate, 3 scale
JNI(void, gizmoSetMode)(JNIEnv *, jobject, jint mode)
{
    g_mode = mode;
    std::lock_guard lock(g_session.mutex);
    ++g_session.scene_version;
}

// Starts a drag if the touch is on a handle (or on the object body in move mode). Returns the handle, 0 = none.
JNI(jint, gizmoBegin)(JNIEnv *env, jobject, jfloat x, jfloat y, jint w, jint h, jfloatArray view, jfloatArray proj)
{
    std::lock_guard lock(g_session.mutex);
    ModelInstance *inst = selected_instance();
    if (g_mode == 0 || inst == nullptr) return None;
    const Camera cam = camera(env, w, h, view, proj);
    const BoundingBoxf3 bb = selected_box();
    const Vec3d c = bb.center();
    const double r = radius(bb), slop = 0.05 * std::min(w, h); // ~5% of the screen: finger sized
    const Vec2d p(x, y);
    int handle = None;
    double best = slop;
    if (g_mode == 1) {
        for (int i = 0; i < 3; ++i)
            if (double d = seg_dist(p, cam.project(c), cam.project(c + AXES[i] * r)); d < best) { best = d; handle = i + 1; }
    } else if (g_mode == 2) {
        for (int i = 0; i < 3; ++i) {
            const auto pts = ring(c, AXES[i], r);
            for (size_t k = 1; k < pts.size(); ++k)
                if (double d = seg_dist(p, cam.project(pts[k - 1]), cam.project(pts[k])); d < best) { best = d; handle = i + 1; }
        }
    } else if (g_mode == 3) {
        for (const Vec3d &q : corners(bb))
            if (double d = (cam.project(q) - p).norm(); d < best * 1.4) { best = d; handle = Uniform; }
    }
    Vec3d o, d;
    cam.ray(x, y, o, d);
    if (handle == None && g_mode == 1 && ray_hits_selected(o, d)) handle = Plane;
    if (handle == None) return None;

    g_drag = Drag{handle, g_session.selected, inst->get_matrix(), c, Vec3d::Zero(), 0., 1.};
    if ((g_mode == 1 && handle <= AxisZ && !closest_on_axis(o, d, c, AXES[handle - 1], g_drag.ref_t)) ||
        (g_mode == 1 && handle == Plane && !hit_plane(o, d, c, Vec3d::UnitZ(), g_drag.ref)) ||
        (g_mode == 2 && !hit_plane(o, d, c, AXES[handle - 1], g_drag.ref)))
        return None;
    g_session.snapshot();
    if (g_mode == 2) g_drag.ref -= c;
    if (g_mode == 3) g_drag.ref_dist = std::max(4., (p - cam.project(c)).norm());
    g_active = handle;
    ++g_session.scene_version;
    return handle;
}

JNI(void, gizmoDrag)(JNIEnv *env, jobject, jfloat x, jfloat y, jint w, jint h, jfloatArray view, jfloatArray proj)
{
    std::lock_guard lock(g_session.mutex);
    if (g_drag.handle == None || g_drag.object != g_session.selected || selected_instance() == nullptr) return;
    const Camera cam = camera(env, w, h, view, proj);
    Vec3d o, d;
    cam.ray(x, y, o, d);
    const Vec3d c = g_drag.center;
    Transform3d delta = Transform3d::Identity();
    if (g_mode == 1 && g_drag.handle <= AxisZ) {
        double t;
        if (!closest_on_axis(o, d, c, AXES[g_drag.handle - 1], t)) return;
        delta = Eigen::Translation3d(AXES[g_drag.handle - 1] * (t - g_drag.ref_t));
    } else if (g_mode == 1) {
        Vec3d p;
        if (!hit_plane(o, d, c, Vec3d::UnitZ(), p)) return;
        delta = Eigen::Translation3d(p - g_drag.ref);
    } else if (g_mode == 2) {
        const Vec3d a = AXES[g_drag.handle - 1];
        Vec3d p;
        if (!hit_plane(o, d, c, a, p)) return;
        double angle = std::atan2(a.dot(g_drag.ref.cross(p - c)), g_drag.ref.dot(p - c));
        const double snap = M_PI / 36.; // 5° steps: steadier than raw finger angles
        angle = std::round(angle / snap) * snap;
        delta = Eigen::Translation3d(c) * Eigen::AngleAxisd(angle, a) * Eigen::Translation3d(-c);
    } else if (g_mode == 3) {
        // Doubles per 35% of the screen dragged outward: steady on small and large objects alike.
        const double moved = (Vec2d(x, y) - cam.project(c)).norm() - g_drag.ref_dist;
        const double s = std::clamp(std::pow(2., moved / (0.35 * std::min(cam.w, cam.h))), 0.05, 20.);
        delta = Eigen::Translation3d(c) * Eigen::Scaling(s) * Eigen::Translation3d(-c);
    }
    ModelObject *obj = g_session.model.objects[g_session.selected];
    obj->instances.front()->set_transformation(Geometry::Transformation(delta * g_drag.start));
    obj->invalidate_bounding_box();
    ++g_session.scene_version;
}

JNI(void, gizmoEnd)(JNIEnv *, jobject)
{
    std::lock_guard lock(g_session.mutex);
    if (g_drag.handle != None && g_drag.object == g_session.selected && selected_instance() != nullptr) {
        ModelObject *obj = g_session.model.objects[g_session.selected];
        obj->invalidate_bounding_box();
        obj->ensure_on_bed();
        g_session.sliced = false;
    }
    g_drag = Drag();
    g_active = None;
    ++g_session.scene_version;
}

// Lay on face (the desktop's Flatten gizmo, by tapping): rotates the tapped object so the tapped
// face rests on the bed. Returns the object index, or -1 if nothing was hit.
JNI(jint, layOnFace)(JNIEnv *env, jobject, jfloat x, jfloat y, jint w, jint h, jfloatArray view, jfloatArray proj)
{
    std::lock_guard lock(g_session.mutex);
    const Camera cam = camera(env, w, h, view, proj);
    Vec3d o, d;
    cam.ray(x, y, o, d);
    int best_obj = -1;
    double best_t = 1e30;
    Vec3d normal = Vec3d::Zero();
    for (size_t oi = 0; oi < g_session.model.objects.size(); ++oi) {
        const ModelObject *obj = g_session.model.objects[oi];
        if (obj->instances.empty()) continue;
        const ModelInstance *inst = obj->instances.front();
        for (const ModelVolume *v : obj->volumes) {
            if (!v->is_model_part()) continue;
            const Transform3d t = inst->get_matrix() * v->get_matrix();
            const auto &its = v->mesh().its;
            for (const auto &f : its.indices) {
                const Vec3d p0 = t * its.vertices[f[0]].cast<double>(), e1 = t * its.vertices[f[1]].cast<double>() - p0,
                            e2 = t * its.vertices[f[2]].cast<double>() - p0, pv = d.cross(e2);
                const double det = e1.dot(pv);
                if (std::abs(det) < 1e-12) continue;
                const Vec3d tv = o - p0;
                const double u = tv.dot(pv) / det;
                if (u < 0. || u > 1.) continue;
                const Vec3d qv = tv.cross(e1);
                const double vv = d.dot(qv) / det, dist = e2.dot(qv) / det;
                if (vv < 0. || u + vv > 1. || dist <= 0. || dist >= best_t) continue;
                best_t = dist; best_obj = int(oi); normal = e1.cross(e2).normalized();
            }
        }
    }
    if (best_obj < 0) return -1;
    g_session.snapshot();
    ModelObject *obj = g_session.model.objects[best_obj];
    ModelInstance *inst = obj->instances.front();
    const Vec3d c = obj->instance_bounding_box(0).center();
    const Eigen::Quaterniond q = Eigen::Quaterniond::FromTwoVectors(normal, -Vec3d::UnitZ());
    const Transform3d rot = Eigen::Translation3d(c) * q * Eigen::Translation3d(-c);
    inst->set_transformation(Geometry::Transformation(rot * inst->get_matrix()));
    obj->invalidate_bounding_box();
    obj->ensure_on_bed();
    g_session.selected = best_obj;
    g_session.sliced = false;
    ++g_session.scene_version;
    return best_obj;
}
