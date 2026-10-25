"""Pure K11 renderer. No HTTP client, route, logging or launch side effects."""
from dataclasses import dataclass
from html import escape
import re
from typing import cast
from urllib.parse import quote

PACKAGES = frozenset({"com.polinalinen.madre", "com.polinalinen.madre.qa"})
RUSTORE_URL = "https://www.rustore.ru/catalog/app/com.polinalinen.madre"
MAX_LABEL_CHARS = 256
MAX_HTML_BYTES = 16384


@dataclass(frozen=True)
class InviteResponse:
    status: int
    headers: dict[str, str]
    body: bytes


def render_invite(token: object, preview: object = None, *, package: object,
                  upstream_status: int = 200) -> InviteResponse:
    """Caller selects a trusted package constant, never a query/user input.

    Only an exact two-field successful DTO is rendered. Invalid local tokens
    are indistinguishable from unavailable invites; invalid DTOs fail closed.
    """
    if type(package) is not str or package not in PACKAGES:
        raise ValueError("unsupported launch package")
    canonical = type(token) is str and re.fullmatch(r"[A-Za-z0-9_-]{16}", token) is not None
    status = 404 if not canonical else upstream_status
    if type(status) is not int or status not in (200, 404, 429, 503):
        status = 503
    if status == 200:
        valid = (type(preview) is dict and set(preview) == {"family_name", "inviter_name"}
                 and all(type(value) is str and 0 < len(value) <= MAX_LABEL_CHARS
                         and not any(ord(c) < 32 or ord(c) == 127
                                     or 0xD800 <= ord(c) <= 0xDFFF for c in value)
                         for value in preview.values()))
        if not valid:
            status = 503
    if status == 200:
        token = cast(str, token)
        preview = cast(dict[str, str], preview)
        page = "https://kdnfx.space/madre/f/" + token
        intent = ("intent://kdnfx.space/madre/f/" + token
                  + "#Intent;scheme=https;package=" + package
                  + ";S.browser_fallback_url=" + quote(page, safe="") + ";end")
        content = ("<h1>" + escape(preview["family_name"], quote=True) + "</h1>"
                   + "<p>Вас приглашает " + escape(preview["inviter_name"], quote=True) + "</p>"
                   + '<p><a href="' + escape(intent, quote=True) + '">Открыть в Мадре</a></p>'
                   + '<p><a href="' + RUSTORE_URL + '">Скачать в RuStore</a></p>'
                   + "<p>Установили? Вернитесь и нажмите «Открыть в Мадре».</p>")
    else:
        content = "<h1>" + {
            404: "Приглашение недоступно",
            429: "Слишком много запросов. Попробуйте позже.",
            503: "Сервис временно недоступен. Попробуйте позже.",
        }[status] + "</h1>"
    body = ('<!doctype html><html lang="ru"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            '<title>Приглашение в Мадре</title></head><body>' + content
            + '</body></html>').encode("utf-8")
    assert len(body) <= MAX_HTML_BYTES
    return InviteResponse(status, {
        "Content-Type": "text/html; charset=utf-8",
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer",
        "Content-Security-Policy": "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        "X-Robots-Tag": "noindex, nofollow, noarchive",
        "X-Content-Type-Options": "nosniff",
    }, body)
