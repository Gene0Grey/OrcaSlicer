// The plate's objects: loading, the object list the app shows, selection, the basic object
// operations, transforms, per-object / per-part / per-height-range settings, undo and redo.
// The object menu's editing commands live in Edit.cpp.

#include "Session.hpp"

#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Orient.hpp"

using namespace Slic3r;
using json = nlohmann::json;

namespace {

json vec(const Vec3d &v) { return {v.x(), v.y(), v.z()}; }

int extruder_of(const ModelConfig &c) { return c.has("extruder") ? c.opt_int("extruder") : 0; }

// The config an object setting call addresses: part >= 0 a volume, -1 the object,
// <= -2 height range number (-2 - part).
ModelConfig *target_config(ModelObject &o, int part)
{
    if (part >= 0) return size_t(part) < o.volumes.size() ? &o.volumes[part]->config : nullptr;
    if (part == -1) return &o.config;
    int n = -2 - part;
    for (auto &[range, cfg] : o.layer_config_ranges)
        if (n-- == 0) return &cfg;
    return nullptr;
}

} // namespace

json objects_json()
{
    json out = json::array();
    const Model &m = g_session.model;
    for (size_t i = 0; i < m.objects.size(); ++i) {
        const ModelObject *o = m.objects[i];
        if (o->instances.empty()) continue;
        const ModelInstance *inst = o->instances.front();
        const BoundingBoxf3 bb = o->instance_bounding_box(0);
        json volumes = json::array();
        bool inch = false, meter = false;
        for (const ModelVolume *v : o->volumes) {
            inch |= v->source.is_converted_from_inches;
            meter |= v->source.is_converted_from_meters;
            volumes.push_back({{"name", v->name}, {"type", int(v->type())}, {"filament", extruder_of(v->config)},
                               {"settings", v->config.size() - (v->config.has("extruder") ? 1 : 0)},
                               {"offset", vec(v->get_offset())}, {"rotation", vec(v->get_rotation())},
                               {"scale", vec(v->get_scaling_factor())}, {"size", vec(v->mesh().bounding_box().size())}});
        }
        json ranges = json::array();
        for (const auto &[range, cfg] : o->layer_config_ranges)
            ranges.push_back({{"lo", range.first}, {"hi", range.second}, {"settings", cfg.size()}});
        out.push_back({{"name", o->name},
                       {"offset", vec(inst->get_offset())},
                       {"rotation", vec(inst->get_rotation())},
                       {"scale", vec(inst->get_scaling_factor())},
                       {"size", vec(bb.size())},
                       {"selected", g_session.picked.count(int(i)) > 0},
                       {"primary", int(i) == g_session.selected},
                       {"outside", g_session.outside(*o)},
                       {"instances", o->instances.size()},
                       {"printable", inst->printable},
                       {"filament", extruder_of(o->config)},
                       {"settings", o->config.size() - (o->config.has("extruder") ? 1 : 0)},
                       {"inch", inch}, {"meter", meter},
                       {"source", o->input_file},
                       {"volumes", volumes}, {"ranges", ranges}});
    }
    return out;
}

void arrange_model()
{
    const BoundingBoxf3 bv = bed_volume();
    BoundingBox bed(Point(scaled(bv.min.x()), scaled(bv.min.y())), Point(scaled(bv.max.x()), scaled(bv.max.y())));
    arrangement::ArrangeParams params;
    params.min_obj_distance = scaled(6.);
    arrange_objects(g_session.model, bed, params, [](arrangement::ArrangePolygon &ap) { ++ap.bed_idx; });
}

JNI(jstring, addModel)(JNIEnv *env, jobject, jstring path)
{
    std::lock_guard lock(g_session.mutex);
    try {
        Model loaded = Model::read_from_file(jstr(env, path), nullptr, nullptr, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
        g_session.snapshot();
        for (ModelObject *o : loaded.objects) {
            ModelObject *added = g_session.model.add_object(*o);
            added->ensure_on_bed();
        }
        arrange_model();
        g_session.select_only(int(g_session.model.objects.size()) - 1);
        model_changed();
        return to_j(env, objects_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

JNI(jstring, objects)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    return to_j(env, objects_json().dump());
}

JNI(void, select)(JNIEnv *, jobject, jint idx)
{
    std::lock_guard lock(g_session.mutex);
    g_session.select_only(idx);
    ++g_session.scene_version;
}

// op: 0 delete (the whole selection), 1 duplicate, 2 arrange all, 3 orient, 4 clear, 5 drop to bed
JNI(jstring, objectOp)(JNIEnv *env, jobject, jint op, jint idx)
{
    std::lock_guard lock(g_session.mutex);
    Model &m = g_session.model;
    const bool valid = idx >= 0 && size_t(idx) < m.objects.size();
    if (valid || op == 2 || op == 4) g_session.snapshot();
    switch (op) {
    case 0:
        if (valid) {
            std::set<int> doomed = g_session.picked.count(idx) ? g_session.picked : std::set<int>{idx};
            for (auto it = doomed.rbegin(); it != doomed.rend(); ++it)
                if (size_t(*it) < m.objects.size()) m.delete_object(size_t(*it));
            g_session.select_only(-1);
        }
        break;
    case 1: if (valid) { m.add_object(*m.objects[idx]); arrange_model(); } break;
    case 2: arrange_model(); break;
    case 3: if (valid) { orientation::orient(m.objects[idx]->instances.front()); m.objects[idx]->ensure_on_bed(); } break;
    case 4: m.clear_objects(); g_session.select_only(-1); break;
    case 5: if (valid) m.objects[idx]->ensure_on_bed(); break;
    }
    model_changed();
    return to_j(env, objects_json().dump());
}

JNI(jstring, setTransform)(JNIEnv *env, jobject, jint idx, jdoubleArray values)
{
    std::lock_guard lock(g_session.mutex);
    Model &m = g_session.model;
    if (idx < 0 || size_t(idx) >= m.objects.size()) return to_j(env, objects_json().dump());
    jdouble v[9];
    env->GetDoubleArrayRegion(values, 0, 9, v);
    g_session.snapshot();
    ModelInstance *inst = m.objects[idx]->instances.front();
    inst->set_offset(Vec3d(v[0], v[1], v[2]));
    inst->set_rotation(Vec3d(v[3], v[4], v[5]));
    inst->set_scaling_factor(Vec3d(v[6], v[7], v[8]));
    m.objects[idx]->invalidate_bounding_box();
    m.objects[idx]->ensure_on_bed();
    model_changed();
    return to_j(env, objects_json().dump());
}

// Per-object setting overrides (the desktop's object settings): keys an object may override,
// and the overrides of an object, one of its parts, or one of its height ranges.
JNI(jstring, objectSettingKeys)(JNIEnv *env, jobject)
{
    json out = json::array();
    for (const std::string &k : PrintObjectConfig().keys()) out.push_back(k);
    for (const std::string &k : PrintRegionConfig().keys()) out.push_back(k);
    return to_j(env, out.dump());
}

JNI(jstring, objectValues)(JNIEnv *env, jobject, jint idx, jint part)
{
    std::lock_guard lock(g_session.mutex);
    json out = json::object();
    if (idx >= 0 && size_t(idx) < g_session.model.objects.size())
        if (const ModelConfig *c = target_config(*g_session.model.objects[idx], part))
            for (const std::string &key : c->keys())
                if (key != "extruder") out[key] = c->get().opt_serialize(key);
    return to_j(env, out.dump());
}

// value == nullptr removes the override.
JNI(jstring, setObjectValue)(JNIEnv *env, jobject, jint idx, jint part, jstring jkey, jstring jvalue)
{
    std::lock_guard lock(g_session.mutex);
    if (idx < 0 || size_t(idx) >= g_session.model.objects.size()) return to_j(env, "No such object");
    try {
        ModelConfig *c = target_config(*g_session.model.objects[idx], part);
        if (c == nullptr) return to_j(env, "No such part");
        g_session.snapshot();
        // The snapshot copied the model; look the target up again in case it moved.
        c = target_config(*g_session.model.objects[idx], part);
        const std::string key = jstr(env, jkey);
        if (jvalue == nullptr) {
            c->erase(key);
        } else {
            ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
            c->set_deserialize(key, jstr(env, jvalue), ctx);
        }
        model_changed();
        return nullptr;
    } catch (const std::exception &e) {
        return to_j(env, e.what());
    }
}

// Undo/redo of plate edits. Return the objects, like the other object calls.
JNI(jstring, undo)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    g_session.step(g_session.undo_stack, g_session.redo_stack);
    return to_j(env, objects_json().dump());
}

JNI(jstring, redo)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    g_session.step(g_session.redo_stack, g_session.undo_stack);
    return to_j(env, objects_json().dump());
}

// {undo, redo} counts for enabling the buttons.
JNI(jintArray, history)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    const jint v[2] = {jint(g_session.undo_stack.size()), jint(g_session.redo_stack.size())};
    jintArray a = env->NewIntArray(2);
    env->SetIntArrayRegion(a, 0, 2, v);
    return a;
}
