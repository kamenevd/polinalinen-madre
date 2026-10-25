# Madre 7.1 — installation-pair bootstrap (additive contract, frozen v1)

Status: CODE ONLY; base Alice `f1380c21665ebd51fff1da856cdd3164ad36ba9c`.
This document adds one Android request; it does **not** change any DTO or byte
in `docs/contracts/madre-7.1-v2.md`. No Android implementation, provider setup,
service/config/edge change or live account linking is included here. Existing
`MADRE_ALICE_ROSTER_V2` and `YANDEX_MATCH` remain default-OFF and unchanged.

## Scope and authority

A new installation, zero bakes and planned-only roster all pair identically.
Do not select/create a bake to connect Alice. SDK bind/enroll proves identity,
not a Dialogs peer. Legacy `grants`, tokens and five-minute per-bake consents
are never copied, rewritten, widened or used to synthesize this authority.
The existing PR64 explicit attach of an already confirmed legacy pair remains
compatible and separate; this bootstrap does not use it.

There are three different states:

1. **Pending authorization challenge**, at most 300 seconds: an authenticated
   phone explicitly allows an attempt to connect this installation. It is NOT
   a confirmed grant and cannot poll/attach/execute. Retry never extends it.
2. **Approved challenge + one-use broker code**, at most 60 seconds and never
   beyond the pending challenge: created only after browser-bound Yandex
   state/PKCE, actual provider code exchange and verified matching UID/client.
   It is still NOT a grant. Provider login consent is not installation consent.
3. **Confirmed installation permission**: created only by authenticated exact
   broker OAuth client code redemption, bound to installation, UID, epoch,
   `installation_current_bakes` and client. Stable until explicit revocation or
   the fixed grant expiry (currently 30 days). The pending challenge expiry does
   not shorten this stable permission; refresh never extends its expiry.

Every control action still needs its separate second-turn «да» (cancellation:
«подтверждаю отмену»). Pairing, attachment and a successful poll never authorize
an action by themselves. Empty roster is a successful linked state.

## New phone request (only additive Android wire)

`POST /alice/madre/voice/phone/v2/pair-consent`

Headers: existing `Authorization: Installation <credential>`,
`Content-Type: application/json`. No query, browser cookies or provider token.
The existing enrollment and verified SDK `/phone/yandex-bind` must already
have succeeded for the current foreground account. Do not persist SDK tokens.

Exact body, no extra/duplicate fields:

```json
{
  "protocol_version": 2,
  "request_id": "a0057010-4837-46be-adc0-0044e0eb685b",
  "consent_scope": "installation_current_bakes",
  "consent": true
}
```

`request_id` is a fresh canonical lowercase UUID, generated once per explicit
foreground action. Preserve it across transport retries; do not generate a new
ID on a network retry, screen opening, restore, SDK bind or OAuth callback.
An explicit new action may replace a pending request; old browser flows cannot
adopt the replacement. Neither request_id nor response is a bearer authority.

The foreground notice/button must explain: «Алиса может читать все текущие
выпечки этой установки и предлагать управление ими. Каждое действие выполняется
только после отдельного подтверждения. Доступ можно отозвать в настройках».
No bake selection and no claim of connection before confirmed attach.

Exact HTTP 200 response:

```json
{
  "request_id": "a0057010-4837-46be-adc0-0044e0eb685b",
  "consent_scope": "installation_current_bakes",
  "status": "pending",
  "expires_at": 1792910100000
}
```

`expires_at`: server Unix epoch milliseconds, authorization deadline only.
No installation/profile/UID, OAuth access token, provider code or redirect URL
is added. Responses are JSON/no-store. Existing strict v2 parser/body limits
apply (booleans are not integers; canonical UUIDs; no nonfinite/duplicate JSON).

Errors use existing `{"error":"..."}` envelope:

| HTTP | error | Meaning |
|---|---|---|
| 400 | invalid_request | Wrong shape, UUID, type, extra or duplicate fields |
| 401 | unauthorized | Missing/wrong installation credential |
| 403 | consent_required | Not exact scope or consent is not boolean true |
| 409 | binding_required | SDK identity not bound; enrollment is insufficient |
| 409 | binding_changed | Auth snapshot epoch/UID no longer current |
| 409 | challenge_consumed | ID expired, approved, revoked, foreign or replaced; do not re-arm it |
| 409 | already_paired | A current confirmed installation grant exists; use attach |
| 426 | unsupported_protocol | Flag off or version not exact integer 2 |
| 429 | rate_limited | Installation request creation limit |
| 503 | pair_unavailable | Required existing OAuth/Yandex-match settings unavailable |

Duplicate pending delivery with the same exact ID is idempotent with the
original deadline. Approval, expiration or revocation is terminal for that ID.
An expired/rejected attempt needs another explicit foreground action, not
silent renewal. Aborting a not-yet-attached attempt uses existing authenticated
`POST /phone/disconnect {}`; it advances epoch and invalidates pending codes.

## Browser / broker sequence (unchanged public OAuth wire)

1. Phone completes the explicit request **before** the user initiates existing
   Dialogs account linking. No automatic login or provider launch on app open.
2. Broker opens existing `/oauth/authorize` with the same exact client,
   `response_type=code`, exact broker redirect, opaque outer state and public
   scope `bake:read&bake:control`. That public scope string is intentionally
   unchanged; the authenticated phone's namespaced consent determines scope
   authority. Do not reinterpret legacy codes as installation permission.
3. Server freezes eligible request IDs into the existing bounded RAM-only
   browser/state/PKCE flow before provider I/O. The provider callback consumes
   that state once, verifies cookie, exchanges provider code with S256 verifier,
   verifies provider client and UID, then rechecks the frozen exact request,
   installation binding, epoch, expiry and scope under BEGIN IMMEDIATE.
4. Exactly one bound installation for this UID and exactly one frozen pending
   request must exist. Two bound installations are ambiguous even if only one
   opted in. Never choose newest/first/arbitrary installation. A stale or
   ambiguous namespaced attempt does not fall through to legacy code issuance.
5. Successful approval creates a separate namespaced one-use broker code and
   the ordinary 303 broker redirect with unmodified outer state/client/scope.
   The existing browser state/cookie checks are the CSRF fence; old manual
   authorize/confirm forms keep their existing same-origin/CSRF contracts.
6. The **broker**, not Android, redeems that code at existing `/oauth/token`,
   `grant_type=authorization_code`, exact redirect/client/secret. Android must
   never receive/embed the broker client secret or broker access/refresh tokens.
   Real redemption creates the stable grant and hashed namespaced bearer tokens;
   it is the first point at which confirmed pair authority exists.
7. Existing `/oauth/token` refresh or `/oauth/refresh` rotates the namespaced
   refresh token for the authenticated same client; old access is revoked.
   Code replay or refresh replay revokes the grant/token family/connection.
   Wrong client, malformed request or foreign secret cannot consume a code.

A callback cannot adopt consent created after authorize started, or a newer
request created during provider I/O. SDK account replacement/unbind/disconnect
at any stage invalidates it. Provider tokens, profile data, PKCE verifier and
raw browser/outer/provider states are never written by this namespace. Only
technical UID/epoch, request/grant IDs and hashes needed for provenance remain.

## Existing Android roster wire stays unchanged

After account-link completion, Android explicitly calls existing v2 attach
with `{protocol_version:2,consent_scope:"installation_current_bakes",consent:true}`.
HTTP 409 `pair_required` means the actual broker redemption is not complete (or
no valid permission remains), not permission to select a bake or use SDK-only
fallback. No new polling DTO/status endpoint is needed: successful attach is
the authoritative phone readback. Bound retries may check attach after the
user returns; do not silently launch another login or synthesize LINKED state.

A successful attach returns the existing connection response and can immediately
poll `bakes:[]`. Planned bakes are read-only. Poll/lease/ack/disconnect fields,
revision fences, snapshot TTL and second-turn confirmations are unchanged.
Every bearer, phone mutation and ack (including an identical duplicate ack)
rechecks current epoch, UID/scope/client/grant revocation. A credential cannot
ack another installation. A namespaced bearer never falls back into v1.

Process restart revokes volatile roster connections, not stable permission:
explicit attach can create a new connection with the original grant expiry.
A stale disconnect retry cannot revoke it. A current disconnect revokes stable
permission as well; another connection then needs a new explicit full pair.

## Latest-DB compatibility / rollback

New `installation_pair_*` tables/indexes/triggers only; old `grants.bake_key`
and `selection_generation` remain NOT NULL. No fake/NULL bake, compatibility
bypass or legacy token row. `PRAGMA user_version=3` stays unchanged. Roster's
source UUID can refer to the separate authority; the new reader resolves it
explicitly, whereas unchanged old readers cannot recognize its bearer.

Keep the **latest DB** on rollback, including revocations. SQL triggers on the
old epoch/binding tables invalidate pending and stable authority even when
unchanged old d499/f138 code unbinds/disconnects. Re-upgrade does not resurrect
revoked requests, codes, grants or tokens. Do not restore an old DB over recent
revocations. Parent alone owns full runtime/storage/config backup and rollout.

## Local acceptance / remaining integration

`backend/alice/tests/test_installation_pair.py` exercises genuine loopback HTTP,
real SQLite, external-only provider HTTPS mock, PKCE exchange, broker client
code redemption/refresh, zero/planned roster, action confirmation/lease/ack,
expiry, strict parsers, scope/UID/client/epoch fences, races, replay, revocation,
transaction rollback and unchanged d499/f138 subprocess readers on latest DB.
The old regression suite keeps legacy poll/select/bind/unbind/token/voice checks.
Fixtures have exact source/archive/file hashes; synthetic ack is NOT Android
execution. No provider, QR, Dialogs console, phone or Station is contacted.

Parent still owns Android transport/UI integration of this additive contract,
capabilities/enable gates, exact CI/artifact acceptance, sole full-7.1 review,
web/edge integration, authorized deployment and actual QA e2e. Version 53 is
not issued by this work; issued latest16 remains immutable.
