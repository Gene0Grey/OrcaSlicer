#!/usr/bin/env python3
"""Extract the desktop settings layout (pages > groups > option keys) from src/slic3r/GUI/Tab.cpp.

The mobile app renders the Process / Filament / Printer settings from this JSON so its pages and
groups match the desktop tabs. Re-run after Tab.cpp changes:
    python3 android/tools/extract_tab_layout.py > android/app/src/main/assets/settings_layout.json
"""
import json
import re
import sys
from pathlib import Path

TAB = Path(__file__).resolve().parents[2] / "src/slic3r/GUI/Tab.cpp"

# Functions whose page building belongs to each settings kind.
FUNCS = {
    "process": ["TabPrint::build"],
    "filament": ["TabFilament::build", "TabFilament::add_filament_overrides_page"],
    "printer": ["TabPrinter::build_fff", "TabPrinter::build_kinematics_page", "TabPrinter::build_unregular_pages"],
}
FUNC_START = re.compile(r"^(?:void|PageShp)\s+(\w+::\w+)\s*\(")
PAGE = re.compile(r'add_options_page\(\s*L\("([^"]+)"\)')
GROUP = re.compile(r'new_optgroup\(\s*L\("([^"]+)"\)')
OPTION = re.compile(r'(?:append_single_option_line|get_option|create_single_option_line|append_option_line\(\s*optgroup\s*,)\s*\(?\s*"([A-Za-z0-9_]+)"')


def main():
    lines = TAB.read_text(encoding="utf-8").splitlines()
    out = {kind: [] for kind in FUNCS}
    func = None
    page = group = None
    for line in lines:
        m = FUNC_START.match(line)
        if m:
            func = m.group(1)
            page = group = None
            continue
        kind = next((k for k, fs in FUNCS.items() if func in fs), None)
        code = line.split("//")[0]
        if kind is None or not code.strip():
            continue
        if m := PAGE.search(code):
            page = next((p for p in out[kind] if p["title"] == m.group(1)), None)
            if page is None:
                page = {"title": m.group(1), "groups": []}
                out[kind].append(page)
            group = None
        if m := GROUP.search(code):
            if page is not None:
                group = {"title": m.group(1), "options": []}
                page["groups"].append(group)
        for key in OPTION.findall(code):
            if group is not None and key not in group["options"]:
                group["options"].append(key)
    for kind in out:
        out[kind] = [p for p in out[kind] if any(g["options"] for g in p["groups"])]
        for p in out[kind]:
            p["groups"] = [g for g in p["groups"] if g["options"]]
    json.dump(out, sys.stdout, indent=1)


if __name__ == "__main__":
    main()
