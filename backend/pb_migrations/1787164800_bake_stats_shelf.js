/**
 * Cycle 27: bake_stats знает автора книги, а не только телефон.
 *
 * Корешок на полке — PocketBase user id. device_id остаётся для ключа
 * идемпотентности и старых записей; новые строки получают user, снимок
 * подписи и снимок названия полки. Кадр — отдельное файловое поле, не
 * обязательное: факт выпечки живёт и без него, а снять кадр можно, не
 * стирая строку.
 *
 * required у user намеренно false: лежащие записи без автора не ломают
 * миграцию и на полке просто не заводят корешка.
 */
migrate((app) => {
    let collection;
    try {
        collection = app.findCollectionByNameOrId("bake_stats");
    } catch (err) {
        return;
    }

    if (!collection.fields.getByName("user")) {
        collection.fields.add(new Field({
            "cascadeDelete": false,
            "collectionId": "_pb_users_auth_",
            "hidden": false,
            "id": "relation1787164801",
            "maxSelect": 1,
            "minSelect": 0,
            "name": "user",
            "presentable": false,
            "required": false,
            "system": false,
            "type": "relation"
        }));
    }

    if (!collection.fields.getByName("display_name")) {
        collection.fields.add(new Field({
            "autogeneratePattern": "",
            "hidden": false,
            "id": "text1787164802",
            "max": 80,
            "min": 0,
            "name": "display_name",
            "pattern": "",
            "presentable": false,
            "primaryKey": false,
            "required": false,
            "system": false,
            "type": "text"
        }));
    }

    if (!collection.fields.getByName("family_name")) {
        collection.fields.add(new Field({
            "autogeneratePattern": "",
            "hidden": false,
            "id": "text1787164803",
            "max": 60,
            "min": 0,
            "name": "family_name",
            "pattern": "",
            "presentable": false,
            "primaryKey": false,
            "required": false,
            "system": false,
            "type": "text"
        }));
    }

    if (!collection.fields.getByName("photo")) {
        collection.fields.add(new Field({
            "hidden": false,
            "id": "file1787164804",
            "maxSelect": 1,
            "maxSize": 5242880,
            "mimeTypes": ["image/jpeg", "image/png", "image/webp"],
            "name": "photo",
            "presentable": false,
            "protected": true,
            "required": false,
            "system": false,
            "thumbs": [],
            "type": "file"
        }));
    }

    // Факт выпечки по-прежнему не правится через общий CRUD. Кадр снимают
    // и ставят только маршруты /api/madre/shelf/photo.
    collection.updateRule = null;
    collection.deleteRule = null;

    app.save(collection);
}, (app) => {
    let collection;
    try {
        collection = app.findCollectionByNameOrId("bake_stats");
    } catch (err) {
        return;
    }

    collection.fields.removeById("relation1787164801");
    collection.fields.removeById("text1787164802");
    collection.fields.removeById("text1787164803");
    collection.fields.removeById("file1787164804");
    app.save(collection);
});
