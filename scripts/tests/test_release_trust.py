import unittest

from scripts import verify_apk_signature, verify_github_release_context


class ReleaseTrustTests(unittest.TestCase):
    def test_all_successful_required_checks_pass(self):
        runs = [
            {"name": name, "status": "completed", "conclusion": "success", "completed_at": "2026-07-25T10:00:00Z"}
            for name in verify_github_release_context.REQUIRED_CHECKS
        ]
        self.assertEqual([], verify_github_release_context.verify_check_runs(runs))

    def test_missing_or_failed_check_is_rejected(self):
        runs = [
            {"name": "Workflow integrity", "status": "completed", "conclusion": "success"},
            {"name": "Android quality", "status": "completed", "conclusion": "failure"},
        ]
        errors = verify_github_release_context.verify_check_runs(runs)
        self.assertTrue(any("Android quality" in error for error in errors))
        self.assertTrue(any("Instrumented smoke" in error for error in errors))

    def test_latest_rerun_wins(self):
        runs = [
            {"name": "Android quality", "status": "completed", "conclusion": "failure", "completed_at": "2026-07-25T09:00:00Z"},
            {"name": "Android quality", "status": "completed", "conclusion": "success", "completed_at": "2026-07-25T10:00:00Z"},
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
        with self.assertRaisesRegex(ValueError, "conflicting Signer #1"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_missing_digest_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exactly one APK signer certificate digest"):
            verify_apk_signature.parse_signer_fingerprint("Verifies\nNumber of signers: 1\n")

    def test_invalid_expected_fingerprint_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_apk_signature.normalize_fingerprint("not-a-fingerprint")


if __name__ == "__main__":
    unittest.main()
