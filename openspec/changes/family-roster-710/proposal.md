# 7.1.0 — семья по ссылке и Алиса на все текущие выпечки
## Зачем
Одна версия объединяет FAMILY-LINK K1–K10 и BAKE-NUMBERS B7–B10; 7.0.16 (52) выдана и неизменяема.
Каноны: FAMILY-LINK.md §§0–7 и BAKE-NUMBERS.md §§4–8 из handoff 25.10.2026; текущие разрешения уточняет intake контроллера.
## Что меняем
Первый ограниченный срез: `integration/v2` — настоящий OkHttp/Gson transport, PB identity/семья, invitation state machine и roster-v2.
Строго канонический URL: HTTPS, точный host/path и 16 base64url; никаких query/fragment/портов/percent-encoding/нормализации.
Возврат после входа только в READY; вступление лишь по явному действию, смена семьи требует второго подтверждения.
PENDING не даёт доступ; JOINED становится MEMBER только после аутентифицированного GET family с проверкой семьи и пользователя.
Поздний ответ после clear/new invite не восстанавливает доступ. В SavedState допустим только invite URL, не OAuth/PB session.
Roster: полный allowlist до 9, уникальные номер и UUID bake_key, revision, planned/finale readonly, явный v2/poll; legacy не меняется.
Проверка команды выдаёт только ELIGIBLE_FOR_COMMIT, не APPLIED; lease/атомарный receipt и исполнитель будут отдельным срезом.
Конкретный общий HTTP/DTO контракт и задания отдельным backend-writer: `docs/contracts/madre-7.1-v2.md`.
## Готово, когда (этот срез, НЕ вся 7.1)
Локальные focused JVM тесты URL/flow/pending/transport/roster/expiry реально исполнены; компиляция перед push с timeout ≥900 с.
Версия только release_cycle: 7.1.0 (53), CHANGELOG; точный head запушен, critical CI запущен, run ID передан контроллеру для watch/review.
Существующие Room 12, number allocator, R8/concrete-array/error-write guards, StyleBoundary, Yandex assets и старые UI/voice paths сохранены.
## Флаг
`FeatureFlags.FAMILY_ROSTER_V2 = false` во всех вариантах; новые public transport entrypoints отказывают до сети.
Включение — отдельный будущий change после готовности обоих backend, совместимости 14/16 и Android/e2e/native приёмки.
## Остаток всей 7.1 (14 открытых критериев, ни один не закрыт этим срезом)
- K1: Yandex-only UI K01/K02, migration card, убрать email/password/code только из нового UI после gate; legacy оставить до 7.2.
- K2: PB HMAC uid/client_id verification + изолированные DB/log tests; foreground Alice→PB orchestration, RAM-only OAuth/retry-marker, signout cleanup.
- K3: backend hashed/derived invites, 7 days/one active/rotate/revoke/rate limits + K03/share UI.
- K4: manifest/MainActivity routing, public assetlinks обоих реальных сертификатов, K11 Huawei fallback; настоящая Huawei приёмка владельцем.
- K5: подключить domain flow к SDK/Compose/SavedState и PB token-store без автоприсоединения; instrumented вход→возврат→join.
- K6: backend capacity/expiry/switch/pending approval ACL/races/status; K05/K06/K10 error/pending UI и соответствующие native кадры.
- K7: owner/self labels, family settings/approval/rename mutation UI, refresh и backend ACL tests.
- K8: removal/default rotate/Undo 8 s, auth-bound restore TTL 10 min/removal revision; removed banner, author data/privacy tests.
- K9: explicit owner transfer/leave/solo soft-delete/revoke, no silent oldest-owner; UI + backend race/retention tests.
- K10: same-record email→Yandex link при двух доказательствах, конфликт uid; compatibility password/code; parent fresh count/report до закрытия ТОЛЬКО новой регистрации.
- B7: реальный current-roster adapter и UI списка/слов/фраз без picker ТОЛЬКО после server gate; dawn/night Roborazzi.
- B8: backend NLU все формы 1–9 × все intents/recipe ambiguity/filler/исключения; формальные «вы», имя «Голос закваски».
- B9: backend dialogue 0/1/many, pending 60 s, numbered confirmation pinned key+revision, planned/finale readonly; серверные диалоги.
- B10: installation grant/consent/revoke, atomic full replacement/revision ordering/≤10 min cleanup, v2 lease/ack + durable Android execution/receipt; old poll/select/bind/unbind и QA e2e.
## Вне рамок этого writer
Серверы/веб/edge/консоль/deploy/новый вход/QR/телефоны, merge/tag/release/RuStore; никаких изменений issued APK 14/15/16, manifest доставки, latest/INDEX.
Deploy-да 24.10 14:33 уже есть, но выполняет parent/backend отдельно после свежих полных backup и проверенного rollback; code/mock tests ≠ серверная готовность.
