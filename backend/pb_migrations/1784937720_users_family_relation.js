/**
 * users.family — членство в семейной книге (Cycle 11, фича 28).
 *
 * Поле ставит только сервер: маршруты /api/madre/family/create и
 * /api/madre/family/join пишут его в обход правил. Правило обновления
 * поэтому явно отбивает поле family, пришедшее в теле запроса — иначе
 * любой участник вписал бы себе чужой family id и открыл чужую книгу.
 *
 * Читать чужие записи users можно только внутри своей семьи (Полка Cycle 12
 * показывает участников) и всегда — свою собственную. Удалять аккаунты через
 * API нельзя никому.
 */
migrate((app) => {
    const collection = app.findCollectionByNameOrId("users");
    const families = app.findCollectionByNameOrId("families");

    collection.fields.add(new Field({
        "cascadeDelete": false,
        "collectionId": families.id,
        "hidden": false,
        "id": "relation1784937720",
        "maxSelect": 1,
        "minSelect": 0,
        "name": "family",
        "presentable": false,
        "required": false,
        "system": false,
        "type": "relation"
    }));

    collection.listRule = "@request.auth.id != '' && (id = @request.auth.id || (@request.auth.family != '' && family = @request.auth.family))";
    collection.viewRule = "@request.auth.id != '' && (id = @request.auth.id || (@request.auth.family != '' && family = @request.auth.family))";
    collection.updateRule = "id = @request.auth.id && @request.body.family:isset = false";
    collection.deleteRule = null;

    app.save(collection);
}, (app) => {
    // Значения PocketBase по умолчанию для коллекции users. Константа объявлена
    // внутри отката: обработчики миграций выполняются в отдельной области и
    // внешних переменных файла не видят.
    const selfOnly = "id = @request.auth.id";
    const collection = app.findCollectionByNameOrId("users");

    collection.fields.removeById("relation1784937720");

    collection.listRule = selfOnly;
    collection.viewRule = selfOnly;
    collection.updateRule = selfOnly;
    collection.deleteRule = selfOnly;

    app.save(collection);
});
