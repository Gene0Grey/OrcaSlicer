#pragma once

#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"

#include <jni.h>
#include <nlohmann/json.hpp>

#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

// One build plate of the project, as on the desktop (PartPlate): objects belong to the plate
// whose area holds their centre; plates sit in a grid, see Session::plate_origin().
struct Plate
{
    std::string name;
    bool        locked = false;
    // Slicing state. The Print is kept between slices so unchanged steps are reused.
    std::shared_ptr<Slic3r::Print>                print;
    std::shared_ptr<Slic3r::GCodeProcessorResult> result;
    std::string                                   gcode_path;
    bool                                          sliced = false;
    nlohmann::json                                stats;
};

// State of the one open project, shared by the JNI calls and the renderer (Scene.cpp).
struct Session
{
    std::mutex                                     mutex;
    std::unique_ptr<Slic3r::PresetBundle>          bundle;
    std::string                                    vendor;
    Slic3r::Model                                  model;
    std::vector<Plate>                             plates = std::vector<Plate>(1);
    int                                            current = 0;   // the plate on screen
    // A G-code file opened in the viewer (not sliced here); Preview shows it while set.
    std::shared_ptr<Slic3r::GCodeProcessorResult>  viewer_result;
    std::string                                    viewer_path;
    // `selected` is the object the gizmos and the object bar act on; `picked` is the whole
    // selection (long press adds objects), which align/distribute/assemble/delete use.
    int                                            selected = -1;
    std::set<int>                                  picked;
    // Copied object settings (Copy / Paste Process Settings).
    Slic3r::DynamicPrintConfig                     clipboard;
    bool                                           has_clipboard = false;
    // Tool overlays drawn in Prepare: the cut plane (world transform of the plane, Z up) and
    // measure feature points.
    bool                                           cut_preview = false;
    Slic3r::Transform3d                            cut_plane   = Slic3r::Transform3d::Identity();
    std::vector<Slic3r::Vec3d>                     marks;
    // Open painting gizmo: 0 none, 1 support, 2 seam, 3 colour, 4 fuzzy skin, 5 brim ears.
    int                                            paint_mode  = 0;
    // Bumped when the plate or its objects change / when a new slice result is ready,
    // so the GL thread knows to rebuild its buffers.
    uint64_t                                       scene_version   = 1;
    uint64_t                                       preview_version = 0;
    // Bumped by every edit; a slice that started before an edit is stale when it ends.
    uint64_t                                       edit_version    = 0;

    Plate &plate() { return plates[size_t(current)]; }
    // What Preview shows: the viewer's file, or the current plate's slice.
    const Slic3r::GCodeProcessorResult *preview_result()
    {
        if (viewer_result) return viewer_result.get();
        return plate().sliced ? plate().result.get() : nullptr;
    }

    // Undo/redo of plate edits: whole-model snapshots (plates are small; the desktop keeps a
    // deduplicating stack, which matters for big projects). Caller holds the mutex.
    struct Snapshot { Slic3r::Model model; int selected; };
    std::vector<Snapshot> undo_stack, redo_stack;
    static constexpr size_t MAX_UNDO = 30;

    void snapshot()
    {
        undo_stack.push_back({model, selected});
        if (undo_stack.size() > MAX_UNDO) undo_stack.erase(undo_stack.begin());
        redo_stack.clear();
    }

    bool step(std::vector<Snapshot> &from, std::vector<Snapshot> &to)
    {
        if (from.empty()) return false;
        to.push_back({model, selected});
        model    = from.back().model;
        selected = from.back().selected;
        from.pop_back();
        select_only(selected);
        invalidate();
        ++scene_version;
        return true;
    }

    void invalidate()
    {
        for (Plate &p : plates) p.sliced = false;
        ++edit_version;
    }

    void select_only(int idx)
    {
        selected = idx;
        picked.clear();
        if (idx >= 0) picked.insert(idx);
    }

    // ---- plate geometry ------------------------------------------------------------------

    // The printable area's bounding box (plate 1 position).
    Slic3r::BoundingBoxf bed_rect() const
    {
        Slic3r::BoundingBoxf bed;
        if (!bundle) return bed;
        if (const auto *pa = bundle->printers.get_edited_preset().config.option<Slic3r::ConfigOptionPoints>("printable_area"))
            for (const Slic3r::Vec2d &p : pa->values) bed.merge(p);
        return bed;
    }

    double bed_height() const { return bundle ? bundle->printers.get_edited_preset().config.opt_float("printable_height") : 0.; }

    // The desktop's grid (PartPlateList::compute_shape_position): ceil(sqrt(n)) columns, rows go
    // towards -Y, a fifth of the plate between plates. Matching it keeps 3MF projects compatible.
    static int columns(size_t n) { return std::max(1, int(std::ceil(std::sqrt(double(n)) - 1e-9))); }
    Slic3r::Vec2d plate_origin(int i, size_t count) const
    {
        const Slic3r::Vec2d size = bed_rect().size();
        const int cols = columns(count);
        return {(i % cols) * double(int(size.x())) * 1.2, -(i / cols) * double(int(size.y())) * 1.2};
    }
    Slic3r::Vec2d plate_origin(int i) const { return plate_origin(i, plates.size()); }

    // The plate an instance stands on (its centre over the plate area), or -1.
    int plate_of(const Slic3r::ModelObject &o, size_t instance) const
    {
        if (instance >= o.instances.size()) return -1;
        const Slic3r::Vec3d c = o.instance_bounding_box(instance).center();
        const Slic3r::BoundingBoxf bed = bed_rect();
        for (int i = 0; i < int(plates.size()); ++i) {
            const Slic3r::Vec2d at = Slic3r::Vec2d(c.x(), c.y()) - plate_origin(i);
            if (at.x() >= bed.min.x() && at.x() <= bed.max.x() && at.y() >= bed.min.y() && at.y() <= bed.max.y()) return i;
        }
        return -1;
    }

    // Whether an object leaves its plate's build volume (XY box of printable_area, printable_height).
    bool outside(const Slic3r::ModelObject &o) const
    {
        for (size_t i = 0; i < o.instances.size(); ++i)
            if (outside(o, i)) return true;
        return false;
    }

    bool outside(const Slic3r::ModelObject &o, size_t instance) const
    {
        if (!bundle || instance >= o.instances.size()) return false;
        const int p = plate_of(o, instance);
        if (p < 0) return true;
        Slic3r::BoundingBoxf bed = bed_rect();
        bed.translate(plate_origin(p));
        const Slic3r::BoundingBoxf3 bb = o.instance_bounding_box(instance);
        const double eps = 0.01;
        return bb.min.x() < bed.min.x() - eps || bb.min.y() < bed.min.y() - eps || bb.max.x() > bed.max.x() + eps ||
               bb.max.y() > bed.max.y() + eps || bb.max.z() > bed_height() + eps;
    }
};

extern Session g_session;

// Helpers shared by the JNI files.
std::string    jstr(JNIEnv *env, jstring s);
jstring        to_j(JNIEnv *env, const std::string &s);
// Printable area (XY) and height (Z) of the selected printer, at the current plate.
Slic3r::BoundingBoxf3 bed_volume();
// The plate's objects for the app: transforms, sizes, parts, ranges, selection.  (Objects.cpp)
nlohmann::json objects_json();
// Arranges the current plate. Instances that do not fit go to the next plates (created as
// needed), or with spill == false are returned instead.  (Plates.cpp)
std::vector<Slic3r::ModelInstance *> arrange_model(bool spill = true);
// Marks the slices stale and the scene for a rebuild.
void           model_changed();
// The plates for the app.  (Plates.cpp)
nlohmann::json plates_json();
// Re-places the plate grid for a new plate count, moving objects with their plates.  (Plates.cpp)
void           set_plate_count(size_t n);
// A copy of the model holding only plate p's instances, moved to plate-local coordinates
// (as G-code for any plate is written in plate 1's frame). Object IDs are kept, so a Print
// that keeps applying it re-uses its unchanged steps.  (Plates.cpp)
Slic3r::Model  plate_model(int p);
// Nearest solid part under a screen point (column-major view/proj); only_object >= 0 limits the
// search to one object.  (Tools.cpp)
struct RayHit { int obj = -1, vol = -1, face = -1; Slic3r::Vec3d point = Slic3r::Vec3d::Zero(); };
RayHit         ray_hit(float x, float y, int w, int h, const float *view, const float *proj, int only_object = -1);
// The slice numbers the preview legend shows (time and filament per line type).  (Slice.cpp)
nlohmann::json slice_stats_json(const Slic3r::GCodeProcessorResult &r, const std::string &gcode);

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_dev_orcaslicer_android_OrcaCore_##name
