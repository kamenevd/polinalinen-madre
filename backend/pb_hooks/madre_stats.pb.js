/**
 * Cycle 17: чья это статистика — решает сервер.
 *
 * bake_stats и feeding_stats с миграции 1784937780 живут по семье: читать
 * можно только свои записи, создавать — только в свою книгу. Поле family при
 * этом клиент не присылает и прислать не может осмысленно: id семьи он у себя
 * не хранит (аккаунт живёт в памяти, а очередь отправки переживает и
 * перезапуск, и обновление приложения). Проставляем его здесь, из
 * @request.auth, до проверки правила создания.
 *
 * Пришедшее в теле значение затирается безусловно. Это не паранойя ради
 * симметрии с users.family: правило коллекции сверит family с семьёй
 * вошедшего и чужую запись отобьёт, но отбить её ошибкой 400 «не ваша семья»
 * — значит рассказать отправителю, что чужая семья существует. Затирание
 * оставляет ровно один возможный исход.
 *
 * Участник без семьи получает отказ здесь же и словами: правило коллекции
 * ответило бы ему тем же 400, но без единого намёка на то, что делать.
 */
onRecordCreateRequest((e) => {
    const family = e.auth ? e.auth.getString("family") : "";
    if (!family) {
        throw new BadRequestError("Сначала нужно завести или открыть семейную книгу.", null);
    }

    e.record.set("family", family);

    if (e.collection.name === "bake_stats") {
        // Автор книги — вошедший. Присланный user затирается, чтобы корешок
        // нельзя было повесить на чужое имя.
        e.record.set("user", e.auth.id);
        e.record.set("display_name", (e.auth.getString("name") || "").trim());
        try {
            const shelf = e.app.findRecordById("families", family);
            e.record.set("family_name", shelf.getString("name"));
        } catch (err) {
            e.record.set("family_name", "");
        }
    }

    e.next();
}, "bake_stats", "feeding_stats");

/**
 * Кадр на полке. Факт выпечки уже лежит; сюда приходит только файл.
 * Снять кадр можно тем же автором — строка формуляра остаётся.
 */
routerAdd("POST", "/api/madre/shelf/photo", (e) => {
    const family = e.auth.getString("family");
    if (!family) {
        throw new BadRequestError("Сначала нужно завести или открыть полку.", null);
    }

    const body = new DynamicModel({ id: "" });
    e.bindBody(body);
    if (!body.id) {
        throw new BadRequestError("Нужна запись выпечки.", null);
    }

    const files = e.findUploadedFiles("photo");
    if (!files || files.length === 0) {
        throw new BadRequestError("Нужен кадр.", null);
    }

    const rec = $app.findRecordById("bake_stats", body.id);
    if (rec.getString("user") !== e.auth.id) {
        throw new ForbiddenError("Кадр ставит тот, чья это выпечка.", null);
    }
    if (rec.getString("family") !== family) {
        throw new ForbiddenError("Эта выпечка с другой полки.", null);
    }

    rec.set("photo", files);
    $app.save(rec);
    return e.json(200, rec);
}, $apis.requireAuth());

routerAdd("POST", "/api/madre/shelf/photo/clear", (e) => {
    const family = e.auth.getString("family");
    if (!family) {
        throw new BadRequestError("Сначала нужно завести или открыть полку.", null);
    }

    const body = new DynamicModel({ id: "" });
    e.bindBody(body);
    if (!body.id) {
        throw new BadRequestError("Нужна запись выпечки.", null);
    }

    const rec = $app.findRecordById("bake_stats", body.id);
    if (rec.getString("user") !== e.auth.id) {
        throw new ForbiddenError("Кадр снимает тот, чья это выпечка.", null);
    }
    if (rec.getString("family") !== family) {
        throw new ForbiddenError("Эта выпечка с другой полки.", null);
    }

    rec.set("photo", null);
    $app.save(rec);
    return e.json(200, rec);
}, $apis.requireAuth());
