/**
 * Cycle 28: durable журнал прежнего членства на полке.
 *
 * users.family остаётся живым ACL и после ухода пустеет. Этот журнал хранит
 * только факт «человек уже был на этой полке» и не даёт доступ сам по себе.
 * Пишется только серверными маршрутами, общий CRUD закрыт наглухо.
 */
migrate((app) => {
    const families = app.findCollectionByNameOrId("families");
    const collection = new Collection({
        "id": "pbc_1787251200",
        "name": "family_members",
        "type": "base",
        "system": false,
        "listRule": null,
        "viewRule": null,
        "createRule": null,
        "updateRule": null,
        "deleteRule": null,
        "fields": [
            {
                "autogeneratePattern": "[a-z0-9]{15}",
                "hidden": false,
                "id": "text1787251201",
                "max": 15,
                "min": 15,
                "name": "id",
                "pattern": "^[a-z0-9]+$",
                "presentable": false,
                "primaryKey": true,
                "required": true,
                "system": true,
                "type": "text"
            },
            {
                "cascadeDelete": false,
                "collectionId": families.id,
                "hidden": false,
                "id": "relation1787251202",
                "maxSelect": 1,
                "minSelect": 0,
                "name": "family",
                "presentable": false,
                "required": true,
                "system": false,
                "type": "relation"
            },
            {
                "cascadeDelete": false,
                "collectionId": "_pb_users_auth_",
                "hidden": false,
                "id": "relation1787251203",
                "maxSelect": 1,
                "minSelect": 0,
                "name": "user",
                "presentable": false,
                "required": true,
                "system": false,
                "type": "relation"
            },
            {
                "autogeneratePattern": "",
                "hidden": false,
                "id": "text1787251204",
                "max": 6,
                "min": 4,
                "name": "status",
                "pattern": "^(active|left)$",
                "presentable": false,
                "primaryKey": false,
                "required": true,
                "system": false,
                "type": "text"
            },
            {
                "hidden": false,
                "id": "autodate1787251205",
                "name": "created",
                "onCreate": true,
                "onUpdate": false,
                "presentable": false,
                "system": false,
                "type": "autodate"
            },
            {
                "hidden": false,
                "id": "autodate1787251206",
                "name": "updated",
                "onCreate": true,
                "onUpdate": true,
                "presentable": false,
                "system": false,
                "type": "autodate"
            }
        ],
        "indexes": [
            "CREATE UNIQUE INDEX `idx_family_members_family_user` ON `family_members` (`family`, `user`)"
        ]
    });
    app.save(collection);

    const ledger = app.findCollectionByNameOrId("family_members");
    const PAGE = 500;
    let offset = 0;
    while (true) {
        const page = app.findRecordsByFilter(
            "users",
            "family != ''",
            "created,id",
            PAGE,
            offset,
        );
        if (!page || page.length === 0) {
            break;
        }
        for (const member of page) {
            const row = new Record(ledger);
            row.set("family", member.getString("family"));
            row.set("user", member.id);
            row.set("status", "active");
            app.save(row);
        }
        if (page.length < PAGE) {
            break;
        }
        offset += PAGE;
    }
}, (app) => {
    const collection = app.findCollectionByNameOrId("family_members");
    app.delete(collection);
});
