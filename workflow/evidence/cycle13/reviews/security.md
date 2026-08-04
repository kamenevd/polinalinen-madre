APPROVE

The remediation correctly closes the membership-race blocker. Verified all three required criteria against the patch hunks.

**1. Re-read lives inside each transaction at the correct position** ✓

`madre_family.pb.js` create hunk (`@@ -51,6 +51,15`): `const member = txApp.findRecordById("users", e.auth.id)` is now the *first* statement of the `runInTransaction((txApp) => {` body, and the guard `if (member.getString("family")) { throw new BadRequestError(...) }` precedes `new Record(families)` and `created.set`/`txApp.save(created)`. The old in-tx `const member` declaration is deleted (`@@ -59,7 +68,6`, the `-` line), so there's no duplicate declaration. Position is exactly right: check → create family → save family → set/save membership, all atomic; a thrown `BadRequestError` aborts and rolls back the whole tx, so no orphan family survives.

Join hunk (`@@ -122,6 +130,13`): the guard is inserted between the existing `const member = txApp.findRecordById(...)` (context line, already inside tx) and `member.set("family", joined.id)`. Re-read uses the transaction-scoped `txApp`, precedes the membership write. ✓

Both reads use `txApp.findRecordById` (fresh, transaction-scoped), not the stale `e.auth` snapshot that the original pre-tx check relied on.

**2. Generic join rejection preserved** ✓

Join's new throw is `new BadRequestError(JOIN_FAILURE, null)` — the same constant used elsewhere for an invalid/generic code failure. A user who already holds a membership gets the identical opaque response as a wrong-code attempt, so the re-check leaks no oracle about whether the invite code was valid. (Create uses a specific Russian message, which is fine — create is self-scoped, no code oracle there.)

**3. Tests actually enforce it** ✓

`test_family_backend_contract.py`:
- `transaction_body()` extracts precisely the `(txApp) => { ... }` callback via `brace_block`, so assertions are scoped to code *inside* the tx and exclude the pre-tx `e.auth.family` check.
- create: asserts the re-check is inside the body (`test_create_rechecks_membership_inside_the_transaction`), precedes both `new Record(` and `member.set("family"` (`..._precedes_...`), and throws `BadRequestError` before family creation (`..._throws_the_same_generic_error`). Reordering or hoisting the check back outside would fail these.
- join: asserts inside-tx presence, precedes `member.set`, and that the thrown constant is exactly `["JOIN_FAILURE"]` with no second distinct message (`..._keeps_the_same_join_failure_without_oracle`). The no-oracle property is asserted by string-equality on the constant.
- regression guard: `member.set("family"` count == 1 in each tx body, catching accidental duplicate writes.

These are static contract tests (no live PocketBase), but they are genuine — each would fail on the exact regression it guards, and they're deterministic rather than timing-flaky.

**One informational note (not a blocker):** the fix's effectiveness rests on PocketBase's default `?_txlock=immediate` serializing write transactions so the inner re-read observes a prior committed membership. That is the actual PocketBase default, so it holds here. If the connection string is ever switched to a deferred lock, a second tx could read a stale snapshot before acquiring the write lock and the race would reopen. Worth a one-line comment near each guard or a pragma assertion in a smoke test, but it does not affect this approval.

No changes required.
