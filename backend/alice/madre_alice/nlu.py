"""Разбор фразы. Коротко: одно кухонное слово важнее умного чата."""

from __future__ import annotations

import re
from dataclasses import dataclass

WORD_NUM = {
    "одну": 1,
    "один": 1,
    "одна": 1,
    "одной": 1,
    "две": 2,
    "два": 2,
    "двое": 2,
    "двоих": 2,
    "двеих": 2,
    "три": 3,
    "трое": 3,
    "троих": 3,
    "четыре": 4,
    "четверых": 4,
    "четырех": 4,
    "пять": 5,
    "пятерых": 5,
}


def norm(text: str) -> str:
    t = (text or "").lower().replace("ё", "е")
    t = re.sub(r"[«»\"“”]", " ", t)
    t = re.sub(r"[,.!?…;:]+", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    return t


@dataclass(frozen=True)
class Intent:
    name: str
    portions: int | None = None
    ingredient: str | None = None
    yes: bool | None = None


def parse(command: str, original: str = "") -> Intent:
    src = norm(command) or norm(original)
    if norm(original) == "ping" or src == "ping":
        return Intent("ping")
    if not src:
        return Intent("empty")

    if _any(src, "хватит", "выйти", "выйди", "закончить", "закончи", "пока", "до свидания", "вернись", "выход"):
        return Intent("exit")
    if src in {"стоп", "стопе"} or src.startswith("стоп "):
        return Intent("exit")
    if src in {"пауза", "паузу"} or src.startswith("пауза "):
        return Intent("not_pause")
    if _any(src, "поставь таймер", "поставь будильник", "таймер на"):
        return Intent("timer_unsupported")
    if _any(src, "испечено", "на полку", "на полке", "сфотографируй", "фото"):
        return Intent("app_only")
    if _any(src, "список рецептов", "какие рецепты", "какие есть"):
        return Intent("list_recipes")
    if _any(src, "помощь", "что умеешь", "команды", "справка", "как пользоваться"):
        return Intent("help")
    if _any(src, "продолжи", "продолжаем", "я вернулась", "я вернулся", "мы вернулись"):
        return Intent("resume")

    if src in {"да", "ага", "угу", "хорошо", "давай", "подтверждаю", "переходи"}:
        return Intent("yes", yes=True)
    if src in {"нет", "не надо", "отмена", "отмени", "не надо переходить"}:
        return Intent("no", yes=False)

    portions = _portions(src)
    ingredient = _ingredient(src)

    if _any(src, "с начала", "заново", "сначала"):
        return Intent("restart", portions=portions)
    if _any(src, "другой рецепт", "сменить рецепт", "новый рецепт"):
        return Intent("switch")
    if _any(src, "что дальше", "какой следующий", "следующий какой"):
        return Intent("peek")
    if src in {"дальше", "далее", "сделала", "сделал", "сделали", "следующий", "следующий шаг"} or _any(
        src, "переходи дальше", "шаг дальше"
    ):
        return Intent("advance")
    if src in {"назад", "предыдущий", "шаг назад"} or _any(src, "верни шаг"):
        return Intent("back")
    if src in {"повтори", "еще раз", "ещё раз", "что сейчас", "текущий шаг"} or _any(src, "повтори шаг"):
        return Intent("repeat")
    if _any(src, "сколько ждать", "как долго", "сколько времени", "сколько минут", "сколько часов"):
        return Intent("duration")
    if ingredient or _any(src, "сколько муки", "сколько воды", "ингредиенты", "что класть", "состав", "на весы"):
        return Intent("ingredient", ingredient=ingredient or _ingredient(src) or "мук", portions=portions)
    if _any(src, "шаги", "весь рецепт", "оглавление", "план"):
        return Intent("outline")
    if portions is not None and _any(src, "порци", "на двоих", "на троих", "на четверых", "на пятерых", "на одну"):
        return Intent("set_portions", portions=portions)
    if portions is not None and src in {str(portions), *WORD_NUM}:
        return Intent("set_portions", portions=portions)

    return Intent("talk", portions=portions, ingredient=ingredient)


def _portions(src: str) -> int | None:
    m = re.search(r"на\s+(\d+)\s*(порц|человек|семь)", src)
    if m:
        n = int(m.group(1))
        return n if 1 <= n <= 5 else None
    m = re.search(r"\b([1-5])\s*(порц|х)\b", src)
    if m:
        return int(m.group(1))
    m = re.search(r"x\s*([1-5])|×\s*([1-5])", src)
    if m:
        return int(m.group(1) or m.group(2))
    for word, n in WORD_NUM.items():
        if re.search(rf"\bна\s+{word}\b", src) or re.search(rf"\b{word}\s+порц", src):
            return n
        if re.search(rf"\bна\s+{word}\b", src):
            return n
    if "на двоих" in src or "в два раза" in src:
        return 2
    if "на троих" in src:
        return 3
    if "на четверых" in src:
        return 4
    if "на пятерых" in src:
        return 5
    if "на одну" in src or "на одного" in src:
        return 1
    return None


def _ingredient(src: str) -> str | None:
    keys = [
        ("мук", "мук"),
        ("вод", "вод"),
        ("сол", "сол"),
        ("сахар", "сахар"),
        ("масл", "масл"),
        ("закваск", "закваск"),
        ("яиц", "яи"),
        ("яйц", "яи"),
        ("молок", "молок"),
        ("сметан", "сметан"),
        ("творог", "творог"),
        ("чеснок", "чеснок"),
        ("кориц", "кориц"),
    ]
    for needle, tag in keys:
        if needle in src:
            return tag
    return None


def _any(src: str, *needles: str) -> bool:
    return any(n in src for n in needles)
