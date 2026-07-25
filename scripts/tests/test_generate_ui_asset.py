import base64
import json
import tempfile
import unittest
from pathlib import Path

from scripts import generate_ui_asset


class UiAssetTests(unittest.TestCase):
    def test_selects_preferred_live_image_model(self):
        models = [
            {"id": "text/model", "architecture": {"output_modalities": ["text"]}},
            {"id": "image/pro", "architecture": {"output_modalities": ["text", "image"]}},
        ]
        self.assertEqual(
            "image/pro",
            generate_ui_asset.select_image_model(models, "image/pro", ["image/fallback"]),
        )

    def test_falls_back_when_preferred_is_not_live(self):
        models = [
            {"id": "image/fallback", "architecture": {"output_modalities": ["image"]}}
        ]
        self.assertEqual(
            "image/fallback",
            generate_ui_asset.select_image_model(models, "missing", ["image/fallback"]),
        )

    def test_rejects_text_only_model(self):
        models = [{"id": "text/model", "architecture": {"output_modalities": ["text"]}}]
        with self.assertRaisesRegex(generate_ui_asset.ImageGenerationError, "no live image model"):
            generate_ui_asset.select_image_model(models, "text/model", [])

    def test_aspect_ratio_mapping(self):
        self.assertEqual("16:9", generate_ui_asset.ASPECT_RATIOS["landscape"])
        self.assertEqual("9:16", generate_ui_asset.ASPECT_RATIOS["portrait"])
        self.assertEqual("1:1", generate_ui_asset.ASPECT_RATIOS["square"])

    def test_decodes_base64_data_url(self):
        raw = b"fake-png"
        url = "data:image/png;base64," + base64.b64encode(raw).decode()
        decoded, mime = generate_ui_asset.decode_image_url(url)
        self.assertEqual(raw, decoded)
        self.assertEqual("image/png", mime)

    def test_provenance_contains_prompt_hash_not_api_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "asset.png"
            output.write_bytes(b"image")
            provenance = generate_ui_asset.make_provenance(
                model="image/pro",
                prompt="warm paper texture",
                aspect_ratio="1:1",
                image_size="2K",
                output=output,
                response_id="generation-1",
            )
            encoded = json.dumps(provenance)
            self.assertNotIn("sk-or", encoded)
            self.assertNotIn("warm paper texture", encoded)
            self.assertEqual(64, len(provenance["prompt_sha256"]))
            self.assertEqual(64, len(provenance["output_sha256"]))

    def test_extracts_image_from_openrouter_shape(self):
        payload = {
            "choices": [{
                "message": {
                    "images": [{"image_url": {"url": "data:image/png;base64,Zm9v"}}]
                }
            }]
        }
        self.assertEqual(
            "data:image/png;base64,Zm9v",
            generate_ui_asset.extract_first_image_url(payload),
        )


if __name__ == "__main__":
    unittest.main()
