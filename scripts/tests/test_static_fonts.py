"""Binary font checks require only stdlib, not fontTools or Android."""
import hashlib
import json
import struct
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FONTS = ROOT / "app/src/main/res/font"
RAW = ROOT / "app/src/main/res/raw"


def tables(b):
    return {b[12+i*16:16+i*16].decode(): struct.unpack_from(">II", b, 20+i*16) for i in range(struct.unpack_from(">H", b, 4)[0])}


def glyph(b, cp):
    off, _ = tables(b)["cmap"]
    for i in range(struct.unpack_from(">H", b, off+2)[0]):
        sub = off + struct.unpack_from(">I", b, off+4+i*8+4)[0]
        if struct.unpack_from(">H", b, sub)[0] != 4:
            continue
        count = struct.unpack_from(">H", b, sub+6)[0]//2
        ends = sub+14
        starts = ends+2*count+2
        deltas = starts+2*count
        ranges = deltas+2*count
        for j in range(count):
            lo = struct.unpack_from(">H", b, starts+2*j)[0]
            hi = struct.unpack_from(">H", b, ends+2*j)[0]
            if lo <= cp <= hi:
                delta = struct.unpack_from(">h", b, deltas+2*j)[0]
                r = struct.unpack_from(">H", b, ranges+2*j)[0]
                g = struct.unpack_from(">H", b, ranges+2*j+r+2*(cp-lo))[0] if r else cp
                return (g+delta) & 0xffff if g else 0
    return 0


class StaticFontTests(unittest.TestCase):
    def test_real_faces_weight_cmap_style_and_license(self):
        records = json.loads((RAW/"static_fonts_manifest.json").read_text())["fonts"]
        self.assertEqual(len(records), 12)
        outlines = []
        for r in records:
            with self.subTest(face=r["file"]):
                b = (FONTS/r["file"]).read_bytes(); t = tables(b)
                self.assertNotIn("fvar", t); self.assertNotIn("gvar", t)
                self.assertEqual(hashlib.sha256(b).hexdigest(), r["sha256"])
                os2 = t["OS/2"][0]
                self.assertEqual(struct.unpack_from(">H", b, os2+4)[0], r["weight"])
                self.assertEqual(bool(struct.unpack_from(">H", b, os2+62)[0] & 1), r["italic"])
                for cp in list(range(0x410, 0x450))+[0x401, 0x451]:
                    self.assertNotEqual(glyph(b, cp), 0, hex(cp))
                self.assertIn("SIL OPEN FONT LICENSE".encode("utf-16-be"), b)
                off, length = t["glyf"]
                outlines.append(hashlib.sha256(b[off:off+length]).hexdigest())
        self.assertEqual(len(set(outlines)), len(records))

    def test_variable_timer_font_is_retained(self):
        self.assertIn("fvar", tables((FONTS/"manrope.ttf").read_bytes()))

    def test_license_files_preserve_original_copyright(self):
        for family in ["literata", "manrope"]:
            text = (RAW/(family+"_ofl.txt")).read_text()
            self.assertIn("SIL OPEN FONT LICENSE Version 1.1", text)
            self.assertIn("Project Authors", text)
