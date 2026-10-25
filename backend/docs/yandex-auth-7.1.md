# 7.1 K2/K10 — PocketBase auth, code-only candidate

Это не деплой, не настоящий вход в Яндекс и не готовность K1–K10 целиком.
База: `4e55c7a2b65bd44137e99ef83abd6a68fa0da487`; отдельная ветка
`feat/family-pb-auth-7-1-0`. Android, Алиса, shared edge и live DB не меняются.

## Источник и сохранение drift

До правок read-only сохранены **только** два deployed hooks, девять migrations
и бинарь; env/конфиги/пользовательская БД не извлекались. Все 11 хэшей повторно
сверены с источником. Приватная provenance/route и pre-edit backup — вне Git.
Публичные хэши: `pb-0.39.11-source-provenance.json` рядом с этим документом.

Drift разрешён историей: deployed `madre_family.pb.js` (SHA256 `d03c7381…`)
**побайтно равен** файлу в Git commit
`15adb6359f1c83900aeaa068333187de0facbc38`, не неизвестный новый hotfix.
Git-база содержит более поздний вариант `96debd46…` с membership ledger и
спящей полкой. Оба исходника сохранены: исторический hook — в
`tests/fixtures/deployed_family_15adb63.js`; кандидатный hook **не изменён**.
Четыре отсутствовавших в Git начальных schema exports импортированы побайтно
без данных; не переписаны старые существующие миграции.

Реальный isolated тест начинает с точного исторического hook/schema, заводит
синтетические email accounts/family/bake, затем обновляет **эту же** БД до Git
ledger + новой identity migration. Он сравнивает все прежние user columns,
семьи и выпечки. Это не разрешение вслепую rsync всего каталога на production:
при cutover parent отдельно учитывает уже существующий Git/live ledger delta.

## Реализовано

* `POST /api/madre/auth/yandex` и `POST /api/madre/auth/yandex/link` требуют
  `X-Madre-Family-Version: 2`; не меняют старые маршруты и DTO.
* Каждый запрос проверяет официальный `/info`, точный `client_id`, строковый
  decimal UID; HMAC-SHA256 с отдельным `MADRE_UID_SALT` (не invite pepper).
  Нет email merge, заимствования Alice binding или идентификации по имени.
* Одна partial-unique identity на `users`; отсутствие — PB text `""`, не SQL
  NULL. Hidden поле плюс правила/REQUEST hooks защищают клиентские записи.
* Link требует живой email-user PB JWT, перечитывает JWT/tokenKey **после**
  внешней проверки, внутри сериализованной PB write transaction. Конфликты
  `uid_already_linked`/`conflict` не меняют ни одну запись. Никакого rebind.
* Сохраняются id, email, password hash/tokenKey, старое name, family и stats;
  повторный login/link не переименовывает человека. Новый аккаунт хранит
  только разрешённый `first_name` как `name`, без email/avatar/raw UID/OAuth.
  Пустое first_name допускается, фиктивные имена/почта не создаются.
* Для новых server-created email-less users штатное поле email становится
  optional; публичный createRule сохраняет прежнее требование непустой почты.
  **Регистрация не закрыта**, passwordAuth и старый invite code не отключены.
* Токен выдаёт официальный `$apis.recordAuthResponse`, не собственный JWT.
  Native AuthRule проверяется до commit, отказ откатывает создание/link.
  При включённом MFA этот срез fail-closed: wire протокол не реализует MFA
  challenge completion. Нельзя считать его обходом MFA.
* Успешный DTO ровно `{token,record:{id,name,family}}`; query/expand отвергаются.
  OAuth не передаётся в PB logs/errors, curl argv/env/tempfile. PB log query,
  Referer/User-Agent очищаются до activity logger; no-store на auth ответах.
  Защитить логи reverse proxy и effective core/swap policy — отдельный gate
  parent перед активацией, не доказанный кодовым тестом.

## Почему не `$http.send`

В pinned 0.39.11 официальный bind вызывает Go `http.DefaultClient.Do` и
`io.ReadAll`; у JS config нет supported no-redirect/response-limit опций.
Выдуманные `allowRedirects:false`/`maxResponseSize` здесь не работают.
Используется существующий `/usr/bin/curl` через официальный `$os.cmd`, без
shell: `-q --config -`, OAuth в анонимном stdin pipe, явный минимальный env,
фиксированный HTTPS origin, без `--location`, `max-redirs=0`, TLS validation,
3s connect / 5s total timeout, чтение stdout ограничено 65541 bytes и child
reaped через `wait`. Это **не** lifecycle helper и не новый управляющий сервис.
Никаких runtime test URL/override env/token allowlist в production коде нет.
Read-only preflight подтвердил установленный curl 7.88.1; effective service
sandbox/exec/CA доступ проверяется parent в sanctioned deployment window.

## Pinned primary API evidence

Проверен официальный tag `v0.39.11`, commit
`5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457`, а не документация текущего master:

* JS bind `$os.cmd`, `$security.hs256`, `toString(reader,maxBytes)`,
  `routerUse/Middleware`, `$apis.recordAuthResponse` и ограничения `$http.send`:
  https://github.com/pocketbase/pocketbase/blob/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457/plugins/jsvm/binds.go
* Nonconcurrent transaction и её one-connection DB:
  https://github.com/pocketbase/pocketbase/blob/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457/core/db_tx.go
  https://github.com/pocketbase/pocketbase/blob/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457/core/base.go
* JWT type/expiry/current tokenKey validation и `canAccessRecord`:
  https://github.com/pocketbase/pocketbase/blob/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457/core/record_query.go
* AuthRule/MFA/official auth response:
  https://github.com/pocketbase/pocketbase/blob/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457/apis/record_helpers.go
* Field hiding и public serialization:
  https://github.com/pocketbase/pocketbase/blob/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457/core/record_model.go
* Реальные request log fields/priority:
  https://github.com/pocketbase/pocketbase/blob/5d217ddb50cb144d80a5d0b0bdf11b52b2c3e457/apis/middlewares.go
* Официальный Yandex `/info`, OAuth header и ответ `id/client_id/first_name`:
  https://yandex.ru/dev/id/doc/ru/user-information
* curl `-q`, config stdin, redirect defaults, max-time/protocol:
  https://curl.se/docs/manpage.html

Все использованные методы дополнительно исполнены настоящим JSVM binary;
нет предположений, что Node.js API доступны в Goja.

## Воспроизведение без production

```sh
python3 -m backend.tests.fetch_pocketbase /private/runtime/pocketbase
MADRE_PB_BINARY=/private/runtime/pocketbase python3 -m unittest discover -s backend/tests -v
python3 -m unittest discover -s scripts/tests -v
ANDROID_HOME=/opt/android-sdk timeout 1200 ./gradlew --offline :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon
```

Бинарь official release побайтно совпал с read-only deployed binary:
`88370d5f6fa4820cd2414fa53c6e168d3dd0e33b7a7fd9ff914265492a7aa3b6`.
Test fetcher откажется при несовпадении hash или перезаписи существующего файла.
CI `PocketBase auth` сам скачивает проверенный бинарь и запускает suite, не
имеет deployment credentials и не выгружает DB/log fixtures как артефакты.

Изоляция: собственный процесс и synthetic DB в private scratch directory;
mock меняет **только фиксированный внешний Yandex URL/protocol в копии**
transport module. Настоящие curl, PB HTTP/JWT/JSVM/transactions/SQLite/ACL
не подменяются. Проверяются upgrade, same-record link, все старые поля,
код/пароль/регистрация, family/bake isolation, wrong client/malformed upstream,
redirect refusal, bounded unknown-length body, conflicts, concurrent auth/link,
revocation during `/info`, настоящий SQL ABORT rollback, native AuthRule/MFA,
non-destructive down/up. Проверяется полный SQL dump + DB/WAL/SHM bytes и PB
stdout/auxiliary logs на OAuth/raw UID/запрещённые provider поля/secret canaries.
Child argv проверяется на настоящем зависшем у external boundary процессе.
В текущем LXC `/proc/<curl>/environ` возвращает EACCES: runtime env read не
выдаётся за проверенный; allowlist env проверяется source contract. Защита
procfs/ptrace не ослаблялась.

Down migration намеренно **не удаляет** identity field/index/guards; up
идемпотентен. Downgrade/re-upgrade не забывает уже сохранённую identity.
Это не rollback live DB на старый snapshot.

## Открыто до общего enable/deploy

* Exact-head CI и независимое review этого diff — parent.
* K3–K9, Android foreground coordinator/UI, публичные App Links/Huawei и
  настоящее пользовательское принятие K2/K10 не выполнены этим срезом.
* Parent: sanctioned PB lifecycle (пока не подтверждён), свежий полный
  DB/storage/code/config/env/keys/edge backup с tested rollback, latest email
  count/report, отдельный стабильный salt, log/core/swap/exec sandbox gates.
  Отсутствующий lifecycle не мешает изолированному code/test срезу; не создан
  обходной helper и не вызван restart/systemctl/live migration.
