/**
 * «Семейная книга» (DESIGN-V4.md Cycle 11, фича 28) — одна запись на семью.
 *
 * Код приглашения в коллекции не лежит: хранится только его HMAC (хук
 * pb_hooks/madre_family.pb.js солит код перцем из MADRE_INVITE_PEPPER), да и
 * тот помечен hidden — наружу его не отдаёт даже участник семьи. Уникальный
 * индекс по хэшу нужен и для поиска по коду при вступлении, и чтобы два
 * разных приглашения не совпали.
 *
 * Правила: создать/изменить/удалить книгу через API нельзя никому — всё это
 * делают наши маршруты /api/madre/family/*, которые пишут в обход правил.
 * Читать книгу может только тот, кто в неё уже входит.
 */
migrate((app) => {
    const collection = new Collection({
        "id": "pbc_1784937660",
        "name": "families",
        "type": "base",
        "system": false,
        "listRule": "@request.auth.id != '' && id = @request.auth.family",
        "viewRule": "@request.auth.id != '' && id = @request.auth.family",
        "createRule": null,
        "updateRule": null,
        "deleteRule": null,
        "fields": [
            {
                "autogeneratePattern": "[a-z0-9]{15}",
                "hidden": false,
                "id": "text3208210256",
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
                "autogeneratePattern": "",
                "hidden": false,
                "id": "text2560465762",
                "max": 60,
                "min": 1,
                "name": "name",
                "pattern": "",
                "presentable": true,
                "primaryKey": false,
                "required": true,
                "system": false,
                "type": "text"
            },
            {
                "cascadeDelete": false,
                "collectionId": "_pb_users_auth_",
                "hidden": false,
                "id": "relation1841317431",
                "maxSelect": 1,
                "minSelect": 0,
                "name": "owner",
                "presentable": false,
                "required": true,
                "system": false,
                "type": "relation"
            },
            {
                "autogeneratePattern": "",
                "hidden": true,
                "id": "text4093183508",
                "max": 64,
                "min": 64,
                "name": "invite_code_hash",
                "pattern": "^[0-9a-f]+$",
                "presentable": false,
                "primaryKey": false,
                "required": true,
                "system": false,
                "type": "text"
            },
            {
                "hidden": false,
                "id": "autodate2990389176",
                "name": "created",
                "onCreate": true,
                "onUpdate": false,
                "presentable": false,
                "system": false,
                "type": "autodate"
            },
            {
                "hidden": false,
                "id": "autodate3332085495",
                "name": "updated",
                "onCreate": true,
                "onUpdate": true,
                "presentable": false,
                "system": false,
                "type": "autodate"
            }
        ],
        "indexes": [
            "CREATE UNIQUE INDEX `idx_families_invite_code_hash` ON `families` (`invite_code_hash`)"
        ]
    });

    app.save(collection);
}, (app) => {
    const collection = app.findCollectionByNameOrId("families");

    app.delete(collection);
});
