"""Порт кухонной математики Мадре: только помеченные веса, времена не трогать."""

from __future__ import annotations

import math
import re
from typing import Any

STEP_QUANTITY = re.compile(r"\{\{([^|{}]+)\|([^:{}]+):([^{}]+)\}\}")
WRITTEN_WEIGHT = re.compile(r"(\d+(?:[.,]\d+)?)\s*(мл|г)")

MIN_PORTIONS = 1
MAX_PORTIONS = 5
NON_DOUGH_SECTIONS = {
    "filling",
    "cream",
    "topping",
    "glaze",
    "frosting",
    "sauce",
    "syrup",
    "decor",
}
ALL_OF_SECTION = "all_of_section"
GRAMS = "г"
MILLILITRES = "мл"
TIMING_NOTE = "Время этапов от количества порций не меняется — тесто зреет столько же"


def clamp_portions(portions: int) -> int:
    return max(MIN_PORTIONS, min(MAX_PORTIONS, int(portions)))


def factor(portions: int) -> float:
    return float(clamp_portions(portions))


def round_weight(value: float) -> float:
    # Java Math.round: половины вверх для положительных.
    rounded = float(math.floor(value + 0.5))
    if rounded == 0.0 and value > 0.0:
        return 1.0
    return rounded


def round_to_half(value: float) -> float:
    rounded = round(value * 2.0) / 2.0
    if rounded == 0.0 and value > 0.0:
        return 0.5
    return rounded


def format_count(value: float) -> str:
    if abs(value - float(int(value))) < 0.001:
        return str(int(value))
    text = f"{value:.1f}".replace(".", ",")
    return text


def grams_value(item: dict[str, Any]) -> float:
    unit = str(item.get("unit") or "")
    if unit == GRAMS:
        return float(item.get("amount") or 0)
    egg = item.get("egg_grams") or item.get("eggGrams")
    if egg is not None:
        return float(egg)
    if unit == MILLILITRES:
        return float(item.get("amount") or 0)
    return 0.0


def is_flour(item: dict[str, Any]) -> bool:
    return bool(item.get("is_flour") or item.get("isFlour"))


def ref_type(item: dict[str, Any]) -> str | None:
    return item.get("ref_type") or item.get("refType")


def ref_section(item: dict[str, Any]) -> str | None:
    return item.get("ref_section") or item.get("refSection")


def scalable_bindings(recipe: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
    out: dict[tuple[str, str], dict[str, Any]] = {}
    for section, items in (recipe.get("ingredients") or {}).items():
        for item in items:
            if not item.get("scalable"):
                continue
            if item.get("egg_grams") is not None or item.get("eggGrams") is not None:
                continue
            if float(item.get("amount") or 0) <= 0:
                continue
            if item.get("unit") not in (GRAMS, MILLILITRES):
                continue
            out[(section, item["name"])] = item
    return out


def scaled_step_text(text: str, scale_factor: float, bindings: dict[tuple[str, str], dict[str, Any]]) -> str:
    def repl(match: re.Match[str]) -> str:
        written = match.group(1).strip()
        section = match.group(2).strip()
        name = match.group(3).strip()
        parsed = _parse_quantity(written, section, name)
        if parsed is None:
            return written
        if (section, name) not in bindings:
            return written
        amount, unit = parsed
        return f"{format_count(round_weight(amount * scale_factor))} {unit}"

    return STEP_QUANTITY.sub(repl, text)


def strip_markers(text: str) -> str:
    return STEP_QUANTITY.sub(lambda m: m.group(1).strip(), text)


def step_quantities(text: str) -> list[tuple[float, str, str, str]]:
    found: list[tuple[float, str, str, str]] = []
    for match in STEP_QUANTITY.finditer(text):
        parsed = _parse_quantity(match.group(1), match.group(2).strip(), match.group(3).strip())
        if parsed is None:
            continue
        amount, unit = parsed
        found.append((amount, unit, match.group(2).strip(), match.group(3).strip()))
    return found


def _parse_quantity(written: str, section: str, name: str) -> tuple[float, str] | None:
    match = WRITTEN_WEIGHT.fullmatch(written.strip())
    if not match or not section or not name:
        return None
    amount = float(match.group(1).replace(",", "."))
    return amount, match.group(2)


def total_flour_grams(recipe: dict[str, Any], portions: int = 1) -> int:
    scale = factor(portions)
    total = 0.0
    for items in (recipe.get("ingredients") or {}).values():
        for item in items:
            if is_flour(item) and item.get("scalable") and ref_type(item) is None:
                total += round_weight(grams_value(item) * scale)
    return int(total)


def own_grams(item: dict[str, Any], scale: float) -> int:
    if not item.get("scalable") or ref_type(item) is not None:
        return 0
    return int(round(grams_value(item) * scale))


def yield_grams(recipe: dict[str, Any], portions: int) -> int:
    scale = factor(portions)
    ingredients = recipe.get("ingredients") or {}
    dough = {k: v for k, v in ingredients.items() if k.lower() not in NON_DOUGH_SECTIONS}
    final = None
    for items in dough.values():
        if any(ref_type(it) is not None for it in items):
            final = items
            break
    if final is None:
        return sum(own_grams(it, scale) for items in dough.values() for it in items)

    total = sum(own_grams(it, scale) for it in final)
    for it in final:
        rt = ref_type(it)
        if rt is None or not it.get("scalable"):
            continue
        if rt != ALL_OF_SECTION:
            total += int(round(grams_value(it) * scale))
            continue
        section = ingredients.get(ref_section(it) or "")
        if not section:
            total += int(round(grams_value(it) * scale))
        else:
            total += sum(own_grams(x, scale) for x in section)
    return total


def scaled_ingredient_line(item: dict[str, Any], scale: float) -> str | None:
    if ref_type(item) == ALL_OF_SECTION:
        return None
    name = item["name"]
    if not item.get("scalable"):
        return name
    egg = item.get("egg_grams") or item.get("eggGrams")
    unit = str(item.get("unit") or "")
    if egg is not None:
        n = round_to_half(float(item.get("amount") or 0) * scale)
        return f"{format_count(n)} {unit} {name}"
    if unit in ("по вкусу",):
        return f"{name} по вкусу"
    amount = round_weight(float(item.get("amount") or 0) * scale)
    return f"{format_count(amount)} {unit} {name}"
