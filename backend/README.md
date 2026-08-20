# Мадре — backend (PocketBase 0.39.7)

Cycle 11 перевёл сервер из домашней сети в production: единственный адрес —
`https://madre-api.kdnfx.space`. Приложение ходит только по HTTPS, исключения
для cleartext в `network_security_config.xml` больше нет.

Схема и логика больше не заводятся руками через админку: всё, что определяет
сервер, лежит здесь и версионируется вместе с приложением.

```
backend/
├── pb_migrations/   схема и правила доступа (запускает сам PocketBase)
└── pb_hooks/        маршруты семейной книги (JSVM)
```

## Миграции (`pb_migrations`)

Файлы применяются по возрастанию имени и обратимы — у каждой `migrate()`
есть второй аргумент с откатом.

| файл | что делает |
|------|------------|
| `1784937600_lock_legacy_collections.js` | закрывает коллекции Cycle 5–7 (`bake_stats`, `feeding_stats`, `margin_notes_sync`, `guest_notes`), у которых правила остались пустыми, то есть публичными |
| `1784937660_created_families.js` | коллекция `families`: название, владелец, HMAC кода приглашения (hidden + уникальный индекс) |
| `1784937720_users_family_relation.js` | `users.family` + правила: читать чужие записи можно только внутри своей семьи |
| `1784937780_family_rules_for_stats.js` | bake_stats / feeding_stats: семья, client_event_id |
| `1787164800_bake_stats_shelf.js` | bake_stats: user, display_name, family_name, photo |

## Маршруты (`pb_hooks`)

`madre_family.pb.js` добавляет маршруты, все под `$apis.requireAuth()`:

| маршрут | тело | ответ |
|---------|------|-------|
| `POST /api/madre/family/create` | `{"name": "Ивановы"}` | `{"family_id", "family_name", "invite_code"}` |
| `POST /api/madre/family/join` | `{"code": "2W4X6Y8ZABCDEFGH"}` | `{"family_id", "family_name"}` |
| `POST /api/madre/family/invite` | — | `{"family_id", "family_name", "invite_code"}` |
| `PATCH /api/madre/family/rename` | `{"name"}` | `{"family_id", "family_name"}` — только тот, кто завёл; снимки в bake_stats не трогает |
| `POST /api/madre/family/leave` | — | `{"ok": true}` |

`madre_stats.pb.js` проставляет `family` и для `bake_stats` ещё `user` /
снимок подписи. Кадр: `POST /api/madre/shelf/photo` и
`POST /api/madre/shelf/photo/clear` — факт выпечки остаётся.

Членство пишет только сервер, из тела запроса оно не читается. На неверный
код `join` отвечает одинаковой ошибкой независимо от того, существует книга
или нет.

## Код приглашения

16 знаков алфавита Crockford base32 без `I/L/O/U` — ровно 80 бит, генерирует
`$security.randomStringWithAlphabet`. Открытым он живёт только в ответе на
`create`/`invite`: в базе лежит `$security.hs256(code, pepper)`.

Перец берётся из окружения и обязателен — без него маршруты отвечают 500,
а не молча хэшируют пустым ключом:

```sh
MADRE_INVITE_PEPPER=<32+ случайных байт, base64>
```

Задаётся в unit-файле сервиса (`Environment=`), в репозиторий не попадает.

## Развёртывание

```sh
rsync -a backend/pb_migrations/ <сервер>:<каталог PocketBase>/pb_migrations/
rsync -a backend/pb_hooks/      <сервер>:<каталог PocketBase>/pb_hooks/
systemctl restart pocketbase
```

PocketBase применяет новые миграции на старте и подхватывает хуки без
отдельной команды. TLS завершается на реверс-прокси перед PocketBase;
`https://madre-api.kdnfx.space` — единственный вход снаружи.

Сброс пароля — штатный маршрут PocketBase
`POST /api/collections/users/request-password-reset`. Клиент его вызывает;
SMTP настраивается в PocketBase отдельно, в этом репозитории писем нет.
