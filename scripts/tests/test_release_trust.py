import tempfile
import unittest
from pathlib import Path

from scripts import verify_apk_signature, verify_github_release_context


ACTIONS = {"app": {"slug": "github-actions"}}


class ReleaseTrustTests(unittest.TestCase):
    def test_all_successful_required_checks_pass(self):
        runs = [
            {"name": name, "status": "completed", "conclusion": "success", "completed_at": "2026-07-25T10:00:00Z", **ACTIONS}
            for name in verify_github_release_context.REQUIRED_CHECKS
        ]
        self.assertEqual([], verify_github_release_context.verify_check_runs(runs))

    def test_missing_or_failed_check_is_rejected(self):
        runs = [
            {"name": "Workflow integrity", "status": "completed", "conclusion": "success", **ACTIONS},
            {"name": "Android quality", "status": "completed", "conclusion": "failure", **ACTIONS},
        ]
        errors = verify_github_release_context.verify_check_runs(runs)
        self.assertTrue(any("Android quality" in error for error in errors))
        self.assertTrue(any("Instrumented smoke" in error for error in errors))

    def test_latest_rerun_wins(self):
        runs = [
            {"name": "Android quality", "status": "completed", "conclusion": "failure", "completed_at": "2026-07-25T09:00:00Z", **ACTIONS},
            {"name": "Android quality", "status": "completed", "conclusion": "success", "completed_at": "2026-07-25T10:00:00Z", **ACTIONS},
        ]
        errors = verify_github_release_context.verify_check_runs(runs, ("Android quality",))
        self.assertEqual([], errors)

    def test_apk_certificate_fingerprint_is_normalized(self):
        fingerprint = "ab" * 32
        output = f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {fingerprint.upper()}\n"
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_cert_output_split_across_stdout_and_stderr_is_combined(self):
        # Different apksigner versions may emit the certificate block on stderr.
        fingerprint = "cd" * 32
        stdout = "Verifies\nNumber of signers: 1\n"
        stderr = f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
        self.assertEqual(
            fingerprint,
            verify_apk_signature.signer_fingerprint_from_streams(stdout, stderr),
        )

    def test_duplicate_identical_signer_one_is_accepted(self):
        # Newer apksigner may repeat the same certificate across signing schemes.
        fingerprint = "ab" * 32
        output = (
            "Number of signers: 1\n"
            f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
            f"Signer #1 certificate SHA-256 digest: {fingerprint.upper()}\n"
        )
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_second_signer_is_rejected(self):
        first = "ab" * 32
        second = "cd" * 32
        output = (
            f"Signer #1 certificate SHA-256 digest: {first}\n"
            f"Signer #2 certificate SHA-256 digest: {second}\n"
        )
        with self.assertRaisesRegex(ValueError, "additional APK signer"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_number_of_signers_two_is_rejected(self):
        fingerprint = "ab" * 32
        output = (
            "Number of signers: 2\n"
            f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_contradictory_signer_count_lines_are_rejected(self):
        fingerprint = "ab" * 32
        output = (
            "Number of signers: 1\n"
            "Number of signers: 2\n"
            f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
        )
        with self.assertRaisesRegex(ValueError, "signer count"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_conflicting_signer_one_fingerprints_are_rejected(self):
        output = (
            f"Signer #1 certificate SHA-256 digest: {'ab' * 32}\n"
            f"Signer #1 certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "conflicting APK signer certificate digests"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_sdk_range_signer_label_is_accepted(self):
        # With a v3.1 block apksigner labels the certificate by the SDK range it
        # covers instead of by signer index, and prints no "#N" at all.
        fingerprint = "ab" * 32
        output = (
            "Verifies\n"
            "Verified using v3 scheme (APK Signature Scheme v3): true\n"
            "Verified using v3.1 scheme (APK Signature Scheme v3.1): true\n"
            "Number of signers: 1\n"
            "Signer (minSdkVersion=24, maxSdkVersion=32) certificate DN: CN=Madre\n"
            f"Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {fingerprint}\n"
            "Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate DN: CN=Madre\n"
            f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {fingerprint}\n"
        )
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_dev_release_annotated_sdk_range_label_is_accepted(self):
        fingerprint = "ab" * 32
        output = (
            "Number of signers: 1\n"
            "Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647) "
            f"certificate SHA-256 digest: {fingerprint}\n"
        )
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_spacing_case_and_line_ending_variations_are_accepted(self):
        fingerprint = "ab" * 32
        colon_separated = ":".join(fingerprint[index : index + 2] for index in range(0, 64, 2))
        output = (
            "  number of signers :\t1\r\n"
            f"\tsigner # 1  certificate\tSHA256  digest :  {colon_separated.upper()}  \r\n"
        )
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_rotated_certificate_per_sdk_range_is_rejected(self):
        # Key rotation genuinely pins two certificates; the release must not pass.
        output = (
            "Number of signers: 1\n"
            f"Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: {'ab' * 32}\n"
            "Signer (minSdkVersion=33, maxSdkVersion=2147483647) "
            f"certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "conflicting APK signer certificate digests"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_build_tools_37_scheme_labels_are_accepted(self):
        # Build Tools 35+ label each certificate block by the signing scheme that
        # produced it, and the label itself ends with a colon.
        fingerprint = "ab" * 32
        output = (
            "Verifies\n"
            "Verified using v1 scheme (JAR signing): true\n"
            "Verified using v3 scheme (APK Signature Scheme v3): true\n"
            "Number of signers: 1\n"
            f"V1 Signer: certificate SHA-256 digest: {fingerprint}\n"
            f"V2 Signer: certificate SHA-256 digest: {fingerprint}\n"
            f"V3.0 Signer: certificate SHA-256 digest: {fingerprint.upper()}\n"
        )
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_v3_1_scheme_label_with_sdk_range_and_index_is_accepted(self):
        fingerprint = "ab" * 32
        output = (
            "Number of signers: 1\n"
            f"V3.0 Signer #1 (minSdkVersion=24, maxSdkVersion=32): "
            f"certificate SHA-256 digest: {fingerprint}\n"
            f"V3.1 Signer (minSdkVersion=33, maxSdkVersion=2147483647): "
            f"certificate SHA-256 digest: {fingerprint}\n"
        )
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_scheme_labelled_rotation_across_sdk_ranges_is_rejected(self):
        output = (
            "Number of signers: 1\n"
            f"V3.0 Signer: certificate SHA-256 digest: {'ab' * 32}\n"
            f"V3.1 Signer: certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "conflicting APK signer certificate digests"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_scheme_labelled_public_key_digest_is_not_accepted(self):
        output = (
            "Number of signers: 1\n"
            f"V3.0 Signer: public key SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_scheme_labelled_source_stamp_is_not_accepted(self):
        output = (
            "Number of signers: 1\n"
            f"Source Stamp Signer: certificate SHA-256 digest: {'cd' * 32}\n"
            f"V2 Source Stamp Signer: certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_unknown_scheme_prefix_is_not_accepted(self):
        # Only the schemes this verifier has been checked against are trusted;
        # an unseen label must fail the release rather than be guessed at.
        output = (
            "Number of signers: 1\n"
            f"V4 Signer: certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_unqualified_signer_label_is_not_accepted(self):
        # No scheme, no index, no SDK range: not a label apksigner documents.
        output = (
            "Number of signers: 1\n"
            f"Signer certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_scheme_labelled_lineage_certificate_is_not_accepted(self):
        output = (
            "Number of signers: 1\n"
            f"V3.1 Signer #1 in lineage certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_diagnostics_name_scheme_labels_that_contain_a_colon(self):
        # Splitting the line at its first colon reported nothing for these labels,
        # which is what made the failing release run undiagnosable.
        fingerprint = "cd" * 32
        output = (
            "Number of signers: 1\n"
            f"V3.0 Signer: public key SHA-256 digest: {fingerprint}\n"
        )
        with self.assertRaises(ValueError) as raised:
            verify_apk_signature.parse_signer_fingerprint(output)
        self.assertIn("V3.0 Signer: public key SHA-256 digest", str(raised.exception))
        self.assertNotIn(fingerprint, str(raised.exception))

    def test_diagnostics_do_not_leak_a_colon_grouped_digest(self):
        grouped = ":".join(["cd"] * 32)
        output = f"Number of signers: 1\nV3.0 Signer: public key SHA-256 digest: {grouped}\n"
        with self.assertRaises(ValueError) as raised:
            verify_apk_signature.parse_signer_fingerprint(output)
        message = str(raised.exception)
        self.assertIn("V3.0 Signer: public key SHA-256 digest", message)
        self.assertNotIn("cd:cd", message)

    def test_public_key_digest_is_not_mistaken_for_the_certificate(self):
        output = (
            "Number of signers: 1\n"
            f"Signer #1 public key SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_source_stamp_certificate_is_not_accepted_as_the_signer(self):
        output = (
            "Number of signers: 1\n"
            f"Source Stamp Signer certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_lineage_certificate_is_not_accepted_as_the_signer(self):
        output = (
            "Number of signers: 1\n"
            f"Signer #1 in lineage certificate SHA-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_unrelated_sha256_text_is_not_accepted(self):
        output = (
            "Number of signers: 1\n"
            f"SHA-256: {'cd' * 32}\n"
            f"{'cd' * 32}\n"
            f"apk sha-256 digest: {'cd' * 32}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_trailing_text_on_a_digest_line_is_rejected(self):
        output = (
            "Number of signers: 1\n"
            f"Signer #1 certificate SHA-256 digest: {'ab' * 32} (untrusted)\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_rejection_names_the_labels_without_leaking_a_digest(self):
        fingerprint = "cd" * 32
        output = f"Number of signers: 1\nSigner #1 public key SHA-256 digest: {fingerprint}\n"
        with self.assertRaises(ValueError) as raised:
            verify_apk_signature.parse_signer_fingerprint(output)
        self.assertIn("Signer #1 public key SHA-256 digest", str(raised.exception))
        self.assertNotIn(fingerprint, str(raised.exception))

    def test_truncated_digest_is_rejected(self):
        output = f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {'ab' * 20}\n"
        with self.assertRaisesRegex(ValueError, "64 hexadecimal characters"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_missing_digest_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint("Verifies\nNumber of signers: 1\n")

    def test_invalid_expected_fingerprint_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_apk_signature.normalize_fingerprint("not-a-fingerprint")


class PinnedBuildToolsTests(unittest.TestCase):
    def make_sdk(self, *versions):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        for version in versions:
            signer = root / "build-tools" / version / "apksigner"
            signer.parent.mkdir(parents=True)
            signer.write_text("#!/bin/sh\n", encoding="utf-8")
        return root

    def test_pinned_version_is_used_instead_of_the_latest_installed(self):
        pinned = verify_apk_signature.PINNED_BUILD_TOOLS_VERSION
        sdk = self.make_sdk(pinned, "37.0.0")
        self.assertEqual(
            sdk / "build-tools" / pinned / "apksigner",
            verify_apk_signature.pinned_apksigner(sdk, pinned),
        )

    def test_missing_pinned_build_tools_fail_closed_even_if_others_exist(self):
        sdk = self.make_sdk("37.0.0")
        with self.assertRaisesRegex(ValueError, "pinned build tools 34.0.0"):
            verify_apk_signature.pinned_apksigner(sdk, "34.0.0")

    def test_unset_android_home_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "ANDROID_HOME"):
            verify_apk_signature.pinned_apksigner(Path(""), "34.0.0")

    def test_version_comes_from_the_environment_when_set(self):
        self.assertEqual(
            "35.0.1",
            verify_apk_signature.pinned_build_tools_version({"MADRE_BUILD_TOOLS_VERSION": "35.0.1"}),
        )

    def test_version_falls_back_to_the_pinned_constant(self):
        self.assertEqual(
            verify_apk_signature.PINNED_BUILD_TOOLS_VERSION,
            verify_apk_signature.pinned_build_tools_version({"MADRE_BUILD_TOOLS_VERSION": "  "}),
        )

    def test_malformed_version_is_rejected(self):
        for value in ("latest", "../35.0.0", "35.0.0/../37.0.0", "35"):
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, "build tools version"):
                verify_apk_signature.pinned_build_tools_version({"MADRE_BUILD_TOOLS_VERSION": value})


if __name__ == "__main__":
    unittest.main()
