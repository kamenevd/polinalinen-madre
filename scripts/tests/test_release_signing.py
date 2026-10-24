import tempfile
import base64
import stat
import unittest
from pathlib import Path

from scripts import check_release_signing, restore_qa_signing


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


class QaSigningTests(unittest.TestCase):
    def environment(self):
        return {
            "QA_KEYSTORE_BASE64": base64.b64encode(b"synthetic opaque keystore bytes").decode("ascii"),
            "QA_KEYSTORE_PASSWORD": "synthetic-store-password",
            "QA_KEY_ALIAS": "synthetic-qa-alias",
            "QA_KEY_PASSWORD": "synthetic-key-password",
            "QA_SIGNING_CERT_SHA256": restore_qa_signing.EXPECTED_QA_CERT_SHA256,
        }

    def test_missing_qa_inputs_fail_before_creating_material(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "qa"
            with self.assertRaisesRegex(ValueError, "missing QA signing inputs"):
                restore_qa_signing.restore_qa_keystore(destination, {})
            self.assertFalse(destination.exists())

    def test_wrong_qa_pin_and_invalid_payload_fail_closed_without_values(self):
        for field, value in (("QA_SIGNING_CERT_SHA256", "ab" * 32), ("QA_KEYSTORE_BASE64", "!invalid-synthetic-payload!")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                environment = self.environment()
                environment[field] = value
                with self.assertRaises(ValueError) as caught:
                    restore_qa_signing.restore_qa_keystore(Path(tmp) / "qa", environment)
                message = str(caught.exception)
                for secret in environment.values():
                    self.assertNotIn(secret, message)
                self.assertFalse((Path(tmp) / "qa").exists())

    def test_qa_restore_preserves_bytes_and_creates_private_material(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "qa"
            environment = self.environment()
            restored = restore_qa_signing.restore_qa_keystore(directory, environment)
            self.assertEqual(base64.b64decode(environment["QA_KEYSTORE_BASE64"]), restored.read_bytes())
            self.assertEqual(0o700, stat.S_IMODE(directory.stat().st_mode))
            self.assertEqual(0o600, stat.S_IMODE(restored.stat().st_mode))

    def test_noncurrent_qa_identity_is_rejected_before_writing_material(self):
        with tempfile.TemporaryDirectory() as tmp:
            environment = self.environment()
            environment["QA_SIGNING_CERT_SHA256"] = "DDE5FA55A95EA8E5310CB35A6C92A108316663564FD7950C7E5C664D5D80F639"
            directory = Path(tmp) / "qa"
            with self.assertRaisesRegex(ValueError, "certificate pin"):
                restore_qa_signing.restore_qa_keystore(directory, environment)
            self.assertFalse(directory.exists())

    def test_qa_restore_never_overwrites_an_existing_keystore(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "qa"
            environment = self.environment()
            restored = restore_qa_signing.restore_qa_keystore(directory, environment)
            before = restored.read_bytes()
            with self.assertRaises(FileExistsError):
                restore_qa_signing.restore_qa_keystore(directory, environment)
            self.assertEqual(before, restored.read_bytes())

    def test_qa_restore_refuses_a_symlink_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            destination = Path(tmp) / "foreign"
            destination.mkdir()
            link = Path(tmp) / "qa"
            link.symlink_to(destination, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "symlink"):
                restore_qa_signing.restore_qa_keystore(link, self.environment())
            self.assertEqual([], list(destination.iterdir()))


if __name__ == "__main__":
    unittest.main()
