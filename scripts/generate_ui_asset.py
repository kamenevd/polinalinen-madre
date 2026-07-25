#!/usr/bin/env python3
"""Generate provenance-tracked UI assets through OpenRouter only."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import tempfile
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

API_ROOT = "https://openrouter.ai/api/v1"
DEFAULT_MODEL = "google/gemini-3-pro-image-preview"
FALLBACK_MODELS = (
    "google/gemini-3.1-flash-image-preview",
    "google/gemini-2.5-flash-image",
)
ASPECT_RATIOS = {"landscape": "16:9", "portrait": "9:16", "square": "1:1"}


class ImageGenerationError(RuntimeError):
    """A safe, secret-free image generation failure."""


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def select_image_model(
    models: list[dict[str, Any]], preferred: str, fallbacks: list[str] | tuple[str, ...]
) -> str:
    image_ids = {
        model.get("id")
        for model in models
        if "image" in ((model.get("architecture") or {}).get("output_modalities") or [])
    }
    for candidate in (preferred, *fallbacks):
        if candidate in image_ids:
            return candidate
    raise ImageGenerationError("no live image model from the approved fallback chain")


def decode_image_url(url: str) -> tuple[bytes, str]:
    if not url.startswith("data:image/") or ";base64," not in url:
        raise ImageGenerationError("OpenRouter returned an unsupported image URL")
    header, encoded = url.split(",", 1)
    mime = header[5:].split(";", 1)[0]
    try:
        return base64.b64decode(encoded, validate=True), mime
    except ValueError as exc:
        raise ImageGenerationError("OpenRouter returned invalid base64 image data") from exc


def extract_first_image_url(response: dict[str, Any]) -> str:
    try:
        images = response["choices"][0]["message"]["images"]
        url = images[0]["image_url"]["url"]
    except (KeyError, IndexError, TypeError) as exc:
        error = response.get("error", {}) if isinstance(response, dict) else {}
        message = error.get("message", "response contains no image") if isinstance(error, dict) else "response contains no image"
        raise ImageGenerationError(f"OpenRouter image generation failed: {message}") from exc
    if not isinstance(url, str):
        raise ImageGenerationError("OpenRouter returned a non-string image URL")
    return url


def make_provenance(
    *, model: str, prompt: str, aspect_ratio: str, image_size: str,
    output: Path, response_id: str | None
) -> dict[str, Any]:
    return {
        "schema_version": 1,
        "provider": "openrouter",
        "model": model,
        "prompt_sha256": sha256_bytes(prompt.encode("utf-8")),
        "aspect_ratio": aspect_ratio,
        "image_size": image_size,
        "output": output.name,
        "output_sha256": sha256_bytes(output.read_bytes()),
        "response_id": response_id,
        "generated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }


def _opener(proxy: str | None) -> urllib.request.OpenerDirector:
    handlers: list[Any] = []
    if proxy:
        handlers.append(urllib.request.ProxyHandler({"http": proxy, "https": proxy}))
    return urllib.request.build_opener(*handlers)


def _json_request(
    opener: urllib.request.OpenerDirector, url: str, api_key: str,
    payload: dict[str, Any] | None = None, timeout: int = 150
) -> dict[str, Any]:
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/kamenevd/polinalinen-madre",
        "X-Title": "Madre workflow",
    }
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    try:
        with opener.open(request, timeout=timeout) as response:
            return json.loads(response.read())
    except urllib.error.HTTPError as exc:
        try:
            body = json.loads(exc.read())
            detail = (body.get("error") or {}).get("message", f"HTTP {exc.code}")
        except Exception:
            detail = f"HTTP {exc.code}"
        raise ImageGenerationError(f"OpenRouter request failed: {detail}") from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise ImageGenerationError(f"OpenRouter request failed: {type(exc).__name__}") from exc


def _atomic_bytes(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    except BaseException:
        try:
            os.unlink(temporary)
        except FileNotFoundError:
            pass
        raise


def _atomic_json(path: Path, payload: dict[str, Any]) -> None:
    _atomic_bytes(path, (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))


def generate(
    *, prompt: str, output: Path, api_key: str, preferred_model: str = DEFAULT_MODEL,
    aspect: str = "square", image_size: str = "2K", proxy: str | None = None
) -> tuple[Path, Path]:
    if not prompt.strip():
        raise ImageGenerationError("prompt must not be empty")
    if aspect not in ASPECT_RATIOS:
        raise ImageGenerationError(f"unsupported aspect: {aspect}")
    opener = _opener(proxy)
    catalog = _json_request(opener, f"{API_ROOT}/models", api_key, timeout=30)
    model = select_image_model(catalog.get("data", []), preferred_model, FALLBACK_MODELS)
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "modalities": ["image", "text"],
        "image_config": {"aspect_ratio": ASPECT_RATIOS[aspect], "image_size": image_size},
        "max_tokens": 4096,
    }
    response = _json_request(opener, f"{API_ROOT}/chat/completions", api_key, payload)
    raw, mime = decode_image_url(extract_first_image_url(response))
    if len(raw) < 256 or not mime.startswith("image/"):
        raise ImageGenerationError("generated image payload is implausibly small or invalid")
    _atomic_bytes(output, raw)
    provenance = make_provenance(
        model=model,
        prompt=prompt,
        aspect_ratio=ASPECT_RATIOS[aspect],
        image_size=image_size,
        output=output,
        response_id=response.get("id"),
    )
    provenance_path = output.with_suffix(output.suffix + ".provenance.json")
    _atomic_json(provenance_path, provenance)
    return output, provenance_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prompt-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--aspect", choices=ASPECT_RATIOS, default="square")
    parser.add_argument("--size", choices=("1K", "2K", "4K"), default="2K")
    parser.add_argument("--proxy", default=os.environ.get("OPENROUTER_PROXY"))
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        raise SystemExit("OPENROUTER_API_KEY is required")
    output, provenance = generate(
        prompt=args.prompt_file.read_text(encoding="utf-8"),
        output=args.output,
        api_key=api_key,
        preferred_model=args.model,
        aspect=args.aspect,
        image_size=args.size,
        proxy=args.proxy,
    )
    print(f"GENERATED {output}")
    print(f"PROVENANCE {provenance}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
