# Independent backend final review — Astra

VERDICT: APPROVE (source/isolated HTTP contract only). Not deployment, Dialogs/OAuth-platform, A8/A9, hardware or release approval. Author Grok4.7/xai-oauth; reviewer Astra, no backend authorship.

The exact backend is pinned by backend-manifest.json. Parent independently reran `python3 -m unittest discover -s tests -v`:37 tests,0 failures/errors, real loopback HTTP+SQLite, evidence/backend-tests-r6.log. Earlier37/1 failure remains preserved; it was not relabelled success.

Closed original F1–F6:
- Router uses SplitResult.path, rejects semicolon/backslash/traversal, real HTTP endpoints no longer crash.
- Rate counting/purge use a common minute-index unit; repeated real endpoints hit429, successful pairing does not consume failure budget.
- Revoked/expired originating grants cannot mint/dispatch queued actions; live grant checked within write transaction; already dispatched durable outcomes are not rewound.
- Revisions cannot decrease; all same-revision canonical contradictions rejected, including same connection. Time projection/capture heartbeat remains accepted. Retired connection cannot regain lease/write state. Old synthetic step fixture now increments revision correctly without weakening its no-command assertions.
- Receipt speech scoped to current grant bake+selection generation and24h read TTL, not installation-global prior bake.
- Waiter refcounts/finally cleanup bound map growth and preserve wake-up delivery.

Request boundary now enforces socket inactivity plus absolute6s lifetime before headers/body, independent of nginx. Deadline disarmed before shutdown, socket ownership guarded against late timer/fd reuse; finally releases permit even on errors. The isolated max_inflight1 tests cover idle headers, incomplete body and trickle faster than inactivity timeout. Test-only settlement waits for actual previous handler permit release before subsequent sequential health assertions; this is not a blind delay or removal of8x200 assertions. Source hashes match r4; only fixture changed in r5. Parent rerun37/0 confirms the corrected executable evidence.

Residual limits (not hidden): saturated clients may receive503; some small denial writes remain inside an IMMEDIATE SQLite transaction, but share the bounded request deadline and400ms lock timeout. Runtime deployment must retain limits and no-body/no-token logs. Receipts are read-limited to24h; cleanup runs on enrollment/authorization and is not a separately installed periodic retention service. No live database or endpoint was modified. Tests use explicit synthetic installation/OAuth clients, not real Yandex or a phone. Canonical Android remains the only state writer; server fixtures are not hardware acceptance.
