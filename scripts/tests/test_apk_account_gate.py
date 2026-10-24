import unittest
from scripts.verify_apk_account_gate import parse_build_config, validate_fields


class ApkAccountGateTest(unittest.TestCase):
    def fixture(self, enabled=True):
        # Parser-only fixture in the real dexdump format, not an issued client or live acceptance.
        client = "a" * 32
        dump = "Class descriptor  : 'Lcom/polinalinen/madre/BuildConfig;'\nStatic fields -\n"
        for name, value in [("APPLICATION_ID", '"com.polinalinen.madre.qa"'),
                            ("YANDEX_ID", str(enabled).lower()),
                            ("YANDEX_CLIENT_ID", '"' + client + '"'),
                            ("VERSION_CODE", "50"), ("VERSION_NAME", '"7.0.14"')]:
            dump += f"  name          : '{name}'\n  value         : {value}\n"
        dump += "Instance fields -\n"
        fields = parse_build_config(dump)
        assert fields is not None
        return fields, client

    def test_parses_the_actual_encoded_static_field_format(self):
        fields, client = self.fixture()
        validate_fields(fields, client)
        self.assertIs(fields["YANDEX_ID"], True)
        self.assertEqual(50, fields["VERSION_CODE"])

    def test_false_apk_cannot_pass_even_if_its_client_matches(self):
        fields, client = self.fixture(False)
        with self.assertRaisesRegex(ValueError, "not enabled"):
            validate_fields(fields, client)

    def test_ordinary_package_cannot_pass_the_qa_contract(self):
        fields, client = self.fixture()
        fields["APPLICATION_ID"] = "com.polinalinen.madre"
        with self.assertRaisesRegex(ValueError, "separate QA package"):
            validate_fields(fields, client)

    def test_mismatched_client_fails_without_echoing_either_value(self):
        fields, client = self.fixture()
        other = "b" * 32
        with self.assertRaises(ValueError) as caught:
            validate_fields(fields, other)
        self.assertNotIn(client, str(caught.exception))
        self.assertNotIn(other, str(caught.exception))
