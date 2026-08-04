/**
 * «Семейная книга» — Мадре, Cycle 11, фича 28 (PocketBase 0.39.7).
 *
 * Три маршрута поверх коллекций users/families, все под входом:
 *   create — завести книгу и получить код приглашения;
 *   join   — войти в чужую книгу по коду;
 *   invite — сменить код (только владелец книги).
 *
 * Членство (users.family) пишет только сервер и только из этих маршрутов:
 * правила коллекции users отбивают поле family, пришедшее от клиента.
 *
 * Код приглашения диктуют вслух и переписывают от руки, поэтому алфавит —
 * Crockford base32 без I/L/O/U: 16 знаков дают ровно 80 бит, перебором такое
 * не берут. На сервере лежит только HMAC кода с перцем из окружения —
 * утечка базы сама по себе не даёт войти в чужую книгу.
 *
 * Обработчики маршрутов PocketBase компилируются в отдельном рантайме и
 * внешнюю область файла не видят, поэтому единственные объявления констант
 * кладутся в общий $app.store(), а обработчики читают их оттуда по имени.
 */

const INVITE_CODE_LENGTH = 16;
const INVITE_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

$app.store().set("madre:invite:length", INVITE_CODE_LENGTH);
$app.store().set("madre:invite:alphabet", INVITE_CODE_ALPHABET);

routerAdd("POST", "/api/madre/family/create", (e) => {
    const pepper = $os.getenv("MADRE_INVITE_PEPPER");
    if (!pepper) {
        throw new ApiError(500, "Семейная книга на сервере ещё не настроена.", null);
    }

    if (e.auth.getString("family")) {
        throw new BadRequestError("Книга уже заведена — из неё сначала нужно выйти.", null);
    }

    const body = new DynamicModel({ name: "" });
    e.bindBody(body);

    const name = (body.name || "").trim();
    if (name.length < 1 || name.length > 60) {
        throw new BadRequestError("Книге нужно название — от 1 до 60 знаков.", null);
    }

    const code = $security.randomStringWithAlphabet(
        $app.store().get("madre:invite:length"),
        $app.store().get("madre:invite:alphabet"),
    );

    let created = null;

    $app.runInTransaction((txApp) => {
        const member = txApp.findRecordById("users", e.auth.id);
        // Проверка e.auth.family снаружи транзакции гонку не закрывает: два
        // параллельных create проходят её оба. Перечитываем членство здесь и
        // до создания книги — иначе второй запрос оставит осиротевшую family
        // без владельца, а этот rollback гарантирует, что её и не появится.
        if (member.getString("family")) {
            throw new BadRequestError("Книга уже заведена — из неё сначала нужно выйти.", null);
        }

        const families = txApp.findCollectionByNameOrId("families");

        created = new Record(families);
        created.set("name", name);
        created.set("owner", e.auth.id);
        created.set("invite_code_hash", $security.hs256(code, pepper));
        txApp.save(created);

        member.set("family", created.id);
        txApp.save(member);
    });

    // Открытый код показывается ровно один раз — второго шанса нет, дальше
    // только смена кода маршрутом invite.
    return e.json(200, {
        family_id: created.id,
        family_name: created.getString("name"),
        invite_code: code,
    });
}, $apis.requireAuth());

routerAdd("POST", "/api/madre/family/join", (e) => {
    // Один и тот же ответ на любой отказ: по нему нельзя понять, есть ли
    // вообще такая книга, поэтому коды нельзя перебирать «на ощупь».
    const JOIN_FAILURE = "Приглашение не подошло.";

    const pepper = $os.getenv("MADRE_INVITE_PEPPER");
    if (!pepper) {
        throw new ApiError(500, "Семейная книга на сервере ещё не настроена.", null);
    }

    if (e.auth.getString("family")) {
        throw new BadRequestError(JOIN_FAILURE, null);
    }

    const body = new DynamicModel({ code: "" });
    e.bindBody(body);

    // Та же нормализация, что и в приложении (account/InviteCode.kt): регистр,
    // разделители и канон Crockford O→0, I/L→1.
    const code = (body.code || "")
        .toUpperCase()
        .replace(/O/g, "0")
        .replace(/[IL]/g, "1")
        .replace(/[^0-9A-Z]/g, "");

    const alphabet = $app.store().get("madre:invite:alphabet");
    if (code.length !== $app.store().get("madre:invite:length")) {
        throw new BadRequestError(JOIN_FAILURE, null);
    }
    for (const symbol of code) {
        if (alphabet.indexOf(symbol) === -1) {
            throw new BadRequestError(JOIN_FAILURE, null);
        }
    }

    const digest = $security.hs256(code, pepper);
    let joined = null;

    $app.runInTransaction((txApp) => {
        try {
            joined = txApp.findFirstRecordByData("families", "invite_code_hash", digest);
        } catch (err) {
            joined = null;
        }
        if (!joined) {
            throw new BadRequestError(JOIN_FAILURE, null);
        }

        const member = txApp.findRecordById("users", e.auth.id);
        // Та же гонка, что в create: внешняя проверка пропускает два
        // параллельных join. Перечитываем членство до записи и отвечаем тем же
        // JOIN_FAILURE, чтобы не перезаписать уже проставленную книгу и не
        // выдать оракулом, что чьё-то приглашение подошло.
        if (member.getString("family")) {
            throw new BadRequestError(JOIN_FAILURE, null);
        }
        member.set("family", joined.id);
        txApp.save(member);
    });

    return e.json(200, {
        family_id: joined.id,
        family_name: joined.getString("name"),
    });
}, $apis.requireAuth());

routerAdd("POST", "/api/madre/family/invite", (e) => {
    const pepper = $os.getenv("MADRE_INVITE_PEPPER");
    if (!pepper) {
        throw new ApiError(500, "Семейная книга на сервере ещё не настроена.", null);
    }

    const familyId = e.auth.getString("family");
    if (!familyId) {
        throw new BadRequestError("Книга ещё не заведена.", null);
    }

    const code = $security.randomStringWithAlphabet(
        $app.store().get("madre:invite:length"),
        $app.store().get("madre:invite:alphabet"),
    );

    let rotated = null;

    $app.runInTransaction((txApp) => {
        rotated = txApp.findRecordById("families", familyId);
        if (rotated.getString("owner") !== e.auth.id) {
            throw new ForbiddenError("Код приглашения меняет только владелец книги.", null);
        }

        rotated.set("invite_code_hash", $security.hs256(code, pepper));
        txApp.save(rotated);
    });

    return e.json(200, {
        family_id: rotated.id,
        family_name: rotated.getString("name"),
        invite_code: code,
    });
}, $apis.requireAuth());
