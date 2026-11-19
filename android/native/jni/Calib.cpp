// The desktop's Calibration menu (Plater::calib_*), minus the belt-printer variants: each test
// starts a new project with the test model from resources/calib, sets the print / filament /
// printer and object settings the desktop sets, and records the Calib_Params the G-code
// generator reads (set on each plate's Print before slicing, see Slice.cpp).
//
// ponytail: single-extruder values (index 0) where the desktop resolves per-extruder variants.

#include "Session.hpp"

#include "libslic3r/Arrange.hpp"
#include "libslic3r/CutUtils.hpp"
#include "libslic3r/Flow.hpp"
#include "libslic3r/LocalesUtils.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r/calib.hpp"

#include <algorithm>

using namespace Slic3r;
using json = nlohmann::json;

namespace {

template<class T, class Opt> void set_all(DynamicPrintConfig &c, const std::string &key, T v)
{
    if (auto *o = c.option<Opt>(key)) for (auto &x : o->values) x = v;
}

DynamicPrintConfig &print_cfg() { return g_session.bundle->prints.get_edited_preset().config; }
DynamicPrintConfig &filament_cfg() { return g_session.bundle->filaments.get_edited_preset().config; }
DynamicPrintConfig &printer_cfg() { return g_session.bundle->printers.get_edited_preset().config; }
double nozzle() { return printer_cfg().option<ConfigOptionFloats>("nozzle_diameter")->get_at(0); }

bool has_junction_deviation(const DynamicPrintConfig &p)
{
    const auto *flavor = p.option<ConfigOptionEnum<GCodeFlavor>>("gcode_flavor");
    const auto *jd     = p.option<ConfigOptionFloats>("machine_max_junction_deviation");
    return flavor && flavor->value == gcfMarlinFirmware && jd && !jd->values.empty() && jd->values.front() > 0;
}

void zero_pa_where_disabled(DynamicPrintConfig &f)
{
    auto *en = f.option<ConfigOptionBools>("enable_pressure_advance");
    auto *pa = f.option<ConfigOptionFloats>("pressure_advance");
    auto *ad = f.option<ConfigOptionBools>("adaptive_pressure_advance");
    if (!en || !pa || !ad || pa->empty() || ad->empty()) return;
    if (pa->size() < en->size()) pa->resize(en->size());
    if (ad->size() < en->size()) ad->resize(en->size());
    for (size_t v = 0; v < en->size(); ++v)
        if (!en->get_at(v)) { en->values[v] = true; pa->values[v] = 0.; ad->values[v] = false; }
}

// A new project holding the test model(s), standing in the middle of plate 1.
ModelObject &start(const std::string &file)
{
    Session &s = g_session;
    s.snapshot();
    s.model.clear_objects();
    s.plates.assign(1, Plate());
    s.current = 0;
    s.calib.reset();
    s.pa_pattern.reset();
    Model loaded = Model::read_from_file(resources_dir() + "/calib/" + file, nullptr, nullptr, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
    const BoundingBoxf bed = s.bed_rect();
    BoundingBoxf3 all;
    for (ModelObject *o : loaded.objects) { ModelObject *a = s.model.add_object(*o); a->ensure_on_bed(); all.merge(a->instance_bounding_box(0)); }
    const Vec3d shift(bed.center().x() - all.center().x(), bed.center().y() - all.center().y(), 0.);
    for (ModelObject *o : s.model.objects) {
        for (ModelInstance *i : o->instances) i->set_offset(i->get_offset() + shift);
        o->invalidate_bounding_box();
    }
    if (s.model.objects.empty()) throw RuntimeError("The calibration model is missing.");
    s.select_only(0);
    return *s.model.objects.front();
}

// Keeps the part of object 0 below (or above) height z (Plater::cut_horizontal).
void cut_at(double z, bool keep_lower)
{
    Model &m = g_session.model;
    ModelObject *o = m.objects.front();
    const Vec3d off = o->instances.front()->get_offset();
    Cut cut(o, 0, Geometry::translation_transform(z * Vec3d::UnitZ() - off),
            keep_lower ? ModelObjectCutAttribute::KeepLower : ModelObjectCutAttribute::KeepUpper);
    ModelObjectPtrs pieces = cut.perform_with_plane();
    if (pieces.empty()) return;
    ModelObject *kept = m.add_object(*pieces.front());
    kept->ensure_on_bed();
    m.delete_object(size_t(0));
    // Back to the front of the list, where the settings below expect it.
    std::rotate(m.objects.begin(), m.objects.end() - 1, m.objects.end());
}

void finish(const Calib_Params &p)
{
    g_session.calib = std::make_shared<Calib_Params>(p);
    for (PresetCollection *c : {&g_session.bundle->prints, &g_session.bundle->filaments, &g_session.bundle->printers}) c->update_dirty();
}

void calib_temp(Calib_Params p)
{
    ModelObject &o = start("temperature_tower/temperature_tower.drc");
    const double nd = nozzle() > 0 ? nozzle() : 0.4, scale = nd / 0.4, block = 10.;
    if (long n = lround((500 - p.end) / 5 + 1); n > 0 && n * block - EPSILON < o.bounding_box_exact().size().z()) cut_at(n * block - EPSILON, true);
    if (long n = lround((500 - p.start) / 5); n > 0 && n * block + EPSILON < g_session.model.objects.front()->bounding_box_exact().size().z()) cut_at(n * block + EPSILON, false);
    ModelObject &t = *g_session.model.objects.front();
    if (p.nozzle_based_resize && std::abs(scale - 1.) > EPSILON) t.scale(scale, scale, scale);
    t.ensure_on_bed();
    printer_cfg().set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    set_all<int, ConfigOptionInts>(filament_cfg(), "nozzle_temperature_initial_layer", int(lround(p.start)));
    set_all<int, ConfigOptionInts>(filament_cfg(), "nozzle_temperature", int(lround(p.start)));
    if (p.nozzle_based_resize) t.config.set_key_value("layer_height", new ConfigOptionFloat(nd / 2));
    t.config.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
    t.config.set_key_value("brim_width", new ConfigOptionFloat(5.0));
    t.config.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
    t.config.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
    t.config.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
    t.config.set_key_value("overhang_reverse", new ConfigOptionBool(false));
    t.config.set_key_value("precise_z_height", new ConfigOptionBool(false));
    print_cfg().set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
    if (p.nozzle_based_resize) print_cfg().set_key_value("initial_layer_print_height", new ConfigOptionFloat(nd / 2));
    p.mode = CalibMode::Calib_Temp_Tower;
    finish(p);
}

// Flow rate: pass 1 / 2 blocks, or the YOLO linear tests (adjust_settings_for_flowrate_calib).
void calib_flow(bool linear, int pass, int pattern)
{
    start(linear ? (pass == 1 ? "filament_flow/Orca-LinearFlow.3mf" : "filament_flow/Orca-LinearFlow_fine.3mf")
                 : (pass == 1 ? "filament_flow/flowrate-test-pass1.3mf" : "filament_flow/flowrate-test-pass2.3mf"));
    Model &m = g_session.model;
    DynamicPrintConfig &pc = print_cfg(), &fc = filament_cfg();
    const float nd = float(nozzle()), xy = nd / 0.6f;
    double first = std::max(pc.opt_float("initial_layer_print_height"), double(nd) / 2.), lh = nd / 2.;
    const float z = float((first + 9 * lh) / 2);
    const Vec3d sc(xy > 1.2f ? xy : 1., xy > 1.2f ? xy : 1., z);
    BoundingBoxf3 all;
    for (ModelObject *o : m.objects) all.merge(o->instance_bounding_box(0));
    for (ModelObject *o : m.objects) {
        for (ModelInstance *i : o->instances) {
            const Vec3d d = i->get_offset() - all.center();
            i->set_offset(all.center() + Vec3d(d.x() * sc.x(), d.y() * sc.y(), d.z()));
            i->set_scaling_factor(i->get_scaling_factor().cwiseProduct(sc));
        }
        o->invalidate_bounding_box();
    }
    const double flow_ratio = fc.option<ConfigOptionFloats>("filament_flow_ratio")->get_at(0);
    const float lw = nd * 1.2f;
    const Flow flow(lw, float(lh), nd);
    const double vmax = fc.option<ConfigOptionFloats>("filament_max_volumetric_speed")->get_at(0);
    const double max_speed = linear ? vmax / (flow.mm3_per_mm() * (flow_ratio + (pass == 2 ? 0.035 : 0.05)) / flow_ratio)
                                    : vmax / (flow.mm3_per_mm() * (pass == 1 ? 1.2 : 1));
    auto speed = [&](const char *key) {
        const auto *o = pc.option<ConfigOptionFloatsNullable>(key);
        return std::floor(std::min(o && !o->values.empty() ? o->values.front() : max_speed, max_speed));
    };
    const double solid = speed("internal_solid_infill_speed"), top = speed("top_surface_speed");
    for (ModelObject *o : m.objects) {
        o->ensure_on_bed();
        auto &c = o->config;
        c.set_key_value("wall_loops", new ConfigOptionInt(1));
        c.set_key_value("only_one_wall_top", new ConfigOptionBool(true));
        c.set_key_value("thick_internal_bridges", new ConfigOptionBool(false));
        c.set_key_value("enable_extra_bridge_layer", new ConfigOptionEnum<EnableExtraBridgeLayer>(eblDisabled));
        c.set_key_value("internal_bridge_density", new ConfigOptionPercent(100));
        c.set_key_value("sparse_infill_density", new ConfigOptionPercent(35));
        c.set_key_value("min_width_top_surface", new ConfigOptionFloatOrPercent(100, true));
        c.set_key_value("bottom_shell_layers", new ConfigOptionInt(2));
        c.set_key_value("top_shell_layers", new ConfigOptionInt(5));
        c.set_key_value("top_shell_thickness", new ConfigOptionFloat(0));
        c.set_key_value("bottom_shell_thickness", new ConfigOptionFloat(0));
        c.set_key_value("detect_thin_wall", new ConfigOptionBool(true));
        c.set_key_value("filter_out_gap_fill", new ConfigOptionFloat(0));
        c.set_key_value("sparse_infill_pattern", new ConfigOptionEnum<InfillPattern>(ipRectilinear));
        c.set_key_value("top_surface_line_width", new ConfigOptionFloatOrPercent(lw, false));
        c.set_key_value("internal_solid_infill_line_width", new ConfigOptionFloatOrPercent(lw, false));
        c.set_key_value("top_surface_pattern", new ConfigOptionEnum<InfillPattern>(InfillPattern(pattern)));
        c.set_key_value("top_solid_infill_flow_ratio", new ConfigOptionFloatsNullable(1, 1.0));
        c.set_key_value("infill_direction", new ConfigOptionFloat(45));
        c.set_key_value("solid_infill_direction", new ConfigOptionFloat(135));
        c.set_key_value("center_of_surface_pattern", new ConfigOptionEnum<CenterOfSurfacePattern>(CenterOfSurfacePattern::Each_Surface));
        c.set_key_value("separated_infills", new ConfigOptionBool(false));
        c.set_key_value("align_infill_direction_to_model", new ConfigOptionBool(true));
        c.set_key_value("ironing_type", new ConfigOptionEnum<IroningType>(IroningType::NoIroning));
        c.set_key_value("internal_solid_infill_speed", new ConfigOptionFloatsNullable(1, solid));
        c.set_key_value("top_surface_speed", new ConfigOptionFloatsNullable(1, top));
        c.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
        c.set_key_value("gap_fill_target", new ConfigOptionEnum<GapFillTarget>(GapFillTarget::gftNowhere));
        c.set_key_value("calib_flowrate_topinfill_special_order", new ConfigOptionBool(true));
        c.set_key_value("top_surface_fill_order", new ConfigOptionEnum<SurfaceFillOrder>(SurfaceFillOrder::Default));
        // Object names carry the modifier: "flowrate_5", "flowrate_m5" (minus), "flowrate_0.02".
        std::string n = o->name.size() > 9 ? o->name.substr(9) : "0";
        if (!n.empty() && n[0] == 'm') n[0] = '-';
        double mod = 0.;
        try { mod = string_to_double_decimal_point(n); } catch (...) {}
        c.set_key_value("print_flow_ratio", new ConfigOptionFloat(linear ? (flow_ratio + mod) / flow_ratio : 1. + mod / 100.));
    }
    pc.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
    pc.set_key_value("layer_height", new ConfigOptionFloat(lh));
    pc.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
    pc.set_key_value("initial_layer_print_height", new ConfigOptionFloat(first));
    pc.set_key_value("reduce_crossing_wall", new ConfigOptionBool(true));
    pc.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
    printer_cfg().set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    Calib_Params p;
    p.mode = CalibMode::Calib_Flow_Rate;
    finish(p);
    g_session.calib.reset(); // flow tests need no G-code calibration hook
}

void calib_pa(Calib_Params p)
{
    DynamicPrintConfig &pc = print_cfg(), &pr = printer_cfg(), &fc = filament_cfg();
    auto common = [&] {
        pc.set_key_value("overhang_reverse", new ConfigOptionBool(false));
        pc.set_key_value("precise_z_height", new ConfigOptionBool(false));
        pc.set_key_value("wipe_inward", new ConfigOptionBool(false));
        pr.set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    };
    if (p.mode == CalibMode::Calib_PA_Line) {
        start("pressure_advance/pressure_advance_test.drc");
        common();
    } else if (p.mode == CalibMode::Calib_PA_Tower) {
        ModelObject &o = start("pressure_advance/tower_with_seam.drc");
        common();
        pc.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
        fc.set_key_value("slow_down_layer_time", new ConfigOptionFloats{1.0});
        // update_speed_parameter: walls at the filament's volumetric limit.
        const double nd = nozzle(), lh = pc.opt_float("layer_height");
        double lw = pc.get_abs_value("line_width", nd);
        if (lw <= 0.) lw = Flow::auto_extrusion_width(frPerimeter, float(nd));
        const double vmax = fc.option<ConfigOptionFloats>("filament_max_volumetric_speed")->get_at(0) / Flow(float(lw), float(lh), float(nd)).mm3_per_mm();
        set_all<double, ConfigOptionFloatsNullable>(pc, "outer_wall_speed", vmax);
        set_all<double, ConfigOptionFloatsNullable>(pc, "inner_wall_speed", vmax);
        auto &c = o.config;
        c.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
        c.set_key_value("seam_position", new ConfigOptionEnum<SeamPosition>(spRear));
        c.set_key_value("wall_loops", new ConfigOptionInt(2));
        c.set_key_value("top_shell_layers", new ConfigOptionInt(0));
        c.set_key_value("bottom_shell_layers", new ConfigOptionInt(0));
        c.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
        c.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btEar));
        c.set_key_value("brim_object_gap", new ConfigOptionFloat(0.));
        c.set_key_value("brim_ears_max_angle", new ConfigOptionFloat(135.));
        c.set_key_value("brim_width", new ConfigOptionFloat(6.));
        c.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
        pc.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
        const double h = std::ceil((p.end - p.start) / p.step) + 1;
        if (h < o.bounding_box_exact().size().z()) cut_at(h, true);
    } else { // pattern
        Session &s = g_session;
        s.snapshot();
        s.model.clear_objects();
        s.plates.assign(1, Plate());
        s.current = 0;
        common();
        std::vector<double> speeds = p.speeds, accels = p.accelerations;
        set_all<bool, ConfigOptionBoolsNullable>(fc, "filament_retract_when_changing_layer", false);
        set_all<bool, ConfigOptionBoolsNullable>(fc, "filament_wipe", false);
        set_all<bool, ConfigOptionBools>(pr, "wipe", false);
        set_all<bool, ConfigOptionBools>(pr, "retract_when_changing_layer", false);
        double accel = pc.get_abs_value_at("outer_wall_acceleration", 0);
        if (accel == 0) accel = pc.get_abs_value_at("inner_wall_acceleration", 0);
        if (accel == 0) accel = pc.get_abs_value_at("default_acceleration", 0);
        if (accels.empty()) accels = {accel}; else accel = *std::max_element(accels.begin(), accels.end());
        set_all<double, ConfigOptionFloatsNullable>(pc, "outer_wall_acceleration", accel);
        pc.set_key_value("print_sequence", new ConfigOptionEnum<PrintSequence>(PrintSequence::ByLayer));
        if (!has_junction_deviation(pr) && pc.get_abs_value_at("default_jerk", 0) > 0) {
            double jerk = pc.get_abs_value_at("outer_wall_jerk", 0);
            if (jerk == 0) jerk = pc.get_abs_value_at("inner_wall_jerk", 0);
            if (jerk == 0) jerk = pc.get_abs_value_at("default_jerk", 0);
            for (const char *k : {"default_jerk", "outer_wall_jerk", "inner_wall_jerk", "top_surface_jerk", "infill_jerk", "travel_jerk"})
                set_all<double, ConfigOptionFloatsNullable>(pc, k, jerk);
        }
        if (has_junction_deviation(pr)) set_all<double, ConfigOptionFloatsNullable>(pc, "default_junction_deviation", 0.);
        const SuggestedConfigCalibPAPattern sug;
        for (const auto &opt : sug.floats_pairs) set_all<double, ConfigOptionFloatsNullable>(pc, opt.first, opt.second[0]);
        for (const auto &opt : sug.nozzle_ratio_pairs) pc.set_key_value(opt.first, new ConfigOptionFloatOrPercent(nozzle() * opt.second / 100, false));
        for (const auto &opt : sug.int_pairs) pc.set_key_value(opt.first, new ConfigOptionInt(opt.second));
        pc.set_key_value(sug.brim_pair.first, new ConfigOptionEnum<BrimType>(sug.brim_pair.second));
        pc.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
        if (speeds.empty()) {
            const double sp = CalibPressureAdvance::find_optimal_PA_speed(s.bundle->full_config(), pc.get_abs_value("line_width", nozzle()), pc.get_abs_value("layer_height"), 0, 0);
            set_all<double, ConfigOptionFloatsNullable>(pc, "outer_wall_speed", sp);
            speeds = {sp};
        } else if (speeds.size() == 1) set_all<double, ConfigOptionFloatsNullable>(pc, "outer_wall_speed", speeds.front());
        // The anchor cube each pattern hangs off, one per speed x acceleration, arranged on the plate.
        const DynamicPrintConfig full = s.bundle->full_config();
        ModelObject *cube = s.model.add_object();
        cube->name = "Cube";
        cube->add_volume(TriangleMesh(its_make_cube(10, 10, 10)));
        cube->add_instance();
        auto pattern = std::make_shared<CalibPressureAdvancePattern>(p, full, s.bundle->is_bbl_vendor(), *cube, Vec3d::Zero());
        const BoundingBoxf3 cb = cube->raw_bounding_box();
        cube->scale((pattern->print_size_x() + 4) / cb.size().x(), (pattern->print_size_y() + 4) / cb.size().y(), pattern->max_layer_z() / cb.size().z());
        arrangement::ArrangePolygons items;
        arrangement::ArrangeParams ap;
        const Points bedpts = arrangement::get_shrink_bedpts(&full, ap);
        for (size_t i = 0; i < speeds.size() * accels.size(); ++i) {
            arrangement::ArrangePolygon poly;
            cube->instances[0]->get_arrange_polygon(&poly);
            poly.bed_idx = 0;
            items.push_back(poly);
        }
        arrangement::arrange(items, bedpts, ap);
        const BoundingBoxf3 cb2 = cube->raw_bounding_box();
        cube->scale(pattern->handle_xy_size() / cb2.size().x(), pattern->handle_xy_size() / cb2.size().y(), pattern->max_layer_z() / cb2.size().z());
        for (size_t t = 0; t < items.size(); ++t) {
            const double tspd = speeds[t % speeds.size()], tacc = accels[t / speeds.size()];
            ModelObject *obj = t == 0 ? cube : s.model.add_object(*cube);
            obj->name = "pa_pattern_" + std::to_string(int(tspd)) + "_" + std::to_string(int(tacc));
            if (speeds.size() > 1) obj->config.set_key_value("outer_wall_speed", new ConfigOptionFloatsNullable(1, tspd));
            if (accels.size() > 1) obj->config.set_key_value("outer_wall_acceleration", new ConfigOptionFloatsNullable(1, tacc));
            const int plate = std::max(0, items[t].bed_idx);
            if (plate >= int(s.plates.size())) set_plate_count(size_t(plate + 1));
            const Vec2d o = s.plate_origin(plate);
            obj->instances[0]->set_offset(Vec3d(o.x(), o.y(), 0.) + Vec3d(unscale<double>(items[t].translation.x()), unscale<double>(items[t].translation.y()), 0.) +
                                          pattern->handle_pos_offset());
            obj->ensure_on_bed();
        }
        s.select_only(0);
        s.pa_pattern = pattern;
    }
    finish(p);
}

void calib_retraction(Calib_Params p)
{
    ModelObject &o = start("retraction/retraction_tower.drc");
    DynamicPrintConfig &pc = print_cfg(), &pr = printer_cfg();
    pc.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
    pc.set_key_value("wipe_inward", new ConfigOptionBool(false));
    const double nd = nozzle(), lh = nd <= 0.1 ? 0.05 : nd <= 0.2 ? 0.1 : 0.2;
    if (auto *m = pr.option<ConfigOptionFloats>("max_layer_height")) for (double &v : m->values) v = std::max(v, lh);
    pr.set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    pr.set_key_value("use_firmware_retraction", new ConfigOptionBool(false));
    auto &c = o.config;
    c.set_key_value("wall_loops", new ConfigOptionInt(2));
    c.set_key_value("top_shell_layers", new ConfigOptionInt(0));
    c.set_key_value("bottom_shell_layers", new ConfigOptionInt(3));
    c.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
    pc.set_key_value("initial_layer_print_height", new ConfigOptionFloat(lh));
    c.set_key_value("layer_height", new ConfigOptionFloat(lh));
    c.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
    c.set_key_value("seam_position", new ConfigOptionEnum<SeamPosition>(spAligned));
    c.set_key_value("wall_sequence", new ConfigOptionEnum<WallSequence>(WallSequence::InnerOuter));
    c.set_key_value("overhang_reverse", new ConfigOptionBool(false));
    c.set_key_value("precise_z_height", new ConfigOptionBool(false));
    c.set_key_value("seam_slope_type", new ConfigOptionEnum<SeamScarfType>(SeamScarfType::None));
    const double h = 1.0 + 0.4 + (p.end - p.start) / p.step - EPSILON;
    if (h < o.bounding_box_exact().size().z()) cut_at(h, true);
    p.mode = CalibMode::Calib_Retraction_tower;
    finish(p);
}

void calib_max_vol(Calib_Params p)
{
    ModelObject &o = start("volumetric_speed/SpeedTestStructure.drc");
    DynamicPrintConfig &pc = print_cfg(), &fc = filament_cfg(), &pr = printer_cfg();
    const BoundingBoxf bed = g_session.bed_rect();
    const double s = (bed.size().x() - 10) / o.bounding_box_exact().size().x();
    if (s < 1.0) o.scale(s, 1, 1);
    const double nd = nozzle(), lw = nd * 1.75, lh = nd * 0.8;
    if (auto *m = pr.option<ConfigOptionFloats>("max_layer_height")) for (double &v : m->values) v = std::max(v, lh);
    const double vmax = fc.option<ConfigOptionFloats>("filament_max_volumetric_speed")->get_at(0);
    set_all<double, ConfigOptionFloats>(fc, "filament_max_volumetric_speed", std::max(vmax, 200.0));
    fc.set_key_value("slow_down_layer_time", new ConfigOptionFloats{0.0});
    pr.set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    auto &c = o.config;
    c.set_key_value("enable_overhang_speed", new ConfigOptionBoolsNullable(1, false));
    c.set_key_value("wall_loops", new ConfigOptionInt(1));
    c.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
    c.set_key_value("top_shell_layers", new ConfigOptionInt(0));
    c.set_key_value("bottom_shell_layers", new ConfigOptionInt(0));
    c.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
    c.set_key_value("outer_wall_line_width", new ConfigOptionFloatOrPercent(lw, false));
    c.set_key_value("layer_height", new ConfigOptionFloat(lh));
    c.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterAndInner));
    c.set_key_value("brim_width", new ConfigOptionFloat(5.0));
    c.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
    c.set_key_value("precise_z_height", new ConfigOptionBool(false));
    pc.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
    pc.set_key_value("spiral_mode", new ConfigOptionBool(true));
    pc.set_key_value("max_volumetric_extrusion_rate_slope", new ConfigOptionFloat(0));
    pc.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
    const double h = (p.end - p.start + 1) / p.step;
    if (h < o.bounding_box_exact().size().z()) cut_at(h, true);
    const double mm3 = Flow(float(lw), float(lh), float(nd)).mm3_per_mm() * fc.option<ConfigOptionFloatsNullable>("filament_flow_ratio")->get_at(0);
    p.end /= mm3; p.start /= mm3; p.step /= mm3;
    p.mode = CalibMode::Calib_Vol_speed_Tower;
    finish(p);
}

void calib_vfa(Calib_Params p)
{
    ModelObject &o = start("vfa/vfa.drc");
    DynamicPrintConfig &pc = print_cfg(), &fc = filament_cfg(), &pr = printer_cfg();
    const double nd = nozzle() > 0 ? nozzle() : vfa_base_nozzle_diameter;
    const double lh = p.vfa_layer_height > 0 ? p.vfa_layer_height : nd / 2.;
    const double h = vfa_base_block_height * ((p.end - p.start) / p.step + 1) - EPSILON;
    if (h < o.bounding_box_exact().size().z()) cut_at(h, true);
    ModelObject &t = *g_session.model.objects.front();
    if (p.nozzle_based_resize) {
        const double xy = nd / vfa_base_nozzle_diameter, z = vfa_layers_per_block * lh / vfa_base_block_height;
        if (std::abs(xy - 1.) > EPSILON || std::abs(z - 1.) > EPSILON) t.scale(xy, xy, z);
    }
    t.ensure_on_bed();
    pr.set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    fc.set_key_value("slow_down_layer_time", new ConfigOptionFloats{0.0});
    set_all<bool, ConfigOptionBoolsNullable>(pc, "enable_overhang_speed", false);
    pc.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
    pc.set_key_value("wall_loops", new ConfigOptionInt(1));
    pc.set_key_value("alternate_extra_wall", new ConfigOptionBool(false));
    pc.set_key_value("top_shell_layers", new ConfigOptionInt(0));
    pc.set_key_value("bottom_shell_layers", new ConfigOptionInt(1));
    pc.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
    pc.set_key_value("detect_thin_wall", new ConfigOptionBool(false));
    pc.set_key_value("spiral_mode", new ConfigOptionBool(true));
    pc.set_key_value("enable_wrapping_detection", new ConfigOptionBool(false));
    pc.set_key_value("precise_z_height", new ConfigOptionBool(false));
    if (p.nozzle_based_resize) {
        pc.set_key_value("initial_layer_print_height", new ConfigOptionFloat(lh));
        t.config.set_key_value("layer_height", new ConfigOptionFloat(lh));
    }
    t.config.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
    t.config.set_key_value("brim_width", new ConfigOptionFloat(3.0));
    t.config.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
    p.vfa_layer_height = p.nozzle_based_resize ? lh : 0.;
    p.mode = CalibMode::Calib_VFA_Tower;
    finish(p);
}

// Input shaping frequency / damping and cornering share the ringing-tower set-up.
void calib_ringing(Calib_Params p)
{
    const std::string model = p.mode == CalibMode::Calib_Cornering && p.test_model == 2 ? "cornering/SCV-V2.drc"
                              : p.test_model < 1 ? "input_shaping/ringing_tower.drc" : "input_shaping/fast_tower_test.drc";
    ModelObject &o = start(model);
    DynamicPrintConfig &pc = print_cfg(), &fc = filament_cfg(), &pr = printer_cfg();
    const bool corner = p.mode == CalibMode::Calib_Cornering;
    if (has_junction_deviation(pr)) {
        const double jd = corner ? p.end : std::max(pr.option<ConfigOptionFloats>("machine_max_junction_deviation")->values.front(), 0.25);
        pr.set_key_value("machine_max_junction_deviation", new ConfigOptionFloats{jd});
        set_all<double, ConfigOptionFloatsNullable>(pc, "default_junction_deviation", 0.);
    } else {
        const auto *flavor = pr.option<ConfigOptionEnum<GCodeFlavor>>("gcode_flavor");
        const double base = flavor && flavor->value == gcfKlipper ? 5. : 10.;
        const double jx = corner ? p.end : std::max(pr.option<ConfigOptionFloats>("machine_max_jerk_x")->values.front(), base);
        const double jy = corner ? p.end : std::max(pr.option<ConfigOptionFloats>("machine_max_jerk_y")->values.front(), base);
        pr.set_key_value("machine_max_jerk_x", new ConfigOptionFloats{jx});
        pr.set_key_value("machine_max_jerk_y", new ConfigOptionFloats{jy});
        set_all<double, ConfigOptionFloatsNullable>(pc, "default_jerk", 0.);
    }
    zero_pa_where_disabled(fc);
    pr.set_key_value("resonance_avoidance", new ConfigOptionBool(false));
    pr.set_key_value("input_shaping_emit", new ConfigOptionBool(corner));
    if (corner) {
        pr.set_key_value("input_shaping_type", new ConfigOptionEnum<InputShaperType>(InputShaperType::Disable));
        const double vmax = fc.option<ConfigOptionFloats>("filament_max_volumetric_speed")->get_at(0);
        fc.set_key_value("filament_max_volumetric_speed", new ConfigOptionFloats{std::max(vmax, 200.0)});
    }
    fc.set_key_value("slow_down_layer_time", new ConfigOptionFloats{0.0});
    fc.set_key_value("slow_down_min_speed", new ConfigOptionFloats{0.0});
    fc.set_key_value("slow_down_for_layer_cooling", new ConfigOptionBools{false});
    if (p.mode == CalibMode::Calib_Input_shaping_freq) pc.set_key_value("layer_height", new ConfigOptionFloat(0.2));
    set_all<bool, ConfigOptionBools>(pc, "enable_overhang_speed", false);
    if (!corner) {
        pc.set_key_value("accel_to_decel_enable", new ConfigOptionBool(true));
        pc.set_key_value("accel_to_decel_factor", new ConfigOptionPercent(100));
    }
    pc.set_key_value("timelapse_type", new ConfigOptionEnum<TimelapseType>(tlTraditional));
    pc.set_key_value("wall_loops", new ConfigOptionInt(1));
    pc.set_key_value("top_shell_layers", new ConfigOptionInt(0));
    pc.set_key_value("bottom_shell_layers", new ConfigOptionInt(1));
    pc.set_key_value("sparse_infill_density", new ConfigOptionPercent(0));
    pc.set_key_value("detect_thin_wall", new ConfigOptionBool(false));
    pc.set_key_value("spiral_mode", new ConfigOptionBool(true));
    pc.set_key_value("spiral_mode_smooth", new ConfigOptionBool(false));
    pc.set_key_value("bottom_surface_pattern", new ConfigOptionEnum<InfillPattern>(ipRectilinear));
    const double vmax = std::min(pr.option<ConfigOptionFloats>("machine_max_speed_x")->get_at(0), pr.option<ConfigOptionFloats>("machine_max_speed_y")->get_at(0));
    const double amax = pr.option<ConfigOptionFloats>("machine_max_acceleration_extruding")->get_at(0);
    set_all<double, ConfigOptionFloatsNullable>(pc, "outer_wall_speed", vmax);
    set_all<double, ConfigOptionFloatsNullable>(pc, "default_acceleration", amax);
    set_all<double, ConfigOptionFloatsNullable>(pc, "outer_wall_acceleration", amax);
    pc.set_key_value("precise_z_height", new ConfigOptionBool(false));
    o.config.set_key_value("brim_type", new ConfigOptionEnum<BrimType>(btOuterOnly));
    o.config.set_key_value("brim_width", new ConfigOptionFloat(3.0));
    o.config.set_key_value("brim_object_gap", new ConfigOptionFloat(0.0));
    finish(p);
}

} // namespace

// test: temp, flow, flow_yolo, pa_line, pa_pattern, pa_tower, retraction, max_vol, vfa,
// shaping_freq, shaping_damp, cornering. args: start, end, step, pass, pattern, speeds,
// accelerations, test_model, freq_*, shaper_type, print_numbers, resize. Returns objects or {"error"}.
JNI(jstring, calibrate)(JNIEnv *env, jobject, jstring jtest, jstring jargs)
{
    std::lock_guard lock(g_session.mutex);
    try {
        if (!g_session.bundle) return to_j(env, json{{"error", "Choose a printer first."}}.dump());
        const std::string test = jstr(env, jtest), args = jstr(env, jargs);
        const json a = args.empty() ? json::object() : json::parse(args);
        Calib_Params p;
        p.start = a.value("start", 0.); p.end = a.value("end", 1.); p.step = a.value("step", 0.1);
        p.print_numbers = a.value("print_numbers", true);
        p.test_model = a.value("test_model", 0);
        p.nozzle_based_resize = a.value("resize", true);
        p.freqStartX = a.value("freq_start_x", 0.); p.freqEndX = a.value("freq_end_x", 1.);
        p.freqStartY = a.value("freq_start_y", 0.); p.freqEndY = a.value("freq_end_y", 1.);
        p.shaper_type = a.value("shaper_type", std::string());
        p.speeds = a.value("speeds", std::vector<double>());
        p.accelerations = a.value("accelerations", std::vector<double>());
        if (test == "temp") calib_temp(p);
        else if (test == "flow") calib_flow(false, a.value("pass", 1), a.value("pattern", int(ipMonotonic)));
        else if (test == "flow_yolo") calib_flow(true, a.value("pass", 1), a.value("pattern", int(ipMonotonic)));
        else if (test == "pa_line") { p.mode = CalibMode::Calib_PA_Line; calib_pa(p); }
        else if (test == "pa_pattern") { p.mode = CalibMode::Calib_PA_Pattern; calib_pa(p); }
        else if (test == "pa_tower") { p.mode = CalibMode::Calib_PA_Tower; calib_pa(p); }
        else if (test == "retraction") calib_retraction(p);
        else if (test == "max_vol") calib_max_vol(p);
        else if (test == "vfa") calib_vfa(p);
        else if (test == "shaping_freq") { p.mode = CalibMode::Calib_Input_shaping_freq; calib_ringing(p); }
        else if (test == "shaping_damp") { p.mode = CalibMode::Calib_Input_shaping_damp; calib_ringing(p); }
        else if (test == "cornering") { p.mode = CalibMode::Calib_Cornering; calib_ringing(p); }
        else return to_j(env, json{{"error", "Unknown test " + test}}.dump());
        model_changed();
        ++g_session.preview_version;
        return to_j(env, objects_json().dump());
    } catch (const std::exception &e) {
        model_changed();
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}
