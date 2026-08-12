# Madre Autonomous Development Workflow v2

## Неподвижные правила

- `main` защищён; разработка идёт только в `cycle/N` или `maintenance/N`.
- `workflow/CYCLE.yaml` — versioned cycle manifest и подписываемый checkpoint.
- Канонический runtime state живёт вне worktree: `/var/lib/madre-workflow/runs/<run-id>/state.json`.
- JSON внутри `CYCLE.yaml` намеренный: JSON является валидным YAML 1.2 и читается Python stdlib без скрытой зависимости.
- Ни модель, ни человек не объявляют PASS без проверяемого evidence.
- Автор изменения не является единственным reviewer.
- Debug-подпись никогда не используется для release.
- Ошибка, timeout или рестарт не сбрасывают стадию и не разрешают повторить необратимый шаг.

## State machine

`backlog → planning → implementing → reviewing → validating → releasable → released`

Переходы монотонны. `scripts/cycle.py` запрещает возврат назад и переход, для
которого не прошли обязательные gates. Обновление состояния атомарное; один
оркестратор удерживает `flock` на `/var/lib/madre-workflow/locks/madre-workflow.lock`.

## Quality gates

`PLAN → TDD → BUILD → REVIEW → VISUAL → RUNTIME → RELEASE`

- **PLAN:** уникальные фичи, наблюдаемые acceptance criteria, оценка данных/миграций/риска, ADR для спорных решений.
- **TDD:** для каждого поведения сохранено RED и GREEN evidence. Документация и CI также тестируются контрактами.
- **BUILD:** Python contracts, Gradle unit tests, strict lint и APK build зелёные.
- **REVIEW:** четыре независимых направления — code/lifecycle, design/UX, backend/security, regression/test coverage. Блокирующие замечания закрыты повторным diff-review.
- **VISUAL:** Roborazzi `verifyRoborazziDebug` проверяет versioned golden baselines на PR; emulator screenshots покрывают runtime-сцены. Baseline меняется только отдельным осознанным `recordRoborazziDebug`. Сгенерированные изображения имеют provenance и два независимых vision-review.
- **RUNTIME:** KVM emulator, clean install, smoke/E2E, отсутствие crash/ANR; эмулятор после проверки выключен.
- **RELEASE:** версия и tag совпадают, signing inputs полны, APK/source/SBOM/manifest имеют SHA-256, GitHub Release доступен и проверен скачиванием.

Evidence хранится рядом с external state либо как HTTPS-ссылка на неизменяемый
CI run/release. `events.ndjson` — append-only журнал переходов и хешей. В Git
попадает только проверенный checkpoint/release manifest, не редактируемая рабочая копия.

## Команда моделей

Перед каждым циклом оркестратор получает live `/v1/models` активных провайдеров;
старые названия из памяти не считаются доступностью.

- **gpt-5.6-sol:** главный оркестратор и gatekeeper; собирает evidence, реализует контрольный слой, принимает финальные решения.
- **gpt-5.6-terra:** параллельные Plan/Explore/Regression агенты с холодным контекстом.
- **gpt-5.6-luna:** финальная reflection-проверка против цели и пропущенных рисков.
- **Claude Opus 5:** глубокая реализация и Android code-review непосредственно на LXC108; default подтверждён live-вызовом.
- **GLM 5.2:** архитектура, Kotlin/Gradle, backend/PocketBase и синтез длинного контекста.
- **MiniMax M3:** продуктовая целостность «Живой книги», UX, альтернативные идеи и критика однообразия.
- **DeepSeek V4 Pro:** независимый adversarial review алгоритмов, concurrency, производительности и security.

Для важных решений минимум три разных семейства моделей. Консенсус не заменяет
тест: фактическое исполнение сильнее голосования моделей.

## Визуальная генерация

OpenRouter используется только если у фичи стоит `needs_generated_asset: true`.
Путь: live discovery → approved image chain → binary download → provenance →
двойной vision-review → обработка → screenshot внутри приложения.

Primary: `google/gemini-3-pro-image-preview`. Fallback выбирается только из
`scripts/generate_ui_asset.py`. Raw prompt не хранится в provenance — только
SHA-256; ключ никогда не пишется в репозиторий или лог. Каждый provenance-файл валидируется генератором по fail-closed контракту `workflow/provenance.schema.json` перед atomic write.

Canvas, Compose layout, типографика и обычные иконки не заменяются картинками
«для красоты». Генерация оправдана только для текстур, фотографий или
иллюстраций, которые невозможно качественно получить программно.

## Цикл выполнения

1. Создать ветку из свежего `main`, проверить clean tree и toolchain.
2. Сверить backlog, DESIGN, ADR и уже реализованные механики.
3. Получить разнообразные предложения моделей; отсеять дубли и фичи без ценности.
4. Записать 1–3 фичи, acceptance criteria и риски в `CYCLE.yaml`; инициализировать
   внешний run через `cycle.py init`; пройти PLAN.
5. Для каждой фичи: failing test → подтверждённый RED → минимальная реализация → GREEN → отдельный commit.
6. Выполнить независимое REVIEW; исправления снова проходят тесты и повторный diff-review.
7. Пройти BUILD, VISUAL и RUNTIME на реальных инструментах.
8. Открыть PR; required checks должны пройти на GitHub, не только локально.
9. Merge без обхода branch protection. Подготовить версию одним runner.
10. Создать tag, signed release, SBOM и checksums; скачать и перепроверить опубликованный APK.
11. Зафиксировать RELEASE evidence, закрыть цикл и создать следующий state.

## Recovery и идемпотентность

- После рестарта загрузить внешний `state.json`, перечитать `events.ndjson`, затем
  `cycle.py --state <path> validate` и `status` и сверить Git/CI.
- PASS gate не выполняется повторно без причины; evidence перечитывается.
- Частичный генератор пишет временный файл и делает atomic rename только после успеха.
- Release с существующим tag/manifest не повторяется автоматически.
- Timeout агента означает «неизвестное состояние»: сначала diff/process/artifacts, потом продолжение.
- При несовпадении state и Git работа блокируется, создаётся blocker; состояние не угадывается.
- Runtime retention безопасен по умолчанию: `cycle.py prune-runs` показывает dry-run; `--apply` удаляет только завершённые `released` runs старше 30 дней сверх 20 последних. Активные, повреждённые и неизвестные runs не удаляются.

## Ритм и долг

`два feature-цикла → один maintenance-цикл`

Maintenance проверяет screenshot coverage, accessibility, startup/performance,
Room migrations, PocketBase rules, зависимости, dead code и документацию.
Новая декоративная механика не имеет приоритета над красным gate или миграцией.


## VersionName (product)

**Только pure semver:** `6.4.4`, не `6.4.4-maintenance23`.

- patch residual: `6.4.4` → `6.4.5`
- важное: `6.5.0`
- кардинально новое: `7.0.x`
- git-ветка по-прежнему `cycle/N` или `maintenance/N`
- `versionCode` только растёт; `prepare-version` пишет `versionName` из `CYCLE.yaml`

