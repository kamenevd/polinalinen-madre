"""Strict snapshot ingest. No server rescale. Null never becomes a precise value."""

from __future__ import annotations

from typing import Any

from config import REMAINING_MAX_S
from security import canon_uuid, is_uuid

SNAPSHOT_KEYS = (
    "bake_key",
    "recipe_id",
    "recipe_revision",
    "recipe_name",
    "portions",
    "step_index",
    "step_count",
    "step_title",
    "step_text",
    "next_step_title",
    "next_step_text",
    "quantities",
    "paused",
    "ended",
    "revision",
    "remaining_seconds",
    "captured_at",
    "control_ready",
)

NULLABLE_STRINGS = (
    "recipe_revision",
    "step_text",
    "next_step_title",
    "next_step_text",
    "quantities",
)


class SnapshotError(ValueError):
    pass


def _req_str(data: dict, key: str, limit: int) -> str:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise SnapshotError("bad_field")
    return value


def _null_str(data: dict, key: str, limit: int) -> str | None:
    value = data.get(key)
    if value is None:
        return None
    if not isinstance(value, str) or len(value) > limit:
        raise SnapshotError("bad_field")
    return value


def _req_int(data: dict, key: str) -> int:
    value = data.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise SnapshotError("bad_field")
    return value


def _req_bool(data: dict, key: str) -> bool:
    value = data.get(key)
    if not isinstance(value, bool):
        raise SnapshotError("bad_field")
    return value


def sanitize_snapshot(raw: object, selected_bake: str) -> dict[str, Any]:
    if not isinstance(raw, dict):
        raise SnapshotError("bad_snapshot")
    if set(raw) != set(SNAPSHOT_KEYS):
        raise SnapshotError("bad_snapshot")
    bake_raw = raw.get("bake_key")
    if not isinstance(bake_raw, str) or not is_uuid(bake_raw) or canon_uuid(bake_raw) != canon_uuid(selected_bake):
        raise SnapshotError("bake_mismatch")
    bake = bake_raw
    portions = raw.get("portions")
    portions_unknown = portions is None
    if portions is not None:
        if isinstance(portions, bool) or not isinstance(portions, int):
            raise SnapshotError("bad_field")
        if portions < 1 or portions > 5:
            portions = None
            portions_unknown = True
    recipe_revision = _null_str(raw, "recipe_revision", 128)
    step_text = _null_str(raw, "step_text", 2000)
    next_title = _null_str(raw, "next_step_title", 300)
    next_text = _null_str(raw, "next_step_text", 2000)
    quantities = _null_str(raw, "quantities", 2000)
    step_index = _req_int(raw, "step_index")
    step_count = _req_int(raw, "step_count")
    revision = _req_int(raw, "revision")
    remaining = _req_int(raw, "remaining_seconds")
    captured_at = _req_int(raw, "captured_at")
    if step_index < 0 or step_count < 0 or revision < 0:
        raise SnapshotError("bad_field")
    if remaining < 0:
        remaining = 0
    if remaining > REMAINING_MAX_S:
        remaining = REMAINING_MAX_S
    control_ready = _req_bool(raw, "control_ready")
    trusted = (
        recipe_revision is not None
        and not portions_unknown
        and portions is not None
        and step_count > 0
        and step_index < step_count
        and not _req_bool(raw, "ended")
    )
    if not trusted:
        control_ready = False
        quantities = None
        step_text = None
        next_text = None
    return {
        "bake_key": canon_uuid(bake),
        "recipe_id": _req_str(raw, "recipe_id", 128),
        "recipe_revision": recipe_revision,
        "recipe_name": _req_str(raw, "recipe_name", 300),
        "portions": portions if not portions_unknown else None,
        "step_index": step_index,
        "step_count": step_count,
        "step_title": _req_str(raw, "step_title", 300),
        "step_text": step_text,
        "next_step_title": next_title,
        "next_step_text": next_text,
        "quantities": quantities,
        "paused": _req_bool(raw, "paused"),
        "ended": _req_bool(raw, "ended"),
        "revision": revision,
        "remaining_seconds": remaining,
        "captured_at": captured_at,
        "control_ready": control_ready,
    }


def clock_sane(captured_at: int, now_ms: int, skew_ms: int) -> bool:
    return abs(now_ms - captured_at) <= skew_ms


def project_remaining(
    snap: dict[str, Any],
    *,
    now_ms: int,
    received_at: int,
    freshness_ms: int,
    clock_ok: bool,
) -> int | None:
    """Elapsed projection uses server receipt time. Pause is not decremented. None if untrusted."""
    if not clock_ok:
        return None
    age = now_ms - received_at
    if age < 0 or age > freshness_ms:
        return None
    base = int(snap["remaining_seconds"])
    if snap.get("paused") or snap.get("ended"):
        return base
    elapsed = age // 1000
    return max(0, base - elapsed)
