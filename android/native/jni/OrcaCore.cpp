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

std::string jstr(JNIEnv *env, jstring s)
{
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring to_j(JNIEnv *env, const std::string &s) { return env->NewStringUTF(s.c_str()); }

void model_changed()
{
    g_session.invalidate();
    ++g_session.scene_version;
}

BoundingBoxf3 bed_volume()
{
    const BoundingBoxf bed = g_session.bed_rect();
    const Vec2d o = g_session.plate_origin(g_session.current);
    return BoundingBoxf3(Vec3d(bed.min.x() + o.x(), bed.min.y() + o.y(), 0.), Vec3d(bed.max.x() + o.x(), bed.max.y() + o.y(), g_session.bed_height()));
}

namespace {

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

} // namespace

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
    if (const auto *nd = pc.option<ConfigOptionFloats>("nozzle_diameter")) s["extruders"] = nd->values.size();
    if (const auto *bt = b.project_config.option<ConfigOptionEnum<BedType>>("curr_bed_type"))
        s["bedType"] = int(bt->value);
    return s;
}

namespace {

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

} // namespace


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

// The edited preset's values. Per-extruder/per-filament vectors show element `index` (the
// extruder being edited; filament presets hold one value); bed shapes (points) stay whole.
JNI(jstring, values)(JNIEnv *env, jobject, jint kind, jint index)
{
    std::lock_guard lock(g_session.mutex);
    const DynamicPrintConfig &c = collection(kind)->get_edited_preset().config;
    json out = json::object();
    for (const std::string &key : c.keys()) {
        const ConfigOption *opt = c.option(key);
        if (opt->is_vector() && opt->type() != coPoints) {
            const auto v = static_cast<const ConfigOptionVectorBase *>(opt)->vserialize();
            out[key] = v.empty() ? std::string() : v[std::min(v.size() - 1, size_t(std::max(0, int(index))))];
        } else {
            out[key] = opt->serialize();
        }
    }
    return to_j(env, out.dump());
}

JNI(jstring, setValue)(JNIEnv *env, jobject, jint kind, jstring jkey, jstring jvalue, jint index)
{
    std::lock_guard lock(g_session.mutex);
    try {
        const std::string key = jstr(env, jkey), value = jstr(env, jvalue);
        Preset &p = collection(kind)->get_edited_preset();
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        ConfigOption *opt = p.config.option(key, true);
        if (opt == nullptr)
            return to_j(env, "Unknown option " + key);
        const size_t i = size_t(std::max(0, int(index)));
        if (opt->type() == coStrings) {
            auto &vals = static_cast<ConfigOptionStrings *>(opt)->values;
            if (vals.size() <= i) vals.resize(i + 1, vals.empty() ? std::string() : vals.back());
            vals[i] = value;
        } else if (opt->is_vector() && opt->type() != coPoints) {
            // Replace element i, keep the others (other extruders / filaments).
            auto vals = static_cast<const ConfigOptionVectorBase *>(opt)->vserialize();
            if (vals.size() <= i) vals.resize(i + 1, vals.empty() ? std::string() : vals.back());
            vals[i] = value;
            std::string joined;
            for (size_t k = 0; k < vals.size(); ++k)
                joined += (k ? "," : "") + vals[k];
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
