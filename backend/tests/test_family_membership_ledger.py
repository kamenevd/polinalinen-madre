"""Cycle 28: журнал прежнего членства family_members.

Контракты на форму миграции и хука:
- журнал закрыт для API;
- create/join/leave пишут статус явно;
- join принимает решение fail-closed без догадок.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / "backend" / "pb_migrations"
HOOK_PATH = ROOT / "backend" / "pb_hooks" / "madre_family.pb.js"
LEDGER_MIGRATION_PATH = MIGRATIONS / "1787251200_family_members_ledger.js"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def brace_block(text: str, open_index: int) -> str:
    assert text[open_index] == "{", "brace_block ждёт индекс открывающей скобки"
    depth = 0
    for i in range(open_index, len(text)):
        char = text[i]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[open_index : i + 1]
    raise AssertionError("не нашёл парную закрывающую скобку — блок не сбалансирован")


class FamilyMembershipLedgerContracts(unittest.TestCase):
    def setUp(self):
        self.hook = read(HOOK_PATH)
        self.migration = read(LEDGER_MIGRATION_PATH)

    def handler(self, route: str) -> str:
        start = self.hook.index(route)
        rest = self.hook[start:]
        nxt = rest.find("routerAdd(", 1)
        return rest if nxt == -1 else rest[:nxt]

    def transaction_body(self, route: str) -> str:
        handler = self.handler(route)
        anchor = handler.index("$app.runInTransaction(")
        open_brace = handler.index("{", anchor)
        return brace_block(handler, open_brace)

    def field_block(self, name: str) -> str:
        needle = f'"name": "{name}"'
        name_index = self.migration.index(needle)
        open_brace = self.migration.rfind("{", 0, name_index)
        return brace_block(self.migration, open_brace)

    def up_body(self) -> str:
        anchor = self.migration.index("migrate((app) => {")
        open_brace = self.migration.index("{", anchor)
        return brace_block(self.migration, open_brace)

    def down_body(self) -> str:
        anchor = self.migration.index("}, (app) => {")
        open_brace = self.migration.index("{", anchor)
        return brace_block(self.migration, open_brace)

    def try_blocks(self, text: str) -> list[str]:
        blocks: list[str] = []
        for match in re.finditer(r"\btry\s*\{", text):
            open_brace = text.index("{", match.start())
            blocks.append(brace_block(text, open_brace))
        return blocks

    def test_ledger_migration_exists_exactly_once(self):
        matches = list(MIGRATIONS.glob("*family_members*.js"))
        self.assertEqual(1, len(matches), "должна быть ровно одна миграция family_members")
        self.assertIn('"name": "family_members"', self.migration)
        self.assertIn("new Collection({", self.migration)

    def test_ledger_is_closed_for_the_api(self):
        for rule in ("listRule", "viewRule", "createRule", "updateRule", "deleteRule"):
            self.assertIn(
                f'"{rule}": null',
                self.migration,
                f"правило {rule} у family_members должно оставаться null",
            )

    def test_ledger_has_the_unique_family_user_index(self):
        self.assertIn(
            "CREATE UNIQUE INDEX `idx_family_members_family_user` ON `family_members` (`family`, `user`)",
            self.migration,
        )

    def test_ledger_relations_do_not_cascade(self):
        family = self.field_block("family")
        user = self.field_block("user")
        self.assertIn('"cascadeDelete": false', family)
        self.assertIn('"cascadeDelete": false', user)

    def test_ledger_status_pattern_is_exact(self):
        status = self.field_block("status")
        self.assertIn('"pattern": "^(active|left)$"', status)
        self.assertNotIn("\\", status, "в pattern status не должно быть экранированного |")

    def test_backfill_reads_only_users_family(self):
        up = self.up_body()
        self.assertIn('"family != \'\'"', up)
        self.assertIn('row.set("status", "active")', up)
        self.assertNotIn("bake_stats", up, "backfill не должен выводить членство из bake_stats")

    def test_backfill_is_paginated(self):
        up = self.up_body()
        self.assertIn("const PAGE = 500;", up)
        self.assertIn("let offset = 0;", up)
        self.assertIn("offset += PAGE;", up)
        self.assertRegex(
            up,
            r'findRecordsByFilter\(\s*"users"[\s\S]*?PAGE,\s*offset,',
            "backfill должен читать users страницами",
        )
        self.assertNotRegex(
            up,
            r'findRecordsByFilter\(\s*"users"[\s\S]*?,\s*0\s*,\s*offset',
            "backfill не должен использовать нулевой лимит",
        )

    def test_down_migration_removes_the_collection(self):
        down = self.down_body()
        self.assertIn('findCollectionByNameOrId("family_members")', down)
        self.assertIn("app.delete(collection);", down)

    def test_create_writes_the_ledger_inside_the_transaction(self):
        create = self.transaction_body("/api/madre/family/create")
        self.assertIn('"family_members"', create)
        self.assertIn('row.set("status", "active")', create)

    def test_join_reads_the_ledger_before_writing_membership(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertLess(
            join.index('"family_members"'),
            join.index('member.set("family"'),
            "join должен проверить proof до записи users.family",
        )

    def test_join_counts_members_before_writing_membership(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertLess(
            join.index('"family = {:family}"'),
            join.index('member.set("family"'),
            "join должен считать участников до записи users.family",
        )

    def test_join_denies_a_dormant_shelf_without_proof(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertRegex(
            join,
            r"if \(dormant && !proof\)\s*\{\s*throw new BadRequestError\(JOIN_FAILURE, null\);",
        )

    def test_join_denial_reuses_the_same_join_failure(self):
        join = self.transaction_body("/api/madre/family/join")
        thrown = re.findall(r"new BadRequestError\(([^,)]+)", join)
        self.assertTrue(thrown, "в join ожидаются явные BadRequestError")
        self.assertEqual(
            {"JOIN_FAILURE"},
            {item.strip() for item in thrown},
            "join не должен подставлять особые тексты ошибок",
        )

    def test_join_takes_ownership_only_when_dormant(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertRegex(
            join,
            r"if \(dormant\)\s*\{[\s\S]*joined\.set\(\"owner\", e\.auth\.id\)",
        )

    def test_join_rotates_the_invite_hash_on_revival(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertRegex(
            join,
            r"if \(dormant\)\s*\{[\s\S]*joined\.set\(\"invite_code_hash\"",
            "оживление спящей полки должно гасить старый код приглашения",
        )
        response = self.handler("/api/madre/family/join")
        self.assertNotIn(
            "invite_code",
            response[response.index("return e.json"):],
            "join не должен отдавать открытый код приглашения",
        )

    def test_join_upserts_and_never_adds_a_second_row(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertIn('if (proof) {', join)
        self.assertIn('proof.set("status", "active")', join)
        self.assertEqual(
            1,
            join.count("new Record("),
            "join должен создавать новую строку журнала только в ветке без proof",
        )

    def test_leave_marks_the_ledger_left(self):
        leave = self.transaction_body("/api/madre/family/leave")
        self.assertIn('set("status", "left")', leave)
        self.assertNotIn("txApp.delete(", leave)

    def test_ledger_writes_are_not_swallowed(self):
        for route in (
            "/api/madre/family/create",
            "/api/madre/family/join",
            "/api/madre/family/leave",
        ):
            body = self.transaction_body(route)
            for block in self.try_blocks(body):
                self.assertNotIn(
                    'set("status"',
                    block,
                    f"запись журнала в {route} не должна прятаться в try/catch",
                )

    def test_join_count_failure_is_a_denial_not_dormant(self):
        join = self.transaction_body("/api/madre/family/join")
        match = re.search(
            r"try\s*\{\s*current = txApp\.findRecordsByFilter\([\s\S]*?\);\s*\}\s*catch\s*\([^)]*\)\s*\{([\s\S]*?)\}",
            join,
        )
        self.assertIsNotNone(match, "не найден catch для счёта участников")
        catch_body = match.group(1)
        self.assertIn("throw new BadRequestError(JOIN_FAILURE, null);", catch_body)
        self.assertNotRegex(catch_body, r"\bcurrent\s*=")
        self.assertNotIn("dormant", catch_body)

    def test_join_proof_failure_is_a_denial(self):
        join = self.transaction_body("/api/madre/family/join")
        match = re.search(
            r"try\s*\{\s*const rows = txApp\.findRecordsByFilter\([\s\S]*?\"family_members\"[\s\S]*?\);\s*proof = rows[\s\S]*?\}\s*catch\s*\([^)]*\)\s*\{([\s\S]*?)\}",
            join,
        )
        self.assertIsNotNone(match, "не найден catch для чтения proof из журнала")
        catch_body = match.group(1)
        self.assertIn("throw new BadRequestError(JOIN_FAILURE, null);", catch_body)
        self.assertNotRegex(catch_body, r"\bproof\s*=")

    def test_dormant_comes_only_from_a_successful_count(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertEqual(1, len(re.findall(r"\bdormant\s*=", join)))
        self.assertIn("const dormant = current.length === 0;", join)
        for catch_body in re.findall(r"catch\s*\([^)]*\)\s*\{([\s\S]*?)\}", join):
            self.assertNotIn("dormant", catch_body)


if __name__ == "__main__":
    unittest.main()
