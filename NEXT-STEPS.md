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

Следующий цикл — **Cycle 11**, стадия `backlog`. Его фичи нельзя начинать до
прохождения PLAN gate и работы в отдельной ветке `cycle/11`.

Невосстановимые пункты v3 №2/4/5 закрыты решением
`docs/adr/0002-unverifiable-v3-bug-list.md`: исходный реестр отсутствует,
угадывание запрещено.
