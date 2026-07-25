import tempfile
import unittest
from pathlib import Path

from scripts import check_release_signing


class ReleaseSigningTests(unittest.TestCase):
    def test_missing_inputs_are_rejected(self):
        errors = check_release_signing.validate_signing_environment({})
        self.assertEqual(1, len(errors))
        self.assertIn("KEYSTORE_PATH", errors[0])
        self.assertIn("KEY_PASSWORD", errors[0])

    def test_nonexistent_keystore_is_rejected_without_leaking_values(self):
        environment = {
            "KEYSTORE_PATH": "/missing/release.jks",
            "KEYSTORE_PASSWORD": "secret-store",
            "KEY_ALIAS": "release",
            "KEY_PASSWORD": "secret-key",
        }
        encoded = " ".join(check_release_signing.validate_signing_environment(environment))
        self.assertIn("does not exist", encoded)
        self.assertNotIn("secret-store", encoded)
        self.assertNotIn("secret-key", encoded)

    def test_complete_environment_with_keystore_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            keystore = Path(tmp) / "release.jks"
            keystore.write_bytes(b"test keystore placeholder")
            errors = check_release_signing.validate_signing_environment({
                "KEYSTORE_PATH": str(keystore),
                "KEYSTORE_PASSWORD": "store-password",
                "KEY_ALIAS": "release",
                "KEY_PASSWORD": "key-password",
            })
            self.assertEqual([], errors)


if __name__ == "__main__":
    unittest.main()
