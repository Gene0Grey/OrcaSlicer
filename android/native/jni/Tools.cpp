// The desktop's left-toolbar tools that change geometry, driven by numbers instead of 3D handles:
// cut (plane at a height and tilt, with the desktop's keep/flip/parts options and a live plane
// preview), simplify (quadric edge collapse), mesh boolean between parts, text and SVG emboss
// (system fonts), and measure (tap two features, get distance and angle).

#include "Session.hpp"

#include "libslic3r/CutUtils.hpp"
#include "libslic3r/Emboss.hpp"
#include "libslic3r/EmbossShape.hpp"
#include "libslic3r/Measure.hpp"
#include "libslic3r/MeshBoolean.hpp"
#include "libslic3r/NSVGUtils.hpp"
#include "libslic3r/QuadricEdgeCollapse.hpp"
#include "libslic3r/TextConfiguration.hpp"

#include <boost/filesystem.hpp>

using namespace Slic3r;
using json = nlohmann::json;
namespace fs = boost::filesystem;

namespace {

std::vector<Measure::SurfaceFeature> g_features; // measure: picked features (max 2)

ModelObject *selected_object()
{
    Session &s = g_session;
    return s.selected >= 0 && size_t(s.selected) < s.model.objects.size() ? s.model.objects[s.selected] : nullptr;
}

double deg(const json &a, const char *k) { return a.value(k, 0.) * PI / 180.; }

// The cut plane in world space: through (object centre x, y, z) tilted by rx, ry degrees.
Transform3d cut_plane(const ModelObject &o, const json &a)
{
    const BoundingBoxf3 bb = o.instance_bounding_box(0);
    const Vec3d centre(bb.center().x(), bb.center().y(), a.value("z", bb.center().z()));
    return Geometry::translation_transform(centre) * Geometry::rotation_transform(Vec3d(deg(a, "rx"), deg(a, "ry"), 0.));
}

// Places a new part so it stands on the object's top, centred (sink > 0 pushes it into the top).
void place_on_top(ModelObject &o, ModelVolume &v, double sink)
{
    const ModelInstance &inst = *o.instances.front();
    BoundingBoxf3 parts;
    for (const ModelVolume *pv : o.volumes)
        if (pv != &v && pv->is_model_part()) parts.merge(pv->mesh().transformed_bounding_box(inst.get_matrix() * pv->get_matrix()));
    const Transform3d no_offset_inv = inst.get_transformation().get_matrix_no_offset().inverse();
    v.set_transformation(Geometry::Transformation(no_offset_inv));
    const BoundingBoxf3 mbb = v.mesh().bounding_box();
    const Vec3d at(parts.center().x(), parts.center().y(), parts.max.z() + 0.5 * mbb.size().z() - sink);
    v.set_offset(no_offset_inv * (at - inst.get_offset()));
    o.invalidate_bounding_box();
}

// Shapes (integer coordinates, `scale` mm per unit) to a mesh `depth` mm deep (desktop EmbossJob).
TriangleMesh extrude(const ExPolygons &shapes, double scale, double depth)
{
    if (shapes.empty() || scale <= 0) throw RuntimeError("Nothing to emboss.");
    Emboss::ProjectScale project(std::make_unique<Emboss::ProjectZ>(depth / scale), scale);
    return TriangleMesh(Emboss::polygons2model(shapes, project));
}

// Adds an embossed mesh: a new object (type -1) or a part / negative part / modifier of the picked object.
void add_emboss(TriangleMesh mesh, int type, const std::string &name, double depth)
{
    Session &s = g_session;
    ModelObject *o = selected_object();
    if (type < 0 || !o) {
        ModelObject *no = s.model.add_object();
        no->name = name;
        no->add_volume(std::move(mesh));
        ModelInstance *inst = no->add_instance();
        const BoundingBoxf3 bed = bed_volume();
        inst->set_offset(Vec3d(bed.center().x(), bed.center().y(), 0.));
        no->ensure_on_bed();
        s.select_only(int(s.model.objects.size()) - 1);
        return;
    }
    ModelVolume *v = o->add_volume(std::move(mesh), ModelVolumeType(type));
    v->name = name;
    v->config.set_key_value("extruder", new ConfigOptionInt(0));
    place_on_top(*o, *v, type == int(ModelVolumeType::NEGATIVE_VOLUME) ? depth : 0.);
}

// Ray from a screen point; returns (object, volume, face, world point) of the nearest hit.
struct Hit { int obj = -1, vol = -1, face = -1; Vec3d point; };
Hit ray_hit(float x, float y, int w, int h, const float *view, const float *proj)
{
    Eigen::Matrix4f vpm = Eigen::Map<const Eigen::Matrix4f>(proj) * Eigen::Map<const Eigen::Matrix4f>(view);
    const Eigen::Matrix4d inv = vpm.cast<double>().inverse();
    const double nx = 2. * x / w - 1., ny = 1. - 2. * y / h;
    Eigen::Vector4d a = inv * Eigen::Vector4d(nx, ny, -1., 1.), b = inv * Eigen::Vector4d(nx, ny, 1., 1.);
    const Vec3d o = a.head<3>() / a.w(), d = (b.head<3>() / b.w() - o).normalized();
    Hit best;
    double best_t = 1e30;
    const Model &m = g_session.model;
    for (size_t oi = 0; oi < m.objects.size(); ++oi)
        for (const ModelInstance *inst : m.objects[oi]->instances)
            for (size_t vi = 0; vi < m.objects[oi]->volumes.size(); ++vi) {
                const ModelVolume *v = m.objects[oi]->volumes[vi];
                if (!v->is_model_part()) continue;
                const Transform3d t = inst->get_matrix() * v->get_matrix();
                const auto &its = v->mesh().its;
                for (size_t fi = 0; fi < its.indices.size(); ++fi) {
                    const auto &f = its.indices[fi];
                    const Vec3d p0 = t * its.vertices[f[0]].cast<double>(), e1 = t * its.vertices[f[1]].cast<double>() - p0,
                                e2 = t * its.vertices[f[2]].cast<double>() - p0;
                    const Vec3d pv = d.cross(e2);
                    const double det = e1.dot(pv);
                    if (std::abs(det) < 1e-12) continue;
                    const Vec3d tv = o - p0;
                    const double u = tv.dot(pv) / det;
                    if (u < 0. || u > 1.) continue;
                    const Vec3d qv = tv.cross(e1);
                    const double vv = d.dot(qv) / det;
                    if (vv < 0. || u + vv > 1.) continue;
                    const double dist = e2.dot(qv) / det;
                    if (dist > 0. && dist < best_t) { best_t = dist; best = {int(oi), int(vi), int(fi), o + d * dist}; }
                }
            }
    return best;
}

json feature_json(const Measure::SurfaceFeature &f)
{
    auto v = [](const Vec3d &p) { return json{p.x(), p.y(), p.z()}; };
    switch (f.get_type()) {
    case Measure::SurfaceFeatureType::Point: return {{"type", "point"}, {"at", v(f.get_point())}};
    case Measure::SurfaceFeatureType::Edge: return {{"type", "edge"}, {"at", v(f.get_edge().first)}, {"to", v(f.get_edge().second)},
                                                    {"length", (f.get_edge().second - f.get_edge().first).norm()}};
    case Measure::SurfaceFeatureType::Circle: return {{"type", "circle"}, {"at", v(std::get<0>(f.get_circle()))}, {"radius", std::get<1>(f.get_circle())}};
    case Measure::SurfaceFeatureType::Plane: return {{"type", "plane"}, {"at", v(std::get<2>(f.get_plane()))}};
    default: return {{"type", "none"}};
    }
}

void update_marks()
{
    g_session.marks.clear();
    for (const auto &f : g_features) {
        switch (f.get_type()) {
        case Measure::SurfaceFeatureType::Point: g_session.marks.push_back(f.get_point()); break;
        case Measure::SurfaceFeatureType::Edge: g_session.marks.push_back(f.get_edge().first); g_session.marks.push_back(f.get_edge().second); break;
        case Measure::SurfaceFeatureType::Circle: g_session.marks.push_back(std::get<0>(f.get_circle())); break;
        case Measure::SurfaceFeatureType::Plane: g_session.marks.push_back(std::get<2>(f.get_plane())); break;
        default: break;
        }
    }
    ++g_session.scene_version;
}

std::string run(const std::string &cmd, const json &a, bool &edited)
{
    Session &s = g_session;
    ModelObject *o = selected_object();
    if (cmd == "cut_preview") {
        s.cut_preview = o && a.value("on", true);
        if (s.cut_preview) s.cut_plane = cut_plane(*o, a);
        ++s.scene_version;
        return "";
    }
    if (cmd == "text" || cmd == "svg") {
        const double size = a.value("size", cmd == "text" ? 10. : 40.), depth = a.value("depth", 2.);
        ExPolygons shapes;
        std::string name;
        double scale = 0.;
        if (cmd == "text") {
            const std::string text = a.value("text", std::string("Text")), font = a.value("font", std::string("/system/fonts/Roboto-Regular.ttf"));
            Emboss::FontFileWithCache ff(Emboss::create_font_file(font.c_str()));
            if (!ff.has_value()) return "Can't read that font.";
            FontProp prop(float(size));
            shapes = Emboss::text2shapes(ff, text.c_str(), prop).expolygons;
            scale  = Emboss::get_text_shape_scale(prop, *ff.font_file);
            name = text.substr(0, 24);
        } else {
            const std::string path = a.value("path", std::string());
            NSVGimage_ptr image = nsvgParseFromFile(path);
            if (!image) return "Can't read that SVG.";
            for (ExPolygonsWithId &e : create_shape_with_ids(*image, NSVGLineParams(0.1)))
                append(shapes, std::move(e.expoly));
            shapes = union_ex(shapes);
            // SVG y points down.
            for (ExPolygon &e : shapes) { e.contour.points = Points(e.contour.points.rbegin(), e.contour.points.rend()); for (Point &p : e.contour.points) p.y() = -p.y();
                for (Polygon &h : e.holes) { h.points = Points(h.points.rbegin(), h.points.rend()); for (Point &p : h.points) p.y() = -p.y(); } }
            name = fs::path(path).stem().string();
            // `size` mm across the larger side.
            const BoundingBox bb = get_extents(shapes);
            const double span = std::max(bb.size().x(), bb.size().y());
            if (span > 0) scale = size / span;
        }
        s.snapshot(); edited = true;
        add_emboss(extrude(shapes, scale, depth), a.value("type", -1), name, depth);
        return "";
    }
    if (cmd == "measure_clear") { g_features.clear(); update_marks(); return ""; }
    if (!o) return "Pick an object first.";

    if (cmd == "cut") {
        s.snapshot(); edited = true;
        ModelObjectCutAttributes attrs = only_if(a.value("upper", true), ModelObjectCutAttribute::KeepUpper) |
                                         only_if(a.value("lower", true), ModelObjectCutAttribute::KeepLower) |
                                         only_if(a.value("parts", false), ModelObjectCutAttribute::KeepAsParts) |
                                         only_if(a.value("flip_upper", false), ModelObjectCutAttribute::FlipUpper) |
                                         only_if(a.value("place_lower", false), ModelObjectCutAttribute::PlaceOnCutLower) |
                                         ModelObjectCutAttribute::InvalidateCutInfo;
        if (!attrs.has(ModelObjectCutAttribute::KeepUpper) && !attrs.has(ModelObjectCutAttribute::KeepLower)) return "Keep at least one side.";
        const Transform3d plane = cut_plane(*o, a);
        const Vec3d offset = o->instances.front()->get_offset();
        Cut cut(o, 0, Geometry::translation_transform(-offset) * plane, attrs);
        ModelObjectPtrs pieces = cut.perform_with_plane();
        if (pieces.empty()) return "The plane misses the object.";
        const int idx = s.selected;
        for (ModelObject *p : pieces) { ModelObject *added = s.model.add_object(*p); added->ensure_on_bed(); }
        s.model.delete_object(size_t(idx));
        s.select_only(int(s.model.objects.size()) - 1);
        s.cut_preview = false;
        arrange_model();
        return "";
    }
    if (cmd == "simplify") {
        s.snapshot(); edited = true;
        const double ratio = std::clamp(a.value("ratio", 0.5), 0.01, 1.);
        for (ModelVolume *v : o->volumes) {
            if (!v->is_model_part()) continue;
            indexed_triangle_set its = v->mesh().its;
            its_quadric_edge_collapse(its, uint32_t(std::max(4., its.indices.size() * ratio)));
            v->set_mesh(TriangleMesh(std::move(its)));
            v->calculate_convex_hull();
            v->invalidate_convex_hull_2d();
        }
        o->invalidate_bounding_box();
        return "";
    }
    if (cmd == "boolean") {
        const int ia = a.value("a", -1), ib = a.value("b", -1);
        if (ia < 0 || ib < 0 || ia == ib || size_t(std::max(ia, ib)) >= o->volumes.size()) return "Pick two different parts.";
        s.snapshot(); edited = true;
        ModelVolume *va = o->volumes[ia], *vb = o->volumes[ib];
        TriangleMesh ma = va->mesh(), mb = vb->mesh();
        ma.transform(va->get_matrix());
        mb.transform(vb->get_matrix());
        const std::string op = a.value("op", std::string("union"));
        if (op == "difference") MeshBoolean::cgal::minus(ma, mb);
        else if (op == "intersection") MeshBoolean::cgal::intersect(ma, mb);
        else MeshBoolean::cgal::plus(ma, mb);
        if (ma.empty()) return "The result is empty.";
        va->set_transformation(Geometry::Transformation());
        va->set_mesh(std::move(ma));
        va->center_geometry_after_creation();
        va->calculate_convex_hull();
        va->invalidate_convex_hull_2d();
        if (!a.value("keep", false)) o->delete_volume(size_t(ib));
        o->invalidate_bounding_box();
        o->ensure_on_bed();
        return "";
    }
    return "Unknown tool " + cmd;
}

} // namespace

// cmd: cut_preview, cut, simplify, boolean, text, svg, measure_clear. Returns objects or {"error"}.
JNI(jstring, tool)(JNIEnv *env, jobject, jstring jcmd, jstring jargs)
{
    std::lock_guard lock(g_session.mutex);
    bool edited = false;
    std::string err;
    try {
        if (!g_session.bundle) return to_j(env, json{{"error", "Choose a printer first."}}.dump());
        const std::string args = jstr(env, jargs);
        err = run(jstr(env, jcmd), args.empty() ? json::object() : json::parse(args), edited);
    } catch (const std::exception &e) {
        err = e.what();
    }
    if (!err.empty() && edited) {
        g_session.model = g_session.undo_stack.back().model;
        g_session.select_only(g_session.undo_stack.back().selected);
        g_session.undo_stack.pop_back();
    }
    if (edited || !err.empty()) model_changed();
    if (!err.empty()) return to_j(env, json{{"error", err}}.dump());
    return to_j(env, objects_json().dump());
}

// System fonts for text emboss.
JNI(jstring, fonts)(JNIEnv *env, jobject)
{
    json out = json::array();
    try {
        for (const auto &e : fs::directory_iterator("/system/fonts")) {
            const std::string ext = e.path().extension().string();
            if (ext == ".ttf" || ext == ".otf") out.push_back({{"name", e.path().stem().string()}, {"path", e.path().string()}});
        }
    } catch (const std::exception &) {
    }
    return to_j(env, out.dump());
}

// Measure: picks the feature under a screen point (point, edge, circle or plane). With two
// picked, returns the distance and angle between them. Returns {features, result}.
JNI(jstring, measurePick)(JNIEnv *env, jobject, jfloat x, jfloat y, jint w, jint h, jfloatArray jview, jfloatArray jproj)
{
    float view[16], proj[16];
    env->GetFloatArrayRegion(jview, 0, 16, view);
    env->GetFloatArrayRegion(jproj, 0, 16, proj);
    std::lock_guard lock(g_session.mutex);
    json out;
    try {
        const Hit hit = ray_hit(x, y, w, h, view, proj);
        if (hit.obj >= 0) {
            const ModelObject &o = *g_session.model.objects[hit.obj];
            // Measure in world space on a transformed copy of the part.
            indexed_triangle_set its = o.volumes[hit.vol]->mesh().its;
            const Transform3d t = o.instances.front()->get_matrix() * o.volumes[hit.vol]->get_matrix();
            for (auto &v : its.vertices) v = (t * v.cast<double>()).cast<float>();
            Measure::Measuring measuring(its);
            if (auto f = measuring.get_feature(size_t(hit.face), hit.point, Transform3d::Identity(), false)) {
                if (g_features.size() >= 2) g_features.clear();
                g_features.push_back(*f);
            }
        }
        update_marks();
        json feats = json::array();
        for (const auto &f : g_features) feats.push_back(feature_json(f));
        out["features"] = feats;
        if (g_features.size() == 2) {
            const Measure::MeasurementResult r = Measure::get_measurement(g_features[0], g_features[1]);
            json res = json::object();
            if (r.distance_strict) res["distance"] = r.distance_strict->dist;
            if (r.distance_infinite) res["distanceInfinite"] = r.distance_infinite->dist;
            if (r.distance_xyz) res["xyz"] = {r.distance_xyz->x(), r.distance_xyz->y(), r.distance_xyz->z()};
            if (r.angle) res["angle"] = r.angle->angle * 180. / PI;
            out["result"] = res;
        }
    } catch (const std::exception &e) {
        out["error"] = e.what();
    }
    return to_j(env, out.dump());
}
