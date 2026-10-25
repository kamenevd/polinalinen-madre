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
## Ограниченный foreground срез K2/K10
Существующий SDK callback/controller реально подключён к Alice → PB auth/link одним RAM owner, за тем же false-флагом. Boolean retry до сети, атомарные Alice metadata + retry на partial, отдельные Alice/PB фазы, cancellation/logout/revision fences. Сохранённый PB token сначала проверяется pinned refresh; link обязан сохранить record.id; ошибки/conflict не создают новый аккаунт и не откатывают Alice неявно. PB credential идёт в прежний защищённый store; logout чистит local family account/cache, не leave и не Room. Auth name допускает пустую строку без fake identity; остальные ID/JSON guards сохранены.
Приёмка этого среза: реальное выполнение новых behavior/cancellation/race/process-death/link-conflict и serialized transport/persistence тестов плюс прежних account/AuthSDK/style/core regressions, compile + unit compile timeout ≥900 перед push. Это НЕ завершение всех K/B критериев.

## Android family UI срез (отдельный от roster executor)
K01–K10 Compose/HTTP/repository/ViewModel, owner/self actions, explicit join/pending/SDK return и INVITE-only SavedState подключены за false.
MainActivity cold/warm strict routing + manifest autoVerify объявлены; public verification/Huawei не утверждаются. Undo8s/rotation, transfer/leave/solo delete требуют auth+roster readback.
Focused serialized controller/socket/Compose tests и отдельный opt-in K render suite; core20/старые baselines/Room/issued16 без изменений. Доказательства и ограничения: `docs/family-link-ui.md`.
## Android B7–B10 source срез (25.10, без enable/deploy)
Реальный persisted roster0..9 (Room, планы, финалы), installation scope consent, строгий attach/poll/disconnect/ack и Activity foreground lifecycle. План→активная→финал переносит ключ/номер и увеличивает revision; unreadable prefs не превращаются в пустую замену. Исполнитель перечитывает Room под Application/transition locks, атомарно коммитит полную CAS state+revision+fingerprintedreceipt+lease marker в прежней Room12, перепроверяет authority/expiry в конце SQL. Policy proposal не является APPLIED; до успешного commit ACK невозможен. Legacy flag-off UI/API/выданные данные сохранены.
J05/J06 — F kit, список/слова/фразы без v2 picker, navigation-context highlight и отдельное installation consent dialog, настоящие dawn/night native Compose fixtures. Изолированный pinned Alice f138 producer через реальные HTTP/SQLite/confirmation/dispatch соединён с настоящим JVM Android Room executor и ACK; подменён только внешний provider/тестовый socket destination. Synthetic pairing — только fixture, НЕ реализация нового bootstrap.
Точный fail-closed gap: новый/пустой device без current confirmed legacy paired grant получает409 pair_required; consent/login/enroll не заменяют пару. Никакого скрытого выбора bake или фиктивного CONNECTED. Полное устройство/SDK/Station/e2e и вся7.1 не приняты. Архитектура/повторяемые команды/границы: `docs/roster-v2-android.md`.
## Android Alice presentation wiring (7.1.1/code54, unissued)
J05 now renders NEEDS_IDENTITY / AUTHORIZATION_PENDING / EXPIRED / INTERRUPTED and same-attempt transport retry, with the existing verified Yandex account control. SDK callback, recomposition, return and visible deadline expiry do not create consent, continue, check or launch a provider. Explicit continue/check use the existing coordinator methods; pending/terminal attempts expose cancellation before a new warning/consent. Stable permission checks and all error/revoke paths stay explicit. Numbered roster, read-only plans/finale and common-speaker/privacy warnings remain unchanged. Focused fake Compose callbacks are part of the short unit gate; no live provider/backend/device acceptance or enablement is claimed.
## Флаг
`FeatureFlags.FAMILY_ROSTER_V2 = false` во всех вариантах; новые public transport entrypoints отказывают до сети.
Включение — отдельный будущий change после готовности обоих backend, совместимости 14/16 и Android/e2e/native приёмки.
## Общие критерии 7.1 (code-ready Android UI не означает backend/native/e2e PASS)
- K1: Yandex-only UI K01/K02, migration card, убрать email/password/code только из нового UI после gate; legacy оставить до 7.2.
- K2: backend candidate HMAC uid/client_id + DB/log tests отдельно; Android foreground Alice→PB/RAM-only/retry/protected-store/signout реализованы, остаются server enable и native/e2e acceptance.
- K3: backend hashed/derived invites, 7 days/one active/rotate/revoke/rate limits + K03/share UI.
- K4: manifest/MainActivity routing, public assetlinks обоих реальных сертификатов, K11 Huawei fallback; настоящая Huawei приёмка владельцем.
- K5: подключить domain flow к SDK/Compose/SavedState и PB token-store без автоприсоединения; instrumented вход→возврат→join.
- K6: backend capacity/expiry/switch/pending approval ACL/races/status; K05/K06/K10 error/pending UI и соответствующие native кадры.
- K7: owner/self labels, family settings/approval/rename mutation UI, refresh и backend ACL tests.
- K8: removal/default rotate/Undo 8 s, auth-bound restore TTL 10 min/removal revision; removed banner, author data/privacy tests.
- K9: explicit owner transfer/leave/solo soft-delete/revoke, no silent oldest-owner; UI + backend race/retention tests.
- K10: same-record foreground link + конфликт uid реализованы Android за false; backend compatibility/password/code, parent fresh count/report и фактическое безопасное переключение до закрытия ТОЛЬКО новой регистрации остаются.
- B7: реальный current-roster adapter и UI списка/слов/фраз без picker ТОЛЬКО после server gate; dawn/night Roborazzi.
- B8: backend NLU все формы 1–9 × все intents/recipe ambiguity/filler/исключения; формальные «вы», имя «Голос закваски».
- B9: backend dialogue 0/1/many, pending 60 s, numbered confirmation pinned key+revision, planned/finale readonly; серверные диалоги.
- B10: installation grant/consent/revoke, atomic full replacement/revision ordering/≤10 min cleanup, v2 lease/ack + durable Android execution/receipt; old poll/select/bind/unbind и QA e2e.
## Вне рамок этого writer
Серверы/веб/edge/консоль/deploy/новый вход/QR/телефоны, merge/tag/release/RuStore; никаких изменений issued APK 14/15/16, manifest доставки, latest/INDEX.
Deploy-да 24.10 14:33 уже есть, но выполняет parent/backend отдельно после свежих полных backup и проверенного rollback; code/mock tests ≠ серверная готовность.
