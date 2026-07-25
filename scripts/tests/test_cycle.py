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

    def test_maintenance_cycle_uses_maintenance_branch(self):
        state = self.valid_state()
        state["cycle"]["kind"] = "maintenance"
        state["cycle"]["branch"] = "maintenance/11"
        self.assertEqual([], cycle.validate_state(state))

    def test_unsafe_cycle_version_is_rejected(self):
        state = self.valid_state()
        state["cycle"]["version"] = "5.1.0;unsafe"
        self.assertIn("cycle.version contains unsafe characters", cycle.validate_state(state))

    def test_unknown_fields_are_rejected_without_jsonschema_dependency(self):
        state = self.valid_state()
        state["typo"] = True
        state["cycle"]["stgae"] = "planning"
        errors = cycle.validate_state(state)
        self.assertIn("unknown root field: typo", errors)
        self.assertIn("unknown cycle field: stgae", errors)

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

    def test_runtime_state_path_is_outside_repo(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "runtime"
            path = cycle.runtime_state_path("run-123", root)
            self.assertEqual(root / "runs" / "run-123" / "state.json", path)
            with self.assertRaisesRegex(cycle.CycleError, "invalid run id"):
                cycle.runtime_state_path("../escape", root)

    def test_initialize_runtime_state_binds_manifest_and_base_sha(self):
        manifest = self.valid_state()
        state = cycle.initialize_runtime_state(manifest, "run-123", "a" * 40)
        self.assertEqual("run-123", state["runtime"]["run_id"])
        self.assertEqual("a" * 40, state["runtime"]["base_sha"])
        self.assertEqual(cycle.state_sha256(manifest), state["runtime"]["manifest_sha256"])
        self.assertEqual({}, state["runtime"]["operation_keys"])

    def test_append_event_is_durable_ndjson(self):
        with tempfile.TemporaryDirectory() as tmp:
            journal = Path(tmp) / "events.ndjson"
            cycle.append_event(journal, "init", {"state_sha256": "a" * 64})
            cycle.append_event(journal, "advance", {"stage": "planning"})
            events = [json.loads(line) for line in journal.read_text().splitlines()]
            self.assertEqual(["init", "advance"], [event["action"] for event in events])
            self.assertTrue(all(event["timestamp"].endswith("Z") for event in events))

    def test_lock_is_exclusive(self):
        with tempfile.TemporaryDirectory() as tmp:
            lock_path = Path(tmp) / "workflow.lock"
            with cycle.workflow_lock(lock_path):
                with self.assertRaisesRegex(cycle.CycleError, "already locked"):
                    with cycle.workflow_lock(lock_path, blocking=False):
                        pass


if __name__ == "__main__":
    unittest.main()
