"""Exact Russian commands for a linked grant. Ambiguous phrases do nothing."""

from __future__ import annotations

import re
from dataclasses import dataclass

from config import DIALOG_TEXT_MAX, SKILL_NAME
from madre_alice.dialog import tts_of

POLITE_PHRASES = ("будьте добры", "будь добра", "будь добр")
POLITE_TOKENS = {"пожалуйста", "спасибо"}

EXACT = {
    "стоп": "exit",
    "выход": "exit",
    "хватит": "exit",
    "отмена": "exit",
    "да": "yes",
    "подтверждаю отмену": "confirm_cancel",
    "пауза": "pause",
    "поставь на паузу": "pause",
    "поставь паузу": "pause",
    "продолжи": "resume",
    "сними с паузы": "resume",
    "возобнови": "resume",
    "дальше": "advance",
    "следующий шаг": "advance",
    "перейди дальше": "advance",
    "назад": "back",
    "шаг назад": "back",
    "предыдущий шаг": "back",
    "отмени выпечку": "cancel",
    "отменить выпечку": "cancel",
    "отмени сессию": "cancel",
    "что готовится": "what",
    "что печем": "what",
    "что сейчас готовится": "what",
    "повтори": "repeat",
    "повтори шаг": "repeat",
    "текущий шаг": "repeat",
    "что сейчас": "repeat",
    "сколько осталось": "remaining",
    "оставшееся время": "remaining",
    "сколько ждать": "remaining",
    "что дальше": "peek",
    "что в следующем шаге": "peek",
    "какой следующий шаг": "peek",
    "сколько": "quantities",
    "граммовки": "quantities",
    "какие количества": "quantities",
    "сколько класть": "quantities",
    "связать аккаунт": "link",
    "привязать аккаунт": "link",
    "подключить аккаунт": "link",
    "помощь": "help",
    "что умеешь": "help",
    "команды": "help",
    "что получилось": "receipt",
    "ping": "ping",
}

OP_OF = {
    "pause": "PAUSE",
    "resume": "RESUME",
    "advance": "ADVANCE",
    "back": "BACK",
    "cancel": "CANCEL",
}


@dataclass(frozen=True)
class Utterance:
    name: str
    operation: str | None = None


def normalize(text: str) -> str:
    t = (text or "").lower().replace("ё", "е")
    t = t.replace("«", " ").replace("»", " ")
    t = re.sub(r"[\"“”]", " ", t)
    t = re.sub(r"[,.!?…;:]+", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    for phrase in POLITE_PHRASES:
        t = t.replace(phrase, " ")
    tokens = [tok for tok in t.split() if tok not in POLITE_TOKENS]
    return " ".join(tokens).strip()


def classify(text: str) -> Utterance:
    name = EXACT.get(text)
    if name is None:
        return Utterance("unknown")
    return Utterance(name, OP_OF.get(name))


def resolve_pair(command: str, original: str) -> Utterance:
    left = normalize(command)
    right = normalize(original)
    if left and right and left != right:
        a = classify(left)
        b = classify(right)
        if a.name != b.name:
            return Utterance("ambiguous")
        return a
    return classify(left or right)


def clip(text: str, limit: int = DIALOG_TEXT_MAX) -> str:
    text = " ".join((text or "").split())
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "…"


def alice_reply(text: str, *, end: bool = False) -> dict:
    text = clip(text)
    return {
        "response": {
            "text": text,
            "tts": clip(tts_of(text)),
            "end_session": bool(end),
        },
        "version": "1.0",
    }


def linking_reply() -> dict:
    return {"start_account_linking": {}, "version": "1.0"}


def speak_duration(seconds: int) -> str:
    seconds = max(0, int(seconds))
    if seconds < 60:
        return f"{seconds} сек"
    minutes, sec = divmod(seconds, 60)
    if minutes < 60:
        if sec == 0:
            return f"{minutes} мин"
        return f"{minutes} мин {sec} сек"
    hours, minutes = divmod(minutes, 60)
    if minutes == 0:
        return f"{hours} ч"
    return f"{hours} ч {minutes} мин"


def help_text() -> str:
    return (
        f"Это {SKILL_NAME}. Читаю выбранную выпечку на телефоне. "
        "«Что сейчас», «что дальше» только читает и шаг не двигает. "
        "«Сколько осталось», «граммовки» — если телефон их подтвердил. "
        "Пауза, продолжи, дальше, назад и отмена выпечки сперва переспрашивают. "
        "На обычное действие скажи «да». Отмена выпечки — только «подтверждаю отмену». "
        "«Стоп», «выход», «хватит» и «отмена» выходят из навыка и выпечку не отменяют. "
        "Если телефон не ответил, результат неизвестен. Код связки вводится только на телефоне."
    )


def not_sent(detail: str) -> str:
    return f"Не отправлено. {detail}".strip()


def unknown_outcome() -> str:
    return "Результат неизвестен, проверь телефон."


def asserts_renamed_skill(text: str) -> bool:
    low = text.lower().replace("ё", "е")
    if "запусти навык мадре" in low:
        return True
    if "навык мадре" in low or "навык «мадре»" in low:
        return True
    return False
