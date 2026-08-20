# Cycle 28 plan — правда полки, тап-крутилки, Испечено

Base: `origin/main` `98ad5dd1`. Branch: `cycle/28`. VersionName **не** трогать, пока не `prepare-version` → `6.7.0`.

RuStore: 6.6.0(35) и 6.5.0(34) pending **не редактировать**.

Автор плана: Cursor `claude-opus-5-thinking-high` (роль 2 по `docs/CURSOR-FIRST-WORKFLOW.md`).
Kotlin по этому плану пишет Cursor `gpt-5.3-codex-xhigh`. Планировщик код не пишет.

---

## USER DECISION

1. Экран полки: только «Уйти с полки · можно вернуться». Статистика на полке остаётся. «Выйти · книга на телефоне останется» убрать с этого экрана (выход из аккаунта — общие Настройки).
2. Имя полки после «Назад» сразу новое в Настройках. Ловить **тестом**.
3. «Обновить код приглашения» сразу показывает новый код.
4. На экране полки видны имена, кто уже на ней.
5. Все выборы значения (список 1–11) — как «Напоминания»: одна строка, тап крутит по кругу, без окна. Интервал кормления тоже. Камера/Галерея — нет.
6. «Испечено»: печать с датой в релизе, не кликается. Под ней **кнопка** существующего `BookButton` (общая стилистика приложения, не мокап). Дефолт `на полке · с кадром`. Тап → `себе`. Текста «тап — себе» нет. Уходит на полку при «На главную». Нет фото — Камера/Галерея.

Копирайт из этих шести пунктов — дословный. Ниже он не переписывается, только переносится.

---

## FACT — код на 108 (не 6.6.0 store), с file:line

### Ф1. Два `FamilyBookViewModel` и устаревшее имя полки

- `ui/screens/SettingsScreen.kt:118` — `familyBookViewModel: FamilyBookViewModel = viewModel()`;
  `ui/screens/SettingsShelfScreen.kt:63` — снова `= viewModel()`. Оба экрана — свои
  `NavBackStackEntry`, то есть **две разные ViewModel** с двумя `_state`.
- `navigation/MadreNavHost.kt:70-72` — `bakingViewModel`, `sourdoughViewModel`,
  `shelfViewModel` подняты в NavHost и раздаются вниз; `familyBookViewModel` —
  единственный, который так **не** поднят (`MadreNavHost.kt:290-318`).
- `viewmodel/FamilyBookViewModel.kt:38-41` — `restore()` выходит сразу, если
  состояние уже `SignedIn`. Значит: переименовал на экране полки → вернулся в
  Настройки → у той ViewModel в `_state` лежит прежний `familyName`, и
  перечитывать его она не станет.
- `ui/screens/SettingsScreen.kt:200-210` — строка раздела «Полка» берёт имя из
  `familyBookState.account.familyName`, то есть ровно из устаревшего состояния.
- Репозиторий-то один: `account/FamilyAccountRepository.kt:132-143` (`renameFamily`)
  пишет новое имя в своё поле `account`. Расходятся не данные, а копии в двух ViewModel.

### Ф2. `Loading` без аккаунта схлопывает экран полки и стирает новый код

- `account/FamilyBookState.kt:26` — `data object Loading`, и `:17` —
  `val account: FamilyAccount? get() = null`. То есть **любой** запрос обнуляет
  аккаунт для UI.
- `viewmodel/FamilyBookViewModel.kt:102-114` — `runNetwork` ставит
  `FamilyBookState.Loading` перед каждым действием (rename, rotate, leave, join…).
- `ui/screens/SettingsShelfScreen.kt:109-123` — ветка
  `account == null || !account.hasFamily` рисует `FamilyBookSection`, то есть форму
  входа. На каждый rotate/rename экран полки на мгновение становится формой входа.
- Отсюда же «код не показывается». `ui/screens/SettingsScreen.kt:551-553` —
  `DisposableEffect(Unit) { onDispose { onCodeHandled() } }` внутри
  `FamilyBookSection`. Последовательность: rotate → `Loading` → секция входит в
  композицию → ответ → `SignedIn` с новым кодом → секция **выходит** из композиции →
  `onDispose` зовёт `clearInviteCode` (`FamilyBookViewModel.kt:44-59`) → код,
  который сервер отдал один раз, стирается в тот же кадр.
  Код при этом честно приехал: `FamilyAccountRepository.kt:210-226` (`remember`)
  кладёт `inviteCode` из ответа в аккаунт.
- Ротация вообще-то работает: `FamilyAccountRepository.kt:119-126`. Ломает показ
  ровно `Loading` без аккаунта.

### Ф3. Уйти с полки / выйти из аккаунта

- `ui/screens/SettingsShelfScreen.kt:216-225` — две `TextAction` подряд:
  «Уйти с полки · книга на телефоне останется» (`leaveFamily`) и
  «Выйти · книга на телефоне останется» (`signOut`). По USER DECISION 1 второй
  здесь не место, а первый меняет хвост на «· можно вернуться».
- `account/FamilyAccountRepository.kt:149-162` — `leaveFamily` **не** трогает токен и
  возвращает `SignedIn` с обнулённой семьёй. `signOut` — `:169-172`, чистит токен.
  То есть «Leave не зовёт signOut» уже верно в репозитории; проверить надо, что и
  экран не зовёт.
- `ui/screens/SettingsScreen.kt:641` и `:684` — внутри `FamilyBookSection` ещё две
  «Выйти · книга на телефоне останется». Ветка `:643-685` («уже в семье») с этого
  экрана **недостижима**: секция вызывается только когда семьи нет
  (`SettingsShelfScreen.kt:110-122`). Это мёртвый дубль карточки полки с
  собственной кнопкой ротации.
- Значит выходу из аккаунта нужен новый дом в общих Настройках: сейчас его там нет
  ни одного (`SettingsScreen.kt:199-210` — раздел «Полка» это одна строка со
  стрелкой).

### Ф4. Люди на полке

- `ui/screens/SettingsShelfScreen.kt:145-172` — «Книги на полке» и имена уже
  рисуются из `shelfViewModel.members`, у основателя подпись «кто завёл полку».
- `viewmodel/ShelfViewModel.kt:46-113` — `refresh` держит `lastConfirmedMembers` и
  на сбое сети не схлопывается в пустоту. Пустой список = не отрисовка, а Ф2:
  экран в этот момент показывает форму входа, а не полку.
- `viewmodel/ShelfViewModel.kt:50-51` — `refresh` сам зовёт
  `familyAccountRepository.restore()` и `refresh()` в обход обеих ViewModel. Ещё один
  источник расхождения: репозиторий обновился, `_state` — нет.
- `ui/screens/SettingsShelfScreen.kt:78-80` — `LaunchedEffect(familyBookState, …)`
  перезапускает `shelfViewModel.refresh` на **каждую** смену состояния, включая
  `Loading`. То есть один rename = минимум два сетевых `refresh`.

### Ф5. Выборы значения — что где лежит

Эталон из USER DECISION 5 — «Напоминания»: `ui/screens/SettingsScreen.kt:344-350`,
одна `SettingsRow` (`:813-843`), тап переключает значение, окна нет.

| № | Что | Где сейчас | Как сейчас |
|---|---|---|---|
| 1 | Напоминания | `SettingsScreen.kt:344-350` | **эталон**, уже строка |
| 2 | Оформление | `SettingsScreen.kt:387-429` (`CalmModeRow`) | два слова рядом, подчёркнуто выбранное |
| 3 | Как часто кормить | `SettingsScreen.kt:289-316` (`FeedingRhythmRow`) | `SettingsChoiceDialog` (`:437-477`) |
| 4 | Ставить выпечку на полку | `SettingsShelfScreen.kt:294-314` + `:232-266` | `AlertDialog` |
| 5 | Кухня / Холод | `FeedingFormScreen.kt:216-232`, чип `:584-609` | два штампа-радиокнопки |
| 6 | Порции | `RecipeDetailScreen.kt:438-486` (`PortionSelector`) | рамка из пяти ячеек, `Role.RadioButton` |
| 7 | Рамка фото | `ui/photo/PhotoDesigner.kt:215-223` | ряд `DecorChip` (`:340-368`) |
| 8 | Тёплый свет | `PhotoDesigner.kt:225-236` | два чипа |
| 9 | Оттиск | `PhotoDesigner.kt:238-255` | ряд чипов + строка про снятие |
| 10 | Угол | `PhotoDesigner.kt:257-267` | ряд чипов, виден только при оттиске |
| 11 | На полку / себе на «Испечено» | `BakingCompleteScreen.kt:253-299` | `AlertDialog` из трёх `BookButton` |

Исключение: `ui/components/PhotoSourceChooser.kt:55-115` («Камера», «Галерея») —
разовый выбор источника, не значение. Не трогать (USER DECISION 5).

**Мина в эталоне.** `ui/components/BookControls.kt:51-70` — `TapGate` с окном
`DEFAULT_WINDOW_MILLIS = 600L`; `bookAction` (`:210-221`) и `BookButton` (`:87`) через
него пропускают тап. У «Напоминаний» два значения, и окно никому не мешало. У
интервала кормления значений пять (`sourdough/FeedingInterval.kt:20`), у порций —
пять (`model/RecipeScale.kt:20-21`): пройти круг = 4 тапа, и с окном 600 мс книга
будет глотать три из четырёх. Это же убьёт и Robolectric-тест на прокрутку круга.

### Ф6. «Испечено»

- `ui/screens/BakingCompleteScreen.kt:149` + `:309-311` — `WaxSealStamp("ИСПЕЧЕНО", romanDate)`.
  Печать уже не нажимается: `clickable` на ней нет. Требование USER DECISION 6 —
  не сделать её переключателем и оставить дату в релизе.
- `BakingCompleteScreen.kt:253-299` — лист «Поставить на полку?» из трёх кнопок:
  `Поставить`, `Поставить с кадром`, `Оставить себе` (константы
  `shelf/ShelfSharePolicy.kt:32-35`).
- `BakingCompleteScreen.kt:96-104`, `:123-127`, `:234-240` — штамп «на полке»
  показывается сразу при политике «всегда», потому что факт к этому моменту **уже
  отправлен**: `viewmodel/BakingViewModel.kt:260-269` зовёт `shareBakeStats(id)` из
  `advanceStep`, сразу после `bakeHistoryRepository.record`, без кадра.
- Значит «уходит на полку при На главную» — это перенос отправки из момента
  готовности в момент выхода с экрана. Иначе «с кадром» физически невозможен (кадр
  выбирают позже), а «себе» опаздывает (уже отправлено).
- `BakingCompleteScreen.kt:85` — `BackHandler { onHome() }`; системная «назад» на этом
  экране означает то же, что «На главную», и обязана решать тот же вопрос.
- `viewmodel/BakingViewModel.kt:130-131`, `:209-212` — `sharingAvailable` = есть токен.
  Правило Cycle 17: делиться некуда → элемента нет вовсе, а не «неактивная кнопка».
- `BakingCompleteScreen.kt:338` — `PastedPhotoPrompt` до сих пор на голом
  `Modifier.clickable`; `ui/photo/PhotoDesigner.kt:155`, `:275`, `:293`, `:345` — тоже.
  По hard rule №9 (`CLAUDE.md`, п.9 и список файлов-исключений) правило вступает в
  силу при **следующей** правке этих файлов, а этот цикл их правит.

### Ф7. Бэкенд: что делает `leave` со статистикой — UNKNOWN закрыт по коду

- `backend/pb_hooks/madre_family.pb.js:215-254` — `POST /api/madre/family/leave`:
  у пользователя `family` становится пустым; `bake_stats` **не упоминается вообще**,
  ни одна строка не удаляется.
- `backend/pb_migrations/1784937780_family_rules_for_stats.js:42-54` — у `bake_stats`
  поле `family` объявлено с `"cascadeDelete": false`;
  `backend/pb_migrations/1787164800_bake_stats_shelf.js:21-34` — поле `user` тоже
  `"cascadeDelete": false`. То есть ни уход человека, ни удаление полки строки не
  трут.
- Граница: `madre_family.pb.js:245-250` — если ушёл **последний**, запись `families`
  удаляется (а если ушёл хозяин, но кто-то остался — владельцем становится
  оставшийся). Правило чтения `family = @request.auth.family`
  (`1784937780_family_rules_for_stats.js:29`), поэтому строки распущенной полки
  остаются в базе, но их больше никто не видит и вернуться в неё нельзя — её нет.

Вывод для копирайта: «статистика на полке остаётся» — правда без оговорок.
«можно вернуться» — правда, пока на полке остался хоть кто-то (см. «Открытые
решения», п. О1).

---

## Плановые решения (следствия, которых в шести пунктах нет)

Формулируются здесь, чтобы автор кода не додумывал их сам. Ни один пункт не
меняет утверждённый копирайт.

- **П1.** SSOT состояния аккаунта — репозиторий. `FamilyAccountRepository` получает
  `val state: StateFlow<FamilyBookState>`, каждый метод публикует в него то, что и
  так возвращает. `FamilyBookViewModel.state` становится этим потоком.
  Так две ViewModel физически не могут разойтись, и `ShelfViewModel.refresh`
  (Ф4) перестаёт обновлять аккаунт втихую. Подписи методов не меняются — старые
  тесты репозитория остаются как есть.
- **П2.** `FamilyBookState.Loading` носит аккаунт:
  `data class Loading(override val account: FamilyAccount? = null)`. Проверки
  `is FamilyBookState.Loading` (`SettingsScreen.kt:539`,
  `FamilyBookViewModel.kt:39`) продолжают работать; сравнение по значению в
  `app/src/test/java/com/polinalinen/madre/account/FamilyBookStateTest.kt:73,95`
  правится на `Loading()`.
- **П3.** `familyBookViewModel` поднимается в `MadreNavHost` рядом с
  `shelfViewModel` (`MadreNavHost.kt:72`) и передаётся в оба экрана. П1 делает это
  необязательным для правильности, но это убирает второй сетевой `restore()` на
  каждый вход в колофон.
- **П4.** Выход из аккаунта в общих Настройках — в разделе «Полка», под строкой с
  названием, тем же текстом «Выйти · книга на телефоне останется» и только когда
  человек вошёл. Мёртвая ветка `FamilyBookSection` (`SettingsScreen.kt:643-685`)
  удаляется целиком вместе со своей копией кнопки ротации.
- **П5.** Одна новая форма нажатия — `TapCycleRow` в `ui/components/BookControls.kt`:
  строка «подпись слева, текущее значение справа», `Role.Button`,
  `onClickLabel = "$label: $value"` (тот же формат, что у `SettingsRow`, чтобы не
  ломать `SettingsIaUiTest:100-111`), мишень ≥48dp, тап — следующее по кругу.
  Пятая форма языка кнопок вписывается в hard rule №9 в `CLAUDE.md` в том же коммите.
- **П6.** `bookAction` получает `repeatable: Boolean = false`. `repeatable = true` —
  без `TapGate`. Причина, которую надо записать комментарием в коде: гейт защищает
  от **необратимого** дубля («Дальше», «Вписать в дневник»), а повторный тап по
  крутилке даёт всего лишь следующее значение — оно видно и отменяется
  продолжением круга. Ставить сюда окно — значит глотать 3 тапа из 4 (Ф5).
  Значение из одного элемента крутилкой не рисуется вовсе (нажатие без последствий
  = hard rule №8).
- **П7.** Крутилка места хранения (№5) остаётся **штампом**, а не строкой: один
  `LocationChip` показывает текущее (`КУХНЯ`/`ХОЛОД`) и по тапу меняет. Видимый
  копирайт не меняется ни на слово; `onClickLabel` — «Где стоит закваска: кухня».
- **П8.** Оттиск (№9) крутится по `PhotoStamp.entries + null`; для `null` значение
  называется «без оттиска». Существующая строка-пояснение
  (`PhotoDesigner.kt:247-255`) переписывается в один вариант: «оттиск не
  обязателен — можно оставить карточку чистой» (уже существующий текст), потому что
  «тап по выбранному оттиску снимает его» после перехода на круг неправда.
- **П9.** Настройка «Ставить выпечку на полку» (№4) сохраняет **настоящее**
  действие: она задаёт дефолт кнопки на «Испечено». `всегда` → `на полке · с кадром`,
  `спросить при готовности` → `себе`. Дефолт из USER DECISION 6 — это дефолт
  поставки, потому что `ShelfSharePolicy.parse` (`ShelfSharePolicy.kt:44-48`) без
  записи даёт `ALWAYS`. Лист «Поставить на полку?» умирает: кнопка и есть вопрос.
- **П10.** Кадр не обязателен для факта. Выбрано `на полке · с кадром`, фото нет →
  открывается «Камера / Галерея»; человек отказался — на «На главную» на полку
  уходит сам факт без кадра (`ShelfShareDecision.PUT`). Полка держит факт, кадр —
  отдельное необязательное поле (`1787164800_bake_stats_shelf.js:71-86`), и терять
  выпечку из-за несделанного снимка неправильно. Третьей позиции в круге нет:
  круг — ровно `на полке · с кадром` ↔ `себе`.
- **П11.** Штамп «на полке» с экрана готовности **убирается**: пока ничего не
  отправлено, он был бы ложью (hard rule №8). Печать «ИСПЕЧЕНО» с датой остаётся и
  остаётся ненажимаемой.

---

## Три коммита, в этом порядке

Порядок не произвольный: 1 → 2 → 3 по зависимостям.

### Коммит 1 — `feat: shelf-settings-truth`

Первым, потому что это единственный релиз-блокер (экран полки на rename/rotate
превращается в форму входа), и потому что коммит 3 читает аккаунт и
`sharingAvailable` из уже починенного состояния.

Файлы:

- `account/FamilyBookState.kt` — П2.
- `account/FamilyAccountRepository.kt` — П1 (`state` + публикация в каждом
  возврате: `restore`, `refresh`, `signIn`, `register`, `createFamily`, `joinFamily`,
  `rotateInviteCode`, `renameFamily`, `leaveFamily`, `signOut`, `clearInviteCode`).
- `viewmodel/FamilyBookViewModel.kt` — `state` = поток репозитория; `runNetwork`
  ставит `Loading(repository.currentAccount())`; `inFlight` и `passwordReset`
  остаются как есть.
- `navigation/MadreNavHost.kt` — П3 (объявление рядом с `:72`, передача в
  `:290-318`).
- `ui/screens/SettingsShelfScreen.kt` — единственная `TextAction`
  «Уйти с полки · можно вернуться»; `signOut` с экрана убран; список людей вынесен
  в `internal fun ShelfPeopleList(members, familyOwnerId)` (тестируемость, Ф4);
  ключ `LaunchedEffect` (`:78`) сужается до
  `account?.familyId to account?.familyName`, чтобы `Loading` не гонял `refresh`.
- `ui/screens/SettingsScreen.kt` — П4; ветка `loading` (`:539`, `:574-575`) больше не
  прячет карточку, если аккаунт известен.
- `CLAUDE.md` — раздел «Навигация»: в колофоне блок «Полка» — строка с названием
  **и выход из аккаунта**; «Данные/Сеть» — `Loading` носит аккаунт, состояние
  аккаунта живёт в репозитории.
- `DESIGN-V4.md` §Cycle 28 фича 1 — детализация до принятых решений.

Тесты (новые классы):

| Класс | Файл | Что держит |
|---|---|---|
| `FamilyAccountStateFlowTest` | `app/src/test/java/com/polinalinen/madre/account/FamilyAccountStateFlowTest.kt` | поток репозитория отдаёт то же, что возвращает метод: `signIn`, `createFamily`, `renameFamily`, `leaveFamily`, `signOut` |
| `FamilyBookRenameVisibilityTest` | `app/src/test/java/com/polinalinen/madre/viewmodel/FamilyBookRenameVisibilityTest.kt` | две ViewModel на одном репозитории: rename в одной → у второй новое `familyName`, и второго `restore()`/`auth` в `api.calls` нет (USER DECISION 2) |
| `FamilyBookLoadingKeepsAccountTest` | `app/src/test/java/com/polinalinen/madre/viewmodel/FamilyBookLoadingKeepsAccountTest.kt` | в `Loading` `state.account` не null на rename/rotate/leave; после успешного rotate новый код лежит в состоянии (USER DECISION 3) |
| `LeaveKeepsBookTest` | `app/src/test/java/com/polinalinen/madre/account/LeaveKeepsBookTest.kt` | `leaveFamily` → `SignedIn` без семьи, токен цел, в `api.calls` нет выхода из аккаунта |
| `SettingsShelfTruthUiTest` | `app/src/test/java/com/polinalinen/madre/settingsui/SettingsShelfTruthUiTest.kt` | на экране есть «Уйти с полки · можно вернуться»; «Выйти · книга на телефоне останется» нет; после rotate код виден и формы входа нет; свод hard rule №9 (роль, подпись, 48dp) |
| `ShelfPeopleListUiTest` | `app/src/test/java/com/polinalinen/madre/settingsui/ShelfPeopleListUiTest.kt` | имена всех участников видны, у основателя «кто завёл полку» (USER DECISION 4) |
| `SettingsSignOutHomeUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/SettingsSignOutHomeUiTest.kt` | «Выйти · книга на телефоне останется» есть в общих Настройках и только у вошедшего (П4) |

Правятся существующие: `FamilyBookStateTest` (`:73`, `:95` — `Loading()`),
`SettingsIaUiTest` (свод по hard rule №9 подхватывает новую строку выхода).

Готово, когда: `testDebugUnitTest` зелёный; на экране полки rename и rotate не
показывают форму входа ни в одном кадре; имя в Настройках новое после «Назад».

### Коммит 2 — `feat: tap-cycle-controls`

Вторым: даёт `TapCycleRow` и `TapCycle`, которыми коммит 3 крутит свою кнопку.
После коммита 1, потому что трогает те же два файла настроек (`SettingsScreen.kt`,
`SettingsShelfScreen.kt`) и не должен разъезжаться с ними в конфликте.

Файлы:

- `ui/components/BookControls.kt` — `TapCycleRow` (П5), `bookAction(repeatable)` (П6),
  `TapGate` без изменений.
- `ui/components/TapCycle.kt` — чистая функция круга:
  `fun <T> next(options: List<T>, current: T): T` (неизвестное текущее → первое,
  один элемент → он же). Без Android, поэтому проверяется юнит-тестом.
- `ui/screens/SettingsScreen.kt` — №1 «Напоминания» на `TapCycleRow` (значение
  `вкл`/`выкл` и ветка «не разрешены телефоном» — как есть), №2 «Оформление»,
  №3 «Как часто кормить» (значение остаётся `Ваш ритм: …`, подпись про Levito
  Madre остаётся); `SettingsChoiceDialog` (`:437-477`) удаляется как мёртвый.
- `ui/screens/SettingsShelfScreen.kt` — №4 на `TapCycleRow`, `AlertDialog`
  (`:232-266`) удаляется.
- `ui/screens/FeedingFormScreen.kt` — №5 по П7 (один штамп вместо двух).
- `ui/screens/RecipeDetailScreen.kt` — №6: `PortionSelector` становится строкой
  «На сколько печём» со значением `×N семья/семьи/семей`, круг 1…5 →1;
  `portionLabel(n)` живёт дальше как `onClickLabel`.
- `ui/photo/PhotoDesigner.kt` — №7–10 на четыре строки (П8); «Угол» по-прежнему
  появляется только при выбранном оттиске; `DecorGroup`/`DecorChip` удаляются, если
  после этого никем не читаются; hard rule №9 для «← Отмена» (`BackLabel`),
  «Готово» (`BookButton` PRIMARY), «Без оформления» (`BookButton` SECONDARY).
- `CLAUDE.md` — hard rule №9: пятая форма нажатия `TapCycleRow`; `PhotoDesigner`
  уходит из списка файлов, которые ещё держат старые `clickable`.
- `DESIGN-V4.md` §Cycle 28 фича 2 — перечень одиннадцати и исключение
  «Камера/Галерея».
- `docs/graveyard.md` — Cycle 28: списки-диалоги выбора значения
  (`SettingsChoiceDialog`, лист «Ставить выпечку на полку»), пара слов «спокойное /
  живое» рядом, рамка из пяти ячеек порций, ряды `DecorChip`. Причина смерти —
  USER DECISION 5, и что придётся объяснить, если возвращать.

Тесты (новые классы):

| Класс | Файл | Что держит |
|---|---|---|
| `TapCycleTest` | `app/src/test/java/com/polinalinen/madre/ui/components/TapCycleTest.kt` | круг замыкается, неизвестное текущее → первое, один элемент → он же |
| `TapCycleRowUiTest` | `app/src/test/java/com/polinalinen/madre/ui/components/TapCycleRowUiTest.kt` | четыре тапа = четыре шага (гейт не глотает, П6); `Role.Button`, непустой `onClickLabel`, ≥48dp; диалога не появляется |
| `SettingsTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/SettingsTapCycleUiTest.kt` | интервал кормления и оформление меняются одним `onClick` без окна; подпись действия у ритма — прежнего формата |
| `ShelfSharePolicyRowUiTest` | `app/src/test/java/com/polinalinen/madre/settingsui/ShelfSharePolicyRowUiTest.kt` | «Ставить выпечку на полку» крутится строкой, листа нет |
| `FeedingStorageTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/FeedingStorageTapCycleUiTest.kt` | место хранения — один штамп, тап меняет кухня↔холод, мишень ≥48dp |
| `PortionTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/recipe/PortionTapCycleUiTest.kt` | круг 1…5→1, значение названо словами, одна кнопка вместо пяти |
| `PhotoDesignerTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/photo/PhotoDesignerTapCycleUiTest.kt` | рамка/тепло/оттиск/угол — четыре строки-круга; «Угол» без оттиска не показывается |
| `PhotoSourceChooserUiTest` | `app/src/test/java/com/polinalinen/madre/ui/components/PhotoSourceChooserUiTest.kt` | «Камера» и «Галерея» — два отдельных действия, не круг (USER DECISION 5) |

Переписываются существующие (фича изменена решением Димы, не «чтобы позеленело»):

- `PortionSelectorUiTest` (`:50-70`) — `assertIsSelected` и `contentDescription` по
  каждой ячейке относятся к удалённой рамке из пяти ячеек. Остаётся проверка, что
  значение названо словами и что мишень с палец.
- `SettingsIaUiTest:117-122` — «оба оформления названы сразу» противоречит USER
  DECISION 5. Заменяется на «видно текущее, тап даёт следующее».
- `TapGateTest` — добавить случай `repeatable`.
- `FeedingFormGoldenTest` — золотые в `app/src/test/snapshots` перезаписать
  (`recordRoborazziDebug`), потому что штамп хранения теперь один.

Готово, когда: ни один из одиннадцати выборов не открывает окна; «Камера/Галерея»
не изменилась; `verifyRoborazziDebug` зелёный на перезаписанных золотых; каждое
изменение золотого показано Gemini на визуальном гейте.

### Коммит 3 — `feat: baked-seal-toggle`

Последним: читает починенный аккаунт (коммит 1) и круг из коммита 2.

Файлы:

- `shelf/ShelfSharePolicy.kt` — новая поверхность:
  `defaultDecision(mode, sharingAvailable): ShelfShareDecision?` (`null` — делиться
  некуда, кнопки нет), `nextDecision(current)`, `labelOf(decision)` с дословными
  `на полке · с кадром` и `себе`; `shouldEnqueue`/`wantsPhoto` остаются;
  `SHEET_TITLE`, `PUT_LABEL`, `PUT_WITH_PHOTO_LABEL`, `KEEP_LABEL`,
  `ON_SHELF_STAMP`, `shouldShareOnComplete`, `shouldAskOnComplete`,
  `showOnShelfStamp` удаляются вместе с листом.
- `viewmodel/BakingViewModel.kt:260-269` — автоотправка из `advanceStep` убирается;
  `shareBakeStats` (`:363`) остаётся и зовётся ровно один раз, по решению с экрана.
- `ui/screens/BakingCompleteScreen.kt` — печать с датой как есть и без `clickable`;
  под ней один `BookButton`, крутящий два значения; лист (`:253-299`), штамп
  «на полке» (`:234-240`) и `askDismissed`/`onShelf` уходят; `onHome` получает
  выбранное решение; нет фото при `на полке · с кадром` → «Камера / Галерея», отказ
  → факт без кадра (П10); `PastedPhotoPrompt` (`:338`) переводится на `bookAction`.
- `navigation/MadreNavHost.kt:230-241` — на `onHome(decision)`: отправить (если
  `shouldEnqueue`), затем `exitSession`, затем `popBackStack`.
- `CLAUDE.md` — «Сеть»: политика полки решается на экране готовности в момент
  «На главную», а не в `advanceStep`; `PhotoDesigner` и `BakingCompleteScreen`
  уходят из списка исключений hard rule №9.
- `DESIGN-V4.md` §Cycle 28 фича 3.
- `docs/graveyard.md` — Cycle 28: лист «Поставить на полку?» и штамп «на полке» на
  экране готовности; автоотправка в момент готовности.

Тесты (новые классы):

| Класс | Файл | Что держит |
|---|---|---|
| `ShelfShareDecisionCycleTest` | `app/src/test/java/com/polinalinen/madre/shelf/ShelfShareDecisionCycleTest.kt` | дефолт `всегда` → `на полке · с кадром`, `спросить` → `себе`, круг из двух, `null` когда делиться некуда, дословные подписи |
| `BakingCompleteShelfButtonUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/BakingCompleteShelfButtonUiTest.kt` | дефолтная подпись `на полке · с кадром`; тап → `себе`; на «На главную» наружу уходит именно выбранное; листа «Поставить на полку?» нет; строки «тап — себе» нет; без токена кнопки нет вовсе |
| `BakedSealNotAControlTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/BakedSealNotAControlTest.kt` | у печати «ИСПЕЧЕНО» с датой нет `OnClick`; дата в подписи есть |

Готово, когда: до «На главную» на полку не уходит ничего; после — ровно один раз
и ровно выбранное; отправку с кадром и без проверил runtime-гейт на живом PB.

---

## Риски

**Р1. `leaveFamily` и статистика на полке.** По коду закрыто: хук не трогает
`bake_stats`, оба relation с `cascadeDelete: false` (Ф7). Остаётся граница —
уход последнего распускает полку, и тогда возвращаться некуда. Ловится: runtime-гейт
проверяет **два** сценария — уход при двух участниках (полка жива, статистика
ушедшего на месте, вернуться по коду можно) и уход единственного (полка
распущена). Копирайт в этом цикле не меняется, решение — О1.

**Р2. `Loading` стирает аккаунт.** Корень трёх симптомов сразу: схлопывание
экрана в форму входа, исчезновение нового кода приглашения (через `onDispose` →
`clearInviteCode`) и лишние `refresh` полки. Лечится П2 + П1; ловится
`FamilyBookLoadingKeepsAccountTest` и `SettingsShelfTruthUiTest`. Обратная
сторона правки: `Loading` перестаёт быть `data object`, поэтому все сравнения
по значению надо найти — их два, `FamilyBookStateTest:73,95`.

**Р3. Две ViewModel.** П1 убирает саму возможность расхождения, П3 — второй
сетевой `restore()`. Отдельно помнить про третий писателя: `ShelfViewModel:50-51`
дёргает репозиторий напрямую; после П1 его вызовы публикуются в тот же поток, и это
уже не расхождение, а обычное обновление. Ловится
`FamilyBookRenameVisibilityTest`.

**Р4. `TapGate` глотает круг.** См. Ф5 и П6. Без этого пункта фича 2 выглядит
работающей на двух значениях и не работает на пяти — то есть худший вид поломки.
Ловится `TapCycleRowUiTest` (четыре тапа = четыре шага).

**Р5. Перенос отправки в «На главную».** Выпечка, экран готовности которой не
открыли (процесс умер до перехода), на полку теперь не попадёт. Осознанный обмен:
раньше факт уходил без кадра и без спроса, теперь ничего не уходит молча, и штампа
«на полке» до отправки нет. Ловится: `BakingCompleteShelfButtonUiTest` +
runtime-проверка «закрыть книгу с экрана готовности через back» (back = «На главную»,
`BakingCompleteScreen.kt:85`).

**Р6. Ослабление тестов под видом переделки.** В коммите 2 умирают ассерты
`PortionSelectorUiTest` и `SettingsIaUiTest:117-122`, в коммите 3 —
`ShelfSharePolicyTest:14-31` (снятые функции политики) и `:52-56` (подписи листа).
Каждый — потому что удалена сама фича, которую он
проверял (USER DECISION 5 и 6), и каждый обязан быть назван в теле коммита и в
`docs/graveyard.md`. Всё, что не про удалённую фичу, остаётся строже или так же.

**Р7. Золотые снимки.** Меняются `FeedingFormGoldenTest`; `PhotoDesigner` золотых
не имеет, поэтому его переверстка проверяется только глазами Gemini на визуальном
гейте — это надо сказать вслух, а не надеяться на `verifyRoborazziDebug`.

**Р8. Мёртвый код после трёх правок.** `SettingsChoiceDialog`, `DecorGroup`,
`DecorChip`, ветка `FamilyBookSection:643-685`, половина константы
`ShelfSharePolicy`. Убирать в том же коммите, иначе следующий цикл будет читать
две правды.

---

## Открытые решения (не блокируют код)

- **О1.** Уход последнего участника распускает полку (Ф7). Текст
  «Уйти с полки · можно вернуться» для одиночной полки неточен. Вариантов два —
  отдельная строка для этого случая или запрет ухода последнему; оба требуют нового
  копирайта, которого в USER DECISION нет. Предложение: в Cycle 28 не изобретать,
  зафиксировать факт в runtime-evidence, решение — Диме на Cycle 29.
- **О2.** Название значения `спросить при готовности` после смерти листа означает
  «по умолчанию себе» (П9). Смысл честный, слово — приблизительное. Переименование
  — отдельное продуктовое решение, в этом цикле копирайт не трогаем.
- **О3.** Формулировки `onClickLabel` из П7 («Где стоит закваска: кухня») и значения
  «без оттиска» из П8 — служебные подписи для TalkBack, не видимый текст. Если Гес
  захочет других слов, это правка одной строки и не меняет план.

---

## Не делать

- Kotlin по этому плану пишет Cursor Codex на 108. Ни планировщик, ни Гес, ни судьи.
- `versionName` руками; второй APK в RuStore; правка карточек 6.6.0(35)/6.5.0(34);
  uninstall production.
- Новый визуальный язык кнопки с мокапа: на «Испечено» — существующий `BookButton`.
- Возвращать `emoji` из `recipes.json`, тёмную тему, углы >4dp, хардкод цвета,
  голый `Modifier.clickable` в правленых файлах.
- «Прибирать» `MarginNoteEntity`/`SealedNoteEntity` и трогать схему Room: в этом
  цикле база не меняется вообще, миграций нет.
- Ослаблять тест, чтобы позеленело. Три попытки — и стоп с полным выводом падения.

---

## VERDICT

**READY FOR CODE.** План реализуем без новых неизвестных: все шесть решений
Димы разложены на файлы и строки, единственный UNKNOWN из прошлой редакции
(`leave` и `bake_stats`) закрыт по коду бэкенда — статистика не удаляется, оба
relation с `cascadeDelete: false`. Порядок коммитов —
`shelf-settings-truth` → `tap-cycle-controls` → `baked-seal-toggle`, зависимости
однонаправленные.

Два условия, без которых код начинать нельзя:

1. Гейт плана (GLM-5.3 · DeepSeek V4 Pro · GPT-5.6 Sol) закрывает замечания по
   П1–П11, отдельно — по П6 (снятие `TapGate` на крутилках) и П10 (факт без кадра).
2. Открытое решение О1 (уход последнего участника распускает полку) зафиксировано
   как известная граница; копирайт в этом цикле не меняется.

Границы неизменны: Room версии 10 без новых миграций, `recipes.json` не тронут,
`versionName` только через `scripts/release_cycle.py prepare-version` → `6.7.0`,
магазин не трогаем.

PLAN_READY
