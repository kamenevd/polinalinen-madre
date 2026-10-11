"""Do not inherit setup-android's removed legacy SDK 'tools' package."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class AndroidSdkPackagesTest(unittest.TestCase):
    def test_every_sdk_setup_uses_available_platform_tools(self):
        for name, expected in (("quality-gates.yml", 2), ("release.yml", 1)):
            text = (ROOT / ".github" / "workflows" / name).read_text()
            steps = re.findall(
                r"^      - uses: android-actions/setup-android@[^\n]+\n(.*?)(?=^      - |\Z)",
                text, re.MULTILINE | re.DOTALL,
            )
            self.assertEqual(expected, len(steps), name)
            for index, block in enumerate(steps):
                with self.subTest(workflow=name, step=index):
                    self.assertRegex(block, r"(?m)^          packages: platform-tools$")


if __name__ == "__main__":
    unittest.main()
