# Local CI tools (steps 1–3 only)

These scripts change no Android source, workflow, properties, version, APK, or remote feature ref.
They are **not installed automatically**. The parent reviews the exact commit before installation.
The existing dirty/active workspaces are never renamed, deleted, reset, or silently skipped.

## Exact-SHA CI wait

```
scripts/ci-wait.sh <full-40-character-sha> --output /absolute/new/evidence-dir
```

One process discovers the manual `quality-gates.yml` run for that exact SHA and invokes
`gh run watch --exit-status`. A single 2400-second monotonic deadline covers discovery,
watch, final readback and failure logs. It never dispatches/retries a workflow itself.
The caller dispatches **once** with `critical_only=true` on the tools branch. Workflow
integrity and Android quality must each exist exactly once and succeed. Run id/SHA,
completed status, conclusion and jobs are read back after watch, even if watch exits 0.
Only concise final status and the last 60 lines of each failed job's last failed step
are printed. Full captured transport/watch/log output and JSON receipts stay in the
private evidence directory. Exit: 0 success, 1 failure/required-job failure, 2 cancelled,
3 timeout, 4 no run before deadline, 5 transport/incomplete readback, 6 identity mismatch.
If a run consumes the entire deadline, final readback cannot succeed: timeout, not PASS.

## Hard push admission and the common Gradle lock

```
scripts/madre-gradle.sh :app:assembleDebug
# Repository-local one-command admission, without installing anything:
git -c core.hooksPath="$PWD/scripts" push origin HEAD:refs/heads/ci/wait-integrator-hooks
```

The hook reads all stdin refs, rejects deleted/mismatched SHAs and non-worker branches,
and rejects feature pushes outside the pinned integrator **before building**. It then
requires a clean exact HEAD, no Git integration in flight, and an unchanged source hash
manifest before/after the real gate. Under `/root/.cache/madre/gradle.lock`, it runs:

```
:app:compileDebugKotlin :app:compileReleaseKotlin :app:testDebugUnitTest -Pmadre.shortApkGate=true
```

The gate and manual wrapper use daemon/build cache, Xmx1536m and workers2; inherited
approved client metadata is preserved without printing it. No daemon/cache/resource
override switches are accepted. Individual Gradle invocation timeout is 30 minutes.
JUnit output is removed before the run; a private init script disables both up-to-date
and build-cache reuse of the unit task only. At least1250 freshly executed tests and
zero failures/errors/skips are required. Receipt, raw log, exact source hashes and copied
JUnit reports are retained under `.git/madre-ci/gate-*/`. Debug and release compilation
may correctly use cache; tests cannot obtain a false PASS from old/cached XML.

All **admitted** local Gradle calls must use this common wrapper (including manual
assemble). This is a Git/build admission control, not an OS sandbox: an administrator
can still directly invoke an old checkout's untouched `./gradlew` or disable Git hooks.
The installer intentionally does not dirty or disguise tracked Gradle launchers.
Do not use direct legacy launchers or `--no-verify`. Raw Gradle outside the wrapper is
not claimed to be intercepted; parent rollout must switch all worker launch commands.

## One integrator, no implicit push

Pinned realpath: `/root/.hermes/work/madre-71-integrator-20261025`.
Workers publish only `wip/<task>` or `ci/*`. Only this workspace may push
`refs/heads/feat/razvorot-7-0-1`; nobody pushes main through these tools.

```
scripts/madre-integrate.sh <expected-current-full-sha> ff <full-worker-sha>
scripts/madre-integrate.sh <expected-current-full-sha> cherry-pick <sha1> <sha2>
```

The nonblocking integrator lock rejects concurrent helper calls. Dirty/stale/in-flight
state and a moved remote feature head reject admission. Full commit IDs are mandatory.
FF is fast-forward-only; a cherry-pick conflict remains visible/in-flight for explicit
resolution, never automatically reset. Integration is local only. A separate push must
pass the same hook on the final combined HEAD. No feature push is performed in this PR.

## Explicit reviewed installation (NOT executed by this task)

Run from the reviewed, clean tools checkout:

```
scripts/install-madre-ci.sh <reviewed-tools-full-sha> /root/.hermes/work/madre-ci-acceleration-evidence/20261025-155945/android-workspaces.json
```

The exact source SHA, all19 inventory workspaces plus the pinned integrator, cleanliness,
in-flight markers and absence of active Java/Gradle are preflighted before any write.
A dirty workspace blocks the entire installation; do not clean it automatically.
The installer copies canonical hooks/tools to a SHA-addressed directory under
`/root/.local/share/madre-ci-tools/`, preserves config privately and original gradlew in
each `.git/madre-ci/`, sets local `core.hooksPath`, and adds `.git/madre-ci/madre-gradle`.
It changes no tracked files. Manual calls can use that launcher from the repository
root. Existing hook directories are not removed. No backend/104/other profile target
can be admitted by the fixed immediate-child Android-workspace scope check.

Backups: `.git/madre-ci/config.before` (0600; never print, may contain secrets) and
`gradlew.original`. Rollback must first ensure no push/build is in flight, compare
current config to the install receipt, then restore only that workspace's local config;
tracked sources were never changed. Do not restore a backup over newer unrelated config.
Installation is deliberately all-or-fail preflight, but an I/O error during writes can
leave a partial install; preserve backups and finish or restore explicit exact targets.
Reinstallation is refused rather than overwrite prior backups.

## Tests

```
python3 -m unittest discover -s scripts/tests -v
```

Includes an executable fake-gh across all final outcomes, exit0/partial, missing/failed
required jobs, real lock exclusion/serialization, dirty/untracked/stale/in-flight/ref
admission, failing builds and cache-without-fresh-JUnit refusal. These fixtures do not
replace the real pre-push Android compile/release/executed-unit gate.
