/** 7.1 auth slice only; legacy family/stat hooks are deliberately unchanged. */
routerUse(new Middleware((e) => {
    const path = e.request.url.path;
    if (path === "/api/madre/auth/yandex" || path === "/api/madre/auth/yandex/link") {
        // Runs BEFORE PocketBase's activity logger. Bodies/Authorization aren't
        // logged by PB, but query, Referer and User-Agent otherwise are.
        e.set("madre:auth:query", !!e.request.url.rawQuery);
        e.request.url.rawQuery = "";
        e.request.header.del("Referer");
        e.request.header.del("User-Agent");
        e.response.header().set("Cache-Control", "no-store");
    }
    return e.next();
}, -1000000, "madreAuthPrivacy"));

/** Client body modifiers (e.g. field+) aren't covered by :isset alone in
 * 0.39.11. These REQUEST-only hooks never run for our server-side tx saves. */
onRecordCreateRequest((e) => {
    if (Object.keys(e.requestInfo().body).some((key) => key.indexOf("yandex_uid_hash") === 0)) {
        throw new ForbiddenError("Identity is server managed.", null);
    }
    return e.next();
}, "users");
onRecordUpdateRequest((e) => {
    if (Object.keys(e.requestInfo().body).some((key) => key.indexOf("yandex_uid_hash") === 0)) {
        throw new ForbiddenError("Identity is server managed.", null);
    }
    return e.next();
}, "users");

routerAdd("POST", "/api/madre/auth/yandex", (e) => {
    return require(__hooks + "/lib/madre_auth.js")(e, false);
}, $apis.bodyLimit(65536));

routerAdd("POST", "/api/madre/auth/yandex/link", (e) => {
    return require(__hooks + "/lib/madre_auth.js")(e, true);
}, $apis.bodyLimit(65536));
