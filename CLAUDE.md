# Мадре — инструкции для Claude Code

«Живая книга» о выпечке на закваске: рецепты, таймеры выпечки, дневник
закваски, общая книга на семью. Android, только светлая тема, весь текст —
по-русски.

Документ описывает архитектуру **на Cycle 18**. Если коммит меняет
архитектуру — CLAUDE.md обновляется **в том же коммите**, а не «потом».

---

## Стек

| Слой | Чем |
|---|---|
| Язык | Kotlin 2.4.10 (KSP 2.3.11 для Room) |
| UI | Jetpack Compose, BOM 2026.06.01, компилятор — плагин `kotlin.plugin.compose` |
| Навигация | Navigation Compose, `NavController` + маршруты-строки |
| Данные | Room 2.8.4 (`madre.db`), `exportSchema = true` |
| Сеть | Retrofit + Gson → PocketBase, Open-Meteo для погоды |
| Фон | WorkManager + один foreground service |
| Архитектура | MVVM: Application → repositories → ViewModel → Compose |
| AGP | 9.3.1, compileSdk 35, Gradle 9.6.1 |

### Тулчейн (Cycle 16)

С AGP 9 поддержка Kotlin **встроена в сам AGP**: плагин
`org.jetbrains.kotlin.android` в `app/build.gradle.kts` не применяется —
сборка падает на конфигурации, если его вернуть. В корневом
`build.gradle.kts` он объявлен с `apply false` и только затем, чтобы поднять
KGP на classpath с 2.2.10 (версия внутри AGP) до 2.4.10.

Отсюда же следствия:

- `android.kotlinOptions` больше нет — `jvmTarget` задаётся в
  `kotlin { compilerOptions { … } }`;
- `composeOptions.kotlinCompilerExtensionVersion` больше нет — версией
  Compose-компилятора управляет плагин `org.jetbrains.kotlin.plugin.compose`,
  его версия обязана совпадать с версией Kotlin;
- kapt заменён на KSP (`ksp("androidx.room:room-compiler")`), аргумент
  `room.schemaLocation` переехал из блока `kapt {}` в `ksp {}`.

**Strong skipping включён** — с Kotlin 2.0.20 это поведение Compose-компилятора
по умолчанию, отдельного флага не нужно. Проверяется не на глаз:
`composeCompiler { metricsDestination }` пишет `app-module.json`, где есть
`"featureFlags": { "StrongSkipping": true }`.

Room подняли до 2.8.4 вынужденно: процессор 2.6.1 не работает с KSP2.
`identityHash` схемы версии 8 при этом **не изменился** — лежащая на телефонах
`madre.db` открывается той же миграционной историей. Room 2.8 не переписывает
файл схемы, если хэш совпал, поэтому `app/schemas` остались как были.

Sealed-class навигации из v3 **нет** и не должно появиться: она была размазана
по `when`-веткам внутри `AnimatedContent`.

---

## Архитектура

```
MadreApplication          единственный владелец Room и репозиториев (by lazy)
  ├── database            MadreDatabase.build(this)
  ├── recipeRepository    recipes.json из assets
  ├── sourdoughRepository закваска: конфиг + кормления
  ├── bakeHistoryRepository  завершённые выпечки
  ├── activeBakeRepository   незавершённые выпечки (переживают перезагрузку)
  ├── familySettingsRepository
  ├── madreApi / familyAccountRepository  PocketBase
  ├── syncRepository      очередь фоновой отправки
  └── activeBakes         что сейчас в печи (ViewModel пишет, service читает)
        ↓
   ViewModel (viewmodel/)  StateFlow наружу
        ↓
   Compose screens (ui/screens/)  без прямого доступа к DAO
```

**Правило:** Room и репозитории создаются **только** в `MadreApplication`,
никогда в ViewModel. Это закрывает баг v3 #1 — `db.close()` в `onCleared()`
ронял приложение при повторном входе на экран.

**Правило:** всё I/O — на `Dispatchers.IO` (баг v3 #6: GSON и `file.delete()`
на главном потоке).

### Пакеты

- `data/db` — Room: сущности, DAO, миграции
- `data/remote` — Retrofit-интерфейсы и фабрики
- `data/repository` — репозитории поверх DAO
- `model/` — чистые модели и вычисления **без Android** (здесь основная
  масса unit-тестов: `RecipeScale`, `RecipeScaler`, `YearRhythm`, `RuDate`…)
- `navigation/` — `MadreDestinations`, `MadreNavHost`
- `notifications/` — планировщики, воркеры, foreground service
- `sync/` — синхронизация семейной книги
- `ui/screens`, `ui/components`, `ui/theme`, `ui/photo`
- `utils/` — `PhotoStore`, `RecipeAssets`, `LegacyPrefs`

---

## Навигация

`navigation/MadreNavHost.kt` — один `NavHost`. Маршруты объявлены строками в
`MadreDestinations`, аргументы — в фигурных скобках:

```
home
recipe/{recipeId}
baking/{sessionId}
baking/{sessionId}/complete
starter            starter/feed
photo-gallery      settings
shelf              shelf/{ownerId}
```

Ссылки строятся **только** хелперами (`MadreDestinations.recipeDetail(id)`),
не конкатенацией на месте вызова.

С Cycle 18 «Полка» на первой полосе ведёт **сразу** в `shelf/me` — свою
летопись. Маршрут `shelf` (`ShelfScreen`) остался в графе, но входа с
ежедневной дороги у него больше нет: разворот показывал ровно один корешок,
свой, и требовал второго нажатия. Вход вернётся, когда книг станет больше
одной; до тех пор экран считается ждущим, а не живым.

---

## Данные

Room **версия 10**, 8 объявленных сущностей, 9 миграций
(`MIGRATION_1_2` … `MIGRATION_9_10`). Список миграций — один, в
`MadreDatabase.Companion.MIGRATIONS`; его же берёт
`androidTest/data/db/MigrationTest.kt`. Миграция, написанная но не
зарегистрированная, роняет тест, а не «проходит» на своей копии списка.

Сущности: `UserEntity`, `SourdoughConfigEntity`, `FeedingEntity`,
`BakeRecordEntity`, `ActiveBakeEntity`, `FamilySettingEntity`, а также
`MarginNoteEntity` и `SealedNoteEntity` — **удалённые фичи, чьи сущности
намеренно остались объявленными**: убрать их из `entities` значит поменять
identity hash схемы, и Room откажется открывать лежащую на телефонах
`madre.db`. DAO у них удалены, читать и писать их нечем. Не «прибирать».

Cycle 26 шёл к `feedings` двумя шагами от базовой версии `v8`:
версия 9 добавила три nullable-поля
ручного снимка — `hydrationPercent`, `starterObservation`, `observedAtMillis`;
версия 10 добавила ещё три — `retainedStarterGrams`, `finalHydrationPercent`
и `generatedComment`. Обе миграции только `ADD COLUMN`: legacy-записи не
переписываются и остаются null. `v9` — это промежуточная, не выпущенная
заполненная база.

Кормление теперь **расчёт, а не форма**: человек называет три массы
(оставленная закваска, мука, вода), гидратацию считает
`sourdough/HydrationMath` от последней сохранённой `finalHydrationPercent`, а
если посчитанных кормлений ещё нет — от объявленной константы
`INITIAL_LEVITO_HYDRATION_PERCENT = 50` (её источник экран называет вслух).
Поля v9 остаются читаемыми как прежние значения: приоритет показа —
`finalHydrationPercent`, затем `hydrationPercent`, затем «—». Ретроспективно
гидратацию за старые кормления не вычисляем: масс закваски в них не называли.
Арифметика в `HydrationMath` после Cycle 26 строго целочисленная на `Long`
по шагам rational вычисления с финальным half-up округлением (`87.5 -> 88`) до
последнего целочисленного процента, без перехода на `Double`.

`generatedComment` — снимок фактов на момент записи (`sourdough/FeedingComment`):
интервал из настроек, прошедшее время, «не позже срока»/насколько опоздали
(`FeedingSchedule.classify`, граница принадлежит сроку), прошлая гидратация,
прошлые мука/вода, новые массы и результат. Погода в нём появляется, только
если разрешение на грубую геолокацию уже дано и точка не старше 30 минут
(`FeedingWeatherSource`); координаты никуда не пишутся, наружу в семейную книгу
по-прежнему уходят только id/мука/вода/время.

Схемы экспортируются в `app/schemas` — они и есть история, по которой
`MigrationTestHelper` поднимает базу нужной версии.

**Фотографии** лежат в `filesDir`, а пути в базе — **относительные**
(с Cycle 15, `PhotoStore.resolve`). Абсолютные пути ломались при переносе
данных и смене пути к пакету. Никогда не писать в базу `absolutePath`.

**Рецепты** — `app/src/main/assets/recipes.json`, читаются через
`RecipeRepository`. Файл **неизменяемый**: это книга Полины, а не наши
данные. Поле `emoji` в нём есть и **не рендерится**.

---

## Сеть

PocketBase на `https://madre-api.kdnfx.space` — общая книга на семью:
`MadreApi` (публичная часть) и `FamilyBookApi` (под аутентификацией, токен в
`SecureTokenStore` через Keystore). Погода — Open-Meteo, без ключа.

Отправка — не из UI: `SyncRepository` кладёт событие в очередь, `SyncWorker`
(WorkManager) досылает. У событий есть ключ идемпотентности — повтор доставки
не создаёт вторую запись.

**Локальные данные никогда не зависят от сети.** Нет сервера или нет
аккаунта — книга работает полностью, просто не делится.

---

## Уведомления и фон

Три разных механизма, и путать их нельзя:

1. **Кормление закваски и шаги WAIT** — WorkManager
   (`FeedingReminderPlanner` / `FeedingReminderWorker`). Отложенные и
   переживающие смерть процесса. У напоминания о кормлении есть кнопка
   «Покормила» (`FeedingReminderAction`): она открывает книгу на форме
   кормления через `MainActivity.EXTRA_OPEN_FEEDING`, а не записывает
   кормление молча — граммы знает человек, и придумывать их за него нельзя.
2. **Активная выпечка** — foreground service `BakingProgressService`
   (`foregroundServiceType="specialUse"`): пока идёт выпечка, в шторке живёт
   строка хода. Тап ведёт в свою выпечку через
   `MainActivity.EXTRA_SESSION_ID` (активность `singleTop`, обрабатываются и
   `onCreate`, и `onNewIntent`).
3. **После перезагрузки телефона** — `BootReceiver` (`BOOT_COMPLETED`) +
   `BootRestorePlanner`: незавершённые выпечки и напоминания восстанавливаются.

Длительности таймеров считаются по `SystemClock.elapsedRealtime()`, **не** по
`System.currentTimeMillis()`: последний прыгает при синхронизации часов.

Разрешение на уведомления спрашивается **один раз** за установку. Отказ —
законный выбор; что именно перестаёт работать, написано в «Выходных данных»
(`SettingsScreen`), там же дорога обратно.

---

## Тесты

| Что | Где | Чем |
|---|---|---|
| Unit + вычисления | `app/src/test/` | JUnit 4 + Truth |
| Compose-взаимодействия | `app/src/test/` | Robolectric + `ui-test-junit4` |
| Золотые скриншоты | `app/src/test/…/ui/visual` | Roborazzi 1.70.0 |
| Миграции Room | `app/src/androidTest/` | `MigrationTestHelper` на настоящей SQLite |
| Навигация на устройстве | `app/src/androidTest/` | `NavigationSmokeTest` |

Compose-тесты гоняются на Robolectric намеренно: эмулятора в основной
сборочной среде нет, а правила «отмена спрашивает» и «мишень не меньше 48dp»
глазами не проверяются.

Но **не всё ловится Robolectric**: он подставляет `java.util.regex`, а
настоящий Android использует ICU. Краш `PatternSyntaxException` при открытии
рецепта (Cycle 14, #15) не поймал ни один unit-тест и поймать не мог — на
такое есть `NavigationSmokeTest`, открывающий все главы книги на эмуляторе.

Арифметика проверяется на **настоящих** рецептах, а не на подобранных числах
(`RealRecipeScaleTest`, `RecipeScaleInvariantTest`): синтетический рецепт не
поймал бы ни одной из ошибок, ради которых эти файлы заведены.

### Запрет на ослабление тестов

Нельзя: удалять тест, ослаблять ассерт, добавлять `@Ignore`, менять ожидаемое
значение — **чтобы сборка стала зелёной**.

Если после 3 попыток тест красный — остановись, откати изменения
(`git stash`) и сообщи: какой тест, полный вывод падения, три гипотезы о
причине.

Красный тест — это информация, а не препятствие.

Единственное законное основание убрать тест — удалена сама фича, которую он
проверял, и удаление описано в коммите и в `docs/graveyard.md`.

Ассерт-диапазон (`isGreaterThan`, `isAtMost`) допустим там, где проверяется
величина, точное значение которой не определено, — и **обязан** нести рядом
комментарий, почему точного значения здесь быть не может. Без комментария
такой ассерт считается ослабленным.

---

## CI

- **`.github/workflows/quality-gates.yml`** — на каждый PR:
  `testDebugUnitTest lintDebug verifyRoborazziDebug assembleDebug`, отдельной
  job'ой инструментальные тесты на эмуляторе (`connectedDebugAndroidTest`).
- **`.github/workflows/release.yml`** — по тегу: подписанный
  `assembleRelease`, SBOM, публикация релиза.

Все GitHub Actions запинены по SHA, а не по тегу.

---

## Сборка

- `gradle.properties`: `org.gradle.jvmargs=-Xmx2048m`. Не поднимать без нужды.
- `--no-daemon` — **только в CI**. Локально демон нужен, иначе каждая сборка
  стартует JVM заново.
- Release собирается с R8 (`minifyEnabled`), правила — в `proguard-rules.pro`.
  Модели, которые читает Gson, обязаны быть в правилах.
- Локально нужен явный `JAVA_HOME` (JDK 17), иначе gradle не стартует.

---

## Hard rules

Нарушение любого пункта — повод откатить правку, а не обсуждать.

1. **Никаких emoji в UI.** Иконки — только существующие `ic_*.webp` и
   минимум outline. Поле `emoji` из `recipes.json` не рендерится.
2. **Углы ≤ 4dp.** Бумага, не пластик.
3. **Только светлая тема.** Тёмной нет и не будет без явного решения
   Димы/Полины.
4. **Никогда не хардкодить цвет в composable** — только токены из
   `ui/theme/Color.kt` или `AppColors`.
5. **`recipes.json` неизменяем.**
6. **Весь текст на русском** — интерфейс, комментарии, сообщения коммитов.
7. **Не возвращать удалённые фичи** — см. `docs/graveyard.md`. У каждой есть
   причина смерти.
8. **Не изображать работающую фичу.** Кнопка, которая не нажимается, и
   заглушка, притворяющаяся данными, — хуже честной строки «этого пока нет».
9. **Голого `Modifier.clickable` в книге нет.** Всякое нажатие — это
   `BookButton` (главное/второстепенное), `TextAction` (тихое), `BackLabel`
   либо площадь с `Modifier.then(bookAction(label) { … })`: строка оглавления,
   талон, фотокарточка. Все четыре дают `Role.Button`, `onClickLabel` и мишень
   не меньше 48dp — три правила, которые голый `clickable` не даёт ни одного.
   Новый `clickable` в diff'е — повод откатить правку.

   Первая полоса приведена к этому в Cycle 18 и держится тестом
   `HomeControlsUiTest`; `FeedingFormScreen` и `StarterDiaryScreen` — в
   Cycle 26. Остальные экраны — ещё нет: `PhotoDesigner`,
   `PhotoSourceChooser`, `Bookplate`, `AgedPhoto`, `HandwrittenOverlay`,
   `BakingCompleteScreen`,
   `BookStatsScreen` и `PhotoGalleryScreen` держат старые `clickable`. Правило
   на них распространяется при следующей правке этих файлов; отдельный обход
   всей книги отложен сознательно (`workflow/CYCLE.yaml`, decisions).

Палитра, типографика и правила экранов — `DESIGN-V4.md`.
Регламент циклов — `docs/WORKFLOW-V2.md`.
