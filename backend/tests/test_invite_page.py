"""K11 security contracts, parsed as HTML rather than substring-only checks."""
from html.parser import HTMLParser
import unittest
from urllib.parse import quote, unquote
from backend.web.invite_page import (MAX_HTML_BYTES, MAX_LABEL_CHARS, PACKAGES,
                                     RUSTORE_URL, render_invite)

TOKEN = "Abcdef012345_-xy"
DTO = {"family_name": "Семья", "inviter_name": "Дима"}
PACKAGE = "com.polinalinen.madre"


class Page(HTMLParser):
    def __init__(self, body):
        super().__init__(convert_charrefs=True)
        self.tags, self.attrs, self.text = [], [], []
        self.feed(body.decode("utf-8"))
        self.close()

    def handle_starttag(self, tag, attrs):
        self.tags.append(tag)
        self.attrs.append((tag, dict(attrs)))

    def handle_data(self, data):
        self.text.append(data)


class InvitePageTests(unittest.TestCase):
    def render(self, token: object = TOKEN, dto: object = DTO, **kwargs):
        return render_invite(token, dto, package=PACKAGE, **kwargs)

    def test_escaped_labels_are_only_text(self):
        label = '\"><script>alert(1)</script>&\' <img src=https://evil.test>'
        response = self.render(dto=dict.fromkeys(DTO, label))
        page = Page(response.body)
        self.assertEqual(200, response.status)
        self.assertIn(label, page.text)
        self.assertIn("Вас приглашает " + label, page.text)
        self.assertFalse({"script", "img", "iframe", "style", "link"} & set(page.tags))
        self.assertLessEqual(len(response.body), MAX_HTML_BYTES)

    def test_explicit_links_fixed_destinations_and_packages(self):
        for package in PACKAGES:
            response = render_invite(TOKEN, DTO, package=package)
            page = Page(response.body)
            links = [attrs["href"] for tag, attrs in page.attrs if tag == "a"]
            canonical = "https://kdnfx.space/madre/f/" + TOKEN
            self.assertEqual(["intent://kdnfx.space/madre/f/" + TOKEN
                              + "#Intent;scheme=https;package=" + package
                              + ";S.browser_fallback_url=" + quote(canonical, safe="") + ";end",
                              RUSTORE_URL], links)
            self.assertEqual(canonical, unquote(links[0].split("S.browser_fallback_url=")[1][:-4]))
            self.assertTrue(set(page.tags) <= {"html", "head", "meta", "title", "body", "h1", "p", "a"})
            for tag, attrs in page.attrs:
                self.assertFalse(any(key.startswith("on") or key in {"src", "action", "http-equiv"} for key in attrs))
            self.assertNotIn("Location", response.headers)
            self.assertNotIn("Set-Cookie", response.headers)
        for package in (None, [], "com.polinalinen.madre.preview", "evil", PACKAGE + ";end"):
            with self.assertRaisesRegex(ValueError, "^unsupported launch package$"):
                render_invite(TOKEN, DTO, package=package)

    def test_invalid_token_spelling_is_uniform_404_never_normalized(self):
        expected = self.render(upstream_status=404)
        for token in (None, 1, b"Abcdef012345_-xy", "", TOKEN[:-1], TOKEN + "x",
                      TOKEN + "=", " " + TOKEN, TOKEN + "\n", TOKEN + "?x=1",
                      TOKEN + "#fragment", "%41" + TOKEN[1:], "/" + TOKEN[1:], "é" * 16):
            self.assertEqual(expected, self.render(token=token))
        self.assertNotIn(TOKEN.encode(), expected.body)
        self.assertNotIn(DTO["family_name"].encode(), expected.body)

    def test_exact_dto_types_and_bounds_fail_closed(self):
        expected = self.render(upstream_status=503)
        for dto in (None, [], {}, {**DTO, "people": []}, {"family_name": "x"},
                    {**DTO, "inviter_name": 1}, {**DTO, "family_name": None},
                    {**DTO, "family_name": ""}, {**DTO, "family_name": "x" * (MAX_LABEL_CHARS + 1)},
                    {**DTO, "inviter_name": "x\nInjected"},
                    {**DTO, "inviter_name": "\ud800"}):
            self.assertEqual(expected, self.render(dto=dto))
        bounded = self.render(dto=dict.fromkeys(DTO, "😀" * MAX_LABEL_CHARS))
        self.assertEqual(200, bounded.status)
        self.assertLessEqual(len(bounded.body), MAX_HTML_BYTES)

    def test_generic_failures_and_security_headers(self):
        for status in (404, 429, 503):
            response = self.render(dto={"error": TOKEN, **DTO}, upstream_status=status)
            self.assertEqual(status, response.status)
            self.assertEqual(response, self.render(dto=None, upstream_status=status))
            self.assertNotIn(TOKEN.encode(), response.body)
            self.assertNotIn(DTO["family_name"].encode(), response.body)
            self.assertNotIn(DTO["inviter_name"].encode(), response.body)
            self.assertNotIn("a", Page(response.body).tags)
            self.assertEqual("no-store", response.headers["Cache-Control"])
            self.assertEqual("no-referrer", response.headers["Referrer-Policy"])
            self.assertIn("noindex", response.headers["X-Robots-Tag"])
            self.assertIn("default-src 'none'", response.headers["Content-Security-Policy"])
        for status in (302, 401, 500, "200", True):
            self.assertEqual(503, self.render(upstream_status=status).status)
