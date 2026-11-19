// Preset tools of the desktop's preset combos and dialogs: filament colours (with the flushing
// volumes recalculated as the desktop does on a colour change), the flushing volumes table,
// editing a given filament slot, rename / delete / import / export, and comparing two presets.

#include "Session.hpp"

#include "libslic3r/AppConfig.hpp"
#include "libslic3r/FlushVolCalc.hpp"

#include <boost/filesystem.hpp>

#include <algorithm>
#include <cmath>

using namespace Slic3r;
using json = nlohmann::json;

json state_json(); // OrcaCore.cpp

// FlushVolCalc (libslic3r) needs RGB2HSV, which the desktop defines in its GUI utilities
// (slic3r/Utils/ColorSpaceConvert.cpp, which pulls in wxWidgets); same definition here.
void RGB2HSV(float r, float g, float b, float* h, float* s, float* v)
{
    float Cmax = std::max(std::max(r, g), b);
    float Cmin = std::min(std::min(r, g), b);
    float delta = Cmax - Cmin;

    if (std::abs(delta) < 0.001) {
        *h = 0.f;
    }
    else if (Cmax == r) {
        *h = 60.f * fmod((g - b) / delta, 6.f);
    }
    else if (Cmax == g) {
        *h = 60.f * ((b - r) / delta + 2);
    }
    else {
        *h = 60.f * ((r - g) / delta + 4);
    }

    if (std::abs(Cmax) < 0.001) {
        *s = 0.f;
    }
    else {
        *s = delta / Cmax;
    }

    *v = Cmax;
}

namespace {

PresetCollection &collection(int kind)
{
    PresetBundle &b = *g_session.bundle;
    return kind == 0 ? b.prints : kind == 1 ? b.filaments : b.printers;
}

std::array<unsigned char, 3> rgb(const std::string &hex)
{
    unsigned v = 0x26A69A;
    if (hex.size() >= 7 && hex[0] == '#') v = unsigned(std::stoul(hex.substr(1, 6), nullptr, 16));
    return {(unsigned char) ((v >> 16) & 255), (unsigned char) ((v >> 8) & 255), (unsigned char) (v & 255)};
}

// Flushing volume between every pair of filaments from their colours (WipingDialog::CalcFlushingVolumes,
// single nozzle). ponytail: min volume = nozzle_volume, without the long-retraction adjustments.
void recalc_flush()
{
    PresetBundle &b = *g_session.bundle;
    const DynamicPrintConfig full = b.full_config();
    const auto *colors = full.option<ConfigOptionStrings>("filament_colour");
    if (!colors) return;
    const size_t n = colors->values.size();
    int min_vol = 0;
    if (const auto *nv = full.option<ConfigOptionFloatsNullable>("nozzle_volume"); nv && !nv->values.empty()) min_vol = int(nv->get_at(0));
    int dataset = 0;
    if (const auto *ds = full.option<ConfigOptionIntsNullable>("nozzle_flush_dataset"); ds && !ds->values.empty()) dataset = ds->get_at(0);
    FlushVolCalculator calc(min_vol, g_max_flush_volume, dataset);
    std::vector<double> m(n * n, 0.);
    for (size_t i = 0; i < n; ++i)
        for (size_t j = 0; j < n; ++j)
            if (i != j) {
                const auto a = rgb(colors->values[i]), c = rgb(colors->values[j]);
                m[i * n + j] = calc.calc_flush_vol(255, a[0], a[1], a[2], 255, c[0], c[1], c[2]);
            }
    b.project_config.option<ConfigOptionFloats>("flush_volumes_matrix", true)->values = m;
}

} // namespace

JNI(jstring, setFilamentColor)(JNIEnv *env, jobject, jint slot, jstring jhex)
{
    std::lock_guard lock(g_session.mutex);
    PresetBundle &b = *g_session.bundle;
    auto *colors = b.project_config.option<ConfigOptionStrings>("filament_colour", true);
    if (colors->values.size() < b.filament_presets.size()) colors->values.resize(b.filament_presets.size(), "#26A69A");
    if (slot >= 0 && size_t(slot) < colors->values.size()) colors->values[slot] = jstr(env, jhex);
    recalc_flush();
    model_changed();
    return to_j(env, state_json().dump());
}

// The flushing volumes table: n x n matrix (row = from, column = to) and the multiplier.
JNI(jstring, flushVolumes)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    const DynamicPrintConfig &pc = g_session.bundle->project_config;
    json out = json::object();
    if (const auto *m = pc.option<ConfigOptionFloats>("flush_volumes_matrix")) out["matrix"] = m->values;
    if (const auto *f = pc.option<ConfigOptionFloats>("flush_multiplier")) out["multiplier"] = f->values.empty() ? 1. : f->values.front();
    out["count"] = g_session.bundle->filament_presets.size();
    return to_j(env, out.dump());
}

// matrix: n*n values, or empty to recalculate from the colours.
JNI(void, setFlushVolumes)(JNIEnv *env, jobject, jdoubleArray jmatrix, jdouble multiplier)
{
    std::lock_guard lock(g_session.mutex);
    PresetBundle &b = *g_session.bundle;
    const jsize len = env->GetArrayLength(jmatrix);
    if (len == 0) {
        recalc_flush();
    } else {
        std::vector<double> m(static_cast<size_t>(len), 0.);
        env->GetDoubleArrayRegion(jmatrix, 0, len, m.data());
        b.project_config.option<ConfigOptionFloats>("flush_volumes_matrix", true)->values = m;
    }
    auto *mul = b.project_config.option<ConfigOptionFloats>("flush_multiplier", true);
    if (mul->values.empty()) mul->values.push_back(multiplier); else mul->values.front() = multiplier;
    model_changed();
}

// Makes filament slot `slot` the one the filament settings edit (the desktop edits the slot whose
// combo was opened).
JNI(jstring, editFilamentSlot)(JNIEnv *env, jobject, jint slot)
{
    std::lock_guard lock(g_session.mutex);
    PresetBundle &b = *g_session.bundle;
    if (slot >= 0 && size_t(slot) < b.filament_presets.size()) b.filaments.select_preset_by_name(b.filament_presets[slot], true);
    return to_j(env, state_json().dump());
}

// Renames the selected user preset (save under the new name, delete the old). Returns state or {"error"}.
JNI(jstring, renamePreset)(JNIEnv *env, jobject, jint kind, jstring jname)
{
    std::lock_guard lock(g_session.mutex);
    try {
        PresetCollection &c = collection(kind);
        const Preset &cur = c.get_selected_preset();
        if (cur.is_system || cur.is_default) return to_j(env, json{{"error", "System presets can't be renamed. Save a copy first."}}.dump());
        const std::string old = cur.name, name = jstr(env, jname);
        c.save_current_preset(name);
        c.delete_preset(old);
        c.select_preset_by_name(name, true);
        PresetBundle &b = *g_session.bundle;
        if (kind == 1) for (std::string &f : b.filament_presets) if (f == old) f = name;
        b.update_compatible(PresetSelectCompatibleType::Never);
        return to_j(env, state_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

JNI(jstring, deletePreset)(JNIEnv *env, jobject, jint kind)
{
    std::lock_guard lock(g_session.mutex);
    try {
        PresetCollection &c = collection(kind);
        const Preset &cur = c.get_selected_preset();
        if (cur.is_system || cur.is_default) return to_j(env, json{{"error", "System presets can't be deleted."}}.dump());
        const std::string old = cur.name;
        c.delete_current_preset();
        PresetBundle &b = *g_session.bundle;
        b.update_compatible(PresetSelectCompatibleType::Always);
        if (kind == 1) for (std::string &f : b.filament_presets) if (f == old) f = b.filaments.get_selected_preset_name();
        model_changed();
        return to_j(env, state_json().dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

// Writes the edited preset as the desktop's preset JSON (File › Export › Export Preset).
JNI(jstring, exportPreset)(JNIEnv *env, jobject, jint kind, jstring jpath)
{
    std::lock_guard lock(g_session.mutex);
    try {
        const Preset &p = collection(kind).get_edited_preset();
        p.config.save_to_json(jstr(env, jpath), p.name, "User", p.version.to_string());
        return to_j(env, "");
    } catch (const std::exception &e) {
        return to_j(env, e.what());
    }
}

// Imports preset files (.json presets, .ini / .3mf / G-code configs; desktop "Import Configs").
JNI(jstring, importPresets)(JNIEnv *env, jobject, jstring jpath)
{
    std::lock_guard lock(g_session.mutex);
    try {
        std::vector<std::string> files{jstr(env, jpath)};
        AppConfig app;
        app.set("preset_folder", "default");
        g_session.bundle->import_presets(files, [](const std::string &) { return 1; /* overwrite */ },
                                         ForwardCompatibilitySubstitutionRule::EnableSilent, app);
        if (files.empty()) return to_j(env, json{{"error", "No presets found in the file."}}.dump());
        g_session.bundle->update_compatible(PresetSelectCompatibleType::Never);
        json out = state_json();
        out["imported"] = files;
        return to_j(env, out.dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

// Settings that differ between two presets of a kind (desktop "Compare presets").
JNI(jstring, comparePresets)(JNIEnv *env, jobject, jint kind, jstring ja, jstring jb)
{
    std::lock_guard lock(g_session.mutex);
    PresetCollection &c = collection(kind);
    const Preset *a = c.find_preset(jstr(env, ja)), *b = c.find_preset(jstr(env, jb));
    json out = json::array();
    if (a && b)
        for (const std::string &key : a->config.diff(b->config))
            out.push_back({{"key", key}, {"a", a->config.opt_serialize(key)}, {"b", b->config.opt_serialize(key)}});
    return to_j(env, out.dump());
}
