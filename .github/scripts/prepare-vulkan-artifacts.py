#!/usr/bin/env python3
"""Verify remapped CI jars and tests, then package only the installable artifacts."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def installable_jar(module):
    jars = [
        jar for jar in (ROOT / module / "build/libs").glob("*.jar")
        if not jar.name.endswith(("-sources.jar", "-dev.jar", "-javadoc.jar"))
    ]
    if len(jars) != 1:
        raise RuntimeError(f"Expected one remapped {module} jar, found: {jars}")
    return jars[0]


def read_manifest(archive):
    # Manifest values can wrap at 72 bytes. Fold continuations before checking them.
    text = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n")
    return dict(
        line.split(": ", 1) for line in text.replace("\n ", "").splitlines() if ": " in line
    )


def main():
    addon = installable_jar("vulkan-addon")
    mcef = installable_jar("fabric")
    with zipfile.ZipFile(addon) as archive:
        names = set(archive.namelist())
        metadata = json.loads(archive.read("fabric.mod.json"))
        assert metadata["id"] == "mcef_vulkan", "Wrong add-on mod id"
        assert metadata["environment"] == "client", "Add-on must be client-only"
        assert "${" not in json.dumps(metadata), "Unexpanded add-on metadata"
        assert metadata["depends"]["minecraft"] == "1.21.11", "Wrong Minecraft version"
        assert "mcef" in metadata["depends"] and "vulkanmod" in metadata["depends"]
        for name in ("MixinMCEFRenderer", "MixinMCEFBrowser"):
            assert f"com/cinemamod/mcef/vulkan/mixin/{name}.class" in names, f"Missing {name}"
        assert "mcef-vulkan-addon.refmap.json" in names, "Missing mixin refmap"
        mixins = json.loads(archive.read("mcef-vulkan-addon.mixins.json"))
        assert mixins["required"] and mixins["injectors"]["defaultRequire"] == 1
        assert not any(name.startswith(("org/cef/", "net/vulkanmod/", "org/lwjgl/", "META-INF/jars/")) for name in names), "Bundled implementation dependency"
        assert "com/cinemamod/mcef/MCEFRenderer.class" not in names, "MCEF embedded in add-on"
        assert "mcef.mixins.json" not in names, "Original MCEF mixins embedded in add-on"
        manifest = read_manifest(archive)
        assert not any(key.lower() == "java-cef-commit" for key in manifest), "Add-on changes native bundle selection"

    jcef_commit = subprocess.check_output(
        ["git", "-C", str(ROOT / "common/java-cef"), "rev-parse", "HEAD"], text=True
    ).strip()
    with zipfile.ZipFile(mcef) as archive:
        mcef_metadata = json.loads(archive.read("fabric.mod.json"))
        assert mcef_metadata["id"] == "mcef", "Wrong original MCEF artifact"
        assert mcef_metadata["version"] == metadata["depends"]["mcef"], "MCEF/add-on version mismatch"
        assert "com/cinemamod/mcef/MCEFRenderer.class" in archive.namelist()
        assert "org/cef/browser/CefBrowserOsr.class" in archive.namelist(), "Missing pinned JCEF classes"
        native_commit = read_manifest(archive).get("java-cef-commit", "")
        assert re.fullmatch(r"[0-9a-f]{40}", native_commit), "Invalid native bundle commit"
        assert native_commit == jcef_commit, "Wrong JCEF native bundle selected"

    reports = list((ROOT / "vulkan-addon/build/test-results/test").glob("TEST-*.xml"))
    assert reports, "JUnit results are missing"
    counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in counts:
            counts[key] += int(suite.attrib.get(key, 0))
    assert counts["tests"] >= 36, f"Expected all 36 compositor cases: {counts}"
    assert not any(counts[key] for key in ("failures", "errors", "skipped")), counts

    destination = ROOT / "build/ci-artifacts"
    destination.mkdir(parents=True, exist_ok=True)
    addon_name = f"mcef-vulkan-addon-{metadata['version']}-1.21.11.jar"
    mcef_name = f"mcef-fabric-{mcef_metadata['version']}-1.21.11.jar"
    for source, name in ((addon, addon_name), (mcef, mcef_name)):
        shutil.copyfile(source, destination / name)
    checksums = "".join(
        f"{hashlib.sha256(jar.read_bytes()).hexdigest()}  {jar.name}\n"
        for jar in sorted(destination.glob("*.jar"))
    )
    (destination / "SHA256SUMS").write_text(checksums, encoding="utf-8")
    summary = (
        "## MCEF Vulkan build\n\n"
        f"- Minecraft: **1.21.11**, Fabric, Java 21\n"
        f"- CPU compositor tests: **{counts['tests']} passed**, no failures/skips\n"
        f"- Add-on: `{addon_name}` (MCEF/JCEF/VulkanMod not embedded)\n"
        f"- Original MCEF: `{mcef_name}`\n"
        f"- Pinned JCEF/native bundle: `{jcef_commit}`\n"
        "- Remapped jars, metadata, mixins, refmap and native bundle manifests verified\n"
        "- SHA-256 checksums included in the jar artifact\n\n"
        "**This CI build does not run Minecraft, Chromium or a Vulkan GPU test.**\n"
    )
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as report:
            report.write(summary)


if __name__ == "__main__":
    main()
