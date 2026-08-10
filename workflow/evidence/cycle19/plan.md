# Cycle 19 plan — polish + release assets

Base: `origin/main` @ d01a11a (v6.2.0-cycle18).
Kind: **feature** (после maintenance/17 и feature/18).

## P0 diagnosis (live)

1. **Hero gone in release** — `isShrinkResources=true` + `getIdentifier("hero_$id")`.
   Source has 11×50–80KB webp; release APK had **2 tiny webp**, no chapter photos.
2. **English section headers** — `else -> section` shows `dough`/`filling`/`cream`/`sponge1`.
3. **«все 11»** — decorative TOC counter, product wants it gone.
4. **Favorites** — DogEar fold; product wants herbarium flowers back (DESIGN Cycle 8 #20,
   intentionally dropped Cycle 13 — restore as mark, not particle budget hog).
5. **Icon** — flat bread face; redesign book+loaf adaptive foreground.
6. **RuStore** — out of APK scope; ship signed AAB + listing pack after gates.

## Features

### A. hero-release-keep
- `RecipeAssets.heroResFor(id)` → explicit `R.drawable.hero_*`
- Keep Context overload deprecated for call-site safety
- Test: 11 ids + unknown null
- Gate: unzip release APK → ≥11 hero-sized webp or resources.arsc refs

### B. russian-ingredient-headers
- `ingredientSectionTitle(key)` shared helper
- Map: sponge, sponge1/2, main, dough, filling, cream (+ topping/glaze spare)
- RecipeDetailScreen uses helper only

### C. toc-and-herbarium
- Remove `все ${recipes.size}` Row; keep single PageLabel «Оглавление»
- `HerbariumMark` Canvas flower; `DogEar` alias
- a11y strings flower-themed
- New `ic_launcher_foreground.xml` (vector, no new PNG density set required for API26+)

## Out of scope
- RuStore console upload (needs Dima developer account session) — prepare AAB + texts
- New recipes / PB schema
- versionCode hand-edit (only `release_cycle.py prepare-version` at ship)

## Tests
- `RecipeAssetsTest` (new)
- Existing Home/Roborazzi must stay green; DogEar alias keeps name

## Risks
- APK size +~0.7MB when heroes kept — acceptable
- Herbarium is static Canvas (no per-frame anim beyond spring) — effect budget OK
