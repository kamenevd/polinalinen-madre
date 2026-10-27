# K11 web preview addendum — narrow read-only backend, no page/edge deployment

Parent authorization is limited to adding this endpoint within the membership feature. It does not authorize publishing a page, exposing an admin API, broadening CORS or changing any other DTO.

```http
GET /api/madre/web/invite/{token}
X-Madre-Family-Version: 2
```

- Same PocketBase family host and **exactly 16 base64url characters**; never trim, decode/normalize a different token spelling, put credentials in a query or follow redirects.
- Public, no Authorization required. Successful JSON is **only** `{ "family_name": string, "inviter_name": string }`; no counts, initials, IDs, people, bakes or approval state. No mutation or join occurs.
- Unknown/revoked/expired/soft-deleted invite: identical HTTP404 `{ "error": "invite_unavailable" }`. Noncanonical URL/query: invalid-request rejection; unsupported/missing family protocol: HTTP426. Do not reflect upstream error bodies or token URLs into logs/HTML.
- Shares app-preview's 30/min/IP budget; HTTP429 `{ "error": "rate_limited" }`. Native PB trusted-proxy configuration must be reviewed by parent without trusting arbitrary X-Forwarded-For input. Do not change global trust or CORS to make previews convenient.
- Responses have `Cache-Control: no-store` and `Referrer-Policy: no-referrer`. PB invite-path/query/Referer/User-Agent logging is redacted, including errors/unknown nested paths. Parent must independently mask the same secret path in every shared-edge access/error/analytics layer **before** public activation.
- Web writer must escape both untrusted labels, keep no-store/no-referrer/no trackers, package-allowlisted explicit «Открыть в Мадре» plus real RuStore fallback, no auto-open loop/open redirect/clipboard/deferred install tracking. Assetlinks/certificates/Huawei acceptance stay outside this backend slice. If same-origin hosting needs a scoped proxy, request parent implementation/review; do not invent an administrative allowlist.

Proof is the real pinned-PB test `test_04_web_preview_exact_two_fields_no_ids_people_counts_or_bakes`, shared rate/rotation/expiry/privacy regressions and SQL-bake-read-failure test proving this web path does not query bake data. Synthetic server tests are not public K11/Huawei acceptance.

## Renderer-only artifact
`backend.web.invite_page.render_invite` is a pure stdlib HTML/status/header renderer,
not a deployed route, HTTP client, proxy or hosting change. Callers supply the exact
two-field successful DTO and explicitly select a trusted release/QA package constant
(never query/user input); Preview delivery belongs to Designer, not this allowlist.
Labels are limited to 256 characters each and HTML to 16 KiB. Invalid DTOs return
generic 503; unavailable/invalid tokens return uniform 404; 429/503 remain truthful.
Only clicking the intent link launches; its encoded fallback is the same canonical
page, with a separate RuStore link and no deferred install or automatic retry loop.
Deployment, edge log masking, assetlinks/certificates and Huawei acceptance remain
later gates; renderer tests do not claim any of those gates passed.
