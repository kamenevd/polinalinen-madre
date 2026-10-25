/** Family-v2. No Node APIs, background purges, or production test seams. */
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
function fail(code, error, extra) { const e = new Error(error); e.code = code; e.extra = extra; throw e; }
function now() { return new Date().toISOString(); }
function plus(ms) { return new Date(Date.now() + ms).toISOString(); }
function rows(app, c, filter, params, limit) { return app.findRecordsByFilter(c, filter, "id", limit || 1000, 0, params || {}); }
function first(app, c, filter, params) { const r = rows(app, c, filter, params, 1); return r.length ? r[0] : null; }
function record(app, c, id) {
    // A missing row is not the same as SQL failure. Filter queries return []
    // for absence; actual DB errors propagate to the generic 503 boundary.
    const r = first(app, c, "id = {:id}", {id: id});
    if (!r) { fail(404, "not_found"); } return r;
}
function fresh(app, e, yandex) {
    let u;
    try { u = app.findAuthRecordByToken(e.request.header.get("Authorization"), "auth"); }
    catch (_) { fail(401, "unauthorized"); }
    if (u.collection().name !== "users" || (yandex && !u.getString("yandex_uid_hash"))) { fail(403, "unauthorized"); }
    e.auth = u; // RequestInfo refreshes its auth pointer from this fresh record.
    if (!app.canAccessRecord(u, e.requestInfo(), u.collection().authRule)) { fail(403, "unauthorized"); }
    return u;
}
function family(app, user) {
    if (!user.getString("family")) { fail(403, "unauthorized"); }
    const f = record(app, "families", user.getString("family"));
    if (f.getString("deleted_at")) { fail(403, "unauthorized"); }
    return f;
}
function owner(f, u) { if (f.getString("owner") !== u.id) { fail(403, "unauthorized"); } }
function people(app, f) { return rows(app, "users", "family = {:f}", {f: f.id}); }
function proof(app, f, u, create) {
    let m = first(app, "family_members", "family = {:f} && user = {:u}", {f: f.id, u: u.id});
    if (!m && create) {
        m = new Record(app.findCollectionByNameOrId("family_members"));
        m.set("family", f.id); m.set("user", u.id); m.set("status", "left");
        m.set("display_name", u.getString("name").substring(0, 80));
        m.set("role", "member"); m.set("joined_at", now());
    }
    return m;
}
function label(app, f, u) { const m = proof(app, f, u, false); return m ? m.getString("display_name") : u.getString("name").substring(0, 80); }
function bump(app, u) { u.set("membership_revision", u.getInt("membership_revision") + 1); app.save(u); }
function clearRestore(m) {
    m.set("restore_token_hash", ""); m.set("restore_until", ""); m.set("restore_owner", "");
    m.set("restore_security_revision", 0); m.set("restore_user_revision", 0);
}
function joined(app, f, u) {
    // Caller has already validated the owner restore/accept (or a fresh join).
    // A one-save SQL permit, not ledger status, allows the retained fence to
    // distinguish this transaction from byte-exact legacy rollback writers.
    const previous = proof(app, f, u, false);
    if (previous && previous.getBool("removed_once")) {
        app.db().newQuery("INSERT INTO _madre_family_join_permits (user, family) VALUES ({:u}, {:f})")
            .bind({u: u.id, f: f.id}).execute();
    }
    u.set("family", f.id); bump(app, u);
    const m = proof(app, f, u, true);
    m.set("status", "active"); m.set("role", f.getString("owner") === u.id ? "owner" : "member");
    m.set("joined_at", now()); clearRestore(m); app.save(m);
    const ev = new Record(app.findCollectionByNameOrId("family_events"));
    ev.set("family", f.id); ev.set("user", u.id); ev.set("kind", "joined"); ev.set("created_at", now()); app.save(ev);
    // Superseded requests cannot later move this account a second time.
    for (const r of rows(app, "family_requests", "user = {:u} && status = 'pending'", {u: u.id})) {
        r.set("status", "declined"); r.set("decided_at", now()); app.save(r);
    }
}
function sizeCheck(app, f) { if (people(app, f).length >= 8) { fail(409, "family_full"); } }
function decline(app, f) {
    for (const r of rows(app, "family_requests", "family = {:f} && status = 'pending'", {f: f.id})) {
        r.set("status", "declined"); r.set("decided_at", now()); app.save(r);
    }
}
function revoke(app, f) {
    for (const i of rows(app, "family_invites", "family = {:f} && revoked_at = ''", {f: f.id})) {
        i.set("revoked_at", now()); app.save(i);
    }
    f.set("security_revision", f.getInt("security_revision") + 1); app.save(f);
    decline(app, f);
}
function key() {
    const k = $os.getenv("MADRE_INVITE_DERIVATION_KEY");
    if (k.length < 32 || k.length > 1024 || !k.trim() || k === $os.getenv("MADRE_UID_SALT") || k === $os.getenv("MADRE_INVITE_PEPPER")) {
        fail(503, "unavailable");
    }
    return k;
}
function derive(i, k) {
    // First 96 HMAC bits encoded as 16 unbiased base64url symbols. Nonce is
    // independently crypto-random, public DB alone cannot reconstruct a URL.
    const hex = $security.hs256("madre.family.invite.v2\u0000" + i.getString("family") + "\u0000" + i.getString("nonce"), k);
    let t = "";
    for (let n = 0; n < 24; n += 3) { const x = parseInt(hex.substring(n, n + 3), 16); t += ALPHABET[x >> 6] + ALPHABET[x & 63]; }
    return t;
}
function rotate(app, f, u) {
    const k = key(); revoke(app, f);
    const i = new Record(app.findCollectionByNameOrId("family_invites"));
    i.set("family", f.id); i.set("nonce", $security.randomString(48));
    i.set("created_by", u.id); i.set("created_at", now()); i.set("expires_at", plus(7 * 24 * 3600 * 1000));
    const t = derive(i, k); i.set("token_hash", $security.sha256(t)); app.save(i);
    return {url: "https://kdnfx.space/madre/f/" + t, expires_at: new Date(i.getString("expires_at")).toISOString(), seats_left: Math.max(0, 8 - people(app, f).length)};
}
function alive(i) { return i && !i.getString("revoked_at") && Date.parse(i.getString("expires_at")) > Date.now(); }
function invite(app, token) {
    if (!/^[A-Za-z0-9_-]{16}$/.test(token)) { fail(404, "invite_unavailable"); }
    const i = first(app, "family_invites", "token_hash = {:h}", {h: $security.sha256(token)});
    if (!alive(i)) { fail(404, "invite_unavailable"); }
    const f = record(app, "families", i.getString("family"));
    if (f.getString("deleted_at") || !f.getString("owner")) { fail(404, "invite_unavailable"); }
    return {i: i, f: f};
}
function countBakes(app, id) { return app.countRecords("bake_stats", $dbx.hashExp({user: id})); }
function roster(app, f, u) {
    const members = people(app, f).map((p) => {
        const m = proof(app, f, p, false);
        if (!m || ["active", "joined"].indexOf(m.getString("status")) < 0) { fail(503, "unavailable"); }
        return {id: p.id, name: label(app, f, p), role: f.getString("owner") === p.id ? "owner" : "member",
            status: "joined", joined_at: new Date(m.getString("joined_at") || m.getString("created")).toISOString(), bakes_count: countBakes(app, p.id)};
    });
    if (!members.length || members.length > 8 || members.filter((m) => m.role === "owner").length !== 1) { fail(503, "unavailable"); }
    const pending = f.getString("owner") === u.id ? rows(app, "family_requests", "family = {:f} && status = 'pending'", {f: f.id}).filter((r) => requestValid(app, r)).map((r) => {
        const p = record(app, "users", r.getString("user"));
        return {id: r.id, user_id: p.id, name: label(app, f, p), status: "pending", requested_at: new Date(r.getString("requested_at")).toISOString()};
    }) : [];
    return {id: f.id, name: f.getString("name"), owner: f.getString("owner"), approval_required: f.getBool("approval_required"), members: members, pending: pending};
}
function requestValid(app, r) {
    const f = record(app, "families", r.getString("family"));
    const u = record(app, "users", r.getString("user"));
    const i = record(app, "family_invites", r.getString("invite"));
    return !f.getString("deleted_at") && f.getInt("owner_revision") === r.getInt("owner_revision") && alive(i) &&
        u.getInt("membership_revision") === r.getInt("source_revision") && u.getString("family") === r.getString("origin_family");
}
function reply(r) { return {status: r.getString("status"), family_id: r.getString("family"), request_id: r.id}; }
function softDelete(app, f) { f.set("deleted_at", now()); app.save(f); revoke(app, f); }
function transfer(app, f, u, target) {
    if (target === u.id) { return; }
    const next = record(app, "users", target);
    if (next.getString("family") !== f.id) { fail(409, "conflict"); }
    const old = proof(app, f, u, false), m = proof(app, f, next, false);
    if (!old || !m || ["active", "joined"].indexOf(m.getString("status")) < 0) { fail(409, "conflict"); }
    old.set("role", "member"); m.set("role", "owner"); app.save(old); app.save(m);
    f.set("owner", next.id); f.set("owner_revision", f.getInt("owner_revision") + 1); app.save(f); revoke(app, f);
}
function depart(app, f, u, newOwner) {
    if (f.getString("owner") === u.id) {
        if (people(app, f).length > 1) {
            if (!newOwner || newOwner === u.id) { fail(409, "owner_transfer_required"); }
            transfer(app, f, u, newOwner);
        } else { softDelete(app, f); }
    }
    const m = proof(app, f, u, true); m.set("status", "left"); clearRestore(m); app.save(m);
    u.set("family", ""); bump(app, u);
}
function switchOut(app, u, f) {
    if (u.getString("family") && u.getString("family") !== f.id) { depart(app, family(app, u), u, ""); }
}
function body(e, fields, required) {
    if (e.request.header.get("Content-Type").split(";")[0].trim().toLowerCase() !== "application/json") { fail(400, "invalid_request"); }
    let b; try { b = e.requestInfo().body; } catch (_) { fail(400, "invalid_request"); }
    if (!b || typeof b !== "object" || Array.isArray(b) || Object.keys(b).some((k) => fields.indexOf(k) < 0) ||
        (required || []).some((k) => !Object.prototype.hasOwnProperty.call(b, k))) { fail(400, "invalid_request"); }
    return b;
}
function text(v, max) { if (typeof v !== "string" || !v.trim() || v.trim().length > max || /[\u0000-\u001f\u007f]/.test(v)) { fail(400, "invalid_request"); } return v.trim(); }
function boolean(v) { if (typeof v !== "boolean") { fail(400, "invalid_request"); } return v; }
function rate(app, kind, id, max, ttl) {
    let allowed = false;
    app.runInTransaction((tx) => {
        const k = $security.sha256(kind + "\u0000" + id);
        // Only expiring abuse counters are deleted, never family/user history.
        for (const r of rows(tx, "family_limits", "until <= {:n}", {n: Date.now()}, 100)) { tx.delete(r); }
        let r = first(tx, "family_limits", "key = {:k}", {k: k});
        if (!r) { r = new Record(tx.findCollectionByNameOrId("family_limits")); r.set("key", k); r.set("until", Date.now() + ttl); }
        if (r.getInt("count") < max) { r.set("count", r.getInt("count") + 1); tx.save(r); allowed = true; }
    });
    if (!allowed) { fail(429, "rate_limited"); }
}
function safe(e, fn) {
    e.response.header().set("Cache-Control", "no-store"); e.response.header().set("Referrer-Policy", "no-referrer");
    try { return fn(); } catch (err) {
        const codes = {invalid_request: 400, unauthorized: 403, not_found: 404, invite_unavailable: 404, conflict: 409,
            already_in_family: 409, family_full: 409, owner_transfer_required: 409, unsupported_protocol: 426, rate_limited: 429, unavailable: 503};
        const k = err && Object.prototype.hasOwnProperty.call(codes, err.message) ? err.message : "unavailable";
        const out = {error: k}; if (k === "already_in_family" && err.extra) { out.family_name = err.extra; }
        return e.json(k === "unauthorized" && err.code === 401 ? 401 : codes[k], out);
    }
}
function handle(e, op) { return safe(e, () => {
    const versions = e.request.header.values("X-Madre-Family-Version");
    if (versions.length !== 1 || versions[0] !== "2") { fail(426, "unsupported_protocol"); }
    if (e.get("madre:family:badurl")) { fail(400, "invalid_request"); }
    const token = e.get("madre:family:token") || "";
    if (op === "preview" || op === "web") { rate(e.app, "preview", e.realIP(), 30, 60000); }
    if (op === "join") { rate(e.app, "join", fresh(e.app, e, true).id, 10, 3600000); }
    let result, status = 200;
    e.app.runInTransaction((app) => {
        function work() {
        if (op === "preview" || op === "web") {
            const x = invite(app, token), f = x.f, p = people(app, f);
            const inviter = record(app, "users", x.i.getString("created_by"));
            result = {family_name: f.getString("name"), inviter_name: label(app, f, inviter)};
            if (op === "preview") {
                result.members_count = p.length; result.bakes_count = p.reduce((s, u) => s + countBakes(app, u.id), 0);
                result.initials = p.map((u) => Array.from(label(app, f, u).trim())[0] || "·"); result.approval_required = f.getBool("approval_required");
            }
            return;
        }
        const u = fresh(app, e, true);
        if (op === "create") {
            const b = body(e, ["name"], ["name"]), name = text(b.name, 60);
            if (u.getString("family")) { fail(409, "already_in_family", family(app, u).getString("name")); }
            const f = new Record(app.findCollectionByNameOrId("families")); f.set("name", name); f.set("owner", u.id);
            // Retain a valid, unissued legacy code hash; never expose it in v2.
            f.set("invite_code_hash", $security.sha256($security.randomString(48))); app.save(f); joined(app, f, u);
            result = roster(app, f, u); return;
        }
        if (op === "join") {
            const b = body(e, ["switch_family"], ["switch_family"]), sw = boolean(b.switch_family);
            const x = invite(app, token), f = x.f;
            if (u.getString("family") === f.id) { result = {status: "joined", family_id: f.id, request_id: null}; return; }
            if (u.getString("family") && !sw) { fail(409, "already_in_family", family(app, u).getString("name")); }
            const m = proof(app, f, u, false);
            if (f.getBool("approval_required") || (m && m.getBool("removed_once"))) {
                let r = first(app, "family_requests", "family = {:f} && user = {:u} && status = 'pending'", {f: f.id, u: u.id});
                if (r && !requestValid(app, r)) { r.set("status", "declined"); r.set("decided_at", now()); app.save(r); r = null; }
                if (!r) {
                    sizeCheck(app, f);
                    r = new Record(app.findCollectionByNameOrId("family_requests")); r.set("family", f.id); r.set("user", u.id);
                    r.set("invite", x.i.id); r.set("status", "pending"); r.set("origin_family", u.getString("family"));
                    r.set("source_revision", u.getInt("membership_revision")); r.set("owner_revision", f.getInt("owner_revision"));
                    r.set("switch_family", sw); r.set("requested_at", now()); app.save(r);
                }
                result = reply(r); return;
            }
            sizeCheck(app, f); switchOut(app, u, f); joined(app, f, u);
            result = {status: "joined", family_id: f.id, request_id: null}; return;
        }
        if (op === "status") {
            const r = record(app, "family_requests", e.request.pathValue("request_id"));
            if (r.getString("user") !== u.id) { fail(404, "not_found"); }
            let s = r.getString("status");
            if (s === "pending" && !requestValid(app, r)) { s = "declined"; }
            if (s === "joined" && (u.getString("family") !== r.getString("family") || u.getInt("membership_revision") !== r.getInt("joined_revision"))) { s = "declined"; }
            result = reply(r); result.status = s; return;
        }
        const f = family(app, u);
        if (op === "roster") { result = roster(app, f, u); return; }
        if (op === "current") {
            const i = first(app, "family_invites", "family = {:f} && revoked_at = ''", {f: f.id});
            if (!alive(i)) { fail(404, "invite_unavailable"); }
            const t = derive(i, key()); if (!$security.equal($security.sha256(t), i.getString("token_hash"))) { fail(503, "unavailable"); }
            result = {url: "https://kdnfx.space/madre/f/" + t, expires_at: new Date(i.getString("expires_at")).toISOString()}; return;
        }
        if (op === "label") {
            const id = e.request.pathValue("id"), b = body(e, ["display_name"], ["display_name"]);
            if (u.id !== id) { owner(f, u); }
            const p = record(app, "users", id); if (p.getString("family") !== f.id) { fail(404, "not_found"); }
            const m = proof(app, f, p, false); if (!m) { fail(409, "conflict"); }
            m.set("display_name", text(b.display_name, 80)); app.save(m); result = roster(app, f, u); return;
        }
        if (op === "leave") {
            const b = body(e, ["new_owner_id"]);
            if (b.new_owner_id !== undefined && typeof b.new_owner_id !== "string") { fail(400, "invalid_request"); }
            depart(app, f, u, b.new_owner_id || ""); result = {ok: true}; return;
        }
        owner(f, u);
        if (op === "rotate") { body(e, []); result = rotate(app, f, u); return; }
        if (op === "revoke") { revoke(app, f); status = 204; return; }
        if (op === "settings") {
            const b = body(e, ["name", "approval_required"]); if (!Object.keys(b).length) { fail(400, "invalid_request"); }
            if (b.name !== undefined) { f.set("name", text(b.name, 60)); }
            if (b.approval_required !== undefined) { f.set("approval_required", boolean(b.approval_required)); }
            app.save(f);
        } else if (op === "remove") {
            const p = record(app, "users", e.request.pathValue("id"));
            if (p.id === u.id || p.getString("family") !== f.id) { fail(409, "conflict"); }
            const m = proof(app, f, p, false); if (!m) { fail(409, "conflict"); }
            const rot = e.get("madre:family:rotate"); if (rot !== "0" && rot !== "1" && rot !== "") { fail(400, "invalid_request"); }
            if (rot !== "0") { rotate(app, f, u); }
            p.set("family", ""); bump(app, p);
            const t = $security.randomStringWithAlphabet(32, ALPHABET);
            m.set("status", "removed"); m.set("removed_once", true); m.set("removed_by", u.id); m.set("removed_at", now());
            m.set("removal_revision", m.getInt("removal_revision") + 1); m.set("restore_token_hash", $security.sha256(t));
            m.set("restore_until", plus(600000)); m.set("restore_owner", u.id);
            m.set("restore_security_revision", f.getInt("security_revision")); m.set("restore_user_revision", p.getInt("membership_revision")); app.save(m);
            for (const r of rows(app, "family_requests", "family = {:f} && user = {:u} && status = 'joined'", {f: f.id, u: p.id})) {
                r.set("status", "declined"); app.save(r);
            }
            result = {restore_token: t, restore_until: new Date(m.getString("restore_until")).toISOString(), removal_revision: m.getInt("removal_revision")}; return;
        } else if (op === "restore") {
            const b = body(e, ["restore_token"], ["restore_token"]);
            const p = record(app, "users", e.request.pathValue("id")), m = proof(app, f, p, false);
            if (!m || typeof b.restore_token !== "string" || !/^[A-Za-z0-9_-]{32}$/.test(b.restore_token) ||
                m.getString("status") !== "removed" || p.getString("family") || m.getString("restore_owner") !== u.id ||
                m.getInt("restore_security_revision") !== f.getInt("security_revision") || m.getInt("restore_user_revision") !== p.getInt("membership_revision") ||
                !(Date.parse(m.getString("restore_until")) > Date.now()) || !$security.equal(m.getString("restore_token_hash"), $security.sha256(b.restore_token))) { fail(409, "conflict"); }
            sizeCheck(app, f); joined(app, f, p);
        } else if (op === "decide") {
            const b = body(e, ["accept"], ["accept"]), accept = boolean(b.accept);
            const r = record(app, "family_requests", e.request.pathValue("request_id"));
            if (r.getString("family") !== f.id) { fail(404, "not_found"); }
            if (r.getString("status") !== "pending") {
                if ((r.getString("status") === "joined") !== accept || r.getString("status") === "joined" && !joinedRequestCurrent(app, r)) { fail(409, "conflict"); }
            } else {
                if (!requestValid(app, r)) { fail(409, "conflict"); }
                if (accept) {
                    sizeCheck(app, f); const p = record(app, "users", r.getString("user"));
                    switchOut(app, p, f); joined(app, f, p); r.set("joined_revision", p.getInt("membership_revision"));
                }
                r.set("status", accept ? "joined" : "declined"); r.set("decided_at", now()); app.save(r);
            }
        } else if (op === "owner") {
            const b = body(e, ["member_id"], ["member_id"]); transfer(app, f, u, text(b.member_id, 15));
        } else if (op === "delete") {
            if (people(app, f).length !== 1) { fail(409, "owner_transfer_required"); }
            depart(app, f, u, ""); status = 204; return;
        } else { fail(400, "invalid_request"); }
        result = roster(app, f, u);
        }
        work();
        // Check actual UTF-8 bytes BEFORE commit, not after changing membership.
        if (status !== 204) {
            // Pinned tools/router/event.go uses Go json.Encoder: account for
            // HTML/U+2028/U+2029 escaping and its trailing newline as well.
            const wire = JSON.stringify(result).replace(/[<>&\u2028\u2029]/g, (c) => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0"));
            if (toBytes(wire).length + 1 > 65536) { fail(503, "unavailable"); }
        }
    });
    if (status === 204) { return e.noContent(204); }
    return e.json(200, result);
}); }
function joinedRequestCurrent(app, r) {
    const p = record(app, "users", r.getString("user"));
    return p.getString("family") === r.getString("family") && p.getInt("membership_revision") === r.getInt("joined_revision");
}
// Legacy helpers execute INSIDE existing serialized transactions. No side door
// through old code, hidden-field modifiers, old JWT family snapshots, or owner
// auto-transfer. Keep old password/code endpoints, not obsolete unsafe rights.
function legacyGuard(app, e) {
    if (e.request.header.values("X-Madre-Family-Version").length) { fail(426, "unsupported_protocol"); }
    return fresh(app, e, false);
}
function legacyJoin(app, f, u) {
    if (!u.getString("email") || f.getString("deleted_at") || f.getBool("approval_required")) { throw new BadRequestError("Приглашение не подошло.", null); }
    const m = proof(app, f, u, false);
    if ((m && m.getBool("removed_once")) || people(app, f).length >= 8 ||
        (!people(app, f).length && f.getString("owner") !== u.id)) { throw new BadRequestError("Приглашение не подошло.", null); }
}
function legacySynced(app, f, u) {
    const m = proof(app, f, u, true); m.set("role", f.getString("owner") === u.id ? "owner" : "member");
    if (!m.getString("joined_at")) { m.set("joined_at", now()); m.set("display_name", u.getString("name").substring(0, 80)); } clearRestore(m); app.save(m); bump(app, u);
    const ev = new Record(app.findCollectionByNameOrId("family_events")); ev.set("family", f.id); ev.set("user", u.id); ev.set("kind", "joined"); ev.set("created_at", now()); app.save(ev);
}
function legacyDeparture(app, f, u, others) {
    if (f.getString("deleted_at")) { throw new ForbiddenError("Membership required.", null); }
    if (f.getString("owner") === u.id) {
        if (others.length) { throw new ApiError(409, "owner_transfer_required", null); }
        softDelete(app, f);
    }
    u.set("membership_revision", u.getInt("membership_revision") + 1);
}
module.exports = {legacyDeparture: legacyDeparture, handle: handle, legacyGuard: legacyGuard, legacyJoin: legacyJoin, legacySynced: legacySynced, legacyRevoke: revoke};
