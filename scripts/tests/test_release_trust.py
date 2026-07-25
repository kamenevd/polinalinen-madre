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
        output = f"Signer #1 certificate SHA-256 digest: {fingerprint.upper()}\n"
        self.assertEqual(fingerprint, verify_apk_signature.parse_signer_fingerprint(output))

    def test_multiple_signers_are_rejected(self):
        fingerprint = "ab" * 32
        output = (
            f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
            f"Signer #1 certificate SHA-256 digest: {fingerprint}\n"
        )
        with self.assertRaisesRegex(ValueError, "exactly one"):
            verify_apk_signature.parse_signer_fingerprint(output)

    def test_invalid_expected_fingerprint_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_apk_signature.normalize_fingerprint("not-a-fingerprint")


if __name__ == "__main__":
    unittest.main()
