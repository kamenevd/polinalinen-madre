"""Explicit 2026-10-24 QA approval must never activate release or alter its identity."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class YandexFeatureGateTest(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / "app/build.gradle.kts").read_text()

    def block(self, variant):
        match = re.search(r"\b" + variant + r"\s*\{(.*?)\n        \}", self.source, re.S)
        assert match is not None
        return match.group(1)

    def test_current_qa_approval_is_explicit_source_literal_not_an_environment_override(self):
        assignments = re.findall(r"val qaYandexActivationApproved\s*=\s*([^\n]+)", self.source)
        self.assertEqual(["true"], assignments)
        self.assertIn('buildConfigField("boolean", "YANDEX_ID", qaYandexActivationApproved.toString())', self.block("debug"))
        self.assertIn('applicationIdSuffix = ".qa"', self.block("debug"))

    def test_release_is_unconditionally_disabled_and_keeps_ordinary_signing_identity(self):
        release = self.block("release")
        self.assertIn('buildConfigField("boolean", "YANDEX_ID", "false")', release)
        self.assertNotIn("qaYandexActivationApproved", release)
        self.assertNotIn('applicationIdSuffix', release)
        self.assertNotIn('signingConfigs.findByName("qa")', release)
        self.assertIn('signingConfigs.findByName("release")', release)
        self.assertEqual(2, len(re.findall(r'buildConfigField\("boolean",\s*"YANDEX_ID"', self.source)))

    def test_feature_flags_delegate_to_the_actual_variant_instead_of_a_second_global_gate(self):
        flags = (ROOT / "app/src/main/java/com/polinalinen/madre/config/FeatureFlags.kt").read_text()
        self.assertIn("const val YANDEX_ID = com.polinalinen.madre.BuildConfig.YANDEX_ID", flags)
