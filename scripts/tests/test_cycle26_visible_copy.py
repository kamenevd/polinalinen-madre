import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_ROOT = ROOT / "app" / "src" / "main"
VISIBLE_LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')
DESIGN = ROOT / "DESIGN-V4.md"
PLAN = ROOT / "workflow" / "evidence" / "cycle26" / "plan.md"
REMINDER_WORKER = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "polinalinen"
    / "madre"
    / "notifications"
    / "FeedingReminderWorker.kt"
)
STARTER_DIARY_SCREEN = (
    ROOT
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "polinalinen"
    / "madre"
    / "ui"
    / "screens"
    / "StarterDiaryScreen.kt"
)


def test_no_legacy_baking_phrase_in_user_visible_literals():
    offenders = []
    for path in SOURCE_ROOT.rglob("*"):
        if path.suffix not in {".kt", ".xml"}:
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for literal in VISIBLE_LITERAL.findall(line):
                if "В печи" in literal or "ещё в печи" in literal:
                    offenders.append(f"{path.relative_to(ROOT)}:{line_number}: {literal}")
    assert not offenders, "Legacy user-visible copy remains:\n" + "\n".join(offenders)


def test_cycle26_visible_contracts_stay_current():
    design = DESIGN.read_text(encoding="utf-8")
    plan = PLAN.read_text(encoding="utf-8")
    reminder = REMINDER_WORKER.read_text(encoding="utf-8")
    starter = STARTER_DIARY_SCREEN.read_text(encoding="utf-8")

    design_lower = design.lower()
    plan_lower = plan.lower()
    reminder_lower = reminder.lower()
    starter_lower = starter.lower()

    stale_cycle26 = (
        "nullable-снимок гидратации",
        "пользовательский комментарий о закваске",
        "перекачивание",
        "влажная печь",
        "legacy hydration shown as `не указана`",
    )
    for phrase in stale_cycle26:
        phrase_lower = phrase.lower()
        if phrase_lower in design_lower:
            raise AssertionError(f"Stale cycle26 wording remains in DESIGN-V4.md: {phrase}")
        if phrase_lower in plan_lower:
            raise AssertionError(f"Stale cycle26 wording remains in cycle26 plan: {phrase}")
        if phrase_lower in starter_lower:
            raise AssertionError(f"Stale cycle26 wording remains in StarterDiaryScreen.kt: {phrase}")

    banned = (
        "приблизительный диапазон",
        "последняя явно подтверждена",
        "примерный диапазон",
    )
    for phrase in banned:
        if phrase in design_lower:
            raise AssertionError(f"Stale cycle26 wording remains in DESIGN-V4.md: {phrase}")
        if phrase in plan_lower:
            raise AssertionError(f"Stale cycle26 wording remains in cycle26 plan: {phrase}")

    required_design = (
        "finalhydrationpercent",
        "hydrationpercent",
        "редактируемые удобные",
        "2:1:2",
        "50/100/50",
        "не указана",
        "generatedcomment",
        "три массы",
    )
    for phrase in required_design:
        if phrase not in design_lower:
            raise AssertionError(f"Missing required cycle26 wording in DESIGN-V4.md: {phrase}")

    required_plan = (
        "db baseline for this cycle is schema v8",
        "8 → 9",
        "9 → 10",
        "working schema is v10",
        "finalhydrationpercent",
        "hydrationpercent",
        "final, then legacy",
    )
    for phrase in required_plan:
        if phrase not in plan_lower:
            raise AssertionError(f"Missing required cycle26 contract phrase in plan: {phrase}")

    required_reminder = (
        "2:1:2",
        "50%",
        "50/100/50",
        "4–6°C",
        "3–5",
        "бел",
        "не формула",
    )
    for phrase in required_reminder:
        if phrase not in reminder:
            raise AssertionError(f"Missing required cycle26 reminder guidance phrase: {phrase}")

    required_diary = (
        "2:1:2",
        "50%",
        "50/100/50",
        "4–6°C",
        "3–5",
        "бел",
        "не формула",
        "редактируемые удобные",
    )
    for phrase in required_diary:
        if phrase not in starter:
            raise AssertionError(f"Missing required cycle26 starter diary guidance phrase: {phrase}")
