import shutil
import tempfile
import unittest
from pathlib import Path

from scripts.verify_yandex_sdk_source import verify


class YandexSdkSourceIntegrityTests(unittest.TestCase):
    def test_pinned_upstream_public_sources_and_narrow_correction_inventory(self):
        library = Path(__file__).resolve().parents[2] / "yandex-authsdk-330"
        self.assertEqual("PASS", verify(library)["status"])
        with tempfile.TemporaryDirectory(prefix="madre-sdk-integrity-") as temporary:
            copy = Path(temporary) / "sdk"
            shutil.copytree(library, copy, ignore=shutil.ignore_patterns("build", ".gradle"))
            public = copy / "src/main/java/com/yandex/authsdk/YandexAuthOptions.kt"
            public.write_text(public.read_text() + "\n// unexpected upstream change\n")
            with self.assertRaisesRegex(ValueError, "SDK source differs"):
                verify(copy)
