#!/usr/bin/env python3
"""
Generates the patches section of README.md from patches-list.json
and injects it between <!-- PATCHES_START --> / <!-- PATCHES_END --> markers.

Spoilers are collapsed by default. They are expanded (open) only when the
README marker explicitly says: <!-- PATCHES_START EXPANDED -->

python3 generate_patches_readme.py <owner/repo> <branch> [patches-list.json] [README.md]
"""

import json
import re
import sys
import os
from pathlib import Path


if len(sys.argv) < 3:
    print("Usage: generate_patches_readme.py <owner/repo> <branch> [json] [readme]")
    sys.exit(1)

repo_full   = sys.argv[1]
branch      = sys.argv[2]
json_path   = Path(sys.argv[3]) if len(sys.argv) > 3 else Path("patches-list.json")
readme_path = Path(sys.argv[4]) if len(sys.argv) > 4 else Path("README.md")


if "/" not in repo_full:
    raise ValueError(f"Invalid repo format: {repo_full} (expected owner/repo)")

owner, repo = repo_full.split("/", 1)


with open(json_path, encoding="utf-8") as f:
    data = json.load(f)


def pkg_emoji(pkg):
    """Return a standard package emoji regardless of the package name."""
    return "📦"


# Apps this bundle renames, rendered as "<source app>  →  <patched app>" in the
# spoiler summary instead of the generic 📦 label. The patched name comes from a
# patch option, not from patches-list.json, so it is declared here.
APP_RENAMES = {
    "com.google.android.apps.maps": (
        '<img src="docs/icons/pin-google.png" width="20" height="20" align="top"> Google Maps',
        '<img src="docs/icons/pin-ungoogled.png" width="20" height="20" align="top"> Ungoogled Maps',
    ),
}


def pkg_label(pkg, entry):
    """Spoiler label for one app."""
    pair = APP_RENAMES.get(pkg)
    if pair:
        src, dst = pair
        return f"{src}&nbsp;&nbsp;-&gt;&nbsp;&nbsp;{dst}"
    return f"{entry['emoji']} {entry['name']}"


# Screenshots for an app live in docs/screenshots/<packageName>/ and are shown in
# one row at the top of its spoiler, in file-name order. The alt text is the file
# name without its numeric prefix ("2-customization.png" -> "Customization").
SCREENSHOT_DIR = Path("docs/screenshots")
SCREENSHOT_TYPES = {".png", ".jpg", ".jpeg", ".webp", ".gif"}


def screenshots_row(pkg):
    """One row of screenshots for an app, or "" when it has none."""
    folder = SCREENSHOT_DIR / pkg
    if not folder.is_dir():
        return ""
    files = sorted(p for p in folder.iterdir() if p.suffix.lower() in SCREENSHOT_TYPES)
    if not files:
        return ""
    width = f"{100 // len(files) - 1}%"
    imgs = []
    for p in files:
        alt = re.sub(r"^\d+[-_]", "", p.stem).replace("-", " ").replace("_", " ").capitalize()
        imgs.append(f'<img src="{p.as_posix()}" width="{width}" alt="{alt}" title="{alt}">')
    return "<p>\n" + "\n".join(imgs) + "\n</p>"

# Group patches by package; patches with no compatiblePackages are universal.
# JSON structure: compatiblePackages is a list of objects with
# { packageName, name, targets: [{ version, isExperimental, description }] }
by_pkg = {}   # packageName -> { name, emoji, patches, targets }
universal = {}

for patch in data["patches"]:
    cp = patch.get("compatiblePackages")
    if not cp:
        # Deduplicate universal patches by name
        if patch["name"] not in universal:
            universal[patch["name"]] = patch
        continue
    for pkg_entry in cp:
        pkg  = pkg_entry["packageName"]
        name = pkg_entry.get("name") or pkg  # fall back to package name if no label
        if pkg not in by_pkg:
            by_pkg[pkg] = {
                "name":    name,
                "emoji":   pkg_emoji(pkg),
                "patches": {},
                "targets": pkg_entry.get("targets", []),
            }
        # Deduplicate patches that appear across multiple packages
        if patch["name"] not in by_pkg[pkg]["patches"]:
            by_pkg[pkg]["patches"][patch["name"]] = patch


def anchor(name):
    """Convert a patch name to a GitHub-compatible anchor slug."""
    return re.sub(r"-+", "-", re.sub(r"[^a-z0-9]+", "-", name.lower())).strip("-")


def patches_table(patches):
    """Render a sorted markdown table of patches with name, description, and options."""
    rows = [
        "| Patch | Description | Options |",
        "|----------|----------------|-----------|",
    ]
    for p in sorted(patches, key=lambda x: x["name"]):
        a = anchor(p["name"])
        options = p.get("options") or []
        if options:
            # Show only option titles as a bullet list
            parts = [opt.get("title") or opt.get("key") or "" for opt in options]
            opts_cell = "<br>".join(f"• {t}" for t in parts)
        else:
            opts_cell = ""
        desc = (p.get("description") or "").replace("\n", "<br>")
        rows.append(f"| [{p['name']}](#{a}) | {desc} | {opts_cell} |")
    return "\n".join(rows)


def versions_line(targets):
    """Render supported versions inline, after the label, rather than as a table.
    Experimental versions are marked, and any per-version description follows it.
    """
    if not targets:
        return ""

    parts = []
    for t in targets:
        ver = t["version"]
        if ver is None:
            continue
        label = ver
        if t.get("isExperimental"):
            label += " (experimental)"
        desc = (t.get("description") or "").replace("\n", " ").strip()
        if desc:
            label += f" — {desc}"
        parts.append(label)

    if not parts:
        return ""

    return "**Supported version(s):** " + ", ".join(parts)


def spoiler(label, count, targets, tbl, expanded=False, shots=""):
    """Wrap a patches table in a <details> spoiler with a versions sub-table.
    If expanded=True, the spoiler is open by default (for repos with few patches).
    Screenshots, when given, come first, above the versions line.
    """
    noun = "patch" if count == 1 else "patches"
    vline = versions_line(targets)
    versions_section = f"{vline}\n\n" if vline else ""
    shots_section = f"{shots}\n\n" if shots else ""
    tag = "<details open>" if expanded else "<details>"
    return f"""{tag}
<summary>{label}&nbsp;&nbsp;•&nbsp;&nbsp;{count} {noun}</summary>
<br>

{shots_section}{versions_section}{tbl}

</details>"""


def build_content(expanded=False):
    """Build the full generated patches section."""
    total_noun = "patch" if total == 1 else "patches"
    lines = [
        f"> **[v{ver}](https://github.com/{owner}/{repo}/releases/tag/v{ver})**"
        f"&nbsp;&nbsp;•&nbsp;&nbsp;`{branch}`&nbsp;&nbsp;•&nbsp;&nbsp;"
        f"{total} {total_noun} total"
    ]

    # One spoiler per app, in the order they appear in the JSON
    for pkg, entry in by_pkg.items():
        patches = list(entry["patches"].values())
        label   = pkg_label(pkg, entry)
        lines.append(spoiler(label, len(patches), entry["targets"], patches_table(patches), expanded,
                             screenshots_row(pkg)))
        lines.append("")

    # Universal patches (no specific app)
    if universal:
        uni_patches = list(universal.values())
        noun = "patch" if len(uni_patches) == 1 else "patches"
        tag  = "<details open>" if expanded else "<details>"
        lines.append(f"""{tag}
<summary>🌐 Universal&nbsp;&nbsp;•&nbsp;&nbsp;{len(uni_patches)} {noun}</summary>
<br>

{patches_table(uni_patches)}

</details>""")
        lines.append("")

    return "\n".join(lines)


# Build and inject
raw_ver = data["version"]
# Strip leading "v" if present
ver   = raw_ver.lstrip("v")
total = sum(len(e["patches"]) for e in by_pkg.values()) + len(universal)

readme = readme_path.read_text(encoding="utf-8")

# Marker pattern — matches both <!-- PATCHES_START --> and <!-- PATCHES_START EXPANDED -->
START_PATTERN = r"<!-- PATCHES_START(?:\s+EXPANDED)?\s*-->"
END_MARKER    = "<!-- PATCHES_END -->"

marker_match = re.search(START_PATTERN, readme)

if not marker_match or END_MARKER not in readme:
    # Fallback: print to stdout so CI can catch the issue
    print(build_content(expanded=False))
    sys.stderr.write(
        f"⚠️  Markers <!-- PATCHES_START [EXPANDED] --> / {END_MARKER} not found in {readme_path}. "
        "Printed to stdout instead.\n"
    )
    sys.exit(1)

actual_start = marker_match.group(0)

# Spoilers are expanded only when the README marker explicitly requests it:
# <!-- PATCHES_START EXPANDED -->
#
# Upstream also auto-expanded any list of <= 20 patches, which meant a small
# bundle could not be collapsed at all. We always want the compact list, so
# the marker is now the only thing that decides.
expanded = "EXPANDED" in actual_start

generated  = build_content(expanded=expanded)

# Replace template links if present
readme = readme.replace("https://morphe.software/add-source?github=xyz-user/xyz-patches", f"https://morphe.software/add-source?github={repo_full}")
readme = readme.replace("https://github.com/xyz-user/xyz-patches", f"https://github.com/{repo_full}")

new_readme = re.sub(
    rf"{START_PATTERN}.*?{re.escape(END_MARKER)}",
    f"{actual_start}\n{generated}\n{END_MARKER}",
    readme,
    flags=re.DOTALL,
)
readme_path.write_text(new_readme, encoding="utf-8")
print(f"✅ Injected patches section into {readme_path} (v{ver}, branch={branch}, {total} patches, expanded={expanded})")
