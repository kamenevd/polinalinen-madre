#!/usr/bin/env python3
"""Evidence-backed state machine for autonomous Madre development cycles."""

from __future__ import annotations

import argparse
import copy
import fcntl
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import time
from contextlib import contextmanager
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
DEFAULT_RUNTIME_ROOT = Path(os.environ.get("MADRE_WORKFLOW_HOME", "/var/lib/madre-workflow"))
RUN_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
GIT_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}([0-9a-f]{24})?$")
SAFE_VERSION_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]*$")


class CycleError(ValueError):
    """A fail-closed cycle contract violation."""


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def state_sha256(state: dict[str, Any]) -> str:
    payload = json.dumps(state, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def runtime_state_path(run_id: str, runtime_root: Path = DEFAULT_RUNTIME_ROOT) -> Path:
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise CycleError("invalid run id")
    root = runtime_root.resolve()
    path = (root / "runs" / run_id / "state.json").resolve()
    try:
        path.relative_to(root)
    except ValueError as exc:
        raise CycleError("runtime state escapes runtime root") from exc
    return path


def initialize_runtime_state(
    manifest: dict[str, Any], run_id: str, base_sha: str
) -> dict[str, Any]:
    errors = validate_state(manifest)
    if errors:
        raise CycleError("invalid manifest: " + "; ".join(errors))
    if not RUN_ID_PATTERN.fullmatch(run_id):
        raise CycleError("invalid run id")
    if not GIT_SHA_PATTERN.fullmatch(base_sha):
        raise CycleError("base SHA must be a 40 or 64 character lowercase hex digest")
    state = copy.deepcopy(manifest)
    state["runtime"] = {
        "run_id": run_id,
        "base_sha": base_sha,
        "manifest_sha256": state_sha256(manifest),
        "operation_keys": {},
        "last_safe_checkpoint": "initialized",
    }
    return state


def released_run_candidates(
    runtime_root: Path, keep: int = 20, min_age_days: int = 30, now_epoch: float | None = None
) -> list[Path]:
    if keep < 0 or min_age_days < 0:
        raise CycleError("retention values must be non-negative")
    runs_root = (runtime_root / "runs").resolve()
    if not runs_root.exists():
        return []
    now_value = time.time() if now_epoch is None else now_epoch
    released: list[tuple[float, Path]] = []
    for run_dir in runs_root.iterdir():
        if not run_dir.is_dir() or run_dir.is_symlink() or not RUN_ID_PATTERN.fullmatch(run_dir.name):
            continue
        state_path = run_dir / "state.json"
        if not state_path.is_file():
            continue
        try:
            state = load_state(state_path)
        except (CycleError, OSError, json.JSONDecodeError):
            continue
        if state.get("cycle", {}).get("stage") == "released":
            released.append((state_path.stat().st_mtime, run_dir))
    released.sort(key=lambda item: item[0], reverse=True)
    cutoff = now_value - min_age_days * 86400
    return [run_dir for mtime, run_dir in released[keep:] if mtime <= cutoff]


def prune_released_runs(candidates: list[Path], runtime_root: Path) -> None:
    runs_root = (runtime_root / "runs").resolve()
    for run_dir in candidates:
        resolved = run_dir.resolve()
        try:
            resolved.relative_to(runs_root)
        except ValueError as exc:
            raise CycleError(f"refusing to prune path outside runtime runs: {run_dir}") from exc
        if resolved.parent != runs_root or resolved.is_symlink():
            raise CycleError(f"refusing to prune unsafe run path: {run_dir}")
        shutil.rmtree(resolved)


def append_event(journal: Path, action: str, details: dict[str, Any]) -> None:
    if not action.strip():
        raise CycleError("event action must not be empty")
    journal.parent.mkdir(parents=True, exist_ok=True)
    event = {"timestamp": utc_now(), "action": action, "details": details}
    payload = (json.dumps(event, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    fd = os.open(journal, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
    try:
        os.write(fd, payload)
        os.fsync(fd)
    finally:
        os.close(fd)


@contextmanager
def workflow_lock(path: Path, blocking: bool = True):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("a+", encoding="utf-8")
    try:
        flags = fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB)
        try:
            fcntl.flock(handle.fileno(), flags)
        except BlockingIOError as exc:
            raise CycleError(f"workflow is already locked: {path}") from exc
        yield
    finally:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        finally:
            handle.close()


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
        dir_fd = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
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
    allowed_root = required | {"runtime"}
    errors.extend(f"unknown root field: {field}" for field in sorted(set(state) - allowed_root))
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
    allowed_cycle = {"number", "kind", "version", "branch", "stage", "created_at", "updated_at"}
    errors.extend(f"unknown cycle field: {field}" for field in sorted(set(meta) - allowed_cycle))
    number = meta.get("number")
    kind = meta.get("kind")
    if not isinstance(number, int) or number < 1:
        errors.append("cycle.number must be a positive integer")
    elif kind in {"feature", "maintenance"}:
        prefix = "cycle" if kind == "feature" else "maintenance"
        if meta.get("branch") != f"{prefix}/{number}":
            errors.append(f"cycle.branch must be {prefix}/{number}")
    if kind not in {"feature", "maintenance"}:
        errors.append("cycle.kind must be feature or maintenance")
    if meta.get("stage") not in STAGE_ORDER:
        errors.append(f"cycle.stage must be one of: {', '.join(STAGE_ORDER)}")
    for field in ("version", "created_at", "updated_at"):
        if not isinstance(meta.get(field), str) or not meta[field].strip():
            errors.append(f"cycle.{field} must be a non-empty string")
    version = meta.get("version")
    if isinstance(version, str) and not SAFE_VERSION_PATTERN.fullmatch(version):
        errors.append("cycle.version contains unsafe characters")

    features = state.get("features")
    if not isinstance(features, list):
        errors.append("features must be an array")
        features = []
    seen: set[str] = set()
    for index, feature in enumerate(features):
        if not isinstance(feature, dict):
            errors.append(f"feature {index}: must be an object")
            continue
        allowed_feature = {"id", "title", "acceptance", "needs_generated_asset"}
        errors.extend(
            f"feature {index}: unknown field: {field}" for field in sorted(set(feature) - allowed_feature)
        )
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
    errors.extend(f"unknown gate: {field}" for field in sorted(set(gates) - set(GATE_ORDER)))
    for index, name in enumerate(GATE_ORDER):
        gate = gates.get(name)
        if not isinstance(gate, dict):
            errors.append(f"missing gate: {name}")
            continue
        errors.extend(
            f"gate {name}: unknown field: {field}"
            for field in sorted(set(gate) - {"status", "evidence"})
        )
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
    else:
        allowed_artifacts = {"apk", "source_archive", "sha256", "release_url"}
        errors.extend(
            f"unknown artifacts field: {field}"
            for field in sorted(set(state["artifacts"]) - allowed_artifacts)
        )
    runtime = state.get("runtime")
    if runtime is not None:
        if not isinstance(runtime, dict):
            errors.append("runtime must be an object")
        else:
            allowed_runtime = {
                "run_id", "base_sha", "manifest_sha256", "operation_keys", "last_safe_checkpoint"
            }
            errors.extend(
                f"unknown runtime field: {field}" for field in sorted(set(runtime) - allowed_runtime)
            )
            run_id = runtime.get("run_id")
            if not isinstance(run_id, str) or not RUN_ID_PATTERN.fullmatch(run_id):
                errors.append("runtime.run_id is invalid")
            base_sha = runtime.get("base_sha")
            if not isinstance(base_sha, str) or not GIT_SHA_PATTERN.fullmatch(base_sha):
                errors.append("runtime.base_sha is invalid")
            manifest_hash = runtime.get("manifest_sha256")
            if not isinstance(manifest_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", manifest_hash):
                errors.append("runtime.manifest_sha256 is invalid")
            if not isinstance(runtime.get("operation_keys"), dict):
                errors.append("runtime.operation_keys must be an object")
            checkpoint = runtime.get("last_safe_checkpoint")
            if not isinstance(checkpoint, str) or not checkpoint.strip():
                errors.append("runtime.last_safe_checkpoint must be a non-empty string")
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
    parser.add_argument("--state", type=Path, help="external runtime state path")
    parser.add_argument("--manifest", type=Path, default=Path("workflow/CYCLE.yaml"))
    parser.add_argument("--runtime-root", type=Path, default=DEFAULT_RUNTIME_ROOT)
    sub = parser.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init")
    init.add_argument("--run-id", required=True)
    init.add_argument("--base-sha", required=True)
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
    prune = sub.add_parser("prune-runs")
    prune.add_argument("--keep", type=int, default=20)
    prune.add_argument("--min-age-days", type=int, default=30)
    prune.add_argument("--apply", action="store_true", help="delete candidates; default is dry-run")
    return parser


def _is_within(path: Path, parent: Path) -> bool:
    try:
        path.resolve().relative_to(parent.resolve())
        return True
    except ValueError:
        return False


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    repo_root = Path.cwd().resolve()
    lock_path = args.runtime_root / "locks" / "madre-workflow.lock"

    if args.command == "init":
        manifest = load_state(args.manifest)
        state = initialize_runtime_state(manifest, args.run_id, args.base_sha)
        state_path = runtime_state_path(args.run_id, args.runtime_root)
        with workflow_lock(lock_path, blocking=False):
            if state_path.exists():
                raise CycleError(f"run already exists: {args.run_id}")
            save_state(state_path, state)
            append_event(
                state_path.parent / "events.ndjson",
                "init",
                {
                    "base_sha": args.base_sha,
                    "manifest_sha256": state["runtime"]["manifest_sha256"],
                    "state_sha256": state_sha256(state),
                },
            )
        print(state_path)
        return 0

    if args.command == "prune-runs":
        with workflow_lock(lock_path, blocking=False):
            candidates = released_run_candidates(args.runtime_root, args.keep, args.min_age_days)
            for candidate in candidates:
                print(candidate)
            if args.apply:
                prune_released_runs(candidates, args.runtime_root)
        print(f"PRUNE {'APPLIED' if args.apply else 'DRY-RUN'} {len(candidates)}")
        return 0

    state_path = args.state or args.manifest
    state = load_state(state_path)
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

    if args.state is None:
        raise CycleError("mutating commands require --state outside the Git worktree")
    if _is_within(state_path, repo_root):
        raise CycleError("runtime state must be outside the Git worktree")

    with workflow_lock(lock_path, blocking=False):
        before = state_sha256(state)
        if args.command == "mark-gate":
            state = mark_gate(state, args.gate, args.status, args.evidence, repo_root)
        elif args.command == "advance":
            state = advance(state, args.stage)
        elif args.command == "add-blocker":
            state = add_blocker(state, args.text)
        save_state(state_path, state)
        append_event(
            state_path.parent / "events.ndjson",
            args.command,
            {
                "before_sha256": before,
                "after_sha256": state_sha256(state),
                "stage": state["cycle"]["stage"],
            },
        )
    print(f"UPDATED {state_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
