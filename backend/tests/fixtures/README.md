# Immutable historical family hooks

These public source snapshots exercise actual old PocketBase writers against the
latest migrated synthetic database. They are not approximations or rewritten
hooks. Runtime tests require neither Git nor historical objects in a checkout.

Both originate from `backend/pb_hooks/madre_family.pb.js` in
`kamenevd/polinalinen-madre`:

| Fixture | Original commit | SHA256 of uncompressed source |
| --- | --- | --- |
| `baseline_family_7af7c32.js.gz` | `7af7c3297acb3e41b77491c0c42c0a4c8e396072` | `96debd46afdcb6b547bee5ede088df16d9ef33881a1f23c70499b38cf7715338` |
| `deployed_family_15adb63.js` | `15adb6359f1c83900aeaa068333187de0facbc38` | `d03c7381e9e999faa5c4fc7bece59ec35bbdfc26244136491d550474cdfb70d3` |

The baseline archive preserves the original bytes, compressed with Python
`gzip.GzipFile(filename="", mode="wb", compresslevel=9, mtime=0)`. No source
normalization is permitted. The deployed fixture is reused unchanged by both
the upgrade harness and the retained-fence regression. Tests verify each source
digest before starting its real old hook; missing/corrupt fixtures fail, without
a history fallback or skipped security assertions.