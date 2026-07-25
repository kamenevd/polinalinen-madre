#!/usr/bin/env python3
"""Evidence-backed state machine for autonomous Madre development cycles."""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

GATE_ORDER = ("plan", "tdd", "build", "review", "visual", "runtime", "release")
STAGE_ORDER = ("backlog", "planning", "implementing", "reviewing", "validating", "releasable", "released")
REQUIRED_GATES = {
    "backlog": (),
    "planning": (),
    "implementing": ("plan",),
    "reviewing": ("plan", "tdd", "build"),
    "validating": ("plan", "tdd", "build", "review"),
    "releasable": ("plan", "tdd", "build", "review", "visual", "runtime"),
    "released": GATE_ORDER,
}
VALID_GATE_STATUSES = {"pending", "pass", "fail"}


class CycleError(ValueError):
    """A fail-closed cycle contract violation."""


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def load_state(path: Path) -> dict[str, Any]:
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CycleError(f"cannot load cycle state: {exc}") from exc
    if not isinstance(state, dict):
        raise CycleError("cycle state root must be an object")
    return state


def save_state(path: Path, state: dict[str, Any]) -> None:
    """Atomically persist JSON (which is valid YAML 1.2)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(state, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp_name, path)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except FileNotFoundError:
            pass
        raise


def _has_evidence(item: Any) -> bool:
    return isinstance(item, str) and bool(item.strip())


def validate_state(state: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    required = {"schema_version", "cycle", "features", "gates", "reviews", "artifacts", "blockers", "decisions"}
    missing = sorted(required - set(state))
    errors.extend(f"missing root field: {field}" for field in missing)
    if missing:
        return errors

    if state.get("schema_version") != 1:
        errors.append("schema_version must be 1")

    meta = state.get("cycle")
    if not isinstance(meta, dict):
        errors.append("cycle must be an object")
        return errors
    number = meta.get("number")
    if not isinstance(number, int) or number < 1:
        errors.append("cycle.number must be a positive integer")
    elif meta.get("branch") != f"cycle/{number}":
        errors.append(f"cycle.branch must be cycle/{number}")
    if meta.get("kind") not in {"feature", "maintenance"}:
        errors.append("cycle.kind must be feature or maintenance")
    if meta.get("stage") not in STAGE_ORDER:
        errors.append(f"cycle.stage must be one of: {', '.join(STAGE_ORDER)}")
    for field in ("version", "created_at", "updated_at"):
        if not isinstance(meta.get(field), str) or not meta[field].strip():
            errors.append(f"cycle.{field} must be a non-empty string")

    features = state.get("features")
    if not isinstance(features, list):
        errors.append("features must be an array")
        features = []
    seen: set[str] = set()
    for index, feature in enumerate(features):
        if not isinstance(feature, dict):
            errors.append(f"feature {index}: must be an object")
            continue
        feature_id = feature.get("id")
        if not isinstance(feature_id, str) or not feature_id:
            errors.append(f"feature {index}: id is required")
        elif feature_id in seen:
            errors.append(f"duplicate feature id: {feature_id}")
        else:
            seen.add(feature_id)
        if not isinstance(feature.get("title"), str) or not feature["title"].strip():
            errors.append(f"feature {feature_id or index}: title is required")
        acceptance = feature.get("acceptance")
        if not isinstance(acceptance, list) or not acceptance or not all(_has_evidence(x) for x in acceptance):
            errors.append(f"feature {feature_id or index}: non-empty acceptance criteria are required")

    gates = state.get("gates")
    if not isinstance(gates, dict):
        errors.append("gates must be an object")
        return errors
    for index, name in enumerate(GATE_ORDER):
        gate = gates.get(name)
        if not isinstance(gate, dict):
            errors.append(f"missing gate: {name}")
            continue
        status = gate.get("status")
        evidence = gate.get("evidence")
        if status not in VALID_GATE_STATUSES:
            errors.append(f"gate {name}: invalid status")
        if not isinstance(evidence, list) or not all(_has_evidence(item) for item in evidence):
            errors.append(f"gate {name}: evidence must be an array of non-empty strings")
            evidence = []
        if status == "pass" and not evidence:
            errors.append(f"gate {name}: pass requires evidence")
        if status == "pass":
            for previous in GATE_ORDER[:index]:
                previous_gate = gates.get(previous, {})
                if previous_gate.get("status") != "pass":
                    errors.append(f"gate {name}: previous gate {previous} is not pass")

    if gates.get("plan", {}).get("status") == "pass" and not features:
        errors.append("plan gate: at least one feature is required")

    for list_name in ("reviews", "blockers", "decisions"):
        if not isinstance(state.get(list_name), list):
            errors.append(f"{list_name} must be an array")
    if not isinstance(state.get("artifacts"), dict):
        errors.append("artifacts must be an object")
    return errors


def _verify_evidence(entries: list[str], repo_root: Path) -> None:
    for entry in entries:
        if entry.startswith("https://"):
            continue
        candidate = (repo_root / entry).resolve()
        try:
            candidate.relative_to(repo_root.resolve())
        except ValueError as exc:
            raise CycleError(f"evidence escapes repository: {entry}") from exc
        if not candidate.exists():
            raise CycleError(f"evidence does not exist: {entry}")


def mark_gate(
    state: dict[str, Any], gate: str, status: str, evidence: list[str], repo_root: Path
) -> dict[str, Any]:
    if gate not in GATE_ORDER:
        raise CycleError(f"unknown gate: {gate}")
    if status not in VALID_GATE_STATUSES:
        raise CycleError(f"invalid gate status: {status}")
    if status == "pass":
        if not evidence:
            raise CycleError(f"gate {gate}: pass requires evidence")
        _verify_evidence(evidence, repo_root)
    updated = copy.deepcopy(state)
    updated["gates"][gate] = {"status": status, "evidence": evidence}
    updated["cycle"]["updated_at"] = utc_now()
    errors = validate_state(updated)
    if errors:
        raise CycleError("; ".join(errors))
    return updated


def advance(state: dict[str, Any], target_stage: str) -> dict[str, Any]:
    if target_stage not in STAGE_ORDER:
        raise CycleError(f"unknown stage: {target_stage}")
    current = state.get("cycle", {}).get("stage")
    if current not in STAGE_ORDER:
        raise CycleError("current stage is invalid")
    if STAGE_ORDER.index(target_stage) <= STAGE_ORDER.index(current):
        raise CycleError(f"stage must move forward from {current}")
    gates = state.get("gates", {})
    for gate in REQUIRED_GATES[target_stage]:
        if gates.get(gate, {}).get("status") != "pass":
            raise CycleError(f"{gate} gate must pass before {target_stage}")
    updated = copy.deepcopy(state)
    updated["cycle"]["stage"] = target_stage
    updated["cycle"]["updated_at"] = utc_now()
    errors = validate_state(updated)
    if errors:
        raise CycleError("; ".join(errors))
    return updated


def add_blocker(state: dict[str, Any], text: str) -> dict[str, Any]:
    if not text.strip():
        raise CycleError("blocker text must not be empty")
    updated = copy.deepcopy(state)
    updated["blockers"].append({"text": text.strip(), "created_at": utc_now(), "resolved": False})
    updated["cycle"]["updated_at"] = utc_now()
    return updated


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=Path("workflow/CYCLE.yaml"))
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    sub.add_parser("validate")
    gate = sub.add_parser("mark-gate")
    gate.add_argument("gate", choices=GATE_ORDER)
    gate.add_argument("status", choices=sorted(VALID_GATE_STATUSES))
    gate.add_argument("evidence", nargs="*")
    stage = sub.add_parser("advance")
    stage.add_argument("stage", choices=STAGE_ORDER)
    blocker = sub.add_parser("add-blocker")
    blocker.add_argument("text")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    state = load_state(args.state)
    repo_root = args.state.resolve().parent.parent
    if args.command == "status":
        print(json.dumps({"cycle": state["cycle"], "gates": state["gates"], "blockers": state["blockers"]}, ensure_ascii=False, indent=2))
        return 0
    if args.command == "validate":
        errors = validate_state(state)
        if errors:
            print("\n".join(f"ERROR: {error}" for error in errors), file=sys.stderr)
            return 1
        print("CYCLE VALID")
        return 0
    if args.command == "mark-gate":
        state = mark_gate(state, args.gate, args.status, args.evidence, repo_root)
    elif args.command == "advance":
        state = advance(state, args.stage)
    elif args.command == "add-blocker":
        state = add_blocker(state, args.text)
    save_state(args.state, state)
    print(f"UPDATED {args.state}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
