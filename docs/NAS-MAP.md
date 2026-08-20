# Madre on NAS — SSOT

Date: 2026-08-20. If a local folder disagrees, **this file wins** until a doctor pass.

## LXC108 (Codex / Cursor / Claude Code)

You work **only** here:

- Code: `/home/claude/projects/madre`
- Signed current: `/opt/madre-releases/madre-v6.6.0.apk` (and `.aab`, `-manifest.json`)
- Rules: `AGENTS.md` + `CLAUDE.md`

Do not treat `/root/projects/*` on 108 as the app.

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

Public APK: copy to **NAS host** nginx (`/usr/share/nginx/downloads/`), not LXC103 disk. URL `https://kdnfx.space/downloads/madre-v6.6.0.apk`

## RuStore

One pending binary. New submit **archives** the previous pending. No restore.
Live card = API + SHA, never `rustore-v650-final` / `corrected`.
Lexicon: **полка = семья**, **книга = человек**.

## After a release

Skill `release-cycle-doctor`. Sync the new signed APK onto **both** 103 and 108 `/opt/madre-releases/`.
