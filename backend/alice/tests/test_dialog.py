#!/usr/bin/env python3
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from madre_alice.dialog import GREET, handle
from madre_alice.recipes import load_recipes
from madre_alice.scale import (
    factor,
    scalable_bindings,
    scaled_step_text,
    total_flour_grams,
    yield_grams,
)


def req(command="", original=None, new=True, session=None, application=None, message_id=0):
    return {
        "meta": {"locale": "ru-RU", "timezone": "Europe/Moscow", "client_id": "test", "interfaces": {}},
        "request": {
            "command": command,
            "original_utterance": original if original is not None else command,
            "type": "SimpleUtterance",
        },
        "session": {
            "new": new,
            "message_id": message_id,
            "session_id": "s",
            "skill_id": "skill",
            "user_id": "u",
            "application": {"application_id": "app"},
        },
        "state": {"session": session or {}, "application": application or {}},
        "version": "1.0",
    }


def turn(command, prev=None, new=None):
    session = (prev or {}).get("session_state") or {}
    application = (prev or {}).get("application_state") or {}
    is_new = new if new is not None else prev is None
    return handle(req(command, new=is_new, session=session, application=application))


class ScaleTests(unittest.TestCase):
    def setUp(self):
        self.book = load_recipes()
        self.bread = self.book["home_bread"]

    def test_flour_x1_x3(self):
        self.assertEqual(total_flour_grams(self.bread, 1), 610)
        self.assertEqual(total_flour_grams(self.bread, 3), 1830)

    def test_autolyse_bindings_not_stage(self):
        step = self.bread["timeline"][2]
        self.assertEqual(step.get("ingredient_stage"), "sponge")
        text = scaled_step_text(step["description"], factor(3), scalable_bindings(self.bread))
        self.assertIn("900 г", text)
        self.assertIn("1545 г", text)
        self.assertNotIn("{{", text)
        self.assertNotIn("285 г", text)

    def test_times_not_scaled(self):
        self.assertEqual(self.bread["timeline"][1]["duration_minutes"], 600)

    def test_yield_positive(self):
        self.assertGreater(yield_grams(self.bread, 1), 0)


class DialogTests(unittest.TestCase):
    def test_ping(self):
        out = handle(req(command="", original="ping"))
        self.assertTrue(out["response"]["end_session"])
        self.assertEqual(out["response"]["text"], "pong")
        self.assertLessEqual(len(out["response"]["text"]), 1024)
        self.assertEqual(out["version"], "1.0")

    def test_greet(self):
        out = handle(req(""))
        self.assertIn("голос закваски", out["response"]["text"].lower())
        self.assertFalse(out["response"]["end_session"])

    def test_oneshot_flour(self):
        out = handle(req("сколько муки в хлебушке"))
        text = out["response"]["text"]
        self.assertTrue(out["response"]["end_session"])
        self.assertIn("610 г", text)
        self.assertIn("хлебушек", text.lower())

    def test_start_home_bread_action_stays(self):
        out = handle(req("хлебушек"))
        self.assertFalse(out["response"]["end_session"])
        self.assertEqual(out["session_state"]["r"], "home_bread")
        self.assertEqual(out["session_state"]["i"], 0)
        self.assertIn("опар", out["response"]["text"].lower())

    def test_peek_does_not_advance(self):
        a = turn("хлебушек")
        b = turn("что дальше", a, new=False)
        self.assertEqual(b["session_state"]["i"], 0)
        self.assertIn("не переходим", b["response"]["text"].lower())

    def test_advance_to_wait_exits(self):
        a = turn("хлебушек")
        b = turn("дальше", a, new=False)
        self.assertTrue(b["response"]["end_session"])
        self.assertIn("таймер", b["response"]["text"].lower())
        self.assertEqual(b["application_state"]["i"], 1)
        self.assertFalse(b.get("session_state"))

    def test_resume_after_wait(self):
        a = turn("хлебушек")
        b = turn("дальше", a, new=False)
        c = handle(req("", new=True, application=b["application_state"]))
        self.assertIn("продолжаем", c["response"]["text"].lower())
        self.assertEqual(c["session_state"]["i"], 1)

    def test_stop_exits(self):
        a = turn("хлебушек")
        b = turn("стоп", a, new=False)
        self.assertTrue(b["response"]["end_session"])
        self.assertIn("выхожу", b["response"]["text"].lower())
        self.assertNotIn("алиса", b["response"]["tts"].lower())

    def test_exit(self):
        a = turn("хлебушек")
        b = turn("хватит", a, new=False)
        self.assertTrue(b["response"]["end_session"])

    def test_autolyse_weigh_uses_main(self):
        s = turn("хлебушек")
        s = turn("дальше", s, new=False)  # wait, ends
        s = handle(req("дальше", new=True, application=s["application_state"]))
        text = s["response"]["text"]
        self.assertIn("300 г", text)
        self.assertIn("515 г", text)
        self.assertNotIn("95 г", text.split("На весы")[-1] if "На весы" in text else "")

    def test_portions_x3_flour(self):
        out = handle(req("сколько муки в хлебушке на троих"))
        self.assertIn("1830 г", out["response"]["text"])

    def test_limits(self):
        out = handle(req("хлебушек"))
        self.assertLessEqual(len(out["response"]["text"]), 1024)
        self.assertLessEqual(len(out["response"]["tts"]), 1024)

    def test_help(self):
        out = handle(req("помощь"))
        self.assertIn("полк", out["response"]["text"].lower())

    def test_unknown_recipe_question(self):
        out = handle(req("сколько муки"))
        self.assertTrue(out["response"]["end_session"] or "рецепт" in out["response"]["text"].lower())

    def test_json_roundtrip(self):
        out = handle(req("хлебушек"))
        json.dumps(out)


if __name__ == "__main__":
    unittest.main()
