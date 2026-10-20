# OpenSpec / Madre runbook

## Канонический корень
LXC108 `/home/claude/projects/madre/openspec`, ветка setup `chore/madre-openspec-20261020`, базовый SHA `fe10334e52b767c0d2499ce430bc695d6fdc918a`.
LXC103 `/root/polinalinen-madre` — старый dirty clone: НЕ использовать и не обновлять автоматически.

## Telegram
`/openspec-propose <идея>` (также `/openspec_propose`) — только план.
`/madre-openspec <задача>` — маршрутизация и контекст.
CLI на 103: `/root/.local/bin/madre-openspec list --json`; wrapper всегда выполняет официальный CLI на 108 от claude в каноническом root. Нет локального fallback при недоступности SSH.
На 108: `cd /home/claude/projects/madre && OPENSPEC_TELEMETRY=0 /home/claude/.local/bin/openspec ...` от claude.
Для чтения/записи артефактов с 103 используй SSH → `pct exec 108` и абсолютные пути; локальные file-tools не пишут в удалённый проект. Не создавай локальный openspec.
Если существующая Telegram-сессия закешировала список skills: `/reload-skills`, без gateway restart и без сброса контекста. Затем команда доступна. Добавление в menu Telegram отдельно не требуется.

## Skills и обновление
Официальные project-local skills созданы `openspec init --tools hermes` на 108. Codex CLI не подключён: provider OAuth не означает CLI.
На 103 external_dirs указывает на `/root/.hermes/work/madre-openspec/runtime-skills` — явную копию skills, не второй набор спецификаций.
Mirror содержит минимальный safety routing header; полные upstream тела сохранены неизменными после header, оригиналы в `upstream-skills/`. После отдельно разрешённого `openspec update` обнови копию и повторно добавь routing header, проверь native resolver. Автосинхронизации нет: спецификации не копируются между машинами, команды идут прямо в 108. SSH-сбой — BLOCKED, не переход на локальную копию.

## Границы
Текущий setup не начинает feature cycle. Действующие product locks и явные решения Димы выше generic workflow. Kanban — статус/исполнение, tasks.md — чеклист; CYCLE.yaml/DESIGN/NEXT-STEPS сохраняются. apply/verify/archive не разрешают приложение, сборки, устройства, QA PASS или публикацию; нужны отдельные разрешения и обязательные независимые gates.

## Backup / rollback
Локальный backup указан в `/root/.hermes/work/madre-openspec/backup-path`; manifest содержит прежние состояния и SHA. На 108 backup `/home/claude/.local/state/madre-openspec-backup` содержит full HEAD bundle, tree и config/absent marker.
Rollback только после проверки отсутствия новых пользовательских изменений: сохранить текущий diff; native `hermes config set skills.external_dirs '[]'` (исходное значение было []); удалить только setup bridge skill/runtime mirror/wrapper; npm uninstall -g @fission-ai/openspec на 103, убрать только setup symlink. На 108 удалить только setup-owned `.hermes/skills/openspec-*`, `openspec/`, `docs/OPENSPEC-MADRE.md`, uninstall package из prefix `/home/claude/.local` от claude; восстановить прежний openspec config из backup или удалить при config.absent. Вернуть 108 на исходный main только если setup commit остаётся вершиной и рабочее дерево чистое, без reset; setup branch сохранить до решения владельца. Не трогать .gitignore/clone103, auth/provider/model или gateway. Не исполнять rollback поверх будущих specs/изменений.

## Проверки и ограничения
Smoke выполняется в отдельной scratch-копии и не создаёт fake change в проекте. Native Telegram-style slash rewrite тестируется без model/API turn; это не свидетельство пользовательской Telegram доставки или выполнения решения моделью.
