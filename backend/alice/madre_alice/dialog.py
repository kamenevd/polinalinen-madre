"""Диалог навыка. Телефон не трогаем. На долгом WAIT — выход, чтобы колонка могла принять таймер."""

from __future__ import annotations

import time
from typing import Any

from .nlu import Intent, norm, parse
from .recipes import catalog_names, find_recipe_id, load_recipes, spoken_name
from .scale import (
    TIMING_NOTE,
    clamp_portions,
    factor,
    format_count,
    round_weight,
    scaled_ingredient_line,
    scaled_step_text,
    scalable_bindings,
    step_quantities,
    strip_markers,
    total_flour_grams,
    yield_grams,
)

RECIPES = load_recipes()

GREET = (
    "Это голос закваски из книги Мадре. Руки в муке — просто спрашивай. "
    "Назови рецепт: хлебушек, чиабатта, пирожки. Скажи «список рецептов» — прочитаю все одиннадцать. "
    "Можно «на двоих», до пяти порций. Внутри: «дальше», «повтори», «сколько муки». "
    "«Стоп» выключает навык — на телефон это не влияет. Скажи «помощь» — подробнее."
)
HELP = (
    "Пока печём. «Дальше» — следующий шаг. «Что дальше» — только читает следующий, шаг не двигает. "
    "«Повтори» — текущий. «Назад» — на шаг раньше. "
    "«Сколько муки», «сколько воды», «сколько соли» — сначала вес на этом шаге, если тут нет — по всему рецепту. "
    "«Сколько ждать» — время шага, от порций оно не меняется. «Другой рецепт» и «с начала» — переспрошу. "
    "На долгом ожидании выхожу, чтобы можно было поставить таймер на колонке; вернёшься — продолжим с того же шага. "
    "Таймер не ставлю, «Испечено» не отмечаю, полку и фото не публикую — это в телефоне. "
    "«Стоп» — выход из навыка, а не пауза выпечки."
)
NOT_PAUSE = "Паузы выпечки здесь нет. Таймер в телефоне не трогаю. Чтобы выйти — «стоп»."
UNKNOWN = "Не разобрать. Скажи «дальше», «повтори» или «помощь»."
UNKNOWN2 = "Пока не выходит. Команды: «дальше», «повтори», «сколько муки», «сколько ждать»."
STALE_SEC = 24 * 3600
WAIT_EXIT_MIN = 5


def handle(body: dict[str, Any]) -> dict[str, Any]:
    request = body.get("request") or {}
    session = body.get("session") or {}
    state = body.get("state") or {}
    command = str(request.get("command") or "")
    original = str(request.get("original_utterance") or "")
    is_new = bool(session.get("new"))
    sess = dict(state.get("session") or {})
    app = dict(state.get("application") or {})

    if norm(original) == "ping":
        return reply("pong", end=True, application_state=app or None)

    intent = parse(command, original)
    text = norm(command) or norm(original)
    recipe_id = find_recipe_id(text)

    if intent.name == "exit":
        return reply("Выхожу. Таймер в телефоне не трогаю.", end=True, application_state=_app(sess, app))

    if intent.name == "help":
        return reply(HELP, end=False, session_state=sess or {"m": "idle"}, application_state=_app(sess, app), buttons=_idle_buttons())

    if intent.name == "list_recipes":
        return reply(
            catalog_names(RECIPES) + ". Назови любой.",
            session_state=sess or {"m": "idle"},
            application_state=_app(sess, app),
            buttons=_idle_buttons(),
        )

    if intent.name == "timer_unsupported":
        return reply("Таймер не ставлю. Скажи «стоп», потом попроси таймер у колонки.", end=False, session_state=sess or {"m": "idle"})

    if intent.name == "app_only":
        return reply("Это только в телефоне. Здесь читаю рецепт.", session_state=sess or {"m": "idle"}, application_state=_app(sess, app))

    if intent.name == "not_pause":
        return reply(NOT_PAUSE, end=False, session_state=sess or {"m": "idle"}, application_state=_app(sess, app))

    if sess.get("m") == "confirm_stale":
        return _confirm_stale(intent, recipe_id, sess, app)
    if sess.get("m") == "confirm_switch":
        return _confirm_switch(intent, recipe_id, sess, app)
    if sess.get("m") == "confirm_restart":
        return _confirm_restart(intent, sess, app)

    if is_new and app.get("r") and app["r"] in RECIPES and not recipe_id:
        if _is_stale(app) and intent.name not in {"resume"}:
            return _ask_stale(app)
        restored = {
            "m": "bake",
            "r": app["r"],
            "i": int(app.get("i") or 0),
            "p": clamp_portions(int(app.get("p") or 1)),
        }
        if intent.name == "resume" or not text or intent.name == "empty":
            return _resume(app, sess)
        return _in_bake(intent, restored, app)

    if intent.name == "resume":
        return _resume(app, sess)

    if is_new and not text:
        return reply(GREET, buttons=_idle_buttons(), session_state={"m": "idle"})

    if recipe_id:
        return _maybe_start(recipe_id, intent, sess, app, one_shot=is_new and intent.name in {"ingredient", "duration", "outline", "talk"} and intent.name != "talk")

    if intent.name == "talk" and recipe_id is None and sess.get("m") != "bake":
        if is_new and intent.ingredient:
            return reply("Назови рецепт: " + catalog_names(RECIPES) + ".", end=True)
        return reply(UNKNOWN if text else GREET, buttons=_idle_buttons(), session_state=sess or {"m": "idle"})

    if sess.get("m") == "bake":
        return _in_bake(intent, sess, app)

    if intent.name in {"repeat", "peek", "advance", "back", "duration", "ingredient", "outline", "set_portions", "restart"}:
        return reply("Сначала назови рецепт.", buttons=_idle_buttons(), session_state={"m": "idle"})

    if is_new:
        return reply(GREET, end=False, buttons=_idle_buttons(), session_state={"m": "idle"})
    return reply(UNKNOWN, buttons=_idle_buttons(), session_state=sess or {"m": "idle"})


def _maybe_start(recipe_id: str, intent: Intent, sess: dict, app: dict, one_shot: bool) -> dict[str, Any]:
    if sess.get("m") == "bake" and sess.get("r") and sess.get("r") != recipe_id:
        if int(sess.get("i") or 0) < 2:
            return _start_bake(recipe_id, intent.portions or int(sess.get("p") or 1), app)
        sess = {**sess, "m": "confirm_switch", "pr": recipe_id}
        name = spoken_name(RECIPES[sess["r"]])
        other = spoken_name(RECIPES[recipe_id])
        return reply(
            f"Сейчас {name}. Перейти на {other}?",
            session_state=sess,
            application_state=_app(sess, app),
            buttons=[{"title": "Да", "hide": True}, {"title": "Нет", "hide": True}],
        )
    portions = intent.portions or 1
    if one_shot and intent.name == "ingredient":
        return _one_shot_ingredient(recipe_id, intent, portions)
    if one_shot and intent.name == "duration":
        recipe = RECIPES[recipe_id]
        minutes = sum(int(s.get("duration_minutes") or 0) for s in recipe["timeline"])
        return reply(
            f"{spoken_name(recipe)} по книге — {_duration_ru(minutes)}. {TIMING_NOTE}.",
            end=True,
        )
    if one_shot and intent.name == "outline":
        return reply(_outline(RECIPES[recipe_id]), end=True)
    return _start_bake(recipe_id, portions, app)


def _one_shot_ingredient(recipe_id: str, intent: Intent, portions: int) -> dict[str, Any]:
    recipe = RECIPES[recipe_id]
    portions = clamp_portions(portions)
    needle = intent.ingredient or "мук"
    lines = _recipe_ingredient_lines(recipe, portions, needle)
    name = spoken_name(recipe)
    if needle.startswith("мук"):
        flour = total_flour_grams(recipe, portions)
        extra = f" Всего муки {flour} г."
    else:
        extra = ""
    if not lines:
        return reply(f"В {name} этого нет в списке ингредиентов.", end=True)
    return reply(f"{name}, ×{portions}. {'; '.join(lines)}.{extra} {TIMING_NOTE}.", end=True)


def _start_bake(recipe_id: str, portions: int, app: dict) -> dict[str, Any]:
    recipe = RECIPES[recipe_id]
    portions = clamp_portions(portions)
    sess = {"m": "bake", "r": recipe_id, "i": 0, "p": portions}
    y = yield_grams(recipe, portions)
    head = f"{spoken_name(recipe)}, ×{portions}."
    if y:
        head += f" Выход около {y} г теста."
    head += " " + TIMING_NOTE + "."
    return _speak_step(sess, app, prefix=head, from_start=True)


def _in_bake(intent: Intent, sess: dict, app: dict) -> dict[str, Any]:
    recipe = RECIPES[sess["r"]]
    if intent.portions and intent.name in {"set_portions", "talk"}:
        sess = {**sess, "p": clamp_portions(intent.portions)}
        return _speak_step(sess, app, prefix=f"Считаю на ×{sess['p']}.")
    if intent.name == "restart":
        sess = {**sess, "m": "confirm_restart"}
        return reply(
            "Начать этот рецепт с первого шага?",
            session_state=sess,
            buttons=[{"title": "Да", "hide": True}, {"title": "Нет", "hide": True}],
        )
    if intent.name == "switch":
        return reply("Назови другой рецепт.", session_state=sess, application_state=_app(sess, app))
    if intent.name == "repeat" or intent.name == "empty":
        return _speak_step(sess, app)
    if intent.name == "peek":
        return _peek(sess, app)
    if intent.name == "advance" or intent.yes is True:
        return _advance(sess, app)
    if intent.name == "back":
        i = max(0, int(sess.get("i") or 0) - 1)
        sess = {**sess, "i": i}
        return _speak_step(sess, app, prefix="Шаг назад.")
    if intent.name == "duration":
        return _duration_now(sess, app)
    if intent.name == "ingredient":
        return _ingredient_now(sess, app, intent)
    if intent.name == "outline":
        return reply(_outline(recipe), session_state=sess, application_state=_app(sess, app))
    if intent.name == "talk":
        return reply(
            "Скажи «повтори», «дальше», «что дальше» или «сколько муки».",
            session_state=sess,
            application_state=_app(sess, app),
            buttons=_bake_buttons(sess),
        )
    return reply(UNKNOWN, session_state=sess, application_state=_app(sess, app), buttons=_bake_buttons(sess))


def _confirm_switch(intent: Intent, recipe_id: str | None, sess: dict, app: dict) -> dict[str, Any]:
    if intent.yes is False or intent.name == "no":
        sess = {**sess, "m": "bake"}
        sess.pop("pr", None)
        return _speak_step(sess, app, prefix="Остаёмся.")
    target = recipe_id or sess.get("pr")
    if intent.yes is True or intent.name == "yes" or target:
        if not target:
            return reply("Назови рецепт.", session_state=sess)
        return _start_bake(str(target), int(sess.get("p") or 1), app)
    return reply("Да или нет?", session_state=sess)


def _confirm_restart(intent: Intent, sess: dict, app: dict) -> dict[str, Any]:
    if intent.yes is False or intent.name == "no":
        sess = {**sess, "m": "bake"}
        return _speak_step(sess, app, prefix="Продолжаем.")
    if intent.yes is True or intent.name == "yes" or intent.name == "restart":
        sess = {**sess, "m": "bake", "i": 0}
        return _speak_step(sess, app, prefix="С первого шага.")
    return reply("Да или нет?", session_state=sess)


def _resume(app: dict, sess: dict) -> dict[str, Any]:
    rid = app.get("r")
    if not rid or rid not in RECIPES:
        return reply(GREET, buttons=_idle_buttons(), session_state={"m": "idle"})
    sess = {
        "m": "bake",
        "r": rid,
        "i": int(app.get("i") or 0),
        "p": clamp_portions(int(app.get("p") or 1)),
    }
    recipe = RECIPES[rid]
    n = len(recipe["timeline"])
    i = sess["i"]
    prefix = f"Продолжаем {spoken_name(recipe)}, шаг {i + 1} из {n}."
    return _speak_step(sess, app, prefix=prefix, resuming=True)


def _advance(sess: dict, app: dict) -> dict[str, Any]:
    recipe = RECIPES[sess["r"]]
    i = int(sess.get("i") or 0) + 1
    if i >= len(recipe["timeline"]):
        text = (
            f"Это был последний шаг {spoken_name(recipe)}. "
            "Отметить «Испечено» и полку — в телефоне."
        )
        return reply(text, end=True, application_state=_stamp({**sess, "i": i - 1}))
    sess = {**sess, "i": i}
    return _speak_step(sess, app)


def _peek(sess: dict, app: dict) -> dict[str, Any]:
    recipe = RECIPES[sess["r"]]
    i = int(sess.get("i") or 0)
    nxt = i + 1
    if nxt >= len(recipe["timeline"]):
        return reply("Это последний шаг книги.", session_state=sess, application_state=_app(sess, app))
    step = recipe["timeline"][nxt]
    text = (
        f"Следующий, ещё не переходим: {step['title']}. {strip_markers(step.get('description') or '')} "
        f"Мы пока на шаге {i + 1}."
    )
    return reply(_clip(text), session_state=sess, application_state=_app(sess, app), buttons=_bake_buttons(sess))


def _speak_step(sess: dict, app: dict, prefix: str = "", from_start: bool = False, resuming: bool = False) -> dict[str, Any]:
    recipe = RECIPES[sess["r"]]
    i = int(sess.get("i") or 0)
    portions = clamp_portions(int(sess.get("p") or 1))
    sess = {**sess, "p": portions, "m": "bake"}
    steps = recipe["timeline"]
    if i >= len(steps):
        return _advance({**sess, "i": len(steps) - 1}, app)
    step = steps[i]
    n = len(steps)
    scale = factor(portions)
    bindings = scalable_bindings(recipe)
    body = scaled_step_text(step.get("description") or "", scale, bindings)
    title = step.get("title") or ""
    kind = step.get("type")
    minutes = int(step.get("duration_minutes") or 0)
    parts = [p for p in [prefix, f"Шаг {i + 1} из {n}. {title}.", body] if p]
    if kind == "wait" and minutes:
        parts.append("По плану шага — " + _duration_ru(minutes) + ".")
        if resuming:
            parts.append("Если ожидание закончилось — «дальше».")
        elif minutes >= WAIT_EXIT_MIN:
            parts.append(
                "Выхожу, чтобы можно было поставить таймер на колонке. "
                "Потом запусти навык голос закваски — продолжим с этого шага."
            )
            text = " ".join(parts)
            return reply(text, end=True, application_state=_stamp(sess))
        else:
            parts.append("Короткое ожидание — остаюсь.")
    weigh = _step_weigh(step, scale, bindings)
    if weigh:
        parts.append("На весы: " + weigh + ".")
    parts.append("Когда сделаешь — «дальше». Только услышать следующий — «что дальше».")
    return reply(
        " ".join(parts),
        end=False,
        session_state=sess,
        application_state=_stamp(sess),
        buttons=_bake_buttons(sess),
    )


def _duration_now(sess: dict, app: dict) -> dict[str, Any]:
    recipe = RECIPES[sess["r"]]
    step = recipe["timeline"][int(sess["i"])]
    minutes = int(step.get("duration_minutes") or 0)
    text = f"{step['title']}: по плану {_duration_ru(minutes)}. {TIMING_NOTE}."
    return reply(text, session_state=sess, application_state=_app(sess, app), buttons=_bake_buttons(sess))


def _ingredient_now(sess: dict, app: dict, intent: Intent) -> dict[str, Any]:
    recipe = RECIPES[sess["r"]]
    i = int(sess["i"])
    portions = clamp_portions(int(sess.get("p") or 1))
    scale = factor(portions)
    bindings = scalable_bindings(recipe)
    step = recipe["timeline"][i]
    needle = intent.ingredient or "мук"
    qty = step_quantities(step.get("description") or "")
    matched = []
    for amount, unit, section, name in qty:
        if (section, name) not in bindings:
            continue
        if needle and needle not in name.lower().replace("ё", "е"):
            continue
        scaled = f"{format_count(round_weight(amount * scale))} {unit} {name}"
        matched.append(scaled)
    if matched:
        return reply("На этом шаге: " + "; ".join(matched) + ".", session_state=sess, application_state=_app(sess, app))
    lines = _recipe_ingredient_lines(recipe, portions, needle)
    if needle.startswith("мук"):
        flour = total_flour_grams(recipe, portions)
        more = f" Всего в рецепте муки {flour} г."
    else:
        more = ""
    if not lines:
        return reply("На этом шаге этого нет, и в списке рецепта тоже не вижу.", session_state=sess, application_state=_app(sess, app))
    return reply(
        "На этом шаге не кладём. В рецепте: " + "; ".join(lines) + "." + more,
        session_state=sess,
        application_state=_app(sess, app),
    )


def _step_weigh(step: dict, scale: float, bindings: dict) -> str:
    bits = []
    for amount, unit, section, name in step_quantities(step.get("description") or ""):
        if (section, name) not in bindings:
            continue
        bits.append(f"{format_count(round_weight(amount * scale))} {unit} {name}")
    return "; ".join(bits)


def _recipe_ingredient_lines(recipe: dict, portions: int, needle: str) -> list[str]:
    scale = factor(portions)
    lines: list[str] = []
    for items in (recipe.get("ingredients") or {}).values():
        for item in items:
            name = str(item.get("name") or "")
            key = name.lower().replace("ё", "е")
            if needle and needle not in key:
                if needle == "мук" and item.get("is_flour"):
                    pass
                else:
                    continue
            line = scaled_ingredient_line(item, scale)
            if line:
                lines.append(line)
    return lines


def _outline(recipe: dict) -> str:
    titles = [f"{i + 1}. {s['title']}" for i, s in enumerate(recipe["timeline"])]
    return spoken_name(recipe) + ": " + "; ".join(titles) + "."


def _stamp(sess: dict) -> dict:
    return {
        "r": sess["r"],
        "i": int(sess.get("i") or 0),
        "p": clamp_portions(int(sess.get("p") or 1)),
        "ts": int(time.time()),
    }


def _is_stale(app: dict) -> bool:
    ts = int(app.get("ts") or 0)
    if not ts:
        return False
    return (time.time() - ts) > STALE_SEC


def _ask_stale(app: dict) -> dict[str, Any]:
    recipe = RECIPES[app["r"]]
    i = int(app.get("i") or 0)
    sess = {"m": "confirm_stale", "r": app["r"], "i": i, "p": app.get("p") or 1}
    return reply(
        f"В прошлый раз остановились на {spoken_name(recipe)}, шаг {i + 1}. "
        "Продолжить? Скажи «да» или назови другой рецепт.",
        session_state=sess,
        application_state=app,
    )


def _confirm_stale(intent: Intent, recipe_id: str | None, sess: dict, app: dict) -> dict[str, Any]:
    if recipe_id:
        return _start_bake(recipe_id, intent.portions or int(sess.get("p") or 1), app)
    if intent.yes is False or intent.name == "no":
        return reply(GREET, buttons=_idle_buttons(), session_state={"m": "idle"})
    if intent.yes is True or intent.name == "yes" or intent.name == "resume":
        fresh = dict(app)
        fresh["ts"] = int(time.time())
        return _resume(fresh, sess)
    return reply("Скажи «да» или назови рецепт.", session_state=sess, application_state=app)


def _app(sess: dict, app: dict) -> dict:
    if sess.get("r"):
        return _stamp(sess)
    return app


def _idle_buttons() -> list[dict[str, Any]]:
    return [
        {"title": "Хлебушек", "hide": True},
        {"title": "Пирожки", "hide": True},
        {"title": "Помощь", "hide": True},
    ]


def _bake_buttons(sess: dict) -> list[dict[str, Any]]:
    return [
        {"title": "Повтори", "hide": True},
        {"title": "Что дальше", "hide": True},
        {"title": "Дальше", "hide": True},
        {"title": "Сколько муки", "hide": True},
    ]


def _duration_ru(minutes: int) -> str:
    minutes = int(minutes)
    if minutes < 60:
        return f"{minutes} {_num_word(minutes, 'минуту', 'минуты', 'минут')}"
    hours = minutes // 60
    rest = minutes % 60
    h = f"{hours} {_num_word(hours, 'час', 'часа', 'часов')}"
    if rest == 0:
        return h
    return h + f" {rest} {_num_word(rest, 'минуту', 'минуты', 'минут')}"


def _num_word(n: int, one: str, few: str, many: str) -> str:
    n = abs(n) % 100
    if 11 <= n <= 14:
        return many
    n = n % 10
    if n == 1:
        return one
    if 2 <= n <= 4:
        return few
    return many


def _clip(text: str, limit: int = 1024) -> str:
    text = " ".join(text.split())
    if len(text) <= limit:
        return text
    return text[: limit - 1].rstrip() + "…"


def tts_of(text: str) -> str:
    t = text
    repl = {
        "муки": "м+уки",
        "мука": "м+ука",
        "муку": "м+уку",
        "Мадре": "М+адре",
        "опара": "оп+ара",
        "опару": "оп+ару",
        "опары": "оп+ары",
        "чиабатта": "чиаб+атта",
        "фокачча": "фок+ачча",
        "синнабоны": "синнаб+оны",
    }
    for a, b in repl.items():
        t = t.replace(a, b)
    return _clip(t)


def reply(
    text: str,
    *,
    end: bool = False,
    buttons: list[dict[str, Any]] | None = None,
    session_state: dict | None = None,
    application_state: dict | None = None,
) -> dict[str, Any]:
    text = _clip(text)
    out: dict[str, Any] = {
        "response": {
            "text": text,
            "tts": tts_of(text),
            "end_session": bool(end),
        },
        "version": "1.0",
    }
    if buttons:
        out["response"]["buttons"] = buttons
    if session_state is not None:
        out["session_state"] = session_state
    if application_state is not None:
        out["application_state"] = application_state
    return out
