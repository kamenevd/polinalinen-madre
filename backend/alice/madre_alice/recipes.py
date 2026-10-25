"""Книга рецептов Мадре + голосовые имена."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from . import RECIPES_SHA256

ROOT = Path(__file__).resolve().parent.parent
RECIPES_PATH = ROOT / "recipes.json"

# Длинные алиасы первыми. ASR-ошибки — только очевидные.
ALIASES: list[tuple[str, str]] = [
    ("пампушки с чесноком", "garlic_buns"),
    ("чесночные пампушки", "garlic_buns"),
    ("хлеб семейный", "family_bread"),
    ("семейный хлеб", "family_bread"),
    ("домашний хлеб", "home_bread"),
    ("хлеб домашний", "home_bread"),
    ("хлебушек домашний", "home_bread"),
    ("булочки с корицей", "cinnamon_buns"),
    ("синабоны", "cinnamon_buns"),
    ("синнабонах", "cinnamon_buns"),
    ("синнабонов", "cinnamon_buns"),
    ("синнабоны", "cinnamon_buns"),
    ("пампушках", "garlic_buns"),
    ("пампушек", "garlic_buns"),
    ("пампушки", "garlic_buns"),
    ("хлебушке", "home_bread"),
    ("хлебушка", "home_bread"),
    ("хлебушку", "home_bread"),
    ("хлебушком", "home_bread"),
    ("хлебушек", "home_bread"),
    ("семейном", "family_bread"),
    ("семейного", "family_bread"),
    ("семейный", "family_bread"),
    ("пирожках", "pirozhki"),
    ("пирожков", "pirozhki"),
    ("пирожки", "pirozhki"),
    ("пирожок", "pirozhki"),
    ("беляшах", "belyashi"),
    ("беляшей", "belyashi"),
    ("беляши", "belyashi"),
    ("беляш", "belyashi"),
    ("чиабатте", "ciabatta"),
    ("чиабатты", "ciabatta"),
    ("чиабата", "ciabatta"),
    ("чабатта", "ciabatta"),
    ("чиабатта", "ciabatta"),
    ("фокачче", "focaccia"),
    ("фокача", "focaccia"),
    ("фокаччи", "focaccia"),
    ("фокачча", "focaccia"),
    ("пицце", "pizza"),
    ("пиццы", "pizza"),
    ("пицца", "pizza"),
    ("вафлях", "waffles"),
    ("вафель", "waffles"),
    ("вафли", "waffles"),
    ("вафля", "waffles"),
    ("оладушках", "pancakes"),
    ("оладьях", "pancakes"),
    ("оладушки", "pancakes"),
    ("оладьи", "pancakes"),
    ("оладья", "pancakes"),
]


def load_recipes() -> dict[str, dict[str, Any]]:
    raw = RECIPES_PATH.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != RECIPES_SHA256:
        raise RuntimeError(f"recipes.json sha mismatch: {digest}")
    data = json.loads(raw.decode("utf-8"))
    return {item["id"]: item for item in data["recipes"]}


def spoken_name(recipe: dict[str, Any]) -> str:
    name = recipe["name"]
    return name.replace("«", "").replace("»", "")


def find_recipe_id(text: str) -> str | None:
    for alias, rid in ALIASES:
        if alias in text:
            return rid
    return None


def catalog_names(recipes: dict[str, dict[str, Any]]) -> str:
    order = [
        "home_bread",
        "family_bread",
        "pirozhki",
        "belyashi",
        "ciabatta",
        "focaccia",
        "pizza",
        "waffles",
        "pancakes",
        "cinnamon_buns",
        "garlic_buns",
    ]
    names = [spoken_name(recipes[i]) for i in order if i in recipes]
    if not names:
        return ""
    if len(names) == 1:
        return names[0]
    return ", ".join(names[:-1]) + " или " + names[-1]
