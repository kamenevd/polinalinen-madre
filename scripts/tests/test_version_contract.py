import copy
import json
import tempfile
import unittest
from pathlib import Path
from scripts.version_contract import semver, validate
from scripts.release_cycle import build_parser


class DeliveryVersionsTest(unittest.TestCase):
    def test_strict_semver(self):
        self.assertEqual((7, 0, 1), semver("7.0.1"))
        for bad in ["07.0.1", "7.00.1", "7.0", "7.0.1-rc", "7.0.1 Разворот"]:
            with self.assertRaises(ValueError): semver(bad)

    def test_cli_compatible(self):
        self.assertIsNone(build_parser().parse_args(["prepare-version"]).version)
        self.assertEqual("7.0.1", build_parser().parse_args(["prepare-version", "--version", "7.0.1"]).version)

    def test_delivery_history(self):
        root = Path(__file__).resolve().parents[2]
        validate(root)
        entries = json.loads((root / "CHANGELOG.json").read_text())
        for field, value in [("version", "6.6.5"), ("versionCode", 36), ("date_msk", "2026-10-23T10:00:00+00:00")]:
            with tempfile.TemporaryDirectory() as tmp:
                p = Path(tmp); (p / "app").mkdir()
                (p / "app/build.gradle.kts").write_text((root / "app/build.gradle.kts").read_text())
                changed = copy.deepcopy(entries); changed[-1][field] = value
                (p / "CHANGELOG.json").write_text(json.dumps(changed))
                with self.assertRaises(AssertionError): validate(p)
