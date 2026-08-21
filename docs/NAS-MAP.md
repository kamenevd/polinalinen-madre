# Madre on NAS — SSOT

Date: 2026-08-21. If a local folder disagrees, **this file wins** until the next doctor pass.

## Current release

- Version: **6.6.5 (36)**
- Commit/tag: `5ab5f581bf44a3f978449fe95adba2f9380bc002` / `v6.6.5`
- Signed APK SHA-256: `8f5369e4449805e3d04b4ef14ecab959450cb6947a12c8625c988ae0a6f19dab`
- Signing cert SHA-256: `230aca3b5382e31d5f4350a841b64646ba6ddc09fb08c049ac00f50f719bf8f1`
- Public APK: https://kdnfx.space/downloads/madre-v6.6.5.apk
- GitHub release: https://github.com/kamenevd/polinalinen-madre/releases/tag/v6.6.5
- RuStore: `6.6.5(36)` is **MODERATION**, automatic 100%; public remains `6.4.5(33)` until approval; `6.6.0(35)` is archived.
- **STOP:** do not submit another binary while 6.6.5 is pending.

## LXC108 (Cursor / Claude / builders)

Work **only** here:

- Code: `/home/claude/projects/madre`
- Signed current: `/opt/madre-releases/madre-v6.6.5.apk`
- Release manifest: `/opt/madre-releases/madre-v6.6.5-manifest.json`
- Rules: `AGENTS.md` + `CLAUDE.md`

Do not treat `/root/projects/madre` on 108 as the app.

## LXC103 Гес

| What | Path |
|---|---|
| Ges git mirror | `/root/madre-c27` — not origin |
| Stale clone | `/root/projects/madre` — do not build |
| Workflow pack | `/root/projects/madre-workflow-v2` — scripts, not app |
| Signed canon | `/opt/madre-releases/` |
| Listing notes | `/root/projects/madre-releases/rustore/CURRENT/LISTING.md` |
| Editorial | `/root/madre-book-review/madre-rustore-premium/` |
| RuStore cookies | `/root/.secrets/rustore-console-cookies.json` |
| Signing source | `/root/.secrets/madre-signing/` |

Reach 108: `ssh -o ConnectTimeout=6 root@192.168.3.2 'pct exec 108 -- …'` as `claude`.

Public APK lives on **NAS host** nginx (`/usr/share/nginx/downloads/madre-v6.6.5.apk`), not LXC103 disk.

## RuStore

One pending binary. A new submit **archives** the previous pending. No restore.
Live card = API + SHA, never `rustore-v650-final` / `corrected`.
Lexicon: **полка = семья**, **книга = человек**.

## Post-release doctor 6.6.5

Confirmed blocker for the next patch: on `Испечено`, when shelf sharing is unavailable, the selector is hidden but the internal default remains `PUT_WITH_PHOTO`; Home can request a photo or enqueue shelf sync without a visible choice. Do not alter the pending store binary; fix and test under the next patch version after the current moderation outcome.
