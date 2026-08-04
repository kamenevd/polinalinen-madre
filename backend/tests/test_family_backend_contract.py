"""Cycle 13 — гонка членства в «Семейной книге» (backend/pb_hooks).

Security review Cycle 13 нашёл единственный release-blocker: create и join
проверяют e.auth.family ДО транзакции, поэтому два параллельных запроса
проходят проверку оба. Итог — осиротевшая family без владельца у create и
перезаписанное членство (плюс oracle «код подошёл») у join.

Тесты детерминированные и НЕ требуют живого PocketBase: они читают
версионированный хук и доказывают, что повторная проверка членства лежит
ВНУТРИ каждого $app.runInTransaction и ПЕРЕД member.set/save — то есть
именно там, где закрывается гонка, а не только снаружи транзакции.

RED-first: до правки Cycle 13 обе проверки re-check отсутствовали и весь
класс InTransactionMembershipRecheckTests падал.
"""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HOOKS = ROOT / "backend" / "pb_hooks"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def brace_block(text: str, open_index: int) -> str:
    """Возвращает подстроку от открывающей `{` до её парной `}` включительно."""
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


class FamilyHookRaceContractTests(unittest.TestCase):
    def setUp(self):
        matches = list(HOOKS.glob("*family*.pb.js"))
        self.assertEqual(1, len(matches), "нужен ровно один хук семейной книги")
        self.text = read(matches[0])

    def handler(self, route: str) -> str:
        start = self.text.index(route)
        rest = self.text[start:]
        nxt = rest.find("routerAdd(", 1)
        return rest if nxt == -1 else rest[:nxt]

    def transaction_body(self, route: str) -> str:
        """Тело колбэка $app.runInTransaction((txApp) => { ... }) маршрута."""
        handler = self.handler(route)
        anchor = handler.index("$app.runInTransaction(")
        open_brace = handler.index("{", anchor)
        return brace_block(handler, open_brace)


class InTransactionMembershipRecheckTests(FamilyHookRaceContractTests):
    """Ядро блокера: re-check членства обязан жить внутри транзакции."""

    def test_create_rechecks_membership_inside_the_transaction(self):
        body = self.transaction_body("/api/madre/family/create")
        self.assertIn(
            'member.getString("family")',
            body,
            "create не перечитывает членство внутри транзакции — гонка открыта",
        )

    def test_create_recheck_precedes_family_creation_and_membership_write(self):
        body = self.transaction_body("/api/madre/family/create")
        recheck = body.index('member.getString("family")')
        # Проверка обязана стоять до сохранения книги, иначе rollback второго
        # запроса всё равно оставит осиротевшую family без владельца.
        self.assertLess(
            recheck,
            body.index("new Record("),
            "re-check должен идти до создания записи family",
        )
        self.assertLess(
            recheck,
            body.index('member.set("family"'),
            "re-check должен идти до записи членства",
        )

    def test_create_recheck_throws_the_same_generic_error(self):
        body = self.transaction_body("/api/madre/family/create")
        recheck = body.index('member.getString("family")')
        tail = body[recheck:]
        self.assertRegex(
            tail[: tail.index("new Record(")],
            r"throw new BadRequestError\(",
            "re-check create обязан бросать BadRequestError, а не молча продолжать",
        )

    def test_join_rechecks_membership_inside_the_transaction(self):
        body = self.transaction_body("/api/madre/family/join")
        self.assertIn(
            'member.getString("family")',
            body,
            "join не перечитывает членство внутри транзакции — гонка открыта",
        )

    def test_join_recheck_precedes_membership_write(self):
        body = self.transaction_body("/api/madre/family/join")
        self.assertLess(
            body.index('member.getString("family")'),
            body.index('member.set("family"'),
            "re-check join должен идти до записи членства",
        )

    def test_join_recheck_keeps_the_same_join_failure_without_oracle(self):
        body = self.transaction_body("/api/madre/family/join")
        recheck = body.index('member.getString("family")')
        tail = body[recheck : body.index('member.set("family"')]
        thrown = re.findall(r"new BadRequestError\(([^,)]+)", tail)
        self.assertEqual(
            ["JOIN_FAILURE"],
            [error.strip() for error in thrown],
            "re-check join обязан бросать тот же JOIN_FAILURE без утечки-оракула",
        )


class MembershipWriteStaysAfterRecheckTests(FamilyHookRaceContractTests):
    """Регрессия: единственная запись членства должна остаться под защитой."""

    def test_create_writes_membership_exactly_once_after_the_recheck(self):
        body = self.transaction_body("/api/madre/family/create")
        self.assertEqual(
            1,
            body.count('member.set("family"'),
            "членство create пишется ровно один раз",
        )

    def test_join_writes_membership_exactly_once_after_the_recheck(self):
        body = self.transaction_body("/api/madre/family/join")
        self.assertEqual(
            1,
            body.count('member.set("family"'),
            "членство join пишется ровно один раз",
        )


if __name__ == "__main__":
    unittest.main()
