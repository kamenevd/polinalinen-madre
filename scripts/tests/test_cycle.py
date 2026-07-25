import json
import tempfile
import unittest
from pathlib import Path

from scripts import cycle


class CycleStateTests(unittest.TestCase):
    def valid_state(self):
        return {
            "schema_version": 1,
            "cycle": {
                "number": 11,
                "kind": "feature",
                "version": "5.1.0-cycle11",
                "branch": "cycle/11",
                "stage": "backlog",
                "created_at": "2026-07-25T00:00:00Z",
                "updated_at": "2026-07-25T00:00:00Z",
            },
            "features": [],
            "gates": {
                name: {"status": "pending", "evidence": []}
                for name in cycle.GATE_ORDER
            },
            "reviews": [],
            "artifacts": {
                "apk": None,
                "source_archive": None,
                "sha256": None,
                "release_url": None,
            },
            "blockers": [],
            "decisions": [],
        }

    def test_valid_initial_state(self):
        self.assertEqual([], cycle.validate_state(self.valid_state()))

    def test_duplicate_feature_ids_are_rejected(self):
        state = self.valid_state()
        state["features"] = [
            {"id": "C11-F1", "title": "A", "acceptance": ["works"]},
            {"id": "C11-F1", "title": "B", "acceptance": ["works"]},
        ]
        self.assertIn("duplicate feature id: C11-F1", cycle.validate_state(state))

    def test_passed_gate_requires_evidence(self):
        state = self.valid_state()
        state["gates"]["plan"]["status"] = "pass"
        self.assertIn("gate plan: pass requires evidence", cycle.validate_state(state))

    def test_gate_cannot_pass_before_previous_gate(self):
        state = self.valid_state()
        state["gates"]["build"] = {"status": "pass", "evidence": ["https://ci/build/1"]}
        errors = cycle.validate_state(state)
        self.assertIn("gate build: previous gate tdd is not pass", errors)

    def test_advance_is_fail_closed(self):
        state = self.valid_state()
        with self.assertRaisesRegex(cycle.CycleError, "plan gate must pass"):
            cycle.advance(state, "implementing")

    def test_advance_to_implementing_after_plan(self):
        state = self.valid_state()
        state["features"] = [
            {"id": "C11-F1", "title": "Feature", "acceptance": ["observable outcome"]}
        ]
        state["gates"]["plan"] = {"status": "pass", "evidence": ["docs/cycle11-plan.md"]}
        advanced = cycle.advance(state, "implementing")
        self.assertEqual("implementing", advanced["cycle"]["stage"])

    def test_mark_gate_rejects_nonexistent_local_evidence(self):
        state = self.valid_state()
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(cycle.CycleError, "evidence does not exist"):
                cycle.mark_gate(state, "plan", "pass", ["missing.md"], Path(tmp))

    def test_atomic_save_round_trip(self):
        state = self.valid_state()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "CYCLE.yaml"
            cycle.save_state(path, state)
            self.assertEqual(state, json.loads(path.read_text()))
            self.assertEqual([], list(path.parent.glob("*.tmp")))


if __name__ == "__main__":
    unittest.main()
