"""Cycle 28: полка переживает уход последнего.

Контракты RED-first для backend/pb_hooks/madre_family.pb.js и миграций полки.
Тесты статические: читают версионированные файлы, живой PocketBase не нужен.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HOOK_PATH = ROOT / "backend" / "pb_hooks" / "madre_family.pb.js"
RULES_MIGRATION = ROOT / "backend" / "pb_migrations" / "1784937780_family_rules_for_stats.js"
SHELF_MIGRATION = ROOT / "backend" / "pb_migrations" / "1787164800_bake_stats_shelf.js"


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
    raise AssertionError("не нашёл парную закрывающую скобку — хук не сбалансирован")


class FamilyLeaveDormantContractTests(unittest.TestCase):
    def setUp(self):
        self.hook = read(HOOK_PATH)
        self.rules_migration = read(RULES_MIGRATION)
        self.shelf_migration = read(SHELF_MIGRATION)

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

    def test_leave_never_deletes_the_family(self):
        leave = self.transaction_body("/api/madre/family/leave")
        self.assertNotIn(
            "txApp.delete(",
            leave,
            "leave не должен удалять запись families: полка должна засыпать, а не исчезать",
        )

    def test_leave_keeps_the_invite_hash(self):
        leave = self.transaction_body("/api/madre/family/leave")
        self.assertNotIn(
            'invite_code_hash',
            leave,
            "leave не должен трогать invite_code_hash: старый код живёт до оживления",
        )

    def test_leave_writes_membership_once(self):
        leave = self.transaction_body("/api/madre/family/leave")
        self.assertEqual(
            1,
            leave.count('member.set("family", "")'),
            "leave должен один раз очищать users.family",
        )

    def test_leave_never_touches_bake_stats(self):
        leave = self.transaction_body("/api/madre/family/leave")
        self.assertNotIn(
            "bake_stats",
            leave,
            "leave не должен править bake_stats: история остаётся как есть",
        )

    def test_owner_transfer_is_deterministic(self):
        leave = self.transaction_body("/api/madre/family/leave")
        self.assertIn(
            '"created,id"',
            leave,
            "поиск кандидатов на нового владельца должен сортироваться по created,id",
        )

    def test_owner_transfer_takes_the_first_of_the_sorted(self):
        leave = self.transaction_body("/api/madre/family/leave")
        query_index = leave.index("findRecordsByFilter(")
        owner_write = leave.index('family.set("owner", others[0].id)')
        self.assertLess(
            query_index,
            owner_write,
            "передача владельца должна брать others[0] после отсортированного запроса",
        )

    def test_no_query_in_the_hook_sorts_by_an_empty_string(self):
        self.assertNotRegex(
            self.hook,
            r'findRecordsByFilter\(\s*"[^"]+"\s*,\s*"[^"]*"\s*,\s*""\s*,',
            "ни один findRecordsByFilter не должен сортироваться пустой строкой",
        )

    def test_join_ownership_write_stays_inside_the_transaction(self):
        join = self.transaction_body("/api/madre/family/join")
        self.assertIn(
            'joined.set("owner", e.auth.id)',
            join,
            "оживление спящей полки должно назначать владельца внутри транзакции join",
        )
        self.assertIn(
            "txApp.save(joined);",
            join,
            "после назначения владельца запись families должна сохраняться в той же транзакции",
        )

    def test_bake_stats_relations_stay_non_cascading(self):
        self.assertIn(
            '"cascadeDelete": false',
            self.rules_migration,
            "поле family в family_rules_for_stats должно оставаться cascadeDelete=false",
        )
        self.assertIn(
            '"cascadeDelete": false',
            self.shelf_migration,
            "поле user в bake_stats_shelf должно оставаться cascadeDelete=false",
        )


if __name__ == "__main__":
    unittest.main()
