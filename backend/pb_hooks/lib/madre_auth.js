/** K2/K10: all identity mutations use the real PB nonconcurrent transaction. */
function fail(status, error) { const e = new Error(error); e.status = status; throw e; }

function configuration() {
    const client = $os.getenv("MADRE_YANDEX_CLIENT_ID");
    const salt = $os.getenv("MADRE_UID_SALT");
    const pepper = $os.getenv("MADRE_INVITE_PEPPER");
    if (!/^[a-zA-Z0-9_-]{1,128}$/.test(client) || salt.length < 32 ||
        salt.length > 1024 || !salt.trim() || salt === pepper) {
        fail(503, "unavailable");
    }
    return {client: client, salt: salt};
}

function verifiedIdentity(token, config) {
    let info;
    try { info = require(__hooks + "/lib/yandex_info.js")(token); }
    catch (err) {
        if (err && err.message === "unauthorized") { fail(401, "unauthorized"); }
        fail(503, "unavailable");
    }
    // Never coerce missing/numeric uid or a different application's client_id.
    if (!info || typeof info !== "object" || Array.isArray(info) ||
        info.client_id !== config.client || typeof info.id !== "string" ||
        !/^[0-9]{1,128}$/.test(info.id)) { fail(401, "unauthorized"); }
    // Only the allowed given name may enter the family domain. All other
    // provider attributes (even email/avatar/phone) are ignored, not merged.
    let name = typeof info.first_name === "string" ? info.first_name.trim() : "";
    if (name.length > 255 || /[\u0000-\u001f\u007f]/.test(name)) { name = ""; }
    return {hash: $security.hs256(info.id, config.salt), name: name};
}

function lookup(app, hash) {
    const rows = app.findRecordsByFilter("users", "yandex_uid_hash = {:hash}", "", 1, 0, {hash: hash});
    return rows.length ? rows[0] : null;
}

function emailPrincipal(app, token) {
    let record;
    try { record = app.findAuthRecordByToken(token, "auth"); }
    catch (ignored) { fail(401, "unauthorized"); }
    if (record.collection().name !== "users" || !record.getString("email")) {
        fail(401, "unauthorized");
    }
    return record;
}

module.exports = function (e, link) {
    e.response.header().set("Cache-Control", "no-store");
    e.response.header().set("Pragma", "no-cache");
    e.response.header().set("Referrer-Policy", "no-referrer");
    try {
        if (e.request.header.get("X-Madre-Family-Version") !== "2") {
            fail(426, "unsupported_protocol");
        }
        if (e.get("madre:auth:query")) { fail(400, "invalid_request"); }
        if (e.request.header.get("Content-Type").split(";")[0].trim().toLowerCase() !== "application/json") {
            fail(400, "invalid_request");
        }
        // Reject credentials on auth rather than silently authenticating a
        // different user. Existing sessions MUST use the explicit link route.
        const oldToken = e.request.header.get("Authorization");
        if (link) { emailPrincipal(e.app, oldToken); }
        else if (oldToken) { fail(400, "invalid_request"); }
        let body;
        try { body = e.requestInfo().body; } catch (ignored) { fail(400, "invalid_request"); }
        if (!body || Object.keys(body).length !== 1 || typeof body.yandex_token !== "string" ||
            !/^[A-Za-z0-9._~-]{1,4096}$/.test(body.yandex_token)) { fail(400, "invalid_request"); }
        const config = configuration();
        const identity = verifiedIdentity(body.yandex_token, config);
        let record;
        e.app.runInTransaction((txApp) => {
            // This wire contract has no MFA challenge completion operation.
            // Fail closed rather than quietly bypassing a future MFA policy.
            if (txApp.findCollectionByNameOrId("users").mfa.enabled) { fail(401, "unauthorized"); }
            // Revalidate the old JWT against the CURRENT tokenKey and expiry
            // after /info, inside the serialized write transaction (revoke race).
            const target = link ? emailPrincipal(txApp, oldToken) : null;
            const bound = lookup(txApp, identity.hash);
            if (link) {
                if (bound && bound.id !== target.id) { fail(409, "uid_already_linked"); }
                const prior = target.getString("yandex_uid_hash");
                if (prior && prior !== identity.hash) { fail(409, "conflict"); }
                record = target;
                if (!prior) {
                    record.set("yandex_uid_hash", identity.hash);
                    txApp.save(record);
                }
                // Idempotent link never changes the old name/password/family.
            } else if (bound) {
                record = bound;
            } else {
                record = new Record(txApp.findCollectionByNameOrId("users"));
                record.set("yandex_uid_hash", identity.hash);
                record.set("name", identity.name);
                record.setRandomPassword();
                // No fabricated email, verified flag, provider uid or OAuth row.
                txApp.save(record);
            }
            // Evaluate the native auth policy before commit. On a new record
            // this must follow save so the rule can query it; failure rolls the
            // entire transaction back, including the just-created identity.
            if (!txApp.canAccessRecord(record, e.requestInfo(), record.collection().authRule)) {
                fail(401, "unauthorized");
            }
        });
        // Use the official PB auth pipeline (AuthRule/MFA/auth hooks), not a
        // hand-signed JWT or a shortcut that disables existing access checks.
        // Hide even PB helper/expand fields so the wire DTO is id/name/family.
        for (const name of record.collection().fields.fieldNames()) {
            if (name !== "id" && name !== "name" && name !== "family") { record.hide(name); }
        }
        record.hide("collectionId", "collectionName", "expand");
        return $apis.recordAuthResponse(e, record, "yandex");
    } catch (err) {
        // No caught exception/upstream body/profile/token reaches PB logs.
        // A native MFA response can already be written; never overwrite it.
        if (e.written()) { return; }
        const allowed = {invalid_request: 400, unauthorized: 401, uid_already_linked: 409,
            conflict: 409, unsupported_protocol: 426, unavailable: 503};
        const key = err && Object.prototype.hasOwnProperty.call(allowed, err.message) ? err.message : "unavailable";
        return e.json(allowed[key], {error: key});
    }
};
