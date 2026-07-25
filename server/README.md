# Мадре — серверная часть (PocketBase)

PocketBase живёт на домашнем сервере: `http://192.168.3.59:8091`
(LXC, виден только из домашней сети — поэтому auth сознательно нет,
см. DESIGN-V4.md Cycle 5 и network_security_config.xml в приложении).

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
Адрес: `http://192.168.3.59:8091/guest.html?recipe=<id>&name=<название>`.

Развёртывание: скопировать `server/pb_public/guest.html` в каталог
`pb_public` рядом с бинарём PocketBase на LXC:

```sh
scp server/pb_public/guest.html root@192.168.3.59:/opt/pocketbase/pb_public/
```

(PocketBase раздаёт `pb_public` со своего корня, рестарт не нужен.)
