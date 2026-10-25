/** Retained SQL fence: executable rollback must never undo an owner's removal. */
migrate((app) => {
    // Not an API collection. Only the trusted v2 transaction can mint a one-save
    // permit after owner/restore/request validation. Consumed by the user save;
    // transaction failure rolls both mint and consumption back. No durable grant.
    app.db().newQuery(`CREATE TABLE IF NOT EXISTS _madre_family_join_permits (
        user TEXT NOT NULL, family TEXT NOT NULL, PRIMARY KEY (user, family)
    )`).execute();
    app.db().newQuery(`CREATE TRIGGER IF NOT EXISTS madre_family_removed_join_fence
        BEFORE UPDATE OF family ON users
        WHEN NEW.family <> '' AND NEW.family <> OLD.family
        AND EXISTS (SELECT 1 FROM family_members
            WHERE user = NEW.id AND family = NEW.family AND removed_once = 1)
        AND NOT EXISTS (SELECT 1 FROM _madre_family_join_permits
            WHERE user = NEW.id AND family = NEW.family)
        BEGIN SELECT RAISE(ABORT, 'owner approval required'); END`).execute();
    app.db().newQuery(`CREATE TRIGGER IF NOT EXISTS madre_family_join_permit_consume
        AFTER UPDATE OF family ON users
        BEGIN DELETE FROM _madre_family_join_permits WHERE user = NEW.id; END`).execute();
}, (app) => {
    // Security floor: retain triggers/table on code/migration rollback. Re-up is
    // idempotent. Never restore stale data or drop revocation metadata.
});
