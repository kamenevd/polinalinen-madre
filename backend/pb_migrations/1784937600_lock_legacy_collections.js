/**
 * Cycle 5–7 заводили коллекции руками через админку, и правила у них остались
 * пустыми — а пустое правило в PocketBase значит «можно кому угодно, без
 * входа». Пока сервер жил только в домашней сети, это было терпимо; на
 * madre-api.kdnfx.space так оставлять нельзя.
 *
 * null — это «через API нельзя никому», данные остаются доступны только
 * изнутри (хуки и админка). Cycle 12 перевесит эти коллекции на family.
 */
migrate((app) => {
    const names = ["bake_stats", "feeding_stats", "margin_notes_sync", "guest_notes"];

    for (const name of names) {
        let collection;
        try {
            collection = app.findCollectionByNameOrId(name);
        } catch (err) {
            // На свежем сервере коллекции Cycle 5–7 могли и не заводиться.
            continue;
        }

        collection.listRule = null;
        collection.viewRule = null;
        collection.createRule = null;
        collection.updateRule = null;
        collection.deleteRule = null;

        app.save(collection);
    }
}, (app) => {
    // Откат возвращает ровно то, что было до миграции: чтение и запись открыты
    // всем, правка и удаление закрыты.
    const open = "";
    const names = ["bake_stats", "feeding_stats", "margin_notes_sync", "guest_notes"];

    for (const name of names) {
        let collection;
        try {
            collection = app.findCollectionByNameOrId(name);
        } catch (err) {
            continue;
        }

        collection.listRule = open;
        collection.viewRule = open;
        collection.createRule = open;
        collection.updateRule = null;
        collection.deleteRule = null;

        app.save(collection);
    }
});
