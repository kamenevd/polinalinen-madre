/** Family-v2 routing and pre-logger URL redaction (pinned PB 0.39.11). */
routerUse(new Middleware((e) => {
    const p = e.request.url.path;
    if (p.indexOf("/api/madre/invite/") === 0 || p.indexOf("/api/madre/web/invite/") === 0 || p.indexOf("/api/madre/family") === 0) {
        const q = e.request.url.rawQuery;
        const removal = e.request.method === "DELETE" && /^\/api\/madre\/family\/members\/[^/]+$/.test(p);
        e.set("madre:family:badurl", !!e.request.url.rawPath || (!!q && !(removal && /^rotate_invite=[01]$/.test(q))));
        e.set("madre:family:rotate", removal && q ? q.split("=")[1] : "");
        e.set("madre:family:token", e.request.pathValue("token"));
        e.request.url.rawQuery = "";
        e.request.url.rawPath = "";
        if (p.indexOf("/api/madre/invite/") === 0 || p.indexOf("/api/madre/web/invite/") === 0) {
            // Router has already selected the handler and path values; logger
            // later reads URL.RequestURI(). Mask failures/unknown routes too.
            e.request.url.path = p.indexOf("/api/madre/web/") === 0 ? "/api/madre/web/invite/[redacted]" : "/api/madre/invite/[redacted]";
        }
        e.request.requestURI = e.request.url.path;
        e.request.header.del("Referer"); e.request.header.del("User-Agent");
        e.response.header().set("Cache-Control", "no-store"); e.response.header().set("Referrer-Policy", "no-referrer");
    }
    return e.next();
}, -1000001, "madreFamilyPrivacy"));

// The native protected-file handler needs its query token during authorization.
// Run INSIDE the activity logger (priority -1040), redact on unwind BEFORE
// logRequest serializes URL.RequestURI(), including denied/expired file reads.
routerUse(new Middleware((e) => {
    if (e.request.url.path.indexOf("/api/files/") !== 0) { return e.next(); }
    e.request.header.del("Referer"); e.request.header.del("User-Agent");
    try { return e.next(); } finally { e.request.url.rawQuery = ""; e.request.url.rawPath = ""; }
}, -1039, "madreFamilyFilePrivacy"));

onRecordCreateRequest((e) => {
    if (Object.keys(e.requestInfo().body).some((k) => k.indexOf("family") === 0 || k.indexOf("membership_revision") === 0)) {
        throw new ForbiddenError("Membership is server managed.", null);
    }
    return e.next();
}, "users");
onRecordUpdateRequest((e) => {
    if (Object.keys(e.requestInfo().body).some((k) => k.indexOf("family") === 0 || k.indexOf("membership_revision") === 0)) {
        throw new ForbiddenError("Membership is server managed.", null);
    }
    return e.next();
}, "users");

routerAdd("GET", "/api/madre/invite/{token}", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "preview"));
routerAdd("GET", "/api/madre/web/invite/{token}", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "web"));
routerAdd("POST", "/api/madre/invite/{token}/join", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "join"), $apis.bodyLimit(65536));
routerAdd("GET", "/api/madre/family/requests/{request_id}/status", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "status"));
routerAdd("POST", "/api/madre/family/requests/{request_id}", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "decide"), $apis.bodyLimit(65536));
routerAdd("GET", "/api/madre/family", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "roster"));
routerAdd("PATCH", "/api/madre/family", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "settings"), $apis.bodyLimit(65536));
routerAdd("DELETE", "/api/madre/family", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "delete"));
routerAdd("GET", "/api/madre/family/invite", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "current"));
routerAdd("DELETE", "/api/madre/family/invite", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "revoke"));
routerAdd("PATCH", "/api/madre/family/members/{id}", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "label"), $apis.bodyLimit(65536));
routerAdd("DELETE", "/api/madre/family/members/{id}", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "remove"));
routerAdd("POST", "/api/madre/family/members/{id}/restore", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "restore"), $apis.bodyLimit(65536));
routerAdd("POST", "/api/madre/family/owner", (e) => require(__hooks + "/lib/family_v2.js").handle(e, "owner"), $apis.bodyLimit(65536));
