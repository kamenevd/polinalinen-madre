#!/usr/bin/env python3
"""CI-only serial native baseline capture; never builds Android on the NAS.

Application sources are an immutable git archive of the named baseline.
Only its screenshot driver gets the requested viewport and a catalog entry.
No application source is edited and no old PNG is rescaled/relabelled.
"""
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tarfile
import tempfile
import hashlib

BASE = "60985a8e3a0afa84f9639d1d41b18ccda3750c8c"
TEST = "app/src/test/java/com/polinalinen/madre/ui/razvorot/RazvorotV3RenderTest.kt"


def capture_driver(text):
    old = 'qualifiers="ru-w360dp-h780dp-xhdpi"'
    assert text.count(old) == 1
    text = text.replace(old, 'qualifiers="ru-w412dp-h915dp-mdpi"')
    text = text.replace("import android.content.Context", "import android.content.Context\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.layout.*\nimport androidx.compose.runtime.remember\nimport androidx.compose.ui.Modifier")
    text = text.replace("            RazvorotDebugScene(scene,recipes)", '''            if(scene=="catalog") {
                val prefs=remember{ReadingPreferences(context,"baseline-catalog")}
                RazvorotTheme(prefs,Atmosphere.DAWN) {
                    Column(Modifier.fillMaxSize().background(LocalPaper.current.bg)) {
                        Box(Modifier.weight(1f)){ChapterCatalog(recipes,{},{},{})}
                        SpineBar(0){}
                    }
                }
            } else RazvorotDebugScene(scene,recipes)''')
    text = text.replace('    @Test fun titleMorning()', '    @Test fun catalogBefore412()=render("catalog")\n    @Test fun titleMorning()')
    return text


def main():
    assert os.environ.get("CI") == "true", "Android rendering is CI-only"
    root = Path(__file__).resolve().parents[1]
    subprocess.run(["git", "fetch", "--no-tags", "origin", BASE], cwd=root, check=True)
    archive = subprocess.check_output(["git", "archive", BASE], cwd=root)
    with tempfile.TemporaryDirectory(prefix="madre-before-", dir=os.environ["RUNNER_TEMP"]) as tmp:
        before = Path(tmp)
        with tarfile.open(fileobj=io.BytesIO(archive)) as t:
            t.extractall(before, filter="data")
        source = {str(p.relative_to(before)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (before/"app/src/main").rglob("*") if p.is_file()}
        for n in source:
            (before/n).chmod(0o444)
        driver = before/TEST
        driver.write_text(capture_driver(driver.read_text()))
        subprocess.run(["./gradlew", "testDebugUnitTest", "--tests", "*RazvorotV3RenderTest", "-Proborazzi.test.record=true", "--no-daemon", "--max-workers=1", "--stacktrace"], cwd=before, check=True)
        assert all(hashlib.sha256((before/n).read_bytes()).hexdigest() == sha for n, sha in source.items())
        assert (before/"app/src/main/assets/recipes.json").read_bytes() == (root/"app/src/main/assets/recipes.json").read_bytes()
        output = root/"app/build/outputs/roborazzi/f-v3-before-60985a8"
        output.mkdir(parents=True, exist_ok=True)
        files = []
        for p in sorted((before/"app/build/outputs/roborazzi/f-v3").glob("*.png")):
            b = p.read_bytes();assert b[:8] == b"\x89PNG\r\n\x1a\n"
            assert struct.unpack(">II", b[16:24]) == (412, 915)
            shutil.copy2(p, output/p.name)
            files.append({"file": p.name, "sha256": hashlib.sha256(b).hexdigest()})
        assert len(files) == 32
        (output/"SOURCE.json").write_text(json.dumps({"head": BASE, "viewport_dp": [412, 915], "png_px": [412, 915], "density": 1, "qualifier": "ru-w412dp-h915dp-mdpi", "renderer": "Roborazzi NATIVE SDK35", "application_source_unchanged": True, "application_source_sha256": source, "capture_driver_changes_only": ["viewport annotation", "direct catalog composable fixture"], "resized_existing_PNG": False, "images": files}, indent=2))
        print(f"Captured {len(files)} real baseline PNGs at412x915; app sources byte-identical to{BASE}")


if __name__ == "__main__":
    main()
