// JNI bridge between the Android app and libslic3r: system profiles (PresetBundle), the
// preset settings shown in the Printer/Filament/Process panels, the plate's model, slicing and
// the slice statistics. Rendering lives in Scene.cpp; both share the Session below.
//
// Every call that touches the session takes g_session.mutex: Kotlin calls in from its IO
// dispatcher and from the GL thread.

// libslic3r references the header-only nanosvg; the final binary provides the implementation,
// before any libslic3r header pulls nanosvg.h in without it.
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
#define NANOSVGRAST_IMPLEMENTATION
#include "nanosvg/nanosvgrast.h"

#include "Session.hpp"

#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Format/ProjectStrategy.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Orient.hpp"
#include "libslic3r/Utils.hpp"

#include <android/log.h>
#include <boost/filesystem.hpp>
#include <nlohmann/json.hpp>

#include <cstdlib>
#include <fstream>

using namespace Slic3r;
using json = nlohmann::json;
namespace fs = boost::filesystem;

Session g_session;

namespace {

std::string jstr(JNIEnv *env, jstring s)
{
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring to_j(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

PresetCollection *collection(int kind)
{
    switch (kind) {
    case 0: return &g_session.bundle->prints;
    case 1: return &g_session.bundle->filaments;
    default: return &g_session.bundle->printers;
    }
}

json preset_names(const PresetCollection &c)
{
    json out = json::array();
    for (const Preset &p : c.get_presets())
        if (p.is_visible && p.is_compatible && !p.is_default)
            out.push_back({{"name", p.name}, {"system", p.is_system}});
    return out;
}

// The printer's own default process and filament, as the app picks them on a printer change.
void select_printer_defaults(PresetBundle &b)
{
    const Preset &printer = b.printers.get_selected_preset();
    const std::string def_print = printer.config.opt_string("default_print_profile");
    if (!def_print.empty())
        b.prints.select_preset_by_name(def_print, true);
    if (const auto *def_fil = printer.config.option<ConfigOptionStrings>("default_filament_profile");
        def_fil && !def_fil->values.empty())
        b.filaments.select_preset_by_name(def_fil->values.front(), true);
    b.filament_presets.assign(1, b.filaments.get_selected_preset_name());
    b.update_multi_material_filament_presets();
}

json state_json()
{
    PresetBundle &b = *g_session.bundle;
    json s;
    s["vendor"] = g_session.vendor;
    s["printer"] = {{"selected", b.printers.get_selected_preset_name()},
                    {"dirty", b.printers.current_is_dirty()},
                    {"list", preset_names(b.printers)}};
    s["process"] = {{"selected", b.prints.get_selected_preset_name()},
                    {"dirty", b.prints.current_is_dirty()},
                    {"list", preset_names(b.prints)}};
    json slots = json::array();
    const auto *colors = b.project_config.option<ConfigOptionStrings>("filament_colour");
    for (size_t i = 0; i < b.filament_presets.size(); ++i)
        slots.push_back({{"name", b.filament_presets[i]},
                         {"color", colors && i < colors->values.size() ? colors->values[i] : "#26A69A"}});
    s["filament"] = {{"slots", slots}, {"dirty", b.filaments.current_is_dirty()}, {"list", preset_names(b.filaments)}};

    const DynamicPrintConfig &pc = b.printers.get_edited_preset().config;
    json area = json::array();
    if (const auto *pa = pc.option<ConfigOptionPoints>("printable_area"))
        for (const Vec2d &p : pa->values)
            area.push_back({p.x(), p.y()});
    s["bed"] = {{"area", area}, {"height", pc.opt_float("printable_height")}};
    if (const auto *bt = b.project_config.option<ConfigOptionEnum<BedType>>("curr_bed_type"))
        s["bedType"] = int(bt->value);
    return s;
}

json option_defs_json()
{
    json out = json::object();
    for (const auto &[key, def] : print_config_def.options) {
        json d;
        d["type"]     = int(def.type);
        d["label"]    = def.label;
        d["full"]     = def.full_label;
        d["category"] = def.category;
        d["tooltip"]  = def.tooltip;
        d["side"]     = def.sidetext;
        d["mode"]     = int(def.mode);
        d["multiline"] = def.multiline;
        d["code"]     = def.is_code;
        d["readonly"] = def.readonly;
        d["gui"]      = int(def.gui_type);
        if (def.min > -FLT_MAX) d["min"] = def.min;
        if (def.max < FLT_MAX) d["max"] = def.max;
        if (!def.enum_values.empty()) d["values"] = def.enum_values;
        if (!def.enum_labels.empty()) d["labels"] = def.enum_labels;
        out[key] = std::move(d);
    }
    return out;
}

json objects_json()
{
    json out = json::array();
    const Model &m = g_session.model;
    for (size_t i = 0; i < m.objects.size(); ++i) {
        const ModelObject *o = m.objects[i];
        if (o->instances.empty()) continue;
        const ModelInstance *inst = o->instances.front();
        const BoundingBoxf3 bb = o->instance_bounding_box(0);
        const Vec3d off = inst->get_offset(), rot = inst->get_rotation(), sc = inst->get_scaling_factor();
        out.push_back({{"name", o->name},
                       {"offset", {off.x(), off.y(), off.z()}},
                       {"rotation", {rot.x(), rot.y(), rot.z()}},
                       {"scale", {sc.x(), sc.y(), sc.z()}},
                       {"size", {bb.size().x(), bb.size().y(), bb.size().z()}},
                       {"selected", int(i) == g_session.selected}});
    }
    return out;
}

void arrange_model()
{
    BoundingBox bed;
    const auto *pa = g_session.bundle->printers.get_edited_preset().config.option<ConfigOptionPoints>("printable_area");
    for (const Vec2d &p : pa->values)
        bed.merge(Point(scaled(p.x()), scaled(p.y())));
    arrangement::ArrangeParams params;
    params.min_obj_distance = scaled(6.);
    arrange_objects(g_session.model, bed, params, [](arrangement::ArrangePolygon &ap) { ++ap.bed_idx; });
}

void model_changed()
{
    g_session.sliced = false;
    ++g_session.scene_version;
}

// The numbers behind the desktop preview legend: time and filament per line type, and totals.
json slice_stats_json()
{
    const GCodeProcessorResult &r = *g_session.result;
    const auto &stats = r.print_statistics;
    std::map<int, double> role_time;
    double travel_time = 0.;
    for (const auto &mv : r.moves) {
        if (mv.type == EMoveType::Extrude)
            role_time[int(mv.extrusion_role)] += mv.time[0];
        else if (mv.type == EMoveType::Travel)
            travel_time += mv.time[0];
    }
    const double total = stats.modes[0].time;
    json roles = json::array();
    double total_m = 0., total_g = 0.;
    for (const auto &[role, t] : role_time) {
        json row{{"role", role}, {"name", ExtrusionEntity::role_to_string(ExtrusionRole(role))}, {"time", t},
                 {"percent", total > 0. ? 100. * t / total : 0.}};
        if (auto it = stats.used_filaments_per_role.find(ExtrusionRole(role)); it != stats.used_filaments_per_role.end()) {
            row["meters"] = it->second.first;
            row["grams"]  = it->second.second;
            total_m += it->second.first;
            total_g += it->second.second;
        }
        roles.push_back(std::move(row));
    }
    return {{"roles", roles}, {"travelTime", travel_time}, {"totalTime", total},
            {"prepareTime", stats.modes[0].prepare_time}, {"meters", total_m}, {"grams", total_g},
            {"gcode", g_session.gcode_path}};
}

struct ProgressListener
{
    JNIEnv   *env;
    jobject   obj;
    jmethodID method;
    void operator()(int percent, const std::string &text) const
    {
        jstring t = env->NewStringUTF(text.c_str());
        env->CallVoidMethod(obj, method, jint(percent), t);
        env->DeleteLocalRef(t);
    }
};

} // namespace

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_dev_orcaslicer_android_OrcaCore_##name

JNI(jstring, init)(JNIEnv *env, jobject, jstring resources, jstring cache)
{
    std::lock_guard lock(g_session.mutex);
    const std::string res = jstr(env, resources);
    setenv("TMPDIR", jstr(env, cache).c_str(), 1);
    set_resources_dir(res);
    set_var_dir(res + "/images");
    set_local_dir(res + "/i18n");
    set_sys_shapes_dir(res + "/shapes");
    set_temporary_dir(jstr(env, cache));
    set_logging_level(2);
    return nullptr;
}

JNI(jstring, optionDefs)(JNIEnv *env, jobject) { return to_j(env, option_defs_json().dump()); }

// Lists vendors and their printer models straight from resources/profiles/<Vendor>.json.
JNI(jstring, vendors)(JNIEnv *env, jobject)
{
    json out = json::array();
    const fs::path dir = fs::path(resources_dir()) / "profiles";
    for (const auto &entry : fs::directory_iterator(dir)) {
        if (entry.path().extension() != ".json") continue;
        try {
            std::ifstream in(entry.path().string());
            const json v = json::parse(in);
            json models = json::array();
            for (const json &m : v.value("machine_model_list", json::array()))
                models.push_back(m.value("name", ""));
            if (models.empty()) continue;
            out.push_back({{"id", entry.path().stem().string()}, {"name", v.value("name", entry.path().stem().string())}, {"models", models}});
        } catch (const std::exception &) {
        }
    }
    return to_j(env, out.dump());
}

// Loads one vendor (plus the Orca filament library) and enables all of its printers.
JNI(jstring, loadVendor)(JNIEnv *env, jobject, jstring vendor, jstring printer)
{
    std::lock_guard lock(g_session.mutex);
    try {
        const std::string v = jstr(env, vendor);
        set_data_dir((fs::path(resources_dir()) / "profiles").string());
        fs::create_directories(fs::path(data_dir()) / PRESET_USER_DIR);
        auto bundle = std::make_unique<PresetBundle>();
        bundle->set_is_validation_mode(true);
        bundle->set_vendor_to_validate(v);
        AppConfig app_config;
        app_config.set("preset_folder", "default");
        bundle->load_presets(app_config, ForwardCompatibilitySubstitutionRule::EnableSilent);
        for (const Preset &p : bundle->printers.get_presets()) {
            if (p.vendor == nullptr) continue;
            const std::string model = p.config.opt_string("printer_model"), variant = p.config.opt_string("printer_variant");
            if (!model.empty() && !variant.empty())
                app_config.set_variant(p.vendor->id, model, variant, true);
        }
        bundle->load_installed_printers(app_config);
        g_session.bundle = std::move(bundle);
        g_session.vendor = v;
        const std::string p = jstr(env, printer);
        if (!p.empty() && g_session.bundle->printers.select_preset_by_name(p, true)) {
            g_session.bundle->update_compatible(PresetSelectCompatibleType::Always);
            select_printer_defaults(*g_session.bundle);
        }
        model_changed();
        return to_j(env, state_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

JNI(jstring, state)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    if (!g_session.bundle) return to_j(env, "{}");
    return to_j(env, state_json().dump());
}

// kind: 0 process, 1 filament (slot = index), 2 printer.
JNI(jstring, selectPreset)(JNIEnv *env, jobject, jint kind, jint slot, jstring name)
{
    std::lock_guard lock(g_session.mutex);
    PresetBundle &b = *g_session.bundle;
    const std::string n = jstr(env, name);
    if (kind == 1) {
        if (slot >= 0 && size_t(slot) < b.filament_presets.size())
            b.filament_presets[slot] = n;
        if (slot == 0)
            b.filaments.select_preset_by_name(n, true);
    } else {
        collection(kind)->select_preset_by_name(n, true);
        if (kind == 2) {
            b.update_compatible(PresetSelectCompatibleType::Always);
            select_printer_defaults(b);
        } else {
            b.update_compatible(PresetSelectCompatibleType::OnlyIfWasCompatible);
        }
    }
    model_changed();
    return to_j(env, state_json().dump());
}

JNI(jstring, setFilamentCount)(JNIEnv *env, jobject, jint count)
{
    std::lock_guard lock(g_session.mutex);
    g_session.bundle->set_num_filaments(unsigned(std::max(1, int(count))));
    model_changed();
    return to_j(env, state_json().dump());
}

JNI(jstring, setBedType)(JNIEnv *env, jobject, jint type)
{
    std::lock_guard lock(g_session.mutex);
    g_session.bundle->project_config.set_key_value("curr_bed_type", new ConfigOptionEnum<BedType>(BedType(type)));
    model_changed();
    return to_j(env, state_json().dump());
}

// The edited preset's values. Per-extruder/per-filament vectors show their first element (the
// panels edit one extruder and the selected filament); bed shapes (points) stay whole.
JNI(jstring, values)(JNIEnv *env, jobject, jint kind)
{
    std::lock_guard lock(g_session.mutex);
    const DynamicPrintConfig &c = collection(kind)->get_edited_preset().config;
    json out = json::object();
    for (const std::string &key : c.keys()) {
        const ConfigOption *opt = c.option(key);
        if (opt->is_vector() && opt->type() != coPoints) {
            const auto v = static_cast<const ConfigOptionVectorBase *>(opt)->vserialize();
            out[key] = v.empty() ? std::string() : v.front();
        } else {
            out[key] = opt->serialize();
        }
    }
    return to_j(env, out.dump());
}

JNI(jstring, setValue)(JNIEnv *env, jobject, jint kind, jstring jkey, jstring jvalue)
{
    std::lock_guard lock(g_session.mutex);
    try {
        const std::string key = jstr(env, jkey), value = jstr(env, jvalue);
        Preset &p = collection(kind)->get_edited_preset();
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        ConfigOption *opt = p.config.option(key, true);
        if (opt == nullptr)
            return to_j(env, "Unknown option " + key);
        if (opt->type() == coStrings) {
            auto &vals = static_cast<ConfigOptionStrings *>(opt)->values;
            if (vals.empty()) vals.push_back(value); else vals.front() = value;
        } else if (opt->is_vector() && opt->type() != coPoints) {
            // Replace the first element, keep the others (other extruders / filaments).
            auto vals = static_cast<const ConfigOptionVectorBase *>(opt)->vserialize();
            if (vals.empty()) vals.push_back(value); else vals.front() = value;
            std::string joined;
            for (size_t i = 0; i < vals.size(); ++i)
                joined += (i ? "," : "") + vals[i];
            p.config.set_deserialize(key, joined, ctx);
        } else {
            p.config.set_deserialize(key, value, ctx);
        }
        collection(kind)->update_dirty();
        model_changed();
        return nullptr;
    } catch (const std::exception &e) {
        return to_j(env, e.what());
    }
}

JNI(void, discardChanges)(JNIEnv *, jobject, jint kind)
{
    std::lock_guard lock(g_session.mutex);
    collection(kind)->discard_current_changes();
    model_changed();
}

JNI(jstring, savePreset)(JNIEnv *env, jobject, jint kind, jstring name)
{
    std::lock_guard lock(g_session.mutex);
    try {
        const std::string n = jstr(env, name);
        collection(kind)->save_current_preset(n);
        if (kind == 1 && !g_session.bundle->filament_presets.empty())
            g_session.bundle->filament_presets[0] = n;
        g_session.bundle->update_compatible(PresetSelectCompatibleType::Never);
        return to_j(env, state_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

JNI(jstring, addModel)(JNIEnv *env, jobject, jstring path)
{
    std::lock_guard lock(g_session.mutex);
    try {
        Model loaded = Model::read_from_file(jstr(env, path), nullptr, nullptr, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
        for (ModelObject *o : loaded.objects) {
            ModelObject *added = g_session.model.add_object(*o);
            added->ensure_on_bed();
        }
        arrange_model();
        g_session.selected = int(g_session.model.objects.size()) - 1;
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
    g_session.selected = idx;
    ++g_session.scene_version;
}

// op: 0 delete, 1 duplicate, 2 arrange all, 3 orient, 4 clear, 5 lay flat (drop to bed)
JNI(jstring, objectOp)(JNIEnv *env, jobject, jint op, jint idx)
{
    std::lock_guard lock(g_session.mutex);
    Model &m = g_session.model;
    const bool valid = idx >= 0 && size_t(idx) < m.objects.size();
    switch (op) {
    case 0: if (valid) { m.delete_object(size_t(idx)); g_session.selected = -1; } break;
    case 1: if (valid) { m.add_object(*m.objects[idx]); arrange_model(); } break;
    case 2: arrange_model(); break;
    case 3: if (valid) { orientation::orient(m.objects[idx]->instances.front()); m.objects[idx]->ensure_on_bed(); } break;
    case 4: m.clear_objects(); g_session.selected = -1; break;
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
    ModelInstance *inst = m.objects[idx]->instances.front();
    inst->set_offset(Vec3d(v[0], v[1], v[2]));
    inst->set_rotation(Vec3d(v[3], v[4], v[5]));
    inst->set_scaling_factor(Vec3d(v[6], v[7], v[8]));
    m.objects[idx]->invalidate_bounding_box();
    m.objects[idx]->ensure_on_bed();
    model_changed();
    return to_j(env, objects_json().dump());
}

JNI(jstring, slice)(JNIEnv *env, jobject, jobject listener)
{
    std::lock_guard lock(g_session.mutex);
    try {
        PresetBundle &b = *g_session.bundle;
        if (g_session.model.objects.empty())
            return to_j(env, json{{"error", "Add a model to the plate first."}}.dump());
        DynamicPrintConfig cfg = b.full_config();
        ProgressListener progress{env, listener, env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(ILjava/lang/String;)V")};

        g_session.print = std::make_unique<Print>();
        Print &print = *g_session.print;
        print.is_BBL_printer() = b.is_bbl_vendor();
        print.apply(g_session.model, cfg);
        StringObjectException err = print.validate();
        if (!err.string.empty())
            return to_j(env, json{{"error", err.string}}.dump());
        Model::setExtruderParams(cfg, int(b.filament_presets.size()));
        Model::setPrintSpeedTable(cfg, print.config());
        print.set_status_callback([&progress](const PrintBase::SlicingStatus &s) {
            if (s.percent >= 0) progress(s.percent, s.text);
        });
        print.process();

        const fs::path out = fs::path(temporary_dir()) / "plate_1.gcode";
        g_session.result = std::make_unique<GCodeProcessorResult>();
        g_session.gcode_path = print.export_gcode(out.string(), g_session.result.get(), nullptr);
        g_session.sliced = true;
        ++g_session.preview_version;
        progress(100, "Done");
        return to_j(env, slice_stats_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

JNI(jstring, gcodePath)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    return to_j(env, g_session.sliced ? g_session.gcode_path : std::string());
}
