# Weekly generated retention — review/activation is separate

This change installs **nothing**, deletes **no real files**, and changes no app,
workflow, Gradle property, feature/main ref, service, artifact slot or existing cron.
CLI is dry-run unless `--apply` is explicit. There is no configurable CLI root:
only `/root/.hermes/work/madre-apk`, `/root/.gradle/caches`, and registered Android
`/root/.hermes/work/madre-*` clones can supply candidates. Backend clone prefixes,
104 imports and other profiles are excluded without traversal.

## Plan / evidence

```
python3 scripts/tests/test_weekly_generated_cleanup.py
python3 scripts/weekly_generated_cleanup.py --lock-wait 120 \
  --output /root/.hermes/work/madre-ci-cleanup-evidence/<unique>/dry-run.json
```

The same `/root/.cache/madre/gradle.lock` is held through planning, preservation
verification and any apply. Default wait is nonblocking; maximum is 300 seconds.
An unreadable live process, unsafe generated subtree, unknown ancestry or filesystem
error never grants deletion. Plans contain exact paths, identities, hashes, bytes,
retention reasons, source/status manifests, errors and measured statvfs delta.
Statvfs is a filesystem-wide measurement, not an attribution guarantee: other host
writes may make even a dry-run delta nonzero/negative. `deleted` is authoritative.
Do not treat candidate byte sizes as actual freed bytes (hardlinks/sparse files).

### Retention decisions

* Keep newest five regular APKs by mtime plus every INDEX/flat receipt basename,
  absolute/relative path or SHA reference, regardless of accepted/unclear status.
  Keep latest symlink/target, exact current canonical-head candidate and unknown names.
  Missing/ambiguous INDEX, opaque metadata or evidence subdirectory keeps all APKs.
  Known PNG and versioned *-png*.zip previews are preserved payloads, not ambiguous
  metadata. INDEX backups and flat receipts still contribute every reference; malformed
  JSON and unknown metadata remain fail-closed. Historical candidates/7.1 files are
  not protected just by their name. Non-APK files are never candidates; metadata and retained artifacts are hashed
  before/after. Evidence directories are opaque, never recursively inspected.
* Cache candidates are **only** immutable dependency payload files at the exact
  `modules-2/files-2.1/group/module/version/hash/file` depth, with recognized
  jar/aar/pom/module suffixes and max(atime,mtime,ctime) strictly older than 14 days.
  Kernel-owned cache locks (POSIX/OFD/flock via /proc/locks), BUSY/unknown daemons
  and workers retain the cache. Persistent unlocked lockfiles and explicitly IDLE
  daemons do not. The last daemon-log busy/idle transition proves idle; missing or
  unreadable state fails closed. Current/open/mapped payloads remain protected.
  Journals, daemon state, transforms and unknown cache layouts are retained.
  No process is killed; no blanket cache directory deletion exists.
* Build candidates are only `build`, `app/build`, `benchmark/build` in known,
  hook-registered clean Android clones whose HEAD is provably an ancestor of the
  current canonical integrator HEAD, using the canonical object store (not a stale
  clone's missing successor). HEAD equal to current is retained. ci/*, wip/*, integrator,
  current madre-711-alice-ui-20261025, tool runtime, dirty/unmerged/locked/open work
  are retained. A clean inactive feature-branch predecessor is eligible. Tracked contents in
  generated paths disqualify deletion. Track source hashes/status before/after.
* All clones and linked worktrees are retained as source trees; worktree removals
  are deliberately **zero**, not inferred from standalone clones. This narrower
  policy avoids any `git worktree remove`/source removal in initial activation.
* Symlink components, nested mounts/device changes and open cwd/fd/maps targets
  cannot be candidates. Apply validates exact identities/content again and uses
  descriptor-relative no-follow path traversal for unlink/rmdir. It checks live
  processes and protected manifests before each candidate; the build lock is not
  an OS sandbox for arbitrary administrators/writers ignoring admission.

Fixture tests use temporary roots under TMPDIR, including actual fixture apply,
INDEX forms/status/SHA, latest/current, five newest, boundary/access/cache locks,
active/dirty/unmerged/ci/wip/integrator, source preservation, mount/symlink escapes,
no104 traversal and common-lock conflict. Real runs in this task are dry-run only.
The mandatory Android pre-push hook independently executes >=1250 fresh unit tests
with zero failures/errors/skips plus debug/release Kotlin compilation under flock.

## Weekly cron TEMPLATE ONLY — do not install

Parent must review the final PR, freeze the exact reviewed script bytes in a
SHA-addressed directory, verify digest, create a private logs directory, and
confirm the system cron timezone is **Europe/Moscow**. This host-local-time
schedule is Sunday **05:20 MSK**; do not assume CRON_TZ is supported by Debian cron.
Replace REVIEWED_FULL_SHA before use; no mutable checkout, model or network needed.

```
# /etc/cron.d/madre-generated-cleanup — TEMPLATE, NOT INSTALLED
SHELL=/bin/sh
PATH=/usr/bin:/bin
20 5 * * 0 root umask 077; /usr/bin/python3 /root/.local/share/madre-cleanup/REVIEWED_FULL_SHA/weekly_generated_cleanup.py --lock-wait 120 --output /var/log/madre-cleanup/plan-$(date +\%Y\%m\%d-\%H\%M\%S).json >>/var/log/madre-cleanup/weekly.log 2>&1
```

Template remains **dry-run**. Only the parent, after the single cleanup review and
explicit activation approval, may add `--apply` and install cron. A saved JSON plan
is evidence, never a replayable deletion authorization. Apply always replans under
the common lock. No services/self-hosted runners/merge/release/RuStore actions.
