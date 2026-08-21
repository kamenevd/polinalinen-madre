"""Cycle 11 фича 1 «Семейная книга» — контракт backend и сетевого слоя.

Тесты детерминированные и НЕ требуют живого PocketBase: проверяется
содержимое версионированных миграций/хуков в backend/ и конфигурация
Android-клиента. Живой сервер (/opt/madre-backend) не трогается.
"""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
MIGRATIONS = BACKEND / "pb_migrations"
HOOKS = BACKEND / "pb_hooks"

PRODUCTION_HOST = "madre-api.kdnfx.space"
LEGACY_COLLECTIONS = ("bake_stats", "feeding_stats", "margin_notes_sync", "guest_notes")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def sole_match(pattern: str, text: str, flags: int = 0) -> str:
    found = re.findall(pattern, text, flags)
    if len(found) != 1:
        raise AssertionError(f"ожидалось ровно одно совпадение {pattern!r}, найдено {len(found)}")
    return found[0]


class VersionedBackendLayoutTests(unittest.TestCase):
    """Backend должен жить в репозитории, а не только на сервере."""

    def test_migrations_and_hooks_are_versioned_in_repository(self):
        self.assertTrue(MIGRATIONS.is_dir(), "нет backend/pb_migrations")
        self.assertTrue(HOOKS.is_dir(), "нет backend/pb_hooks")
        self.assertTrue(list(MIGRATIONS.glob("*.js")), "нет ни одной миграции")
        self.assertTrue(list(HOOKS.glob("*.pb.js")), "нет ни одного хука")

    def test_migration_filenames_are_ordered_pocketbase_timestamps(self):
        for migration in MIGRATIONS.glob("*.js"):
            self.assertRegex(migration.name, r"^\d{10}_[a-z0-9_]+\.js$", migration.name)

    def test_every_migration_is_reversible(self):
        for migration in MIGRATIONS.glob("*.js"):
            text = read(migration)
            self.assertIn("migrate((app) => {", text, migration.name)
            # Второй аргумент migrate() — откат; без него миграция необратима.
            self.assertIn("}, (app) => {", text, migration.name)

    def test_backend_carries_no_credentials(self):
        forbidden = ("superuser", "pb_data", "PASSWORD=", "password:")
        for path in list(MIGRATIONS.glob("*.js")) + list(HOOKS.glob("*.pb.js")):
            text = read(path).lower()
            for needle in forbidden:
                self.assertNotIn(needle.lower(), text, f"{path.name}: {needle}")


class LegacyCollectionLockdownTests(unittest.TestCase):
    """Cycle 5–7 коллекции стояли с пустыми (=публичными) правилами."""

    def setUp(self):
        matches = list(MIGRATIONS.glob("*_lock_legacy_collections.js"))
        self.assertEqual(1, len(matches), "нужна ровно одна миграция блокировки legacy")
        self.text = read(matches[0])

    def test_all_legacy_collections_are_named(self):
        for name in LEGACY_COLLECTIONS:
            self.assertIn(name, self.text, name)

    def test_every_rule_is_locked_to_superuser_only(self):
        # null = «только суперюзер». Пустая строка = «всем можно» — именно то,
        # что чинит эта миграция, поэтому её быть не должно.
        for rule in ("listRule", "viewRule", "createRule", "updateRule", "deleteRule"):
            self.assertIn(f"collection.{rule} = null", self.text, rule)
            self.assertNotIn(f'collection.{rule} = ""', self.text, rule)


class FamiliesCollectionTests(unittest.TestCase):
    def setUp(self):
        matches = list(MIGRATIONS.glob("*_created_families.js"))
        self.assertEqual(1, len(matches), "нужна ровно одна миграция создания families")
        self.text = read(matches[0])
        self.collection = json.loads(sole_match(r"new Collection\((\{.*?\})\);", self.text, re.S))

    def test_collection_is_named_families(self):
        self.assertEqual("families", self.collection["name"])

    def test_public_crud_is_denied(self):
        # PocketBase: null → только суперюзер, "" → кто угодно.
        for rule in ("createRule", "updateRule", "deleteRule"):
            self.assertIsNone(self.collection[rule], rule)

    def test_reads_are_isolated_by_family_membership(self):
        for rule in ("listRule", "viewRule"):
            expression = self.collection[rule]
            self.assertIsNotNone(expression, rule)
            self.assertIn("@request.auth.id != ''", expression, rule)
            self.assertIn("@request.auth.family", expression, rule)

    def test_invite_code_is_stored_hashed_and_hidden(self):
        fields = {field["name"]: field for field in self.collection["fields"]}
        self.assertIn("invite_code_hash", fields, "код приглашения должен храниться хэшем")
        self.assertNotIn("invite_code", fields, "открытый код приглашения не хранится")
        self.assertTrue(fields["invite_code_hash"]["hidden"], "хэш не отдаётся клиентам")
        self.assertTrue(fields["invite_code_hash"]["required"])

    def test_invite_code_hash_is_unique(self):
        indexes = " ".join(self.collection["indexes"])
        self.assertIn("UNIQUE", indexes.upper())
        self.assertIn("invite_code_hash", indexes)

    def test_family_has_owner_relation_to_users(self):
        fields = {field["name"]: field for field in self.collection["fields"]}
        self.assertIn("owner", fields)
        self.assertEqual("relation", fields["owner"]["type"])
        self.assertEqual("_pb_users_auth_", fields["owner"]["collectionId"])


class UsersFamilyRelationTests(unittest.TestCase):
    def setUp(self):
        matches = list(MIGRATIONS.glob("*_users_family_relation.js"))
        self.assertEqual(1, len(matches), "нужна ровно одна миграция users.family")
        self.text = read(matches[0])

    def test_users_get_a_single_family_relation(self):
        self.assertIn('"name": "family"', self.text)
        self.assertIn('"type": "relation"', self.text)
        self.assertIn('"maxSelect": 1', self.text)

    def test_client_cannot_reassign_its_own_family(self):
        # Правило обновления обязано запрещать приход поля family в теле запроса,
        # иначе клиент впишет себе чужой family id и прочитает чужую книгу.
        update_rule = sole_match(r'collection\.updateRule = "([^"]*)"', self.text)
        self.assertIn("@request.body.family:isset = false", update_rule)
        self.assertIn("id = @request.auth.id", update_rule)

    def test_users_are_not_publicly_listable(self):
        for rule in ("listRule", "viewRule"):
            expression = sole_match(rf'collection\.{rule} = "([^"]*)"', self.text)
            self.assertIn("@request.auth.id != ''", expression, rule)
            self.assertIn("family", expression, rule)

    def test_users_cannot_be_deleted_or_created_publicly_without_auth_rules(self):
        self.assertIn("collection.deleteRule = null", self.text)


class FamilyHookContractTests(unittest.TestCase):
    def setUp(self):
        matches = list(HOOKS.glob("*family*.pb.js"))
        self.assertEqual(1, len(matches), "нужен ровно один хук семейной книги")
        self.text = read(matches[0])

    def handler(self, route: str) -> str:
        start = self.text.index(route)
        rest = self.text[start:]
        nxt = rest.find("routerAdd(", 1)
        return rest if nxt == -1 else rest[:nxt]

    def test_create_and_join_routes_exist_under_api_namespace(self):
        self.assertIn('routerAdd("POST", "/api/madre/family/create"', self.text)
        self.assertIn('routerAdd("POST", "/api/madre/family/join"', self.text)

    def test_every_route_requires_authentication(self):
        routes = re.findall(r'routerAdd\("[A-Z]+", "([^"]+)"', self.text)
        self.assertIn("/api/madre/family/create", routes)
        self.assertIn("/api/madre/family/join", routes)
        for route in routes:
            self.assertIn("$apis.requireAuth()", self.handler(route), route)

    def test_invite_code_uses_crypto_random_with_at_least_80_bits(self):
        length = int(sole_match(r"INVITE_CODE_LENGTH = (\d+)", self.text))
        alphabet = sole_match(r'INVITE_CODE_ALPHABET = "([^"]+)"', self.text)
        self.assertEqual(len(set(alphabet)), len(alphabet), "алфавит с повторами режет энтропию")
        entropy_bits = length * (len(alphabet).bit_length() - 1)
        self.assertGreaterEqual(entropy_bits, 80, f"{entropy_bits} бит — код подбирается перебором")
        self.assertIn("$security.randomStringWithAlphabet(", self.text)
        self.assertNotIn("Math.random", self.text)

    def test_ambiguous_characters_are_absent_from_the_alphabet(self):
        alphabet = sole_match(r'INVITE_CODE_ALPHABET = "([^"]+)"', self.text)
        # Crockford base32: I/L/O/U выброшены, 0 и 1 остаются как канонические.
        for char in "ILOU":
            self.assertNotIn(char, alphabet, f"{char} путается при наборе с руки")

    def test_invite_code_is_hashed_with_a_secret_from_environment(self):
        self.assertIn("$security.hs256(", self.text)
        self.assertIn('$os.getenv("MADRE_INVITE_PEPPER")', self.text)
        # Fail-closed: без секрета сервер не должен молча хэшировать пустым ключом.
        self.assertIn("MADRE_INVITE_PEPPER", self.text)
        self.assertRegex(self.text, r"if \(!pepper\)")

    def test_join_never_reveals_whether_a_family_exists(self):
        handler = self.handler("/api/madre/family/join")
        errors = re.findall(r"new BadRequestError\(([^,)]+)", handler)
        self.assertTrue(errors, "join обязан отвечать ошибкой на неверный код")
        self.assertEqual({"JOIN_FAILURE"}, set(error.strip() for error in errors))
        for leak in ("не найдена", "not found", "неверный код", "does not exist", "unknown code"):
            self.assertNotIn(leak.lower(), handler.lower(), leak)

    def test_family_is_assigned_server_side_from_the_authenticated_user(self):
        for route in ("/api/madre/family/create", "/api/madre/family/join"):
            handler = self.handler(route)
            self.assertIn("e.auth", handler, route)
            self.assertNotIn("requestInfo().body.family", handler, route)

    def test_create_and_join_are_transactional(self):
        for route in ("/api/madre/family/create", "/api/madre/family/join"):
            self.assertIn("$app.runInTransaction(", self.handler(route), route)

    def test_a_user_already_in_a_family_cannot_silently_switch(self):
        self.assertIn('e.auth.getString("family")', self.text)


class ProductionEndpointTests(unittest.TestCase):
    def test_api_url_is_https_production_host(self):
        gradle = read(ROOT / "app/build.gradle.kts")
        url = sole_match(r'buildConfigField\("String", "MADRE_API_URL", "\\"([^\\]+)\\""\)', gradle)
        self.assertEqual(f"https://{PRODUCTION_HOST}", url)

    def test_no_source_file_points_at_the_old_lan_address(self):
        offenders = []
        for path in ROOT.rglob("*"):
            if not path.is_file() or "/build/" in str(path) or "/.git/" in str(path):
                continue
            if path.suffix not in (".kt", ".kts", ".xml", ".md", ".html", ".js", ".py"):
                continue
            if path.name == Path(__file__).name:
                continue
            if "192.168.3.59" in read(path):
                offenders.append(str(path.relative_to(ROOT)))
        self.assertEqual([], offenders, "LAN-адрес Cycle 5 остался в исходниках")

    def test_cleartext_traffic_is_denied_everywhere(self):
        config = read(ROOT / "app/src/main/res/xml/network_security_config.xml")
        self.assertNotIn('cleartextTrafficPermitted="true"', config)
        self.assertIn('<base-config cleartextTrafficPermitted="false">', config)
        self.assertNotIn("<domain-config", config, "исключений для отдельных хостов больше нет")

    def test_manifest_keeps_the_hardened_network_config(self):
        manifest = read(ROOT / "app/src/main/AndroidManifest.xml")
        self.assertIn('android:networkSecurityConfig="@xml/network_security_config"', manifest)
        self.assertNotIn('android:usesCleartextTraffic="true"', manifest)


class ShelfOfBooksBackendTests(unittest.TestCase):
    """Cycle 27: полка считает книги по user id, переименование не переписывает
    старые страницы, кадр снимается отдельно от факта выпечки."""

    def test_bake_stats_gain_user_display_name_and_optional_photo(self):
        matches = list(MIGRATIONS.glob("*_bake_stats_shelf.js"))
        self.assertEqual(1, len(matches), "нужна миграция bake_stats для полки")
        text = read(matches[0])
        for field in ("user", "display_name", "family_name", "photo"):
            self.assertIn(f'"name": "{field}"', text, field)
        self.assertIn('"type": "relation"', text)
        self.assertIn('"type": "file"', text)
        self.assertIn("collection.updateRule = null", text)

    def test_family_hook_can_rename_and_leave(self):
        text = read(list(HOOKS.glob("*family*.pb.js"))[0])
        self.assertIn('routerAdd("PATCH", "/api/madre/family/rename"', text)
        self.assertIn('routerAdd("POST", "/api/madre/family/leave"', text)
        rename = text[text.index("/api/madre/family/rename") :]
        self.assertIn("$apis.requireAuth()", rename[: rename.find("routerAdd(", 1)])
        self.assertNotIn("bake_stats", rename[: rename.find("routerAdd(", 1)])

    def test_stats_hook_stamps_user_from_auth_and_has_photo_routes(self):
        text = read(HOOKS / "madre_stats.pb.js")
        self.assertIn('e.record.set("user", e.auth.id)', text)
        self.assertIn('routerAdd("POST", "/api/madre/shelf/photo"', text)
        self.assertIn('routerAdd("POST", "/api/madre/shelf/photo/clear"', text)
        self.assertIn("$apis.requireAuth()", text)

    def test_last_member_leave_keeps_families_row_dormant(self):
        """Cycle 28: last member leaving does not delete the families row.
        The shelf sleeps with invite hash and bake_stats intact.
        """
        text = read(list(HOOKS.glob("*family*.pb.js"))[0])
        leave = text[text.index("/api/madre/family/leave"):]
        self.assertNotIn("txApp.delete(family)", leave)
        self.assertNotIn("txApp.delete(family)", text)


class OfflineLocalBookTests(unittest.TestCase):
    """Локальная книга обязана работать без входа — значит Room-слой не знает
    ни про аккаунт, ни про сеть."""

    def test_room_layer_does_not_depend_on_account_or_network(self):
        forbidden = ("com.polinalinen.madre.account", "data.remote", "MadreApi")
        roots = (
            ROOT / "app/src/main/java/com/polinalinen/madre/data/repository",
            ROOT / "app/src/main/java/com/polinalinen/madre/data/db",
        )
        for root in roots:
            for source in root.rglob("*.kt"):
                text = read(source)
                for needle in forbidden:
                    self.assertNotIn(needle, text, f"{source.name}: {needle}")


class DeploymentDocumentationTests(unittest.TestCase):
    def test_backend_readme_documents_https_only_deployment(self):
        readme = BACKEND / "README.md"
        self.assertTrue(readme.is_file(), "нет backend/README.md")
        text = read(readme)
        for required in (
            PRODUCTION_HOST,
            "pb_migrations",
            "pb_hooks",
            "MADRE_INVITE_PEPPER",
            "https://",
        ):
            self.assertIn(required, text, required)
        self.assertNotIn("http://madre-api", text)


if __name__ == "__main__":
    unittest.main()
