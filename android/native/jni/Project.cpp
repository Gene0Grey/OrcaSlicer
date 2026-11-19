// 3MF projects in the desktop's format (bbs_3mf): objects with their parts, settings and
// placement, the plates (names, locks, which objects stand on them), the full configuration,
// and plate thumbnails. Also the "sliced file": the same 3MF with every sliced plate's G-code.

#include "Session.hpp"

#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/GCode/ThumbnailData.hpp"

#include <boost/filesystem.hpp>

using namespace Slic3r;
using json = nlohmann::json;

ThumbnailData render_thumbnail(const Model &model, const std::vector<std::string> &colors, unsigned w, unsigned h, bool transparent);

namespace {

// The desktop's 3MF plate thumbnail size (THUMBNAIL_SIZE_3MF).
constexpr unsigned THUMB = 512;

std::string save(const std::string &path, bool with_gcode)
{
    Session &s = g_session;
    DynamicPrintConfig cfg = s.bundle->full_config();
    std::vector<std::string> colors;
    if (const auto *c = s.bundle->project_config.option<ConfigOptionStrings>("filament_colour")) colors = c->values;

    PlateDataPtrs plates;
    std::vector<ThumbnailData> thumbs(s.plates.size());
    std::vector<ThumbnailData *> thumb_ptrs;
    for (int p = 0; p < int(s.plates.size()); ++p) {
        std::set<std::pair<int, int>> members;
        for (int oi = 0; oi < int(s.model.objects.size()); ++oi)
            for (int k = 0; k < int(s.model.objects[oi]->instances.size()); ++k)
                if (s.plate_of(*s.model.objects[oi], size_t(k)) == p) members.insert({oi, k});
        auto *pd = new PlateData(p, members, s.plates[p].locked);
        pd->plate_name = s.plates[p].name;
        const Plate &pl = s.plates[p];
        if (with_gcode && pl.sliced && pl.result) {
            pd->gcode_file       = pl.gcode_path;
            pd->is_sliced_valid  = true;
            pd->gcode_prediction = std::to_string(int(pl.result->print_statistics.modes[0].time));
            pd->gcode_weight     = std::to_string(pl.stats.value("grams", 0.));
            pd->printer_model_id = cfg.opt_string("printer_model");
        }
        plates.push_back(pd);
        thumbs[p] = render_thumbnail(plate_model(p), colors, THUMB, THUMB, true);
    }
    for (ThumbnailData &t : thumbs) thumb_ptrs.push_back(&t);

    StoreParams params;
    params.path                 = path;
    params.model                = &s.model;
    params.plate_data_list      = plates;
    params.config               = &cfg;
    params.thumbnail_data       = thumb_ptrs;
    // The writer walks these lists by their size: left empty, nothing else is written.
    params.strategy = (with_gcode ? SaveStrategy::Silence | SaveStrategy::SplitModel | SaveStrategy::WithGcode
                                  : SaveStrategy::SplitModel | SaveStrategy::ShareMesh) | SaveStrategy::Zip64;
    const bool ok = store_bbs_3mf(params);
    release_PlateData_list(plates);
    return ok ? "" : "Could not write the project file.";
}

} // namespace

// Saves the project; with_gcode adds every sliced plate's G-code (desktop "Export all plate
// sliced file"). Returns "" or an error.
JNI(jstring, saveProject)(JNIEnv *env, jobject, jstring jpath, jboolean with_gcode)
{
    std::lock_guard lock(g_session.mutex);
    try {
        if (!g_session.bundle) return to_j(env, "Choose a printer first.");
        return to_j(env, save(jstr(env, jpath), with_gcode));
    } catch (const std::exception &e) {
        return to_j(env, e.what());
    }
}

// Opens a 3MF project: replaces the plate's objects and plates, and loads its printer, filament and
// process settings as the desktop does. Returns {"name"} or {"error"}.
JNI(jstring, openProject)(JNIEnv *env, jobject, jstring jpath)
{
    std::lock_guard lock(g_session.mutex);
    Session &s = g_session;
    PlateDataPtrs plates;
    std::vector<Preset *> presets;
    try {
        if (!s.bundle) return to_j(env, json{{"error", "Choose a printer first."}}.dump());
        const std::string path = jstr(env, jpath);
        DynamicPrintConfig config;
        ConfigSubstitutionContext subs(ForwardCompatibilitySubstitutionRule::EnableSilent);
        bool is_bbl = false;
        Semver version;
        Model model = Model::read_from_file(path, &config, &subs, LoadStrategy::LoadModel | LoadStrategy::LoadConfig, &plates, &presets,
                                            &is_bbl, &version);
        for (Preset *p : presets) delete p;
        presets.clear();
        if (!config.empty()) s.bundle->load_config_model(path, std::move(config), version);

        s.snapshot();
        s.model = std::move(model);
        for (ModelObject *o : s.model.objects) o->input_file.clear(); // parts are inside the 3MF now
        s.plates.assign(std::max<size_t>(1, plates.size()), Plate());
        for (const PlateData *pd : plates)
            if (pd && pd->plate_index >= 0 && size_t(pd->plate_index) < s.plates.size()) {
                s.plates[pd->plate_index].name   = pd->plate_name;
                s.plates[pd->plate_index].locked = pd->locked;
            }
        release_PlateData_list(plates);
        s.current = 0;
        s.viewer_result.reset();
        s.select_only(-1);
        model_changed();
        ++s.preview_version;
        return to_j(env, json{{"name", boost::filesystem::path(path).stem().string()}}.dump());
    } catch (const std::exception &e) {
        for (Preset *p : presets) delete p;
        release_PlateData_list(plates);
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}
