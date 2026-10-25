/** K3–K9 additive upgrade. NEVER close registration or purge user data here. */
migrate((app) => {
    function add(c, f) { if (!c.fields.getByName(f.name)) { c.fields.add(f); } }
    function text(name, max) { return new TextField({name: name, max: max || 255}); }
    function number(name) { return new NumberField({name: name, onlyInt: true, min: 0}); }
    function date(name) { return new DateField({name: name}); }
    function relation(name, c) { return new RelationField({name: name, collectionId: c.id, maxSelect: 1, cascadeDelete: false}); }
    function privateCollection(name, fields, indexes) {
        let c;
        try { c = app.findCollectionByNameOrId(name); } catch (_) {
            c = new Collection({name: name, type: "base"});
        }
        for (const f of fields) { add(c, f); }
        for (const idx of indexes || []) { if (c.indexes.indexOf(idx) < 0) { c.indexes.push(idx); } }
        for (const rule of ["listRule", "viewRule", "createRule", "updateRule", "deleteRule"]) { c[rule] = null; }
        app.save(c);
        return c;
    }
    const users = app.findCollectionByNameOrId("users");
    const revision = number("membership_revision"); revision.hidden = true;
    add(users, revision);
    for (const rule of ["createRule", "updateRule"]) {
        if (users[rule] !== null) {
            for (const field of ["family", "membership_revision"]) {
                const guard = "@request.body." + field + ":isset = false";
                if (String(users[rule]).indexOf(guard) < 0) { users[rule] = "(" + String(users[rule]) + ") && " + guard; }
            }
        }
    }
    app.save(users);
    const families = app.findCollectionByNameOrId("families");
    add(families, new BoolField({name: "approval_required"}));
    add(families, date("deleted_at"));
    add(families, number("security_revision"));
    add(families, number("owner_revision"));
    families.listRule = "@request.auth.id != '' && id = @request.auth.family && deleted_at = ''";
    families.viewRule = families.listRule;
    app.save(families);
    const members = app.findCollectionByNameOrId("family_members");
    const status = members.fields.getByName("status");
    status.max = 8; status.min = 4;
    // Keep historical active on disk, serialize it as joined in the v2 DTO.
    status.pattern = "^(active|joined|left|removed|pending|dormant)$";
    for (const f of [text("role", 6), text("display_name", 80), date("joined_at"),
        new BoolField({name: "removed_once"}), relation("removed_by", users), date("removed_at"),
        number("removal_revision"), text("restore_token_hash", 64), date("restore_until"),
        relation("restore_owner", users), number("restore_security_revision"), number("restore_user_revision")]) { add(members, f); }
    app.save(members);
    // Paginated, metadata-only backfill. Preserve every old user/family/bake value.
    while (true) {
        const rows = app.findRecordsByFilter("family_members", "role = ''", "id", 500, 0);
        if (!rows.length) { break; }
        for (const row of rows) {
            const f = app.findRecordById("families", row.getString("family"));
            const u = app.findRecordById("users", row.getString("user"));
            row.set("role", f.getString("owner") === u.id ? "owner" : "member");
            row.set("display_name", u.getString("name").substring(0, 80));
            row.set("joined_at", row.getString("created"));
            app.save(row);
        }
        // Query only unbackfilled rows: don't advance past newly excluded rows.
    }
    const invites = privateCollection("family_invites", [relation("family", families), text("nonce", 64),
        text("token_hash", 64), relation("created_by", users), date("created_at"), date("expires_at"), date("revoked_at")], [
        "CREATE UNIQUE INDEX idx_family_invite_hash ON family_invites (token_hash)",
        "CREATE UNIQUE INDEX idx_family_invite_active ON family_invites (family) WHERE revoked_at = ''"
    ]);
    privateCollection("family_requests", [relation("family", families), relation("user", users),
        relation("invite", invites), text("status", 8), text("origin_family", 15), number("source_revision"),
        number("joined_revision"), number("owner_revision"), new BoolField({name: "switch_family"}),
        date("requested_at"), date("decided_at")], [
        "CREATE UNIQUE INDEX idx_family_pending_user ON family_requests (family, user) WHERE status = 'pending'"
    ]);
    privateCollection("family_events", [relation("family", families), relation("user", users), text("kind", 16), date("created_at")]);
    privateCollection("family_limits", [text("key", 64), number("count"), number("until")], [
        "CREATE UNIQUE INDEX idx_family_limit_key ON family_limits (key)"
    ]);
    // Old rule used a family snapshot. Removed authors must disappear from the
    // family feed immediately; their OWN book and immutable bake rows survive.
    const bakes = app.findCollectionByNameOrId("bake_stats");
    bakes.listRule = "@request.auth.id != '' && (user = @request.auth.id || (@request.auth.family != '' && user.family = @request.auth.family && user.family.deleted_at = ''))";
    bakes.viewRule = bakes.listRule;
    app.save(bakes);
}, (app) => {
    // Non-destructive executable rollback retains membership/identity/revocation
    // and ACL guards. Never restore an old snapshot over newer user writes.
});
