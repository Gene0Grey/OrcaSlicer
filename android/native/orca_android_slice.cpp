// Minimal headless slicer for Android, built directly on libslic3r.
//
// The desktop CLI (src/OrcaSlicer.cpp) depends on GUI code (PartPlateList, OpenGLManager,
// BitmapCache, wxFileName), so it cannot be built without wxWidgets. This driver covers the
// single-plate FFF path: load models, apply standalone machine/process/filament presets,
// arrange on the bed, slice, export G-code.
//
// usage: orca_android_slice --resources DIR --datadir DIR --machine M.json --process P.json
//                           --filament F.json --output OUT.gcode MODEL...
// stdout: "PROGRESS <percent> <text>" lines while slicing; errors on stderr; exit 0 on success.

// libslic3r references the header-only nanosvg; executables provide the implementation, before any
// libslic3r header pulls nanosvg.h in without it (same as OrcaSlicer_profile_validator.cpp).
#define NANOSVG_IMPLEMENTATION
#include "nanosvg/nanosvg.h"
#define NANOSVGRAST_IMPLEMENTATION
#include "nanosvg/nanosvgrast.h"

#include "libslic3r/libslic3r.h"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Format/ProjectStrategy.hpp"

#include <cstdio>
#include <exception>
#include <map>
#include <string>
#include <vector>

using namespace Slic3r;

namespace {

int fail(const std::string &msg)
{
    std::fprintf(stderr, "ERROR %s\n", msg.c_str());
    return 1;
}

// Loads one Orca preset JSON (type/name/from + option keys) on top of `config`.
bool apply_preset(DynamicPrintConfig &config, const std::string &file, std::string &error)
{
    DynamicPrintConfig preset;
    std::map<std::string, std::string> key_values;
    std::string reason;
    preset.load_from_json(file, ForwardCompatibilitySubstitutionRule::Enable, key_values, reason);
    if (!reason.empty()) {
        error = file + ": " + reason;
        return false;
    }
    config.apply(preset);
    return true;
}

} // namespace

int main(int argc, char **argv)
{
    std::map<std::string, std::string> opt;
    std::vector<std::string> models;
    for (int i = 1; i < argc; ++i) {
        std::string a = argv[i];
        if (a.rfind("--", 0) == 0 && i + 1 < argc)
            opt[a.substr(2)] = argv[++i];
        else
            models.push_back(a);
    }
    for (const char *k : {"resources", "datadir", "machine", "process", "filament", "output"})
        if (opt[k].empty())
            return fail(std::string("missing --") + k);
    if (models.empty())
        return fail("no model files given");

    const std::string &res = opt["resources"];
    set_resources_dir(res);
    set_var_dir(res + "/images");
    set_local_dir(res + "/i18n");
    set_sys_shapes_dir(res + "/shapes");
    set_data_dir(opt["datadir"]);
    set_temporary_dir(opt["datadir"]);
    set_logging_level(2); // errors and warnings

    try {
        DynamicPrintConfig config = DynamicPrintConfig::full_print_config();
        std::string error;
        for (const char *k : {"machine", "process", "filament"})
            if (!apply_preset(config, opt[k], error))
                return fail(error);
        config.normalize_fdm();
        // Single extruder, single plate.
        config.option<ConfigOptionInts>("filament_map", true)->values = {1};

        Model model;
        for (const std::string &path : models) {
            Model loaded = Model::read_from_file(path, nullptr, nullptr, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
            for (ModelObject *o : loaded.objects)
                model.add_object(*o);
        }
        if (model.objects.empty())
            return fail("no printable objects in input");
        for (ModelObject *o : model.objects)
            o->ensure_on_bed();

        // Arrange everything inside the printable area's bounding box.
        BoundingBox bed;
        for (const Vec2d &p : config.option<ConfigOptionPoints>("printable_area")->values)
            bed.merge(Point(scaled(p.x()), scaled(p.y())));
        arrangement::ArrangeParams params;
        params.min_obj_distance = scaled(6.);
        if (!arrange_objects(model, bed, params, [](arrangement::ArrangePolygon &ap) { ++ap.bed_idx; }))
            std::fprintf(stderr, "WARN some objects do not fit on the bed\n");

        Print print;
        print.apply(model, config);
        StringObjectException err = print.validate();
        if (!err.string.empty())
            return fail(err.string);

        Model::setExtruderParams(config, 1);
        Model::setPrintSpeedTable(config, print.config());
        print.set_status_callback([](const PrintBase::SlicingStatus &s) {
            if (s.percent >= 0)
                std::printf("PROGRESS %d %s\n", s.percent, s.text.c_str());
            std::fflush(stdout);
        });
        print.process();

        GCodeProcessorResult result;
        const std::string out = print.export_gcode(opt["output"], &result, nullptr);
        std::printf("DONE %s\n", out.c_str());
        return 0;
    } catch (const std::exception &e) {
        return fail(e.what());
    }
}
