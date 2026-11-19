// Slicing: one plate at a time, off the session lock (the app keeps editing and drawing while a
// plate slices), cancellable, with G-code thumbnails drawn by a small software rasterizer (the
// GL context belongs to the view's thread). Also the G-code viewer: a G-code file from disk shown
// in Preview.

#include "Session.hpp"

#include "libslic3r/GCode/ThumbnailData.hpp"
#include "libslic3r/Utils.hpp"

#include <boost/filesystem.hpp>

#include <algorithm>
#include <limits>

using namespace Slic3r;
using json = nlohmann::json;
namespace fs = boost::filesystem;

namespace {

std::mutex             g_one_slice;     // one slice at a time: a plate's Print is not re-entrant
std::mutex             g_slicing_mutex;
std::shared_ptr<Print> g_slicing; // the Print being processed, for cancel

// Reports slicing progress to Kotlin. The slicer calls back from its TBB worker threads, so each
// call attaches its thread to the VM (as a daemon: TBB keeps its workers) and the listener is a
// global reference.
struct ProgressListener
{
    JavaVM    *vm = nullptr;
    jobject    obj = nullptr;
    jmethodID  method = nullptr;
    std::mutex mutex;

    ProgressListener(JNIEnv *env, jobject listener)
    {
        env->GetJavaVM(&vm);
        obj    = env->NewGlobalRef(listener);
        method = env->GetMethodID(env->GetObjectClass(listener), "onProgress", "(ILjava/lang/String;)V");
    }
    ~ProgressListener()
    {
        JNIEnv *env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) env->DeleteGlobalRef(obj);
    }
    void operator()(int percent, const std::string &text)
    {
        std::lock_guard lock(mutex);
        JNIEnv *env = nullptr;
        if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK &&
            vm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK)
            return;
        jstring t = env->NewStringUTF(text.c_str());
        env->CallVoidMethod(obj, method, jint(percent), t);
        env->DeleteLocalRef(t);
    }
};

std::array<float, 3> rgb(const std::string &hex)
{
    unsigned v = 0x26A69A;
    if (hex.size() >= 7 && hex[0] == '#') v = unsigned(std::stoul(hex.substr(1, 6), nullptr, 16));
    return {((v >> 16) & 255) / 255.f, ((v >> 8) & 255) / 255.f, (v & 255) / 255.f};
}

} // namespace

// The plate's solid parts from the front-left-top (the desktop's iso thumbnail view), z-buffered,
// lit like the 3D view. Rows bottom-up, as glReadPixels gives them to the desktop's encoders.
ThumbnailData render_thumbnail(const Model &model, const std::vector<std::string> &colors, unsigned w, unsigned h, bool transparent)
{
    ThumbnailData td;
    td.set(w, h);
    if (w == 0 || h == 0) return td;
    const Vec3d f = Vec3d(1., 1., -1.).normalized(), r = f.cross(Vec3d::UnitZ()).normalized(), u = r.cross(f);
    struct Tri { Vec3d p[3]; std::array<float, 3> c; };
    std::vector<Tri> tris;
    BoundingBox2d box; bool any = false;
    double zmin = 1e30, zmax = -1e30;
    for (const ModelObject *o : model.objects)
        for (const ModelInstance *inst : o->instances) {
            if (!inst->printable) continue;
            for (const ModelVolume *v : o->volumes) {
                if (!v->is_model_part()) continue;
                int ext = v->config.has("extruder") ? v->config.opt_int("extruder") : 0;
                if (ext <= 0 && o->config.has("extruder")) ext = o->config.opt_int("extruder");
                const size_t ci = size_t(std::max(1, ext) - 1);
                const auto c = rgb(ci < colors.size() ? colors[ci] : "#26A69A");
                const Transform3d t = inst->get_matrix() * v->get_matrix();
                const indexed_triangle_set &its = v->mesh().its;
                for (const auto &face : its.indices) {
                    Tri tri;
                    for (int k = 0; k < 3; ++k) {
                        const Vec3d p = t * its.vertices[face[k]].cast<double>();
                        tri.p[k] = Vec3d(p.dot(r), p.dot(u), p.dot(f));
                        box.merge(Vec2d(tri.p[k].x(), tri.p[k].y())); any = true;
                    }
                    tri.c = c;
                    tris.push_back(tri);
                }
            }
        }
    const unsigned char bg[4] = {0x16, 0x18, 0x1B, 0xFF};
    for (size_t i = 0; i < size_t(w) * h; ++i)
        for (int k = 0; k < 4; ++k) td.pixels[i * 4 + k] = transparent ? 0 : bg[k];
    if (!any) return td;
    const double scale = 0.9 * std::min(w / std::max(box.size().x(), 1e-6), h / std::max(box.size().y(), 1e-6));
    const Vec2d mid = box.center();
    std::vector<float> depth(size_t(w) * h, std::numeric_limits<float>::max());
    const Vec3d l1 = Vec3d(-0.457, 0.457, 0.762).normalized(), l2 = Vec3d(0.6, 0.6, 1.0).normalized();
    for (const Tri &t : tris) {
        Vec3d s[3];
        for (int k = 0; k < 3; ++k)
            s[k] = Vec3d((t.p[k].x() - mid.x()) * scale + w / 2., (t.p[k].y() - mid.y()) * scale + h / 2., t.p[k].z());
        const double area = (s[1].x() - s[0].x()) * (s[2].y() - s[0].y()) - (s[2].x() - s[0].x()) * (s[1].y() - s[0].y());
        if (std::abs(area) < 1e-12) continue;
        // Normal in eye space (x right, y up, z towards the viewer) for the shader's lights.
        Vec3d n = (t.p[1] - t.p[0]).cross(t.p[2] - t.p[0]).normalized();
        Vec3d ne(n.x(), n.y(), -n.z());
        if (ne.z() < 0) ne = -ne;
        const float light = float(std::min(1.2, 0.3 + 0.6 * std::max(0., ne.dot(l1)) + 0.3 * std::max(0., ne.dot(l2))));
        const int x0 = std::max(0, int(std::floor(std::min({s[0].x(), s[1].x(), s[2].x()}))));
        const int x1 = std::min(int(w) - 1, int(std::ceil(std::max({s[0].x(), s[1].x(), s[2].x()}))));
        const int y0 = std::max(0, int(std::floor(std::min({s[0].y(), s[1].y(), s[2].y()}))));
        const int y1 = std::min(int(h) - 1, int(std::ceil(std::max({s[0].y(), s[1].y(), s[2].y()}))));
        for (int y = y0; y <= y1; ++y)
            for (int x = x0; x <= x1; ++x) {
                const double px = x + 0.5, py = y + 0.5;
                const double b0 = ((s[1].x() - px) * (s[2].y() - py) - (s[2].x() - px) * (s[1].y() - py)) / area;
                const double b1 = ((s[2].x() - px) * (s[0].y() - py) - (s[0].x() - px) * (s[2].y() - py)) / area;
                const double b2 = 1. - b0 - b1;
                if (b0 < 0 || b1 < 0 || b2 < 0) continue;
                const float z = float(b0 * s[0].z() + b1 * s[1].z() + b2 * s[2].z());
                float &d = depth[size_t(y) * w + x];
                if (z >= d) continue;
                d = z;
                unsigned char *px4 = &td.pixels[(size_t(y) * w + x) * 4];
                for (int k = 0; k < 3; ++k) px4[k] = (unsigned char) std::clamp(t.c[k] * light * 255.f, 0.f, 255.f);
                px4[3] = 255;
            }
    }
    return td;
}

json slice_stats_json(const GCodeProcessorResult &r, const std::string &gcode)
{
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
    // Per filament: metres, grams and cost from the volume used (model, support, tower, flush),
    // as the desktop's filament table.
    json filaments = json::array();
    double total_cost = 0.;
    for (const auto &[ext, mm3] : stats.total_volumes_per_extruder) {
        const double d = ext < r.filament_diameters.size() ? r.filament_diameters[ext] : 1.75;
        const double rho = ext < r.filament_densities.size() ? r.filament_densities[ext] : 1.24;
        const double cost = ext < r.filament_costs.size() ? r.filament_costs[ext] : 0.;
        const double grams = mm3 * rho / 1000.;
        const double flush = stats.flush_per_filament.count(ext) ? stats.flush_per_filament.at(ext) * rho / 1000. : 0.;
        total_cost += grams / 1000. * cost;
        filaments.push_back({{"filament", int(ext) + 1}, {"meters", mm3 / (PI * d * d / 4.) / 1000.}, {"grams", grams},
                             {"flushGrams", flush}, {"cost", grams / 1000. * cost}});
    }
    return {{"roles", roles}, {"travelTime", travel_time}, {"totalTime", total},
            {"prepareTime", stats.modes[0].prepare_time}, {"meters", total_m}, {"grams", total_g},
            {"filaments", filaments}, {"cost", total_cost}, {"changes", stats.total_filament_changes},
            {"gcode", gcode}};
}

// Slices plate `plate`. Returns its stats, {"cancelled": true}, or {"error"}.
JNI(jstring, slice)(JNIEnv *env, jobject, jint plate, jobject listener)
{
    // A newer slice waits for the previous one, which the app cancels first.
    std::lock_guard one(g_one_slice);
    std::shared_ptr<Print> print;
    Model model;
    DynamicPrintConfig cfg;
    std::vector<std::string> colors;
    uint64_t version = 0;
    bool bbl = false;
    Slic3r::Calib_Params calib;
    int filaments = 1;
    std::string out_path;
    {
        std::lock_guard lock(g_session.mutex);
        Session &s = g_session;
        if (!s.bundle) return to_j(env, json{{"error", "Choose a printer first."}}.dump());
        if (plate < 0 || plate >= int(s.plates.size())) return to_j(env, json{{"error", "No such plate."}}.dump());
        model = plate_model(plate);
        if (model.objects.empty())
            return to_j(env, json{{"error", "Plate " + std::to_string(plate + 1) + " has nothing to print."}}.dump());
        PresetBundle &b = *s.bundle;
        cfg = b.full_config();
        if (const auto *c = b.project_config.option<ConfigOptionStrings>("filament_colour")) colors = c->values;
        Plate &pl = s.plates[plate];
        if (!pl.print) pl.print = std::make_shared<Print>();
        print     = pl.print;
        version   = s.edit_version;
        bbl       = b.is_bbl_vendor();
        filaments = int(b.filament_presets.size());
        if (s.calib) calib = *s.calib;
        // Pressure advance pattern: its test lines are custom G-code per layer, generated for the
        // anchor cubes on this plate (desktop _calib_pa_pattern_gen_gcode), merged per layer.
        if (s.pa_pattern && !model.objects.empty()) {
            std::vector<CustomGCode::Info> per_object;
            for (const ModelObject *o : model.objects)
                per_object.push_back(s.pa_pattern->generate_custom_gcodes(cfg, bbl, *o, Vec3d::Zero()));
            CustomGCode::Info merged = per_object.front();
            for (size_t i = 1; i < per_object.size(); ++i)
                for (size_t k = 0; k < merged.gcodes.size() && k < per_object[i].gcodes.size(); ++k)
                    merged.gcodes[k].extra += per_object[i].gcodes[k].extra;
            model.plates_custom_gcodes[plate] = merged;
        }
        out_path  = (fs::path(temporary_dir()) / ("plate_" + std::to_string(plate + 1) + ".gcode")).string();
    }
    {
        std::lock_guard lock(g_slicing_mutex);
        g_slicing = print;
    }
    ProgressListener progress(env, listener);
    json out;
    try {
        print->is_BBL_printer() = bbl;
        print->set_calib_params(calib);
        print->apply(model, cfg);
        StringObjectException err = print->validate();
        if (!err.string.empty()) throw RuntimeError(err.string);
        Model::setExtruderParams(cfg, filaments);
        Model::setPrintSpeedTable(cfg, print->config());
        print->set_status_callback([&progress](const PrintBase::SlicingStatus &st) {
            if (st.percent >= 0) progress(st.percent, st.text);
        });
        print->process();
        auto result = std::make_shared<GCodeProcessorResult>();
        const Model &thumb_model = model;
        const std::string path = print->export_gcode(out_path, result.get(), [&thumb_model, &colors](const ThumbnailsParams &p) {
            ThumbnailsList list;
            for (const Vec2d &sz : p.sizes)
                list.push_back(render_thumbnail(thumb_model, colors, unsigned(sz.x()), unsigned(sz.y()), p.transparent_background));
            return list;
        });
        print->set_status_callback({});
        std::lock_guard lock(g_session.mutex);
        Session &s = g_session;
        if (plate < int(s.plates.size()) && s.plates[plate].print == print) {
            Plate &pl = s.plates[plate];
            pl.result     = result;
            pl.gcode_path = path;
            pl.sliced     = version == s.edit_version; // an edit during slicing makes it stale
            pl.stats      = slice_stats_json(*result, path);
            if (plate == s.current) { s.viewer_result.reset(); ++s.preview_version; }
            out = pl.stats;
            out["stale"] = !pl.sliced;
        } else {
            out = {{"cancelled", true}};
        }
        progress(100, "Done");
    } catch (const CanceledException &) {
        print->set_status_callback({});
        out = {{"cancelled", true}};
    } catch (const std::exception &e) {
        print->set_status_callback({});
        out = {{"error", e.what()}};
    }
    {
        std::lock_guard lock(g_slicing_mutex);
        g_slicing.reset();
    }
    out["plate"] = plate;
    return to_j(env, out.dump());
}

// Stops the slice in progress (any thread); slice() then returns {"cancelled": true}.
JNI(void, cancelSlice)(JNIEnv *, jobject)
{
    std::lock_guard lock(g_slicing_mutex);
    if (g_slicing) g_slicing->cancel();
}

// The current plate's G-code (or the viewer's file), "" when there is none.
JNI(jstring, gcodePath)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    Session &s = g_session;
    if (s.viewer_result) return to_j(env, s.viewer_path);
    return to_j(env, s.plate().sliced ? s.plate().gcode_path : std::string());
}

// What Preview shows: the viewer's file or the current plate's slice ({} when not sliced).
JNI(jstring, previewStats)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    Session &s = g_session;
    if (s.viewer_result) return to_j(env, slice_stats_json(*s.viewer_result, s.viewer_path).dump());
    return to_j(env, s.plate().sliced ? s.plate().stats.dump() : std::string("{}"));
}

// Every sliced plate's G-code, in plate order.
JNI(jstring, slicedPlates)(JNIEnv *env, jobject)
{
    std::lock_guard lock(g_session.mutex);
    json out = json::array();
    for (size_t p = 0; p < g_session.plates.size(); ++p)
        if (g_session.plates[p].sliced) out.push_back({{"plate", p}, {"gcode", g_session.plates[p].gcode_path}});
    return to_j(env, out.dump());
}

// The G-code viewer: reads a G-code file for Preview. Returns its stats or {"error"}.
JNI(jstring, openGcode)(JNIEnv *env, jobject, jstring jpath)
{
    const std::string path = jstr(env, jpath);
    try {
        GCodeProcessor processor;
        processor.process_file(path);
        auto result = std::make_shared<GCodeProcessorResult>(std::move(processor.extract_result()));
        std::lock_guard lock(g_session.mutex);
        g_session.viewer_result = result;
        g_session.viewer_path   = path;
        ++g_session.preview_version;
        return to_j(env, slice_stats_json(*result, path).dump());
    } catch (const std::exception &e) {
        return to_j(env, json{{"error", e.what()}}.dump());
    }
}

JNI(void, closeViewer)(JNIEnv *, jobject)
{
    std::lock_guard lock(g_session.mutex);
    if (g_session.viewer_result) { g_session.viewer_result.reset(); ++g_session.preview_version; }
}
