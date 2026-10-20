# Madre: короткий цикл, флаги, скриншоты, падения

Единственный источник кода: GitHub `kamenevd/polinalinen-madre`. Каждая задача идёт в своей ветке, через PR, а судья — CI.

## Цикл
`openspec/changes/<id>/proposal.md` → один исполнитель → зелёный CI → 1 ревью → merge → релиз из тега (`release.yml`) → RuStore (`rustore-publish`, сначала `--dry-run`).

## Флаги (маленькие релизы)
- Незаконченную или «юридически висящую» функцию держим в коде за флагом. По умолчанию флаг **выключен**.
- Флаг: `const val` в `FeatureFlags` (или `buildConfigField` для debug/release). Не делаем удалённых конфигов, пока не попросят.
- Release с выключенным флагом идёт в магазин. Включение — отдельный маленький PR.
- Флаг удаляем в течение двух релизов после включения.

## CI-скриншоты
`MainScreensScreenshotTest` снимает главные экраны на эмуляторе (job `Instrumented smoke`). Артефакт: `madre-screenshots`.
Получить: `gh run download <run-id> -n madre-screenshots -R kamenevd/polinalinen-madre`.
Это не golden-проверка: регрессии ловит Roborazzi, а здесь — картинки для человека.

## Падения (AppMetrica)
- SDK `io.appmetrica.analytics:analytics` подключён, но **выключен**, пока не задан секрет репозитория `MADRE_APPMETRICA_API_KEY`.
- Включается только в release-сборке. Геолокация выключена.
- Перед первым релизом с ключом:
  1. создать приложение в appmetrica.yandex.ru и положить API key в секрет;
  2. дописать AppMetrica в политику конфиденциальности и в `privacyDataTypes` RuStore (диагностика / сбои).
- Утренний брифинг: Hermes читает Logs API AppMetrica (`crashes` за сутки, по версии) с OAuth-токеном из `/root/.secrets/appmetrica-api.json` и пишет 1 строку: «💥 N падений в X.Y.Z, топ-1: …».
