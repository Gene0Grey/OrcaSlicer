#pragma once

#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"

#include <jni.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>

// State of the one open project, shared by the JNI calls (OrcaCore.cpp) and the renderer (Scene.cpp).
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
    int                                            selected = -1;
    // Bumped when the plate or its objects change / when a new slice result is ready,
    // so the GL thread knows to rebuild its buffers.
    uint64_t                                       scene_version   = 1;
    uint64_t                                       preview_version = 0;
};

extern Session g_session;
