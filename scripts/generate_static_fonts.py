#!/usr/bin/env python3
"""Offline static instances of bundled OFL fonts. Requires fonttools==4.67.0.

No font downloads: retain original variable files and pin every variation axis.
License text is preserved from Google Fonts OFL (trailing whitespace normalized).
"""
from pathlib import Path
import hashlib
import json
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

ROOT = Path(__file__).resolve().parents[1]
FONTS = ROOT / "app/src/main/res/font"
RAW = ROOT / "app/src/main/res/raw"
JOBS = [("literata", w, False) for w in (200, 300, 400, 500)] + [("literata_italic", w, True) for w in (400, 500)] + [("manrope", w, False) for w in (200, 400, 500, 600, 700, 800)]


def main():
    records = []
    for source, weight, italic in JOBS:
        family = "manrope" if source == "manrope" else "literata"
        original = TTFont(FONTS / f"{source}.ttf", recalcTimestamp=False)
        axes = {a.axisTag: a.defaultValue for a in original["fvar"].axes}
        axes["wght"] = weight
        font = instantiateVariableFont(original, axes, inplace=False, static=True)
        assert "fvar" not in font and "gvar" not in font
        assert font["OS/2"].usWeightClass == weight
        assert bool(font["OS/2"].fsSelection & 1) == italic
        cmap = font.getBestCmap()
        assert all(c in cmap for c in list(range(0x410, 0x450)) + [0x401, 0x451])
        style = {200: "ExtraLight", 300: "Light", 400: "Regular", 500: "Medium", 600: "SemiBold", 700: "Bold", 800: "ExtraBold"}[weight]
        if italic:
            style = "Italic" if weight == 400 else style + " Italic"
        title = family.title()
        for platform, enc, lang in [(3, 1, 0x409), (1, 0, 0)]:
            for name_id, value in [(1, title), (2, style), (6, title + "-" + style.replace(" ", "")), (16, title), (17, style)]:
                font["name"].setName(value, name_id, platform, enc, lang)
            font["name"].setName((RAW / f"{family}_ofl.txt").read_text(), 13, platform, enc, lang)
            font["name"].setName("https://openfontlicense.org/", 14, platform, enc, lang)
        name = f"{family}_{'italic_' if italic else ''}{weight}.ttf"
        output = FONTS / name
        font.save(output)
        records.append({"file": name, "source": source + ".ttf", "source_sha256": hashlib.sha256((FONTS / (source + '.ttf')).read_bytes()).hexdigest(), "weight": weight, "italic": italic, "axes_pinned": axes, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(), "cyrillic": True, "license": "SIL OFL1.1", "glyph_sha256": hashlib.sha256(font.getTableData('glyf')).hexdigest()})
    assert len({r['glyph_sha256'] for r in records}) == len(records)
    (RAW / "static_fonts_manifest.json").write_text(json.dumps({"generator": "fontTools4.67.0 varLib.instancer; all axes pinned", "fonts": records}, indent=2) + "\n")
    print(f"Verified {len(records)} genuine static faces, distinct outlines, Cyrillic and italic flags")


if __name__ == "__main__":
    main()
