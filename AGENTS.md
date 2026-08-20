# Madre — правила для Codex и Cursor (LXC108)

Этот каталог `/home/claude/projects/madre` — **единственный код**. Не ходи в `/root/projects/madre`, `/root/madre-c27` и в деревья Геса на LXC103.

Подробная архитектура: `CLAUDE.md`. Этот файл — где жить и чего не трогать.

## Где что на 108

| Что | Путь |
|---|---|
| Код | `/home/claude/projects/madre` (ты здесь) |
| Signed APK текущий | `/opt/madre-releases/madre-v6.6.0.apk` (+ `.aab`, `-manifest.json`) |
| Карта NAS | `docs/NAS-MAP.md` |
| Секреты подписи | `/home/claude/.secrets/madre-signing/` — не копировать наружу, не печатать |
| Витрина RuStore | **не здесь**. Гес на LXC103. Не выдумывать `rustore-v650-*` |

Старые apk в `/opt/madre-releases/` (5.x, 6.0, 6.1) — история, не текущий шип.

## Сейчас (2026-08-20)

Стоп: **6.6.0 (35)** в модерации RuStore. Второй бинарь не собирать «чтобы обновить карточку». Не bump versionCode. Не слать в магазин.

Лексикон UI/копирайта: **полка = семья**, **книга = один человек**. Запрещено: «семейная книга», «общая книга».

## Утверждённый Cursor-first процесс

SSOT: `docs/CURSOR-FIRST-WORKFLOW.md`.

- План: Cursor `claude-opus-5-thinking-high`; фолбэк Claude Code Opus 5.
- План проверяют **все трое**: GLM-5.3 + DeepSeek V4 Pro + GPT-5.6 Sol.
- Код: Cursor `gpt-5.3-codex-xhigh`; фолбэк Codex Spark.
- Код проверяют Grok 4.6 + Cursor Gemini 3.1 Pro.
- `auto` не может быть автором. Автор не судит себя. Гес и судьи Kotlin не правят.

## Gradle

Только если явно попросили. Не гонять длинную сборку параллельно с другими агентами. `versionName` только `X.Y.Z` через `scripts/release_cycle.py prepare-version`.

## Cursor

Читает `CLAUDE.md` сам. Этот `AGENTS.md` — для Codex и для людей. `.cursorrules` указывает сюда.
