# OpenRouter UI asset prompt contract

Use this only when a feature in `workflow/CYCLE.yaml` has `needs_generated_asset: true`.
Generated images are supporting assets, never a substitute for Compose layout or typography.

## Required prompt content

- Describe the exact asset, viewing angle, material, lighting, and intended Android use.
- State the Madre visual language: warm paper, restrained terracotta/ink palette, tactile handmade book, no emoji, no generic glossy app aesthetic.
- For cut-out objects request a pure `#FFFFFF` background and no shadow outside the object.
- For textures request a seamless flat top-down fill with no text or objects.
- Prohibit logos, watermarks, signatures, letters, and numbers unless the feature explicitly needs them.
- Specify safe crop area and requested aspect ratio.

## Gate contract

1. Discover live OpenRouter models before generation.
2. Prefer `google/gemini-3-pro-image-preview`; fall back only through the approved chain in `scripts/generate_ui_asset.py`.
3. Keep the generated image and its `.provenance.json` together.
4. Inspect the actual downloaded binary with two independent vision reviewers.
5. Compare against the prompt line by line; regenerate on any blocking mismatch.
6. Add the image, provenance, reviewer reports, and in-app emulator screenshot to the visual-gate evidence.
7. Record asset license/provenance in the cycle state. Never commit the API key or raw API response.
