# Мадре — серверная часть (PocketBase)

PocketBase отвечает по адресу `https://madre-api.kdnfx.space`
(Cycle 11 увёл его из домашней сети в production — теперь только HTTPS
и только с входом, см. DESIGN-V4.md Cycle 11).

Схема и маршруты с Cycle 11 версионируются в `backend/` (см. `backend/README.md`);
здесь описано то, что осталось от Cycle 5–7, — и эти коллекции миграция
`lock_legacy_collections` закрыла: правила у них были пустые, то есть публичные.

## Коллекции

Созданы вручную через админку PocketBase.

### Cycle 5–6 (уже на сервере)

- `bake_stats` — device_id, recipe_id, recipe_name, portions, baked_at (date)
- `feeding_stats` — device_id, flour_grams, water_grams, fed_at (date)
- `margin_notes_sync` — device_id, recipe_id, text, written_at (date)

### Cycle 7: `guest_notes` — гостевая страница

Поля:

| поле       | тип   | опции                    |
|------------|-------|--------------------------|
| recipe_id  | text  | required                 |
| author     | text  | max 40, можно пустое     |
| text       | text  | required, max 500        |
| created_at | date  | required                 |

API rules (гости пишут без аккаунта, книга только читает):

- **List/Search rule**: пустое (публичное чтение)
- **View rule**: пустое
- **Create rule**: пустое (публичная запись с телефона гостя)
- **Update rule / Delete rule**: заперто (только админ) — отзыв в книге
  не редактируется, как чернила на бумаге.

## Гостевая форма (pb_public)

`pb_public/guest.html` — публичная страница для гостей: открывается по QR
с экрана «Испечено» (BakingCompleteScreen), без установки приложения.
Адрес: `https://madre-api.kdnfx.space/guest.html?recipe=<id>&name=<название>`.

Развёртывание: скопировать `server/pb_public/guest.html` в каталог
`pb_public` рядом с бинарём PocketBase:

```sh
rsync -a server/pb_public/guest.html <сервер>:<каталог PocketBase>/pb_public/
```

(PocketBase раздаёт `pb_public` со своего корня, рестарт не нужен.)
