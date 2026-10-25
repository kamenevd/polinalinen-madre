/** 7.1 K2/K10 only. Additive; registration/password/code stay enabled. */
migrate((app) => {
    const users = app.findCollectionByNameOrId("users");
    if (!users.fields.getByName("yandex_uid_hash")) {
        users.fields.add(new TextField({
            name: "yandex_uid_hash", hidden: true, required: false,
            min: 0, max: 64, pattern: "^[a-f0-9]{64}$",
        }));
    }
    // PB text fields encode an absent value as "", not SQL NULL. A partial
    // unique index gives nullable-identity semantics to ALL existing records.
    const index = "CREATE UNIQUE INDEX idx_users_yandex_uid_hash ON users (yandex_uid_hash) WHERE yandex_uid_hash != ''";
    if (users.indexes.indexOf(index) < 0) { users.indexes.push(index); }
    // Hidden is a serialization flag, NOT write protection. Never accept a
    // client-supplied identity, including an empty value or field modifier.
    const guard = "@request.body.yandex_uid_hash:isset = false";
    for (const key of ["createRule", "updateRule"]) {
        if (users[key] !== null) {
            const prior = String(users[key]);
            if (prior.indexOf(guard) < 0) {
                users[key] = prior.length ? "(" + prior + ") && " + guard : guard;
            }
        }
    }
    // Default PB auth email is required. Permit a genuinely email-less
    // SERVER-created Yandex record, while preserving the old public email
    // registration requirement (do not create placeholder identities).
    const email = users.fields.getByName("email");
    if (email.required) {
        email.required = false;
        if (users.createRule !== null) {
            users.createRule = "(" + String(users.createRule) + ") && @request.body.email != ''";
        }
    }
    // No password/auth-rule/family/name/statistics or existing email changes.
    app.save(users);
}, (app) => {
    // Deliberately retain the additive field/index/guards on executable rollback.
    // Dropping a durable binding could create a duplicate identity on re-upgrade.
    // No old database snapshot restore, no clearing identities or user records.
});
