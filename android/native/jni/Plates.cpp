// Build plates, as the desktop's PartPlateList: a grid of plates, objects belonging to the plate
// they stand on; add, delete, rename, duplicate, lock, switch, move objects between plates, and
// arrange the current plate (spilling what does not fit onto the next plates).

#include "Session.hpp"

#include "libslic3r/Arrange.hpp"
#include "libslic3r/CustomGCode.hpp"

#include <algorithm>
#include <cmath>

using namespace Slic3r;
using json = nlohmann::json;

namespace {

void move_instance(ModelObject &o, size_t k, const Vec2d &d)
{
    ModelInstance *inst = o.instances[k];
    inst->set_offset(inst->get_offset() + Vec3d(d.x(), d.y(), 0.));
    o.invalidate_bounding_box();
}

// Every instance on plate p, as (object, instance) pairs.
std::vector<std::pair<ModelObject *, size_t>> instances_on(int p)
{
    std::vector<std::pair<ModelObject *, size_t>> out;
    for (ModelObject *o : g_session.model.objects)
        for (size_t k = 0; k < o->instances.size(); ++k)
            if (g_session.plate_of(*o, k) == p) out.emplace_back(o, k);
    return out;
}

} // namespace

void set_plate_count(size_t n)
{
    Session &s = g_session;
    n = std::max<size_t>(1, n);
    if (n == s.plates.size()) return;
    // Membership is decided in the old grid, then everything moves with its plate.
    std::vector<std::vector<std::pair<ModelObject *, size_t>>> members(s.plates.size());
    for (int p = 0; p < int(s.plates.size()); ++p) members[p] = instances_on(p);
    const size_t old_n = s.plates.size();
    for (int p = 0; p < int(std::min(old_n, n)); ++p) {
        const Vec2d d = s.plate_origin(p, n) - s.plate_origin(p, old_n);
        if (d.squaredNorm() > 0.)
            for (auto &[o, k] : members[p]) move_instance(*o, k, d);
    }
    s.plates.resize(n);
    s.current = std::min(s.current, int(n) - 1);
}

std::vector<ModelInstance *> arrange_model(bool spill)
{
    Session &s = g_session;
    const int p = s.current;
    ArrangePolygons polys;
    std::vector<ModelInstance *> insts;
    for (auto &[o, k] : instances_on(p)) {
        ArrangePolygon ap;
        o->instances[k]->get_arrange_polygon(&ap);
        polys.push_back(std::move(ap));
        insts.push_back(o->instances[k]);
    }
    // Objects not on any plate (e.g. just loaded far off) join the current plate.
    for (ModelObject *o : s.model.objects)
        for (size_t k = 0; k < o->instances.size(); ++k)
            if (s.plate_of(*o, k) < 0) {
                ArrangePolygon ap;
                o->instances[k]->get_arrange_polygon(&ap);
                polys.push_back(std::move(ap));
                insts.push_back(o->instances[k]);
            }
    if (polys.empty()) return {};

    BoundingBoxf bed = s.bed_rect();
    bed.translate(s.plate_origin(p));
    arrangement::ArrangeParams params;
    params.min_obj_distance = scaled(6.);
    arrangement::arrange(polys, BoundingBox(Point(scaled(bed.min.x()), scaled(bed.min.y())), Point(scaled(bed.max.x()), scaled(bed.max.y()))), params);

    // Results come in plate p's frame; virtual beds k > 0 map to plates p + k.
    const Vec2d old_origin = s.plate_origin(p);
    int last = p;
    for (const ArrangePolygon &ap : polys) if (spill && ap.bed_idx > 0) last = std::max(last, p + ap.bed_idx);
    if (last >= int(s.plates.size())) set_plate_count(size_t(last + 1));
    std::vector<ModelInstance *> overflow;
    for (size_t i = 0; i < polys.size(); ++i) {
        const ArrangePolygon &ap = polys[i];
        if (ap.bed_idx < 0 || (ap.bed_idx > 0 && !spill)) { overflow.push_back(insts[i]); continue; }
        const Vec2d shift = s.plate_origin(p + ap.bed_idx) - old_origin;
        insts[i]->apply_arrange_result(ap.translation.cast<double>() + Vec2d(scaled(shift.x()), scaled(shift.y())), ap.rotation);
        insts[i]->get_object()->invalidate_bounding_box();
    }
    return overflow;
}

Model plate_model(int p)
{
    Session &s = g_session;
    Model out = s.model; // copy keeps the object IDs
    out.curr_plate_index = p; // the plate's pauses / colour changes (plates_custom_gcodes)
    const Vec2d origin = s.plate_origin(p);
    for (int oi = int(out.objects.size()) - 1; oi >= 0; --oi) {
        ModelObject *o = out.objects[oi];
        for (int k = int(o->instances.size()) - 1; k >= 0; --k) {
            if (s.plate_of(*s.model.objects[oi], size_t(k)) != p) o->delete_instance(size_t(k));
        }
        if (o->instances.empty()) { out.delete_object(size_t(oi)); continue; }
        for (ModelInstance *inst : o->instances) inst->set_offset(inst->get_offset() - Vec3d(origin.x(), origin.y(), 0.));
        o->invalidate_bounding_box();
    }
    return out;
}

json plates_json()
{
    Session &s = g_session;
    json list = json::array();
    for (int p = 0; p < int(s.plates.size()); ++p) {
        const Plate &pl = s.plates[p];
        const Vec2d o = s.plate_origin(p);
        list.push_back({{"name", pl.name}, {"locked", pl.locked}, {"sliced", pl.sliced}, {"objects", instances_on(p).size()},
                        {"origin", {o.x(), o.y()}}});
    }
    json gcodes = json::array();
    if (auto it = s.model.plates_custom_gcodes.find(s.current); it != s.model.plates_custom_gcodes.end())
        for (const CustomGCode::Item &g : it->second.gcodes)
            gcodes.push_back({{"z", g.print_z}, {"type", int(g.type)}, {"extruder", g.extruder}, {"color", g.color}, {"extra", g.extra}});
    return {{"current", s.current}, {"plates", list}, {"gcodes", gcodes}, {"viewer", s.viewer_result ? s.viewer_path : std::string()}};
}

JNI(jstring, plates)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    return to_j(env, plates_json().dump());
}

// cmd: select, add, delete, rename, duplicate, lock, move_objects (the selection to plate "to"),
// arrange (current plate). Returns the plates, or {"error"}.
JNI(jstring, plateCmd)(JNIEnv *env, jobject, jstring jcmd, jstring jargs)
{
    std::lock_guard lock(g_session.mutex);
    Session &s = g_session;
    try {
        if (!s.bundle) return to_j(env, json{{"error", "Choose a printer first."}}.dump());
        const std::string cmd = jstr(env, jcmd), args = jstr(env, jargs);
        const json a = args.empty() ? json::object() : json::parse(args);
        const int idx = a.value("index", s.current);
        if (idx < 0 || idx >= int(s.plates.size())) return to_j(env, json{{"error", "No such plate."}}.dump());
        if (cmd == "select") {
            if (s.current != idx) { s.current = idx; ++s.preview_version; ++s.scene_version; }
            s.viewer_result.reset();
            return to_j(env, plates_json().dump());
        }
        if (cmd == "rename") { s.plates[idx].name = a.value("name", std::string()); return to_j(env, plates_json().dump()); }
        if (cmd == "lock") { s.plates[idx].locked = a.value("value", true); return to_j(env, plates_json().dump()); }

        if (cmd == "delete" && s.plates.size() == 1) return to_j(env, json{{"error", "A project keeps at least one plate."}}.dump());
        if (cmd == "arrange" && s.plate().locked) return to_j(env, json{{"error", "The plate is locked."}}.dump());
        s.snapshot();
        if (cmd == "add") {
            set_plate_count(s.plates.size() + 1);
            s.current = int(s.plates.size()) - 1;
        } else if (cmd == "delete") {
            // The plate's objects go with it; later plates move up one place.
            std::vector<std::vector<std::pair<ModelObject *, size_t>>> members(s.plates.size());
            for (int p = 0; p < int(s.plates.size()); ++p) members[p] = instances_on(p);
            const size_t n = s.plates.size();
            for (int p = idx + 1; p < int(n); ++p) {
                const Vec2d d = s.plate_origin(p - 1, n - 1) - s.plate_origin(p, n);
                for (auto &[o, k] : members[p]) move_instance(*o, k, d);
            }
            for (int p = 0; p < idx; ++p) {
                const Vec2d d = s.plate_origin(p, n - 1) - s.plate_origin(p, n);
                for (auto &[o, k] : members[p]) move_instance(*o, k, d);
            }
            std::set<ModelInstance *> doomed;
            for (auto &[o, k] : members[idx]) doomed.insert(o->instances[k]);
            for (int oi = int(s.model.objects.size()) - 1; oi >= 0; --oi) {
                ModelObject *o = s.model.objects[oi];
                for (int k = int(o->instances.size()) - 1; k >= 0; --k)
                    if (doomed.count(o->instances[k])) o->delete_instance(size_t(k));
                if (o->instances.empty()) s.model.delete_object(size_t(oi));
            }
            s.plates.erase(s.plates.begin() + idx);
            s.current = std::min(s.current, int(s.plates.size()) - 1);
            s.select_only(-1);
        } else if (cmd == "duplicate") {
            set_plate_count(s.plates.size() + 1);
            const int to = int(s.plates.size()) - 1;
            s.plates[to].name = s.plates[idx].name;
            const Vec2d d = s.plate_origin(to) - s.plate_origin(idx);
            for (auto &[o, k] : instances_on(idx)) {
                ModelObject *copy = s.model.add_object(*o);
                copy->clear_instances();
                ModelInstance *inst = copy->add_instance(*o->instances[k]);
                inst->set_offset(inst->get_offset() + Vec3d(d.x(), d.y(), 0.));
                copy->invalidate_bounding_box();
            }
            s.current = to;
        } else if (cmd == "move_objects") {
            const int to = a.value("to", 0);
            if (to < 0 || to >= int(s.plates.size())) return to_j(env, json{{"error", "No such plate."}}.dump());
            for (int oi : s.picked) {
                ModelObject *o = s.model.objects[oi];
                for (size_t k = 0; k < o->instances.size(); ++k) {
                    const int from = s.plate_of(*o, k);
                    move_instance(*o, k, s.plate_origin(to) - s.plate_origin(from < 0 ? s.current : from));
                }
            }
            s.current = to;
        } else if (cmd == "add_gcode" || cmd == "remove_gcode") {
            // Pause / colour change / filament change / custom G-code at a layer (desktop layer slider "+").
            CustomGCode::Info &info = s.model.plates_custom_gcodes[s.current];
            const double z = a.value("z", 0.);
            auto &g = info.gcodes;
            g.erase(std::remove_if(g.begin(), g.end(), [z](const CustomGCode::Item &it) { return std::abs(it.print_z - z) < 1e-4; }), g.end());
            if (cmd == "add_gcode") {
                CustomGCode::Item it;
                it.print_z  = z;
                it.type     = CustomGCode::Type(a.value("type", 0));
                it.extruder = a.value("extruder", 1);
                it.color    = a.value("color", std::string());
                it.extra    = a.value("extra", std::string());
                g.push_back(it);
                std::sort(g.begin(), g.end());
            }
            info.mode = s.bundle->filament_presets.size() > 1 ? CustomGCode::MultiAsSingle : CustomGCode::SingleExtruder;
        } else if (cmd == "arrange") {
            arrange_model(true);
        } else {
            s.undo_stack.pop_back();
            return to_j(env, json{{"error", "Unknown plate command " + cmd}}.dump());
        }
        s.viewer_result.reset();
        model_changed();
        ++s.preview_version;
        return to_j(env, plates_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}
