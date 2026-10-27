# Madre 7.1 / family-v2 + roster-v2 — HTTP/DTO contract, revision 1

Статус: Android domain/transport + foreground provisioning K2/K10 за default-false flag; НЕ действующий сервер и НЕ приёмка K1–K10/B7–B10.
Владелец согласования — контроллер; отдельные writer: PocketBase family/auth, Alice roster/NLU; веб/edge только parent.
Каноны FAMILY-LINK §§0–7 / BAKE-NUMBERS §§4–8, свежий handoff 25.10 supersede исторические same-writer/удалить старые API строки.
Все примеры ниже — типы/синтетические формы, не записи пользователей. Room остаётся 12.

## 1. Общие правила wire/security
- JSON UTF-8, `Content-Type: application/json`, request и response ≤65536 bytes. Числа — JSON integers, bool — JSON bool, null явно там, где указан.
- Family base: `https://madre-api.kdnfx.space`; на ВСЕ запросы этого контракта `X-Madre-Family-Version: 2`. Без заголовка старые create/invite/leave/join DTO/семантика сохраняются для 14/16.
- Alice base: `https://kdnfx.space/alice/madre/voice/phone`; новые маршруты только `/v2/*`, в JSON `protocol_version:2`. Старые `/poll`, `/select`, enroll/pair/bind/unbind/consent/ack не удалять, не 410.
- PB auth: `Authorization: <PocketBase token>` БЕЗ Bearer; Alice: `Authorization: Installation <43 base64url chars>`. UID hash и installation ID никогда не заменяют PB token/user ID.
- HTTP redirect не следовать; никакого cache/cookies/logger/analytics. Android ошибки содержат только enum; upstream body/URL/exception/cause не сохраняются. Обязательны server/edge log masking invite path, PB/OAuth/installation/restore token и UID.
- Success для JSON маршрутов — ровно 200, delete — 204/no body; 202/redirect/HTML не success. Строки ID непустые, PB opaque IDs, request_id `[A-Za-z0-9_-]{1,64}`; даты семьи ISO8601 UTC, все Alice timestamps epoch milliseconds.
- Ошибка JSON `{error:<enum>}`. 401/403 unauthorized; 404 `invite_unavailable` одинаков для unknown/revoked/expired; 409 `family_full`, `already_in_family` (+family_name для будущего UI), `uid_already_linked`, `owner_transfer_required`, `conflict`; 429 `rate_limited`; 5xx generic. Тело ошибки не публиковать в лог/исключение.
- Family-v2 header должен проверяться сервером до mutation; не согласованный протокол — 426 `unsupported_protocol`, без mutation. Capabilities handshake и безопасное включение остаются следующему integration slice.

## 2. PB auth и sequential foreground provisioning (K2/K10)
| Method/path | Authorization | JSON request | 200 response |
|---|---|---|---|
| POST `/api/madre/auth/yandex` | отсутствует | `{yandex_token:string}` | `FamilyAuth` |
| POST `/api/madre/auth/yandex/link` | живой старый PB email token | `{yandex_token:string}` | `FamilyAuth`, record.id строго прежний |

`FamilyAuth = {token:string, record:{id:string,name:string,family:string|null}}`; пустая relation family `""` также читается как null. Auth `name` может честно быть пустой строкой, если first_name отсутствует; missing/null/non-string name не принимается. Непустые ID/token и тип/непустое значение заданного family не ослабляются.
Для first_name/name разрешено хранение только family-доменом; не email/phone/avatar/raw UID/OAuth.
Сервер каждый раз вызывает официальный Yandex `/info`, сверяет наш client_id и использует HMAC-SHA256(uid, отдельный secret salt), unique UID index.
Не merge по email/имени, не присвоение чужой записи. Link конфликт UID →409 без изменения records/family/bakes/password/code. Auth и link race-safe.
Действующий за `FAMILY_ROSTER_V2=false` foreground controller: explicit SDK result → существующий Alice bind → PB auth/link последовательно одним RAM-owned `YandexLoginToken` → close в finally и completion callback (включая cancel-before-body). Никакого нового SDK launch из init/profile restore/upgrade.
До первого запроса записывается только boolean retry marker; после process death НОВЫЙ foreground вход по действию, не реконструкция токена из profile/UID, не worker/автологин при upgrade.
Частичный успех Alice не означает PB login; PB failure не рисует общую готовность и не откатывает/не удаляет аккаунт неявно. Оба результата подтверждаются отдельно.
Android `HttpsFamilyLinkApi.authenticate` строит настоящий PB request; сам по себе не присоединяет к семье. `MadreApplication` → Activity-owned `YandexAccountViewModel` → существующий SDK controller → `ForegroundFamilyProvisioning` → общий `FamilyAccountRepository` подключены за false-флагом. Наличие saved PB credential требует pinned POST `/api/collections/users/auth-refresh` и link к точно тому же record.id; refresh failure НЕ превращается в auth нового аккаунта. Repo mutex сериализует с legacy операциями; revision/current-attempt/cancellation fence не даёт позднему ответу воскресить logout. Только PB credential сохраняется в существующий `SecureTokenStore`, не OAuth. Alice profile + boolean retry пишутся атомарно до PB; retry сбрасывается лишь после обоих успешных ответов и metadata commit. `provisioning` отдельно сообщает Alice-bound, PB-authenticated, retry/conflict; один частичный успех не COMPLETE. Старый flag-off Alice-only путь сохранён. До завершения UI/AppLinks и server enable gate этот код не включать.
Выход чистит локальный PB session и family cache, не серверное членство/Room; текст «Полка семьи станет недоступна на этом телефоне до входа».
7.1 сохраняет ВСЕ email записи/passwordAuth/старый code join. Parent перед закрытием ТОЛЬКО новой регистрации пересчитывает и сообщает число (предыдущий read 24.10 14:49:35 МСК: 2, оба с семьёй; не вечная константа). 7.2 отключение пароля/кода НЕ разрешено этим контрактом.

## 3. Invite/pending API (реальный Android transport этого среза)
Canonical UI URL ТОЛЬКО `https://kdnfx.space/madre/f/<token>`, ровно 16 base64url символов =96 random bits.
Нет port/userinfo/query/fragment/percent-encoding/редиректов/trim/case-normalisation; только точное написание.

| Method/path | Auth | Request | 200 response |
|---|---|---|---|
| GET `/api/madre/invite/{token}` | НЕТ | — | `InvitePreview` |
| POST `/api/madre/invite/{token}/join` | PB | `{switch_family:boolean}` | `JoinReply` status joined/pending |
| GET `/api/madre/family/requests/{request_id}/status` | PB заявителя | — | `JoinReply` status pending/joined/declined |
| GET `/api/madre/family` | PB члена | — | `FamilyRoster` |

`InvitePreview = {family_name:string,inviter_name:string,members_count:0..8,bakes_count:integer>=0,initials:string[],approval_required:boolean}`.
Initials ≤members_count, каждая 1..16 символов; никаких user IDs/списка людей/выпечек. Веб preview ещё уже: ТОЛЬКО family_name/inviter_name.
`JoinReply = {status:"joined"|"pending"|"declined",family_id:string,request_id:string|null}`. Pending обязательно request_id; повторный pending join того же пользователя idempotent.
Status — НОВОЕ уточнение канона для честного ожидания: authoritative owner approval не требует повторного join. Scope только authenticated requester; чужой request →404.
Status остаётся доступен заявителю после expiry/rotation исходного invite, но никогда не обходит revoke/removal/новую семью. Для status/join ответ family_id стабилен.
После approval status joined должен сохранять тот же request_id. Declined/removed/revoked нельзя превратить в joined устаревшим ответом.
Join without explicit switch в другой семье →409 `already_in_family`; switch=true — атомарная проверка target/token/capacity/leave rights и переход, НЕ отдельный безусловный leave перед проверкой target.
Если target требует pending, исходное членство сохраняется до атомарного accept/перехода; owner-transfer prerequisites проверяются повторно при accept. Pending не получает доступ.
Owner approval/removed rejoin always recheck capacity (max8), token/revocation/ownership/request generation и принадлежность пользователя. Removed всегда pending даже после свежего invite и approval=false.
Public preview 30/min/IP, join10/hour/user; один живой invite на семью, TTL7 days, reuse до expiry, rotation/revoke немедленны.
DB token hash-only. Для authorized GET current invite не «восстанавливать SHA»: отдельный domain-separated HMAC derivation key + random invite nonce/id, минимум96 bits, stored hash verifies derived token. Не UID salt. Альтернатива требует отдельного reviewed storage решения.

`FamilyRoster = {id,name,owner,approval_required,members:FamilyMember[],pending:FamilyRequest[]}`.
`FamilyMember = {id (PB user record ID),name,role:"owner"|"member",status:"joined"|"left"|"removed"|"pending"|"dormant",joined_at:ISO8601,bakes_count:integer>=0}`.
`FamilyRequest = {id:request_id,user_id:string,name:string,status:"pending",requested_at:ISO8601}` — это заявка, не членство/не joined_at.
members в authenticated roster — только joined, 1..8, ровно один owner с id=owner; pending только заявки. Request IDs уникальны; user IDs уникальны среди members + pending. Остальные member statuses — журнал/события, не доступ.
Член семьи не получает pending список других заявителей: `pending:[]`; полный pending только владельцу. Сервер проверяет ACL при каждом чтении/изменении.
Android `InvitationFlow`: LOADING→SIGN_IN/READY→explicit JOINING→PENDING/VERIFYING; sign-in return и SavedState restore не вызывают join.
JOINED acknowledgement НЕ даёт MEMBER: сразу GET family и проверка target family_id + PB user в joined. Ошибка/нечитаемый roster/другая семья →ERROR без доступа.
Pending refresh — foreground single request; никакого бесконечного poll/самопринятия. clear/new invitation invalidates late responses. Session/OAuth не входят в SavedState.

## 4. Остальные family endpoints — конкретное задание backend writer, Android UI следующими срезами
Все следующие запросы PB-authenticated + family-v2 header; mutation ACL/ownership проверяются транзакционно. Если не указан иной результат — 200 свежий FamilyRoster.
| Method/path | Request | Response/ACL |
|---|---|---|
| POST `/api/madre/family/create` | `{name:string}` | FamilyRoster, user без семьи, owner=user |
| POST `/api/madre/family/invite` | `{}` | `{url,expires_at:ISO8601,seats_left:0..7}`, owner, предыдущая отозвана |
| GET `/api/madre/family/invite` | — | `{url,expires_at}`, member/owner, 404 если нет активной |
| DELETE `/api/madre/family/invite` | — | 204, owner |
| PATCH `/api/madre/family` | `{name?:string,approval_required?:boolean}` | owner, хотя бы одно поле |
| PATCH `/api/madre/family/members/{id}` | `{display_name:string}` | self/owner |
| DELETE `/api/madre/family/members/{id}?rotate_invite=1` | — | `{restore_token,restore_until:ISO8601,removal_revision:integer}`, owner не себя; default rotate=1 |
| POST `/api/madre/family/members/{id}/restore` | `{restore_token:string}` | owner-bound family/member/removal-revision, token hash only, TTL10min |
| POST `/api/madre/family/requests/{id}` | `{accept:boolean}` | owner; repeated matching decision idempotent; approve rechecks max8/rejoin/identity |
| POST `/api/madre/family/owner` | `{member_id:string}` | owner, target joined |
| POST `/api/madre/family/leave` | `{new_owner_id?:string}` | `{ok:true}`, member; owner с другими обязан передать явно |
| DELETE `/api/madre/family` | — | 204, только solo-owner; soft-delete + revoke invites/requests |

Restore не перетаскивает из другой семьи, не оживляет deleted family, не обходит revoke/смену owner/новую removal revision. UI Undo8s не расширяет server TTL10min.
После remove/leave user data/author bakes остаются у автора, family feed их больше не видит через users.family. Фото/privacy consents не расширять. Последний owner soft-delete, physical purge30days только отдельной санкционированной retention процедурой.
PB migrations: users + unique nullable yandex_uid_hash; families approval_required/deleted_at; family_invites nonce/hash/created_by/expires/revoked; family_members роли/labels/status/removal/restore fields; request identity+family+generation/decision. Старые поля/password/code сохранять.

## 5. Roster-v2 (реальный Android poll builder/parser этого среза)
POST `/v2/poll`, Installation auth, body:
```json
{"protocol_version":2,"connection_id":"00000000-0000-0000-0000-000000000001","roster_revision":4,"bakes":[{"bake_key":"00000000-0000-0000-0000-000000000002","bake_number":2,"revision":7,"recipe_revision":"recipe-sha","recipe_name":"Хлеб","state":"running","step_index":0,"step_count":2,"step_title":"Опара","remaining_seconds":60,"start_at":null,"step_text":null,"next_step_title":null,"next_step_text":null,"quantities":null,"portions":1,"control_ready":true}]}
```
- bakes полный список current, 0..9. Пустой массив атомарно очищает roster; никогда не merge с отсутствующими старыми выпечками.
- connection_id, bake_key, command id — canonical UUID. Numbers1..9 и bake_key уникальны. bake_key стабилен, переиспользованный номер не identity.
- roster_revision monotonically increases на connection; меньше текущей →409 stale_roster, same revision+same digest idempotent; same revision+different digest →409 conflict. Новое connection требует explicit attach/consent; старый request после reconnect не перезаписывает новый roster.
- per-bake revision integer>=0, recipe_revision nonempty; step_index0..<step_count, count>0, remaining>=0. state running/paused/scheduled/finale; start_at epoch ms >0 ТОЛЬКО scheduled, иначе null.
- step_text/next_step_title/next_step_text/quantities nullable strings; portions null либо1..5 (как RecipeScale); не доверенные recipe revision/scaling →null quantities/text/portions, control_ready=false. Backend не выдумывает граммовки/текст при null, просит открыть телефон.
- control_ready разрешён только running/paused с trusted portions/revision; planned/finale строго read-only. Фактический persisted-store→roster adapter и его trust checks — следующий Android срез.
- Ни history, фото, имён людей/семьи, local sessionId. Server snapshot удалить при replacement сразу, при потере связи TTL≤10min, историю не хранить.

200 response:
```json
{"protocol_version":2,"connection_id":"00000000-0000-0000-0000-000000000001","roster_revision":4,"server_time":1000,"commands":[{"id":"00000000-0000-0000-0000-000000000003","connection_id":"00000000-0000-0000-0000-000000000001","bake_key":"00000000-0000-0000-0000-000000000002","bake_number":2,"expected_revision":7,"recipe_revision":"recipe-sha","operation":"PAUSE","issued_at":1000,"expires_at":4000,"lease_id":"00000000-0000-0000-0000-000000000004","expected_step":0,"expected_paused":false}]}
```
connection/revision echo точные; commands concrete array0..9, unique id, canonical UUID lease_id, expected_step>=0, expected_paused boolean, operation PAUSE/RESUME/ADVANCE/BACK/CANCEL, expected_revision>=0, expiry>issued, TTL≤4000ms. Неверная форма →никакой команды/успеха, не coercion строк в числа.
Android `RosterCommandPolicy` проверяет attach + installation consent, connection, age/clock/expiry, stable key+number+revision+recipe revision, readonly и control_ready; выдаёт ТОЛЬКО ELIGIBLE_FOR_COMMIT.
НЕ исполнено этим срезом: lease/ack, current persisted state read under Application mutex, atomic state+receipt+revision, replay dedup/fingerprint, commit revalidation, expiry receipt, cancel-specific confirmation. Старый защищённый исполнитель не заменять более слабой v2 policy.

## 6. Уточнения следующего v2 executor backend handshake (задание writer, ещё нет Android вызовов)
Не растягивать старое single-bake consent молча. Новый installation grant требует отдельного explicit scope и пользовательского согласия.
- POST `/v2/attach`: `{protocol_version:2,consent_scope:"installation_current_bakes",consent:true}` →200 `{connection_id,consent_scope:"installation_current_bakes",attached:true}` после проверки существующей подтверждённой identity/pair; consent=false не attach. Идемпотентный attach не выдаёт права новой identity.
- POST `/v2/disconnect`: `{connection_id}` →204 после revoke connection/grants/leases/commands/pending и snapshots; legacy bind/unbind revocation гасит также v2.
- POST `/v2/ack`: `{connection_id,command_id,lease_id,outcome:"applied"|"stale"|"expired"|"rejected"|"phone_required",resulting_revision:integer|null}` →200 `{acknowledged:true}` только для данного installation/command/lease; same-id different payload conflict.
- Poll command уже требует `lease_id`, expected_step/expected_paused. Lease server-issued, UUID, привязан installation/connection/command/key/revision и тому же expiry≤4s. Сервер выдаёт command только после action-specific confirmation; не подтверждённый intent не command. Android ещё должен выполнять lease/atomic receipt commit по существующим security guards, а не доверять одному presence lease_id. До этого feature flag НЕ включать.
- Internal list_bakes(installation) — snapshot allowlist, grant на installation только с новым scope, lease/command authority всё равно на конкретный resource key+revision.
- Confirmation echo number+recipe, «да» для каждого действия; cancel отдельно «подтверждаю отмену». Pending confirmation pinned исходным bake_key/revision/connection; reuse number никогда не retarget.
- NLU все §4.2 формы1–9 × все intents; ё→е/filler, исключить «раз/одну минуту»; ambiguous recipe переспрос. 0/1/≥2 dialogue, pending_intent60s; planned/finale messages; «вы»/«Голос закваски» во всех ответах.

## 7. Обязательные доказательства перед enable/deploy (parent/backend, НЕ выполнены Android mock suite)
1. Изолированные реальные PB hooks/DB tests: client_id/uid isolation/link conflicts, отсутствие OAuth в DB/logs; concurrent max8/accept/rotate/restore/transfer, photo/privacy, existing records/password/code14/16.
2. Реальный Alice server CI: isolation/consent scopes/replay/revoke/lease/roster revisions/atomic replacement/expiry/cleanup, full NLU matrix, pinned confirmations, old poll/select/bind/unbind14/16.
3. Parent-only: fresh full DB+storage+code+env/keys+config/unit/edge backups, hashes/integrity и изолированный tested rollback сохраняющий последние записи/отзывы; fresh email count/report. Уже полученное deploy-да не означает readiness и не расширяет lifecycle helper.
4. Android remaining slices: UI/App Links/SavedState/K01–K11, invitation-return/session integration; real roster adapter/executor/lease/receipt; native states/QA e2e. Foreground sequential provisioning + retry marker/protected token-store реализованы и проверяются synthetic JVM/serialized boundary, НЕ живым входом. Держать default false до полной приёмки и server gate.
5. Public assetlinks обоих действительных cert без redirects, K11 escaped names/no trackers/no clipboard/open-redirect, no-store/no-referrer, Huawei owner «Открыть в Мадре» и реальный K5; никакого phone/login/QR от этого writer.
