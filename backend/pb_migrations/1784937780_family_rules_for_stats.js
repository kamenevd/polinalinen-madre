/**
 * Cycle 17: bake_stats и feeding_stats переезжают на семью.
 *
 * До этой миграции обе коллекции стояли закрытыми наглухо (1784937600
 * lock_legacy_collections: все правила null). Это было честнее публичного
 * доступа, но приложение всё равно продолжало слать туда POST и показывать
 * «Поделились» — доставки не было ни одной. Здесь коллекции получают ровно те
 * правила, под которые написан клиент:
 *
 *   создать — только вошедшему участнику семьи, и только в СВОЮ семью;
 *   читать  — только записи своей семьи.
 *
 * Поле family клиент не заполняет: его проставляет хук pb_hooks/madre_stats.pb.js
 * из @request.auth. Правило создания всё равно сверяет family с семьёй
 * вошедшего — сломавшийся хук должен ронять запись, а не открывать чужую книгу.
 *
 * client_event_id (ключ идемпотентности, sync/SyncEventId) до сих пор жил
 * договорённостью на стороне клиента. Уникальность его проверяет сервер —
 * СВОИМ индексом в КАЖДОЙ коллекции: общего пространства ключей у bake_stats и
 * feeding_stats нет, и выпечка не должна отбивать кормление с тем же номером.
 *
 * Индекс частичный (WHERE client_event_id != ''): у записей, поставленных в
 * очередь до Cycle 15, ключа нет, и в SQLite все они лягут пустой строкой —
 * а пустые строки, в отличие от NULL, друг другу равны. Полный UNIQUE пропустил
 * бы ровно одну такую запись и отбил все остальные.
 */
migrate((app) => {
    const names = ["bake_stats", "feeding_stats"];
    const owned = '@request.auth.id != "" && @request.auth.family != "" && family = @request.auth.family';

    for (const name of names) {
        let collection;
        try {
            collection = app.findCollectionByNameOrId(name);
        } catch (err) {
            // На свежем сервере коллекций Cycle 5 могло и не быть.
            continue;
        }

        const families = app.findCollectionByNameOrId("families");

        if (!collection.fields.getByName("family")) {
            collection.fields.add(new Field({
                "cascadeDelete": false,
                "collectionId": families.id,
                "hidden": false,
                "id": "relation1784937780",
                "maxSelect": 1,
                "minSelect": 0,
                "name": "family",
                "presentable": false,
                "required": true,
                "system": false,
                "type": "relation"
            }));
        }

        if (!collection.fields.getByName("client_event_id")) {
            collection.fields.add(new Field({
                "autogeneratePattern": "",
                "hidden": false,
                "id": "text1784937781",
                "max": 120,
                "min": 0,
                "name": "client_event_id",
                "pattern": "",
                "presentable": false,
                "primaryKey": false,
                "required": false,
                "system": false,
                "type": "text"
            }));
        }

        collection.indexes = [
            "CREATE UNIQUE INDEX `idx_" + name + "_client_event_id` ON `" + name +
                "` (`client_event_id`) WHERE `client_event_id` != ''"
        ];

        collection.listRule = owned;
        collection.viewRule = owned;
        collection.createRule = owned;
        // Статистика не правится и не удаляется через API вовсе: запись о
        // выпечке — событие, а не документ. Переписать историю книги нечем.
        collection.updateRule = null;
        collection.deleteRule = null;

        app.save(collection);
    }
}, (app) => {
    // Откат возвращает состояние после 1784937600: коллекции закрыты наглухо,
    // добавленных полей и индекса нет.
    const names = ["bake_stats", "feeding_stats"];

    for (const name of names) {
        let collection;
        try {
            collection = app.findCollectionByNameOrId(name);
        } catch (err) {
            continue;
        }

        collection.fields.removeById("relation1784937780");
        collection.fields.removeById("text1784937781");
        collection.indexes = [];

        collection.listRule = null;
        collection.viewRule = null;
        collection.createRule = null;
        collection.updateRule = null;
        collection.deleteRule = null;

        app.save(collection);
    }
});
