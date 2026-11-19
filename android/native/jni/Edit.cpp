// The desktop's object context menu as one command call, edit(cmd, args): mirror, scale to the
// build volume, unit conversion, instances and fill bed, rename, printable, filament, copy/paste
// settings, parts and modifiers (primitives or files), height range modifiers, split, assemble,
// mesh repair, reload/replace from file, export, align/distribute and selection.
// Commands act on the selected object (`selected`) or the whole selection (`picked`).

#include "Session.hpp"

#include "libslic3r/Format/DRC.hpp"
#include "libslic3r/Format/OBJ.hpp"
#include "libslic3r/Format/STL.hpp"
#include "libslic3r/TriangleMesh.hpp"

#include <algorithm>
#include <cmath>

using namespace Slic3r;
using json = nlohmann::json;

namespace {

ModelObject *object_at(int idx)
{
    Model &m = g_session.model;
    return idx >= 0 && size_t(idx) < m.objects.size() ? m.objects[idx] : nullptr;
}

std::vector<ModelObject *> picked_objects()
{
    std::vector<ModelObject *> out;
    for (int i : g_session.picked)
        if (ModelObject *o = object_at(i)) out.push_back(o);
    return out;
}

double angle_step(double r) { return 2. * PI / std::clamp(int(r * 4.), 24, 180); } // ponytail: fixed facet density

// The desktop's primitives (GUI_ObjectList create_mesh), sized like it: a tenth of the bed.
TriangleMesh primitive(const std::string &shape, const BoundingBoxf3 &object_bb)
{
    const BoundingBoxf3 bed = bed_volume();
    const double side = 0.1 * std::max(bed.size().x(), bed.size().y());
    if (shape == "Cylinder") return TriangleMesh(its_make_cylinder(0.5 * side, side, angle_step(0.5 * side)));
    if (shape == "Disc") return TriangleMesh(its_make_cylinder(0.5 * side, 0.2, angle_step(0.5 * side)));
    if (shape == "Sphere") return TriangleMesh(its_make_sphere(0.5 * side, angle_step(0.5 * side)));
    if (shape == "Cone") return TriangleMesh(its_make_cone(0.5 * side, side, angle_step(0.5 * side)));
    if (shape == "Torus") {
        const double tube = 0.125 * side, radius = 0.375 * side + tube;
        return TriangleMesh(its_make_torus(radius, tube, angle_step(radius + tube), angle_step(tube)));
    }
    if (shape == "Slab")
        return TriangleMesh(its_make_cube(object_bb.size().x() * 1.5, object_bb.size().y() * 1.5, object_bb.size().z() * 0.5));
    return TriangleMesh(its_make_cube(side, side, side));
}

TriangleMesh load_mesh(const std::string &path)
{
    Model loaded = Model::read_from_file(path, nullptr, nullptr, LoadStrategy::LoadModel);
    TriangleMesh mesh;
    for (const ModelObject *o : loaded.objects) mesh.merge(o->raw_mesh());
    if (mesh.empty()) throw RuntimeError("The file has no mesh.");
    return mesh;
}

// Replaces a volume's mesh, keeping it where the old one was (bounding box centres match).
void swap_mesh(ModelVolume &v, TriangleMesh mesh)
{
    const Vec3d shift = v.mesh().bounding_box().center() - mesh.bounding_box().center();
    mesh.translate(shift.cast<float>());
    v.set_mesh(std::move(mesh));
    v.calculate_convex_hull();
    v.invalidate_convex_hull_2d();
}

void add_volume(ModelObject &o, TriangleMesh mesh, ModelVolumeType type, const std::string &name)
{
    // As the desktop: aligned with the bed, at the instance's right front corner, on the bed.
    const ModelInstance &inst = *o.instances.front();
    const BoundingBoxf3 ibb = o.instance_bounding_box(0);
    ModelVolume *v = o.add_volume(std::move(mesh), type);
    const Transform3d no_offset_inv = inst.get_transformation().get_matrix_no_offset().inverse();
    v->set_transformation(Geometry::Transformation(no_offset_inv));
    const BoundingBoxf3 mbb = v->mesh().bounding_box();
    const Vec3d at = Vec3d(ibb.max.x(), ibb.min.y(), ibb.min.z()) + 0.5 * mbb.size() - inst.get_offset();
    v->set_offset(no_offset_inv * at);
    v->name = name;
    v->config.set_key_value("extruder", new ConfigOptionInt(type == ModelVolumeType::MODEL_PART && o.config.has("extruder") ? o.config.opt_int("extruder") : 0));
    v->source.is_from_builtin_objects = true;
    o.invalidate_bounding_box();
}

ModelObject &add_object(TriangleMesh mesh, const std::string &name)
{
    ModelObject *o = g_session.model.add_object();
    o->name = name;
    o->add_volume(std::move(mesh));
    ModelInstance *inst = o->add_instance();
    const BoundingBoxf3 bed = bed_volume();
    inst->set_offset(Vec3d(bed.center().x(), bed.center().y(), 0.));
    o->ensure_on_bed();
    g_session.select_only(int(g_session.model.objects.size()) - 1);
    return *o;
}

int model_parts(const ModelObject &o)
{
    return int(std::count_if(o.volumes.begin(), o.volumes.end(), [](const ModelVolume *v) { return v->is_model_part(); }));
}

// Instances beyond the bed (after a fill-bed arrange) are dropped.
void drop_outside_instances(ModelObject &o)
{
    for (int i = int(o.instances.size()) - 1; i > 0; --i)
        if (g_session.outside(o, size_t(i))) o.delete_instance(size_t(i));
}

void align(const std::vector<ModelObject *> &objs, const std::string &mode)
{
    BoundingBoxf3 all;
    for (ModelObject *o : objs) all.merge(o->instance_bounding_box(0));
    for (ModelObject *o : objs) {
        const BoundingBoxf3 bb = o->instance_bounding_box(0);
        Vec3d d = Vec3d::Zero();
        if (mode == "left") d.x() = all.min.x() - bb.min.x();
        else if (mode == "right") d.x() = all.max.x() - bb.max.x();
        else if (mode == "front") d.y() = all.min.y() - bb.min.y();
        else if (mode == "back") d.y() = all.max.y() - bb.max.y();
        else if (mode == "center_x") d.x() = all.center().x() - bb.center().x();
        else if (mode == "center_y") d.y() = all.center().y() - bb.center().y();
        o->instances.front()->set_offset(o->instances.front()->get_offset() + d);
        o->invalidate_bounding_box();
    }
}

// Equal gaps between the objects along an axis, first and last stay put.
void distribute(std::vector<ModelObject *> objs, int axis)
{
    if (objs.size() < 3) return;
    std::sort(objs.begin(), objs.end(), [axis](ModelObject *a, ModelObject *b) {
        return a->instance_bounding_box(0).min[axis] < b->instance_bounding_box(0).min[axis];
    });
    double used = 0.;
    for (ModelObject *o : objs) used += o->instance_bounding_box(0).size()[axis];
    const double lo = objs.front()->instance_bounding_box(0).min[axis], hi = objs.back()->instance_bounding_box(0).max[axis];
    const double gap = (hi - lo - used) / double(objs.size() - 1);
    double at = lo;
    for (ModelObject *o : objs) {
        const BoundingBoxf3 bb = o->instance_bounding_box(0);
        Vec3d off = o->instances.front()->get_offset();
        off[axis] += at - bb.min[axis];
        o->instances.front()->set_offset(off);
        o->invalidate_bounding_box();
        at += bb.size()[axis] + gap;
    }
}

// The selected objects become parts of the first one (desktop Assemble).
void assemble(const std::vector<int> &idxs)
{
    Model &m = g_session.model;
    ModelObject *target = m.objects[idxs.front()];
    const Transform3d inv = target->instances.front()->get_matrix().inverse();
    for (size_t k = 1; k < idxs.size(); ++k) {
        const ModelObject *src = m.objects[idxs[k]];
        for (const ModelVolume *v : src->volumes) {
            ModelVolume *nv = target->add_volume(*v);
            nv->set_transformation(Geometry::Transformation(inv * src->instances.front()->get_matrix() * v->get_matrix()));
            if (!nv->config.has("extruder") && src->config.has("extruder"))
                nv->config.set_key_value("extruder", new ConfigOptionInt(src->config.opt_int("extruder")));
        }
    }
    for (size_t k = idxs.size() - 1; k > 0; --k) m.delete_object(size_t(idxs[k]));
    target->invalidate_bounding_box();
    target->ensure_on_bed();
    g_session.select_only(idxs.front());
}

// Runs one command; returns an error message or "".
std::string run(const std::string &cmd, const json &a, bool &edited)
{
    Session &s = g_session;
    Model &m = s.model;
    ModelObject *sel = object_at(s.selected);
    const int vol = a.value("vol", -1);
    ModelVolume *v = sel && vol >= 0 && size_t(vol) < sel->volumes.size() ? sel->volumes[vol] : nullptr;

    if (cmd == "toggle_select") {
        const int i = a.value("idx", -1);
        if (object_at(i) == nullptr) return "";
        if (s.picked.erase(i)) s.selected = s.picked.empty() ? -1 : *s.picked.rbegin();
        else { s.picked.insert(i); s.selected = i; }
        return "";
    }
    if (cmd == "select_all") {
        for (size_t i = 0; i < m.objects.size(); ++i) s.picked.insert(int(i));
        if (!m.objects.empty() && s.selected < 0) s.selected = int(m.objects.size()) - 1;
        return "";
    }
    if (cmd == "copy_settings") {
        if (!sel) return "Pick an object first.";
        s.clipboard = sel->config.get();
        s.clipboard.erase("extruder");
        s.has_clipboard = true;
        return "";
    }
    if (cmd == "export") {
        TriangleMesh mesh;
        for (int i : a.at("objects").get<std::vector<int>>()) if (ModelObject *o = object_at(i)) mesh.merge(o->mesh());
        const std::string path = a.at("path").get<std::string>(), fmt = a.value("format", "stl");
        const bool ok = fmt == "drc" ? store_drc(path.c_str(), &mesh, 16) : fmt == "obj" ? store_obj(path.c_str(), &mesh) : store_stl(path.c_str(), &mesh, true);
        return ok ? "" : "Could not write the file.";
    }

    // Everything below edits the model.
    s.snapshot();
    edited = true;
    const std::vector<ModelObject *> objs = picked_objects();
    if (cmd == "add_object") {
        const std::string path = a.value("path", "");
        if (!path.empty()) {
            Model loaded = Model::read_from_file(path, nullptr, nullptr, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
            for (ModelObject *o : loaded.objects) m.add_object(*o)->ensure_on_bed();
            arrange_model();
            s.select_only(int(m.objects.size()) - 1);
        } else {
            add_object(primitive(a.at("shape").get<std::string>(), {}), a.at("shape").get<std::string>());
        }
        return "";
    }
    if (objs.empty() || !sel) return "Pick an object first.";

    if (cmd == "mirror") {
        for (ModelObject *o : objs) { o->mirror(Axis(a.value("axis", 0))); o->ensure_on_bed(); }
    } else if (cmd == "scale_to_bed") {
        const BoundingBoxf3 bed = bed_volume();
        for (ModelObject *o : objs) {
            const Vec3d sz = o->instance_bounding_box(0).size();
            const double f = 0.99 * std::min({bed.size().x() / sz.x(), bed.size().y() / sz.y(), bed.size().z() / sz.z()});
            for (ModelInstance *inst : o->instances) inst->set_scaling_factor(inst->get_scaling_factor() * f);
            o->invalidate_bounding_box();
            const BoundingBoxf3 bb = o->instance_bounding_box(0);
            ModelInstance *inst = o->instances.front();
            inst->set_offset(inst->get_offset() + Vec3d(bed.center().x() - bb.center().x(), bed.center().y() - bb.center().y(), 0.));
            o->ensure_on_bed();
        }
    } else if (cmd == "units") {
        std::vector<int> idxs(s.picked.rbegin(), s.picked.rend());
        ModelObjectPtrs converted;
        for (int i : idxs) { m.objects[i]->convert_units(converted, ConversionType(a.value("type", 1)), {}); m.delete_object(size_t(i)); }
        s.picked.clear();
        for (auto it = converted.rbegin(); it != converted.rend(); ++it) {
            m.add_object(**it)->ensure_on_bed();
            delete *it;
            s.picked.insert(int(m.objects.size()) - 1);
        }
        s.selected = *s.picked.rbegin();
    } else if (cmd == "instances") {
        const size_t n = size_t(std::max(1, a.value("count", 1)));
        while (sel->instances.size() > n) sel->delete_last_instance();
        while (sel->instances.size() < n) sel->add_instance(*sel->instances.back());
        arrange_model();
    } else if (cmd == "fill_bed") {
        const BoundingBoxf3 bed = bed_volume(), bb = sel->instance_bounding_box(0);
        const double cell = (bb.size().x() + 6.) * (bb.size().y() + 6.);
        const size_t want = size_t(std::clamp(1.2 * bed.size().x() * bed.size().y() / cell, 1., 400.));
        while (sel->instances.size() < want) sel->add_instance(*sel->instances.back());
        arrange_model();
        drop_outside_instances(*sel);
    } else if (cmd == "rename") {
        (v ? v->name : sel->name) = a.at("name").get<std::string>();
    } else if (cmd == "printable") {
        for (ModelObject *o : objs) for (ModelInstance *inst : o->instances) inst->printable = a.value("value", true);
    } else if (cmd == "filament") {
        auto *opt = new ConfigOptionInt(a.value("value", 0));
        if (v) v->config.set_key_value("extruder", opt);
        else { for (ModelObject *o : objs) o->config.set_key_value("extruder", opt->clone()); delete opt; }
    } else if (cmd == "paste_settings") {
        if (!s.has_clipboard) return "Copy an object's settings first.";
        for (ModelObject *o : objs) {
            const int ext = o->config.has("extruder") ? o->config.opt_int("extruder") : -1;
            o->config.assign_config(s.clipboard);
            if (ext >= 0) o->config.set_key_value("extruder", new ConfigOptionInt(ext));
        }
    } else if (cmd == "add_volume") {
        const ModelVolumeType type = ModelVolumeType(a.value("type", 0));
        const std::string path = a.value("path", ""), shape = a.value("shape", "Cube");
        add_volume(*sel, path.empty() ? primitive(shape, sel->instance_bounding_box(0)) : load_mesh(path), type,
                   path.empty() ? "Generic-" + shape : a.value("name", std::string("Part")));
        sel->ensure_on_bed();
    } else if (cmd == "volume_type") {
        const ModelVolumeType type = ModelVolumeType(a.value("type", 0));
        if (!v) return "Pick a part first.";
        if (v->is_model_part() && type != ModelVolumeType::MODEL_PART && model_parts(*sel) == 1)
            return "An object needs at least one solid part.";
        v->set_type(type);
    } else if (cmd == "delete_volume") {
        if (!v) return "Pick a part first.";
        if (v->is_model_part() && model_parts(*sel) == 1) return "An object needs at least one solid part.";
        sel->delete_volume(size_t(vol));
        sel->ensure_on_bed();
    } else if (cmd == "volume_transform") {
        if (!v) return "Pick a part first.";
        const std::vector<double> t = a.at("values").get<std::vector<double>>();
        v->set_offset(Vec3d(t[0], t[1], t[2]));
        v->set_rotation(Vec3d(t[3], t[4], t[5]));
        v->set_scaling_factor(Vec3d(t[6], t[7], t[8]));
        sel->invalidate_bounding_box();
        sel->ensure_on_bed();
    } else if (cmd == "add_range" || cmd == "set_range") {
        const double lo = a.value("lo", 0.), hi = a.value("hi", 2.);
        if (!(hi > lo) || lo < 0.) return "The range must start below where it ends.";
        ModelConfig cfg;
        if (cmd == "set_range") {
            int n = a.value("index", 0);
            for (auto it = sel->layer_config_ranges.begin(); it != sel->layer_config_ranges.end(); ++it)
                if (n-- == 0) { cfg = it->second; sel->layer_config_ranges.erase(it); break; }
        } else {
            cfg.set_key_value("layer_height", new ConfigOptionFloat(s.bundle->prints.get_edited_preset().config.opt_float("layer_height")));
        }
        sel->layer_config_ranges[{lo, hi}] = cfg;
    } else if (cmd == "delete_range") {
        int n = a.value("index", 0);
        for (auto it = sel->layer_config_ranges.begin(); it != sel->layer_config_ranges.end(); ++it)
            if (n-- == 0) { sel->layer_config_ranges.erase(it); break; }
    } else if (cmd == "split_objects") {
        ModelObjectPtrs parts;
        sel->split(&parts, false); // adds the pieces to the model
        if (parts.size() > 1) { m.delete_object(size_t(s.selected)); s.select_only(int(m.objects.size()) - 1); }
        else return "The object is one piece.";
        for (ModelObject *o : m.objects) o->ensure_on_bed();
    } else if (cmd == "split_parts") {
        const unsigned filaments = unsigned(s.bundle->filament_presets.size());
        bool split = false;
        for (int i = int(sel->volumes.size()) - 1; i >= 0; --i)
            if (sel->volumes[i]->is_model_part() && sel->volumes[i]->split(filaments, false) > 1) split = true;
        if (!split) return "The parts are one piece each.";
        sel->invalidate_bounding_box();
    } else if (cmd == "assemble") {
        if (s.picked.size() < 2) return "Pick two or more objects (long press adds to the selection).";
        assemble(std::vector<int>(s.picked.begin(), s.picked.end()));
    } else if (cmd == "merge_parts") {
        if (model_parts(*sel) < 2) return "The object has one part.";
        sel->merge();
    } else if (cmd == "repair") {
        for (ModelObject *o : objs)
            for (ModelVolume *mv : o->volumes) {
                indexed_triangle_set its = mv->mesh().its;
                its_merge_vertices(its);
                its_remove_degenerate_faces(its);
                its_compactify_vertices(its);
                mv->set_mesh(TriangleMesh(std::move(its)));
                mv->calculate_convex_hull();
                mv->invalidate_convex_hull_2d();
            }
    } else if (cmd == "reload") {
        for (ModelObject *o : objs) {
            if (o->input_file.empty()) return "The object was not loaded from a file.";
            Model loaded = Model::read_from_file(o->input_file, nullptr, nullptr, LoadStrategy::LoadModel);
            std::vector<const ModelVolume *> fresh;
            for (const ModelObject *lo : loaded.objects) for (const ModelVolume *lv : lo->volumes) if (lv->is_model_part()) fresh.push_back(lv);
            std::vector<ModelVolume *> old;
            for (ModelVolume *ov : o->volumes) if (ov->is_model_part()) old.push_back(ov);
            if (fresh.size() == old.size()) for (size_t k = 0; k < old.size(); ++k) swap_mesh(*old[k], fresh[k]->mesh());
            else if (old.size() == 1) swap_mesh(*old.front(), load_mesh(o->input_file));
            else return "The file's parts no longer match the object.";
            o->invalidate_bounding_box();
            o->ensure_on_bed();
        }
    } else if (cmd == "replace") {
        ModelVolume *target = v;
        if (!target) for (ModelVolume *ov : sel->volumes) if (ov->is_model_part()) { target = ov; break; }
        const std::string path = a.at("path").get<std::string>();
        swap_mesh(*target, load_mesh(path));
        target->source.input_file = path;
        sel->input_file = path;
        sel->invalidate_bounding_box();
        sel->ensure_on_bed();
    } else if (cmd == "align") {
        if (objs.size() < 2) return "Pick two or more objects (long press adds to the selection).";
        align(objs, a.at("mode").get<std::string>());
    } else if (cmd == "distribute") {
        if (objs.size() < 3) return "Pick three or more objects to distribute.";
        distribute(objs, a.value("axis", 0));
    } else {
        return "Unknown command " + cmd;
    }
    return "";
}

} // namespace

// Returns the objects, or {"error": message}.
JNI(jstring, edit)(JNIEnv *env, jobject, jstring jcmd, jstring jargs)
{
    std::lock_guard lock(g_session.mutex);
    try {
        if (!g_session.bundle) return to_j(env, json{{"error", "Choose a printer first."}}.dump());
        const std::string args = jstr(env, jargs);
        bool edited = false;
        std::string err;
        try {
            err = run(jstr(env, jcmd), args.empty() ? json::object() : json::parse(args), edited);
        } catch (const std::exception &e) {
            err = e.what();
        }
        if (!err.empty() && edited) {
            // A refused or failed edit is rolled back whole and leaves nothing to undo.
            Session::Snapshot &last = g_session.undo_stack.back();
            g_session.model = last.model;
            g_session.select_only(last.selected);
            g_session.undo_stack.pop_back();
        }
        model_changed();
        if (!err.empty()) return to_j(env, json{{"error", err}}.dump());
        return to_j(env, objects_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}
