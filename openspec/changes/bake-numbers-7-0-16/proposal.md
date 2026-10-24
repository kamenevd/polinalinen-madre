# 7.0.16 (52) — Номер каждой выпечки

## Приоритет P0 — Huawei, 24.10.2026
Канон: /root/.hermes/work/madre-design/qa-7015/HUAWEI-P0-7.0.16.md, прочитан целиком. Прямая вибрация 25 мс и конечный waveform первичны; app opt-out и политика ОС сохраняются, диагностика не обещает физический отклик. Настоящая QA: R8, debuggable=false, прежние package/ключ/OAuth, встроенный профиль, скрытый локальный JankStats. Доказательства: реальные platform fixtures, точный CI, проверка бинарного APK/mapping/профиля и сопоставимые traces до/после. B1–B6 и миграции сохраняются; физическая приёмка Huawei остаётся владельцу. Новый проверенный бэкап: /root/.hermes/work/madre-design/worker-backups/20261024-7016-huawei-p0-183453/source-before.bundle. Родитель выполняет одно review и доставку.

## Зачем
Реальный парный run40113600137 сохранён целиком: пакетирование точек не ускорило Печь/Оглавление (CPU p95 около458/451 мс на software AVD), глава изменилась мало. Это не приёмка текущего head. Теперь неизменное зерно растеризуется один раз в draw cache (тот же seed, координаты, радиус, цвет), на кадр остаётся одна картинка; выделение ограничено, большие поверхности имеют прежний fallback. Требуется новый парный замер. Перед изменением проверен source-before-raster.bundle на7215d39; исходные raw45 traces сохранены lossless ZIP в review-fix backup, не переименованы в новые результаты.

Единственное Astra review 2880abb: REQUEST_CHANGES. Исправление — конкретный PlannedBake[] вместо anonymous TypeToken, ошибка чтения блокирует put/remove/nonce и backfill без изменения исходных prefs. Проверка: negative-before 2880abb и positive-after на настоящих minified QA, legacy JSON15, новый план, отдельный процесс повторного чтения и неизменность всех prefs при ошибке. Других anonymous TypeToken в main нет; конкретные Gson DTO в model/account/remote сохранены, добавлены недостающие keep для VoiceIdentity/VoiceCommand/VoiceOperation. Тапы явно TOUCH: VibrationAttributes на API33+, AudioAttributes sonification на API26–32. Системная touch-политика не обходится и показана в диагностике; физический Huawei не принят. Новый бэкап с проверенным изолированным восстановлением: /root/.hermes/work/madre-design/worker-backups/20261024-7016-review-fix-210221/source-before.bundle. Второго review не будет.

Текущая выпечка получает короткий стабильный номер 1–9 для всех Android-поверхностей и голосовых подсказок. Внутренний sessionId не является пользовательским номером.

## Объём
B1–B6 из BAKE-NUMBERS.md и досок J: наименьший свободный номер, мягкий карантин 30 минут, предел девять с PaperSnackbar; стабильность при планировании, старте, паузе, финале и отмене с восемью секундами Undo. Room 11→12 с безопасным backfill, сохранением legacy-данных и тестами миграции/гонок. PaperBakeNumber в kit и на всех указанных поверхностях, приватные public-уведомления. В Алисе остаётся выбор одной выпечки, подпись «№2 · Чиабатта».

Наследуется performance head 36fe448ca22d4fcede5c3e71436b0ff18cfe4b26 без потери baseline/ART evidence. Чистые native кадры cutout, ночной главы, нижней панели и полных настроек снимаются заново без системного ANR и обрезки. Физические Huawei/haptics этим не принимаются.

## Данные и порядок блокировок
Application operation gate → локальный transition gate → короткий PLANS_LOCK; не держать PLANS_LOCK через suspend. Номер резервируется на основании durable Room и prefs, а не устаревшего UI. План передаёт номер активной выпечке без перевыдачи. Удаление номера только после durable закрытия, не при входе в финал.

Перед изменениями создан и проверен git bundle: /root/.hermes/work/madre-design/worker-backups/20261024-7016-171244/source-before.bundle; SHA256 86085ca566e0ccb04cb4d98f73e31189cb3e132be536ea07c11fde0ecbb9f8de. Откат кода — изолированный clone bundle, без reset новых пользовательских данных. До миграции БД — согласованный SQLite backup, без destructive fallback.

## Флаг и совместимость
Завершённые B1–B6 включены как единый Android data/UI контракт; частичный UI не выдаётся за готовый. B7–B10, сервер, backend/edge, OAuth/QR, устройства семьи исключены. Совместимость старого сервера и consent не меняется. Выданный APK 7.0.15 неизменяем.

## Готово
- Тесты аллокатора 1–9, карантина, предела, lifecycle/Undo, конкурентного start/plan и повторного запуска плана.
- Настоящая SQLite/Room миграция 11→12 с проверкой сохранности и детерминированного backfill; экспорт схемы 12.
- Номер на карточке/плане, старте, чипах, шаге, крупном таймере, финале, главе, Станции и уведомлениях; internal id не показан.
- Локальная compileDebugKotlin + compileDebugUnitTestKotlin (timeout ≥900 с), известный offline plugin blocker сохраняется в лог; затем зелёный critical CI точного head без ослабления миграций/StyleBoundary.
- QA APK 7.0.16 (52) с прежним сертификатом и абсолютными путями PNG передаётся parent; одно review и доставка выполняются parent.
- Без merge, тега, release, RuStore или установки на семейные телефоны.
