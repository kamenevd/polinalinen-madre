# NEXT-STEPS — указатель на текущий цикл

С 25.07.2026 этот файл больше не является очередью автономного агента.
История старого чеклиста остаётся в Git.

Источники без раздвоения ролей:

- `workflow/CYCLE.yaml` — versioned manifest/checkpoint цикла;
- `/var/lib/madre-workflow/runs/<run-id>/state.json` — текущее runtime-состояние;
- соседний `events.ndjson` — append-only аудит переходов;
- `docs/WORKFLOW-V2.md` — правила выполнения;
- `python3 scripts/cycle.py --state <path> status` — человекочитаемый статус;
- `python3 scripts/cycle.py validate` — fail-closed проверка контракта.

Текущий цикл — **Cycle 17** (`maintenance/17`), стадия `implementing`.
Это maintenance после feature-циклов 15 и 16: версии, PocketBase/family sync,
защита данных, документация. Новые декоративные фичи не входят.

Невосстановимые пункты v3 №2/4/5 закрыты решением
`docs/adr/0002-unverifiable-v3-bug-list.md`.
Отсутствующие evidence cycle15/16 — `docs/adr/0003-missing-cycle-evidence.md`.
