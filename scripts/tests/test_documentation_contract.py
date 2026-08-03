import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class DocumentationContractTests(unittest.TestCase):
    def read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_design_has_exactly_one_heading_per_cycle_with_no_gaps(self):
        text = self.read("DESIGN-V4.md")
        cycles = [int(value) for value in re.findall(r"^## Cycle (\d+)\b", text, re.MULTILINE)]
        # Циклов становится больше со временем, но нумерация обязана оставаться
        # сплошной и без повторов — дубль заголовка означает потерянный раздел.
        self.assertGreaterEqual(len(cycles), 11)
        self.assertEqual(list(range(1, len(cycles) + 1)), cycles)

    def test_design_documents_the_current_cycle(self):
        manifest = json.loads(self.read("workflow/CYCLE.yaml"))
        current = manifest["cycle"]["number"]
        self.assertIn(f"## Cycle {current}", self.read("DESIGN-V4.md"))

    def test_resolved_page_number_is_removed_from_screen_contract(self):
        screen_contract = self.read("DESIGN-V4.md").split("## Решённые вопросы", 1)[0]
        self.assertNotIn("СТР. N", screen_contract)

    def test_next_steps_has_no_legacy_unchecked_queue(self):
        text = self.read("NEXT-STEPS.md")
        self.assertNotIn("- [ ]", text)
        self.assertIn("workflow/CYCLE.yaml", text)

    def test_workflow_document_contains_operational_contract(self):
        text = self.read("docs/WORKFLOW-V2.md")
        for required in (
            "backlog → planning → implementing → reviewing → validating → releasable → released",
            "PLAN → TDD → BUILD → REVIEW → VISUAL → RUNTIME → RELEASE",
            "два feature-цикла → один maintenance-цикл",
            "OpenRouter",
            "gpt-5.6-sol",
            "gpt-5.6-terra",
            "Claude Opus 5",
            "GLM 5.2",
            "MiniMax M3",
            "DeepSeek V4 Pro",
            "/var/lib/madre-workflow",
            "events.ndjson",
            "flock",
        ):
            self.assertIn(required, text)

    def test_adr_index_exists(self):
        self.assertTrue((ROOT / "docs/adr/README.md").is_file())
        self.assertTrue((ROOT / "docs/adr/0001-evidence-backed-cycle-state.md").is_file())
        self.assertTrue((ROOT / "docs/adr/0002-unverifiable-v3-bug-list.md").is_file())


if __name__ == "__main__":
    unittest.main()
