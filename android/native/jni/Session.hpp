#pragma once

#include "libslic3r/BoundingBox.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"

#include <jni.h>
#include <nlohmann/json.hpp>

#include <cstdint>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <vector>

// State of the one open project, shared by the JNI calls (OrcaCore.cpp, Gizmo.cpp) and the
// renderer (Scene.cpp).
struct Session
{
    std::mutex                                     mutex;
    std::unique_ptr<Slic3r::PresetBundle>          bundle;
    std::string                                    vendor;
    Slic3r::Model                                  model;
    std::unique_ptr<Slic3r::Print>                 print;
    std::unique_ptr<Slic3r::GCodeProcessorResult>  result;
    std::string                                    gcode_path;
    bool                                           sliced   = false;
    // `selected` is the object the gizmos and the object bar act on; `picked` is the whole
    // selection (long press adds objects), which align/distribute/assemble/delete use.
    int                                            selected = -1;
    std::set<int>                                  picked;
    // Copied object settings (Copy / Paste Process Settings).
    Slic3r::DynamicPrintConfig                     clipboard;
    bool                                           has_clipboard = false;
    // Bumped when the plate or its objects change / when a new slice result is ready,
    // so the GL thread knows to rebuild its buffers.
    uint64_t                                       scene_version   = 1;
    uint64_t                                       preview_version = 0;

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
        sliced = false;
        ++scene_version;
        return true;
    }

    void select_only(int idx)
    {
        selected = idx;
        picked.clear();
        if (idx >= 0) picked.insert(idx);
    }

    // Whether an object leaves the printer's build volume (XY box of printable_area, printable_height).
    bool outside(const Slic3r::ModelObject &o) const
    {
        for (size_t i = 0; i < o.instances.size(); ++i)
            if (outside(o, i)) return true;
        return false;
    }

    bool outside(const Slic3r::ModelObject &o, size_t instance) const
    {
        if (!bundle || instance >= o.instances.size()) return false;
        const auto &cfg = bundle->printers.get_edited_preset().config;
        const auto *pa  = cfg.option<Slic3r::ConfigOptionPoints>("printable_area");
        if (pa == nullptr || pa->values.empty()) return false;
        Slic3r::BoundingBoxf bed;
        for (const Slic3r::Vec2d &p : pa->values) bed.merge(p);
        const Slic3r::BoundingBoxf3 bb = o.instance_bounding_box(instance);
        const double eps = 0.01;
        return bb.min.x() < bed.min.x() - eps || bb.min.y() < bed.min.y() - eps || bb.max.x() > bed.max.x() + eps ||
               bb.max.y() > bed.max.y() + eps || bb.max.z() > cfg.opt_float("printable_height") + eps;
    }
};

extern Session g_session;

// Helpers shared by the JNI files (defined in OrcaCore.cpp / Objects.cpp).
std::string    jstr(JNIEnv *env, jstring s);
jstring        to_j(JNIEnv *env, const std::string &s);
// Printable area (XY) and height (Z) of the selected printer.
Slic3r::BoundingBoxf3 bed_volume();
// The plate's objects for the app: transforms, sizes, parts, ranges, selection.
nlohmann::json objects_json();
void           arrange_model();
// Marks the slice stale and the scene for a rebuild.
void           model_changed();

#define JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_dev_orcaslicer_android_OrcaCore_##name
