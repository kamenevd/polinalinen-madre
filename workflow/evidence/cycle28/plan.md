# Cycle 28 plan — правда полки, тап-крутилки, Испечено

Base: `origin/main` `98ad5dd1`. Branch: `maintenance/28`. VersionName **не** трогать, пока не `prepare-version` → `6.6.1.

RuStore: 6.6.0(35) и 6.5.0(34) pending **не редактировать**.

Автор плана: Cursor `claude-opus-5-thinking-high` (роль 2 по `docs/CURSOR-FIRST-WORKFLOW.md`).
Kotlin по этому плану пишет Cursor `gpt-5.3-codex-xhigh`. Планировщик код не пишет.

Редакция 2 (ремедиация после гейта плана). Что изменилось против редакции 1 —
раздел «Гейт плана: три REVISE и как они закрыты» в конце файла.

---

## USER DECISION

1. Экран полки: только «Уйти с полки · можно вернуться». Статистика на полке остаётся. «Выйти · книга на телефоне останется» убрать с этого экрана (выход из аккаунта — общие Настройки).
2. Имя полки после «Назад» сразу новое в Настройках. Ловить **тестом**.
3. «Обновить код приглашения» сразу показывает новый код.
4. На экране полки видны имена, кто уже на ней.
5. Все выборы значения (список ниже) — как «Напоминания»: одна строка, тап крутит по кругу, без окна. Интервал кормления тоже. Камера/Галерея — нет.
6. «Испечено»: печать с датой в релизе, не кликается. Под ней **кнопка** существующего `BookButton` (общая стилистика приложения, не мокап). Дефолт `на полке · с кадром`. Тап → `себе`. Текста «тап — себе» нет. Уходит на полку при «На главную». Нет фото — Камера/Галерея.

### USER DECISION 2026-08-20 (правка после гейта)

7. Настройку «Ставить выпечку на полку» **удалить целиком** — и строку в
 колофоне полки, и enum режима, и хранимое значение. Решение о полке
 принимается ровно один раз и ровно в одном месте: кнопкой на «Испечено».
 Настройка, задающая дефолт этой кнопки, — вторая правда о том же, и её нет.
8. Дефолт кнопки на «Испечено» — константа поставки, дословно
 `на полке · с кадром`; тап переключает в `себе` и обратно. Это не читается ни
 из каких prefs.
9. Крутилка по кругу остаётся формой **всех** оставшихся выборов значения,
 включая те, где значений от трёх до пяти. Цена — обнаружимость: увидеть
 весь список нельзя, к нужному значению идут тапами. Дима эту цену назвал и
 принял; предлагать вместо круга список — это уже не правка плана, а отмена
 решения 5.

Копирайт из этих девяти пунктов — дословный. Ниже он не переписывается, только
переносится.

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
- `ui/screens/ShelfScreen.kt:65-67` — второй вызов того же `refresh`, со своим
 ключом. Обоим нужен аккаунт, и оба берут его не там, где он живёт.

### Ф4б. На экране полки нечем показать отказ

- `ui/screens/SettingsShelfScreen.kt:124-226` — ветка «уже на полке» не читает
 `familyBookState` как `Failed` вовсе. `NetworkFailure.message`
 (`account/FamilyBookState.kt:69-77`) существует и осмысленный, но на этом экране
 его негде увидеть: переименование, ротация и уход в оффлайне выглядят как
 «ничего не произошло».
- Строка про отказ есть только в форме входа (`SettingsScreen.kt:565-573`), то
 есть ровно там, куда человек с полки не попадает.

### Ф5. Выборы значения — что где лежит

Эталон из USER DECISION 5 — «Напоминания»: `ui/screens/SettingsScreen.kt:344-350`,
одна `SettingsRow` (`:813-843`), тап переключает значение, окна нет.

| № | Что | Где сейчас | Как сейчас |
|---|---|---|---|
| 1 | Напоминания | `SettingsScreen.kt:344-350` | **эталон**, уже строка |
| 2 | Оформление | `SettingsScreen.kt:387-429` (`CalmModeRow`) | два слова рядом, подчёркнуто выбранное |
| 3 | Как часто кормить | `SettingsScreen.kt:289-316` (`FeedingRhythmRow`) | `SettingsChoiceDialog` (`:437-477`) |
| ~~4~~ | ~~Ставить выпечку на полку~~ | `SettingsShelfScreen.kt:294-314` + `:232-266` | **удаляется целиком** (USER DECISION 7) |
| 5 | Кухня / Холод | `FeedingFormScreen.kt:216-232`, чип `:584-609` | два штампа-радиокнопки |
| 6 | Порции | `RecipeDetailScreen.kt:438-486` (`PortionSelector`) | рамка из пяти ячеек, `Role.RadioButton` |
| 7 | Рамка фото | `ui/photo/PhotoDesigner.kt:215-223` | ряд `DecorChip` (`:340-368`) |
| 8 | Тёплый свет | `PhotoDesigner.kt:225-236` | два чипа |
| 9 | Оттиск | `PhotoDesigner.kt:238-255` | ряд чипов + строка про снятие |
| 10 | Угол | `PhotoDesigner.kt:257-267` | ряд чипов, виден только при оттиске |
| 11 | На полку / себе на «Испечено» | `BakingCompleteScreen.kt:253-299` | `AlertDialog` из трёх `BookButton` |

Крутилками в коммите 3 становятся девять строк: 1, 2, 3, 5, 6, 7, 8, 9, 10.
№ 4 умирает по USER DECISION 7. № 11 — это кнопка на «Испечено», она живёт в
коммите 4 и крутит два значения.

Исключение: `ui/components/PhotoSourceChooser.kt:55-115` («Камера», «Галерея») —
разовый выбор источника, не значение. Не трогать (USER DECISION 5).

**Мина в эталоне.** `ui/components/BookControls.kt:51-70` — `TapGate` с окном
`DEFAULT_WINDOW_MILLIS = 600L`; `bookAction` (`:210-221`) и `BookButton` (`:87`) через
него пропускают тап. У «Напоминаний» два значения, и окно никому не мешало. У
интервала кормления значений пять (`sourdough/FeedingInterval.kt:20`), у порций —
пять (`model/RecipeScale.kt:20-21`): пройти круг = 4 тапа, и с окном 600 мс книга
будет глотать три из четырёх. Это же убьёт и Robolectric-тест на прокрутку круга.

**Второе свойство эталона, которое нельзя потерять.** `SettingsRow` несёт
`valueColor` (`SettingsScreen.kt:813`, `:837`), и он не декоративный:
`RemindersRow` красит `вкл` в `sage`, `выкл` — в `cocoa`, а
«не разрешены телефоном» — в `terracotta` (`:348`, `:357`). Крутилка обязана
принимать тот же параметр, иначе «Напоминания» после переезда потеряют цветовую
разницу между «книга напомнит» и «телефон запретил».

### Ф6. «Испечено»

- `ui/screens/BakingCompleteScreen.kt:149` + `:309-311` — `WaxSealStamp("ИСПЕЧЕНО", romanDate)`.
 Печать уже не нажимается: `clickable` на ней нет. Требование USER DECISION 6 —
 не сделать её переключателем и оставить дату в релизе.
- **Дата на печати берётся от часов отрисовки:** `BakingCompleteScreen.kt:149` —
 `romanDate(System.currentTimeMillis())`. Испекли в 23:58, экран пережил
 полночь или пересоздание — на сургуче встанет следующий день. Тем же способом
 врёт `AgedPhoto(takenAtMillis = System.currentTimeMillis())` (`:201`).
 Настоящее время завершения лежит в базе: `bake_records.completedAtMillis`
 (`data/db/entities/BakeRecord.kt:20`), читается
 `BakeHistoryRepository.getCompletedAt` (`data/repository/BakeHistoryRepository.kt:36-38`).
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
 экране сегодня буквально равна «На главную». После переноса отправки это
 значило бы: смахнул назад — выпечка уехала на полку. Так нельзя (см. П12).
- `viewmodel/BakingViewModel.kt:130-131`, `:209-212` — `sharingAvailable` = есть токен.
 Правило Cycle 17: делиться некуда → элемента нет вовсе, а не «неактивная кнопка».
- `BakingCompleteScreen.kt:338` — `PastedPhotoPrompt` до сих пор на голом
 `Modifier.clickable`; `ui/photo/PhotoDesigner.kt:155`, `:275`, `:293`, `:345` — тоже.
 По hard rule №9 (`CLAUDE.md`, п.9 и список файлов-исключений) правило вступает в
 силу при **следующей** правке этих файлов, а этот цикл их правит.

### Ф6б. Дорога фотокарточки не умеет говорить «не получилось»

- `ui/photo/PhotoAttachment.kt:50-163` — `rememberPhotoAttachment` принимает
 **только** `onAttached`. Отказ не сообщается никуда ни в одном из шести мест:
 лист источника закрыт без выбора (`:134`), галерея вернула `null` (`:68`),
 камера вернула `success=false` или пустой файл (`:85`), камеры на телефоне нет
 (`:93-97`), разрешение не дали и диалог закрыт (`:100`, `:103-130`),
 «Стол оформления» отменён (`:150-153`). Ещё один тихий отказ —
 `PhotoStore.stage` вернул `null` (`:70`), и тогда `stagedPath` просто остаётся
 пустым.
- Отсюда следствие для П10: сценарий «нет фото при `на полке · с кадром`»
 нечем завершить честно, пока у дороги нет обратного сигнала.
- `viewmodel/BakingViewModel.kt:395-402` — `attachBakePhoto` пишет в Room
 из `viewModelScope.launch` и возвращается сразу. «Сначала сохранили кадр, потом
 отправили» этим API выразить нельзя: порядок не наблюдаем.

### Ф7. Бэкенд: уход с полки

- `backend/pb_hooks/madre_family.pb.js:215-254` — `POST /api/madre/family/leave`:
 у пользователя `family` становится пустым; `bake_stats` **не упоминается вообще**,
 ни одна строка не удаляется.
- `backend/pb_migrations/1784937780_family_rules_for_stats.js:42-54` — у `bake_stats`
 поле `family` объявлено с `"cascadeDelete": false`;
 `backend/pb_migrations/1787164800_bake_stats_shelf.js:21-34` — поле `user` тоже
 `"cascadeDelete": false`. То есть ни уход человека, ни удаление полки строки не
 трут.
- **Но полку хук удаляет:** `madre_family.pb.js:245-246` — если ушёл последний,
 `txApp.delete(family)`. Вместе с записью уходит `invite_code_hash`, то есть код
 приглашения; вернуться некуда, а строки `bake_stats` остаются висеть с
 relation на несуществующую запись, и правило чтения
 `family = @request.auth.family` (`1784937780_family_rules_for_stats.js:29`)
 не покажет их уже никому и никогда.
- **Передача владения недетерминирована:** `:247-250` берёт `others[0]`, а
 `findRecordsByFilter` на `:230-237` вызван с сортировкой `""`. Порядок задаёт
 движок, не мы, и «кому досталась полка» — вопрос без ответа в коде.

---

## Плановые решения (следствия, которых в девяти пунктах нет)

Формулируются здесь, чтобы автор кода не додумывал их сам. Ни один пункт не
меняет утверждённый копирайт.

### П1. SSOT состояния аккаунта — репозиторий, с явной сериализацией

Пункт переписан после гейта: «репозиторий получает StateFlow» — это не проект,
а намерение. Проект такой.

**Держатель ровно один.** В `FamilyAccountRepository` приватное поле `account`
**удаляется**. Единственный держатель — `private val _state:
MutableStateFlow<FamilyBookState>` со стартовым `SignedOut`; наружу
`val state: StateFlow<FamilyBookState> = _state.asStateFlow()`.
`currentAccount()` становится `_state.value.account`. Токен остаётся отдельным
`private var token: String?` — он не часть `FamilyBookState` намеренно: в UI
токену делать нечего, и попасть туда он не должен даже случайно.

**Сериализация.** `private val mutex = Mutex()` (`kotlinx.coroutines.sync`).
Тело **каждого** suspend-метода целиком в `mutex.withLock { … }`. Внутри лока
метод читает аккаунт и токен **из полей**, а не из значения, захваченного до
вызова: сегодняшний `val current = account ?: return …` перед сетевым запросом —
это и есть незакрытое read-modify-write, из-за которого `ShelfViewModel.refresh`
(Ф4) может перетереть только что переименованную полку.

**Loading публикует репозиторий.** Первое, что делает сетевой метод под локом, —
`publish(FamilyBookState.Loading(_state.value.account))`. Из
`FamilyBookViewModel.runNetwork` строка `_state.value = FamilyBookState.Loading`
уходит: второго писателя не остаётся вовсе.

**Протухший ответ.** Два метода не suspend и лока не берут — `signOut()` и
`clearInviteCode()`; их зовут прямо из UI. Значит окно есть, и правило для него
одно, в одном месте:

```
private val revision = AtomicLong(0)     // растёт на каждую публикацию

private fun publish(next: FamilyBookState): FamilyBookState { … }        // revision++
private fun publishFresh(seen: Long, next: FamilyBookState): FamilyBookState
```

Сетевой метод под локом берёт `val seen = revision.get()` **до** запроса и
отдаёт ответ через `publishFresh(seen, …)`. Если `revision` не менялся — ответ
публикуется как есть. Если менялся, значит пока ждали сеть:

- вышли из аккаунта — состояние уже `SignedOut`, ответ **выбрасывается целиком**
 (выход сильнее любого ответа; протухший токен в запросе тем более не повод
 воскрешать аккаунт);
- погасили одноразовый код — ответ публикуется, но через
 `FamilyBookState.withoutInviteCode()` (новая extension в `FamilyBookState.kt`):
 код, который человек уже скопировал и закрыл, не должен всплыть из ответа,
 отправленного раньше.

Больше состояний у окна нет, потому что больше нет писателей.

**Подписи методов не меняются** — старые тесты репозитория
(`FamilyAccountRepositoryTest`, 470 строк) остаются как есть, включая
`repository.signOut()` на `:467`.

**Защита от двойного тапа остаётся в ViewModel** (`inFlight: AtomicBoolean`,
`FamilyBookViewModel.kt:36`) и не переезжает: mutex второй вызов **ставит в
очередь**, а нужно его **уронить**. После П3 экземпляр ViewModel один на весь
NavHost, то есть флаг снова глобален. Даже если бы их было два, второй
`createFamily`/`joinFamily` отбивает сервер — повторная проверка членства внутри
транзакции, уже доказанная `backend/tests/test_family_backend_contract.py`.

### П2. `Loading` носит аккаунт

`data class Loading(override val account: FamilyAccount? = null)`. Проверки
`is FamilyBookState.Loading` (`SettingsScreen.kt:539`, `FamilyBookViewModel.kt:39`)
продолжают работать; сравнение по значению в
`app/src/test/java/com/polinalinen/madre/account/FamilyBookStateTest.kt:73,95`
правится на `Loading()`.

### П3. Один `FamilyBookViewModel`, аккаунт раздаётся сверху

`familyBookViewModel` объявляется в `MadreNavHost` рядом с `shelfViewModel`
(`MadreNavHost.kt:72`) и передаётся в `SettingsScreen` и `SettingsShelfScreen`.
Там же NavHost один раз собирает `val familyBookState by
familyBookViewModel.state.collectAsState()` и передаёт `account` вниз.

Следствие для `ShelfViewModel`: `refresh` меняет подпись на
`refresh(account: FamilyAccount?, localName: String, localRecords: List<BakeRecordEntity>)`
и **перестаёт звать** `familyAccountRepository.restore()` и `.refresh()`
(`ShelfViewModel.kt:50-51`). Третий писатель состояния исчезает. Чтение
участников (`listFamilyUsers`) и статистики (`madreApi.listBakeStats`) остаётся —
это чтения, они состояние аккаунта не пишут.

Перечитывание владельца полки (то, ради чего звался `refresh()`) переезжает в
явный `FamilyBookViewModel.refreshFamily()` → `repository.refresh()`, и зовёт его
`SettingsShelfScreen` один раз на вход, `LaunchedEffect(account?.familyId)`.

Ключ `LaunchedEffect` для `shelfViewModel.refresh` сужается с целого
`familyBookState` (`SettingsShelfScreen.kt:78`) до
`account?.familyId to account?.familyName`: `Loading` больше не гоняет сеть.

### П4. Выход из аккаунта — в общих Настройках

В разделе «Полка» (`SettingsScreen.kt:199-210`), под строкой с названием, тем же
текстом «Выйти · книга на телефоне останется» и только когда человек вошёл.
Мёртвая ветка `FamilyBookSection` (`SettingsScreen.kt:643-685`) удаляется целиком
вместе со своей копией кнопки ротации и своим `DisposableEffect(Unit) { onDispose
{ onCodeHandled() } }` (`:551-553`) — тем самым, что гасил только что полученный
код (Ф2).

Гашение одноразового кода после этого живёт ровно в двух местах, и оба —
осознанное действие человека: «Скопировать»/«Отправить»
(`SettingsShelfScreen.kt:184-198`) и уход с экрана полки — один
`DisposableEffect(Unit)` на весь `SettingsShelfScreen`, а не на ветку, которая
входит и выходит из композиции на каждый `Loading`.

### П5. `TapCycleRow` — пятая форма нажатия

Новая строка в `ui/components/BookControls.kt`:

```
@Composable
fun TapCycleRow(
    label: String,
    value: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
)
```

Подпись слева, текущее значение справа; `Role.Button`,
`onClickLabel = "$label: $value"` (тот же формат, что у `SettingsRow`, чтобы не
ломать `SettingsIaUiTest:100-111`), мишень ≥48dp, тап — следующее по кругу.
`valueColor` обязателен по сигнатуре и обязателен по смыслу: без него
«Напоминания» теряют разницу между `sage` и `cocoa` (Ф5).

Пятая форма языка кнопок вписывается в hard rule №9 в `CLAUDE.md` в том же
коммите.

### П6. `repeatable` — крутилка без `TapGate`

`bookAction` и `BookButton` получают `repeatable: Boolean = false`;
`repeatable = true` — гейт не ставится. Причина, которую надо записать
комментарием в коде: гейт защищает от **необратимого** дубля («Дальше»,
«Вписать в дневник»), а повторный тап по крутилке даёт всего лишь следующее
значение — оно видно и отменяется продолжением круга. Ставить сюда окно 600 мс
значит глотать 3 тапа из 4 (Ф5).

`repeatable = true` ставится в двух местах и только в них: `TapCycleRow` и
кнопка решения на «Испечено» (П9). Кнопка «На главную» гейт сохраняет.

Значение из одного элемента крутилкой не рисуется вовсе (нажатие без последствий
= hard rule №8).

### П7. Место хранения — обычная строка значения, без исключения

Отменяет редакцию 1, где № 5 оставался штампом. USER DECISION 5 не делает
исключений, кроме «Камера/Галерея», и придумывать шестое — не дело плана.

`FeedingFormScreen.kt:216-232` — пара `LocationChip` заменяется на `TapCycleRow`
с подписью «Где стоит закваска» и значениями `Кухня` ↔ `Холод` (ровно те строки,
что объявлены сегодня на `:221` и `:227`; `uppercase()` был приёмом отрисовки
штампа, а не копирайтом). `LocationChip` (`:584-609`) удаляется вместе с
`selectable`/`Role.RadioButton`.

«Где стоит закваска» — **единственная новая видимая строка этого цикла**. Она
названа здесь вслух, чтобы Гес мог её отклонить одной правкой; смена этой строки
плана не меняет (О3).

### П8. Оттиск крутится вместе с «без оттиска»

№ 9 крутится по `PhotoStamp.entries + null`; для `null` значение называется
«без оттиска». Существующая строка-пояснение (`PhotoDesigner.kt:247-255`)
переписывается в один вариант: «оттиск не обязателен — можно оставить карточку
чистой» (уже существующий текст), потому что «тап по выбранному оттиску снимает
его» после перехода на круг неправда.

### П9. Настройка «Ставить выпечку на полку» умирает целиком

Переписано по USER DECISION 7–8. В редакции 1 настройка сохранялась и задавала
дефолт кнопки — этого больше нет.

Удаляется: строка `SettingsShelfShareRow` и её `AlertDialog`
(`SettingsShelfScreen.kt:211-214`, `:232-266`, `:294-314`); `enum ShelfShareMode`;
`PREFS`/`KEY`; `SETTING_LABEL`, `ALWAYS_LABEL`, `ASK_LABEL`; `read`, `write`,
`parse`, `labelOf(mode)`; `shouldShareOnComplete`, `shouldAskOnComplete`,
`showOnShelfStamp`; `SHEET_TITLE`, `PUT_LABEL`, `PUT_WITH_PHOTO_LABEL`,
`KEEP_LABEL`, `ON_SHELF_STAMP`. Из `BakingViewModel` уходит и чтение prefs
(`:262-268`), и импорт (`:25`).

Остаётся `enum ShelfShareDecision`, из него — только `PUT_WITH_PHOTO` и `KEEP`;
`PUT` удаляется вместе с третьей кнопкой листа (круг по USER DECISION 6 ровно
из двух значений, и третьего в нём быть не может).

Новая поверхность `shelf/ShelfSharePolicy.kt`:

```
val DEFAULT_DECISION = ShelfShareDecision.PUT_WITH_PHOTO       // константа поставки
fun next(current: ShelfShareDecision): ShelfShareDecision      // круг из двух
fun labelOf(d: ShelfShareDecision): String                     // дословно
fun shouldEnqueue(d: ShelfShareDecision): Boolean              // как есть
fun wantsPhoto(d: ShelfShareDecision): Boolean                 // как есть
```

Подписи дословные: `PUT_WITH_PHOTO` → `на полке · с кадром`, `KEEP` → `себе`.
Кнопки нет вовсе, когда `sharingAvailable == false` (правило Cycle 17), — не
неактивная кнопка, а её отсутствие.

### П10. Кадр не обязателен для факта, отказ ничего не публикует

Дорога, когда выбрано `на полке · с кадром`, фото нет и нажата «На главную»:

1. Экран **не** уходит. Открывается «Камера / Галерея» (та же
 `rememberPhotoAttachment`), и экран помнит `pendingExit = true`.
2. Дорога кончилась отказом — отмена листа источника, пустой выбор в галерее,
 отказ камеры, отсутствие камеры, запрет разрешения, отмена «Стола оформления»,
 неудачное `PhotoStore.stage` (все шесть-семь случаев из Ф6б): `pendingExit`
 гасится, человек **остаётся на «Испечено»**, на полку не уходит **ничего**,
 подпись кнопки остаётся `на полке · с кадром`. Никакой молчаливой подмены
 решения на `себе` и никакой отправки без кадра.
3. Дорога кончилась кадром: сначала кадр **дописывается в запись формуляра и
 запись подтверждена**, затем ровно одна постановка в очередь, затем выход.
 Порядок наблюдаем, потому что выражен одним методом (П16), а не тремя
 независимыми `launch`.

Отменяет П10 редакции 1 («отказ → факт без кадра»): молча превращать «с кадром»
в «без кадра» — это решение за человека, а по hard rule №8 книга такого не
делает. Третьей позиции в круге по-прежнему нет.

Чтобы у шага 2 был сигнал, `ui/photo/PhotoAttachment.kt` получает
`onCancelled: () -> Unit = {}`, а правило «когда именно дорога считается
законченной без кадра» выносится в чистый `ui/photo/PhotoRoad.kt`:

```
enum class PhotoRoadState { IDLE, CHOOSING, PICKING, DESIGNING }
object PhotoRoad { fun next(state, event): PhotoRoadState }
```

Чистая функция нужна ровно потому, что наивный вариант неверен:
`PhotoSourceChooser.onDismiss` (`PhotoSourceChooser.kt:70`) срабатывает **и**
когда источник выбран, и лист «отмена» зовёт `onDismiss` дважды
(`:124` — `choose(onDismiss)` вызывает его как действие и ещё раз после
`hide()`). `onCancelled` обязан прозвучать ровно один раз на переход в `IDLE`
и ни разу, если был `onAttached`. Сам `PhotoSourceChooser.kt` при этом **не
правится** — значит его голый `clickable` и скругление 8dp остаются известным
долгом, а не незамеченной ошибкой (О4).

### П11. Штамп «на полке» с экрана готовности убирается

Пока ничего не отправлено, он был бы ложью (hard rule №8). Печать «ИСПЕЧЕНО» с
датой остаётся и остаётся ненажимаемой.

### П12. Системная «назад» уходит «себе» и молча

`BackHandler` на `BakingCompleteScreen.kt:85` перестаёт быть синонимом «На
главную». Он:

- закрывает сессию (`exitSession`) — это как раз то, ради чего он написан:
 иначе завершённая выпечка останется висеть в активных;
- **не** ставит ничего в очередь, какое бы значение ни стояло на кнопке;
- **не** открывает «Камера / Галерея».

Причина словами: смахнуть назад — это уйти, а не опубликовать. Публикация
семейных данных обязана быть следствием нажатия, названного словами, и только
его. «На главную» финализирует ровно то значение, которое видно на кнопке.

Комментарий на `:82-84` («означает ровно то же, что кнопка На главную») в том же
коммите переписывается: он станет неправдой.

### П13. Дата на сургуче — из записи, не от часов отрисовки

`BakingViewModel` заводит `val completedAt: StateFlow<Map<Long, Long>>`
(sessionId → millis). Значение кладётся в `advanceStep` в тот же момент, что и
`bakeRecordIds[id]` (`BakingViewModel.kt:259-262`), и тем же числом, которым
запись легла в базу: `bakeHistoryRepository.record(...)` получает
`completedAtMillis` явным аргументом (`BakeRecordEntity.completedAtMillis`,
`data/db/entities/BakeRecord.kt:20`, default остаётся для остальных вызовов —
схема Room **не меняется**, версия 10, миграций нет).

Экран берёт `romanDate(completedAt[sessionId])`, а не
`System.currentTimeMillis()`; тем же числом кормится `AgedPhoto(takenAtMillis =
…)` (`BakingCompleteScreen.kt:201`). Часовой пояс — местный, как и сейчас
(`java.util.Calendar.getInstance()`, `:374`): римская дата книги — это дата на
кухне, а не UTC.

Пока число ещё не известно (запись создаётся асинхронно), печать рисуется без
подписи-даты, а не с сегодняшней: пустая подпись честна, вчерашняя выпечка с
завтрашним числом — нет.

### П14. Отказ и оффлайн видны на экране полки

В ветке «уже на полке» (`SettingsShelfScreen.kt:124-226`) над блоком действий
появляется строка `failed.failure.message` цветом `terracotta` — тем же приёмом,
что в форме входа (`SettingsScreen.kt:565-573`), и теми же уже существующими
текстами `NetworkFailure` (`FamilyBookState.kt:69-77`), нового копирайта нет.

`Loading` с известным аккаунтом рисует тихую строку «проверяем полку» рядом с
действиями и **не** подменяет содержимое экрана. Правило одно: пока аккаунт
известен, экран полки остаётся экраном полки — в отказе, в загрузке и в
оффлайне.

Закрывает USER DECISION 3 до конца: rotate в оффлайне теперь говорит, почему
кода не появилось, вместо того чтобы просто ничего не показать.

### П15. Хранимое значение старой настройки убирается миграцией, а не молчанием

Ключ `shelf_share_mode` лежит в `madre_prefs` у всех, кто когда-либо открывал
экран полки. `madre_prefs` — общий файл: там же `my_name`, спокойный режим,
счётчики кофейных кругов. Стирать файл нельзя.

`utils/LegacyPrefs.kt` (`:22`) уже держит ровно этот механизм: список
префиксов + чистая функция `obsoleteKeys`, покрытая юнит-тестом, и
`purge` из `MadreApplication.onCreate` (`MadreApplication.kt:37-39`) на IO.
Добавляется единственный элемент — точный ключ `shelf_share_mode` (точный, не
префикс: префикс `shelf_` захватил бы будущие ключи полки).

Порядок обязателен: **сначала** код перестаёт читать ключ (коммит 4 целиком),
**в том же коммите** он добавляется в `LegacyPrefs`. Обратного порядка быть не
может — почищенный ключ, который ещё читают, даст `parse(null) → ALWAYS`,
то есть молчаливую отправку на старом коде.

Что меняется для тех, у кого стояло `спросить при готовности`: раньше на
готовности вставал лист с тремя кнопками, теперь — кнопка со значением
`на полке · с кадром`. Это **не** тихая отправка: значение названо словами на
экране, до «На главную» не уходит ничего, а системная «назад» уходит «себе»
(П12). Осознанное следствие USER DECISION 8, названное здесь, а не обнаруженное
потом.

Ни одной подписи, обещающей «спросить», после коммита 4 в книге не остаётся —
константы удалены, рисовать их нечем.

### П16. Порядок отправки и «ровно один раз» — в одном методе

`BakingViewModel` получает единственную дверь наружу с экрана готовности:

```
fun finish(
    sessionId: Long,
    decision: ShelfShareDecision,
    onExit: () -> Unit,
)
```

Внутри — один `viewModelScope.launch`, и порядок в нём линейный:

1. если `wantsPhoto(decision)` и кадр есть — `bakeHistoryRepository.attachPhoto`
 **с ожиданием** (существующий suspend, `BakeHistoryRepository.kt:30-34`);
2. если `shouldEnqueue(decision)` и сессия ещё не отправлялась — ровно один
 `sync.shareBakeStat(...)`;
3. `exitSession(sessionId)`;
4. `onExit()`.

Автоотправка из `advanceStep` (`BakingViewModel.kt:259-269`) удаляется. Публичный
`shareBakeStats(id, withPhoto)` (`:363-382`) перестаёт быть публичным: единственный
вызывающий — `finish`.

**Ровно один раз — три слоя, и каждый закрывает свой случай:**

| Слой | Где | Что закрывает |
|---|---|---|
| `sharedSessionIds` в ViewModel | `BakingViewModel` (поле уже есть, `:120`) | два быстрых «На главную», пересоздание экрана |
| уникальное имя `sync-bake-chain-$recordId` + `KEEP` | `SyncRepository.kt:59-70` | повтор, пока работа ещё в очереди этого телефона |
| `client_event_id = "$deviceId-$recordId"` | `SyncEventId.kt:39` | доставленный POST с потерянным ответом, доотправка после переустановки |

**Порядок «факт → кадр»** уже выражен цепочкой
`beginUniqueWork(chain, KEEP, bake).then(photo)` (`SyncRepository.kt:63-67`) и
не переписывается. Но проверить его юнит-тестом сейчас нечем: WorkManager в
`app/build.gradle.kts` тестовой зависимости не имеет (`work-testing` нет,
`:191-197`). Заводить её ради одного теста — лишняя зависимость; вместо этого
правило вынимается в чистую функцию `sync/ShelfSharePlan.kt`:

```
data class SyncStep(val kind: String)
data class SharePlan(val uniqueName: String, val policy: String, val steps: List<SyncStep>)
fun planForBake(recordId: Long, photoPath: String?): SharePlan
```

`SyncRepository.shareBakeStat` строит план и исполняет его; тест проверяет план
без Android. Наблюдаемость порядка на живом WorkManager остаётся за
runtime-гейтом.

Для проверки шага 1→2 в юнит-тесте `BakingViewModel` перестаёт брать
`madreApp.syncRepository` конкретным классом: вводится интерфейс
`sync/ShelfSync` с двумя методами (`shareBakeStat`, `clearBakePhoto`), который
реализует `SyncRepository`, а `MadreApplication.syncRepository`
(`MadreApplication.kt:74`) объявляется этим типом. Никакой DI-библиотеки, один
интерфейс.

---

## Четыре коммита, в этом порядке

Порядок не произвольный: 1 → 2 → 3 → 4 по зависимостям. Каждый коммит собирается
и зелёный сам по себе.

### Коммит 1 — `fix: полка переживает уход последнего`

Первым и отдельно, потому что это **бэкенд**, Kotlin в нём нет вовсе, и потому
что копирайт «Уйти с полки · можно вернуться» до этой правки — неправда для
одиночной полки (Ф7). Закрывает findings гейта № 2.

Файлы:

- `backend/pb_hooks/madre_family.pb.js`, маршрут `leave` (`:215-254`):
 - `txApp.delete(family)` (`:246`) **удаляется**. Полка без участников не
 исчезает, а становится спящей: запись `families` цела, `invite_code_hash`
 цел, строки `bake_stats` с relation на неё целы.
 - передача владения (`:247-250`) становится детерминированной: сортировка в
 `findRecordsByFilter` (`:230-237`) меняется с `""` на `"created,id"`, и
 владельцем становится **самый ранний** оставшийся. «Кто именно» перестаёт
 быть вопросом к движку.
 - уход последнего владельца поле `owner` не трогает (оно указывает на
 ушедшего) — это не сирота, а метка спящей полки.
- `backend/pb_hooks/madre_family.pb.js`, маршрут `join` (`:84-148`): внутри
 транзакции, после того как полка найдена, считается число её участников. Ноль —
 полка спящая, и вошедший становится её владельцем (`joined.set("owner",
 e.auth.id)`). Без этого правила спящую полку мог бы оживить человек, который
 никогда не сможет ни переименовать её, ни сменить код: обе операции проверяют
 `owner` (`:170`, `:202`).
 Правило одно и произносится одной фразой: **пустую полку оживляет тот, кто в
 неё вошёл, — он и становится владельцем.** Вернувшийся основатель под него
 подпадает наравне со всеми.
- `docs/adr/` — короткий ADR «спящая полка»: почему записи копятся и не
 собираются автоматически (сборка мусора требует политики хранения чужих
 `bake_stats`, а её никто не принимал), и почему это лучше, чем
 «вернуться некуда».

Тесты (`backend/tests/test_family_leave_dormant.py`, тот же статический стиль,
что и `test_family_backend_contract.py`, живой PocketBase не нужен):

| Тест | Что держит |
|---|---|
| `test_leave_never_deletes_the_family` | в теле `leave` нет ни одного `txApp.delete(` |
| `test_leave_keeps_the_invite_hash` | `leave` не присваивает `invite_code_hash` |
| `test_leave_writes_membership_once` | `member.set("family", "")` ровно один раз |
| `test_leave_never_touches_bake_stats` | подстроки `bake_stats` в маршруте нет |
| `test_owner_transfer_is_deterministic` | третий аргумент `findRecordsByFilter` — `"created,id"`, не `""` |
| `test_owner_transfer_takes_the_first_of_the_sorted` | владельцем ставится `others[0]` **после** отсортированного запроса |
| `test_join_revives_a_dormant_shelf_by_taking_ownership` | в транзакции `join` есть подсчёт участников и `set("owner"` под условием «ноль» |
| `test_join_ownership_write_stays_inside_the_transaction` | присвоение владельца — внутри `runInTransaction`, а не после |
| `test_bake_stats_relations_stay_non_cascading` | в обеих миграциях `family` и `user` по-прежнему `"cascadeDelete": false` |

Runtime-гейт (живой PocketBase, обязателен — статический тест доказывает форму
хука, а не поведение сервера):

| № | Сценарий | Ожидание |
|---|---|---|
| R1 | двое, уходит не-владелец | полка жива; `bake_stats` ушедшего на месте; тот же код возвращает его; полка снова видна |
| R2 | двое, уходит владелец | владельцем стал оставшийся (самый ранний по `created`); старый код всё ещё работает; вернувшийся — обычный участник |
| R3 | один, уходит единственный | запись `families` **существует**; `bake_stats` на месте; тот же код возвращает его; после возврата он владелец |
| R4 | возврат по коду | второй полки не появилось; `bake_stats` не задвоились; счётчик выпечек тот же |
| R5 | спящую полку оживил другой человек | он владелец; переименование и ротация кода ему доступны |

Готово, когда: python-контракты зелёные; R1–R5 отработаны на живом PB и записаны
в `workflow/evidence/cycle28/runtime.md`.

### Коммит 2 — `feat: shelf-settings-truth`

Первый Kotlin-коммит: это единственный релиз-блокер (экран полки на rename/rotate
превращается в форму входа), и коммит 4 читает аккаунт и `sharingAvailable` из
уже починенного состояния. Закрывает findings № 1 и № 6.

Файлы:

- `account/FamilyBookState.kt` — П2 + `withoutInviteCode()`.
- `account/FamilyAccountRepository.kt` — П1: `_state`/`state`, удаление поля
 `account`, `Mutex`, `revision`, `publish`/`publishFresh`, `Loading` из
 репозитория. Публикуют все: `restore`, `refresh`, `signIn`, `register`,
 `createFamily`, `joinFamily`, `rotateInviteCode`, `renameFamily`, `leaveFamily`,
 `signOut`, `clearInviteCode`.
- `viewmodel/FamilyBookViewModel.kt` — `state` = поток репозитория;
 `runNetwork` больше не пишет `Loading`; `clearInviteCode` больше не правит
 состояние сам (`:44-59` схлопывается до вызова репозитория); `inFlight` и
 `passwordReset` остаются; появляется `refreshFamily()`.
- `viewmodel/ShelfViewModel.kt` — П3: новая подпись `refresh`, без вызовов
 `restore()`/`refresh()` репозитория (`:50-51`).
- `navigation/MadreNavHost.kt` — П3: объявление рядом с `:72`, сбор состояния,
 передача `account` в `SettingsScreen` (`:290-310`), `SettingsShelfScreen`
 (`:311-318`) и `ShelfScreen` (`:319-327`).
- `ui/screens/ShelfScreen.kt` — новый аргумент `account`, ключ `LaunchedEffect`
 (`:65`).
- `ui/screens/SettingsShelfScreen.kt` — единственная `TextAction`
 «Уйти с полки · можно вернуться»; `signOut` с экрана убран; список людей вынесен
 в `internal fun ShelfPeopleList(members, familyOwnerId)` (тестируемость, Ф4);
 строка отказа и тихая строка загрузки (П14); один `DisposableEffect` на экран
 для гашения кода (П4); ключ `LaunchedEffect` сужен.
 Строка «Ставить выпечку на полку» в этом коммите **не трогается** — она умирает
 целиком в коммите 4, вместе со своим чтением из `BakingViewModel`.
- `ui/screens/SettingsScreen.kt` — П4; удаление мёртвой ветки `:643-685` и её
 `DisposableEffect` `:551-553`; ветка `loading` (`:539`, `:574-575`) больше не
 прячет карточку, если аккаунт известен.
- `CLAUDE.md` — «Навигация»: в колофоне блок «Полка» — строка с названием **и
 выход из аккаунта**; «Сеть»: состояние аккаунта живёт в репозитории, `Loading`
 носит аккаунт, у полки один `FamilyBookViewModel`.
- `DESIGN-V4.md` §Cycle 28 фича 1.

Тесты (новые классы):

| Класс | Файл | Что держит |
|---|---|---|
| `FamilyAccountStateFlowTest` | `app/src/test/java/com/polinalinen/madre/account/FamilyAccountStateFlowTest.kt` | поток отдаёт то же, что возвращает метод: `signIn`, `createFamily`, `renameFamily`, `leaveFamily`, `signOut`; `Loading` публикует репозиторий и он несёт аккаунт |
| `FamilyAccountSerializationTest` | `app/src/test/java/com/polinalinen/madre/account/FamilyAccountSerializationTest.kt` | два одновременных вызова не перетирают друг друга (rename + refresh на медленном фейке api → в потоке новое имя, не старое) |
| `FamilyAccountStaleResponseTest` | `app/src/test/java/com/polinalinen/madre/account/FamilyAccountStaleResponseTest.kt` | `signOut` во время висящего запроса → ответ выброшен, состояние `SignedOut`; `clearInviteCode` во время висящего `rotate` → состояние без кода |
| `FamilyBookRenameVisibilityTest` | `app/src/test/java/com/polinalinen/madre/viewmodel/FamilyBookRenameVisibilityTest.kt` | две ViewModel на одном репозитории: rename в одной → у второй новое `familyName`, и второго `restore()`/`auth` в `api.calls` нет (USER DECISION 2) |
| `FamilyBookLoadingKeepsAccountTest` | `app/src/test/java/com/polinalinen/madre/viewmodel/FamilyBookLoadingKeepsAccountTest.kt` | в `Loading` `state.account` не null на rename/rotate/leave; после успешного rotate новый код лежит в состоянии (USER DECISION 3) |
| `LeaveKeepsBookTest` | `app/src/test/java/com/polinalinen/madre/account/LeaveKeepsBookTest.kt` | `leaveFamily` → `SignedIn` без семьи, токен цел, в `api.calls` нет выхода из аккаунта |
| `ShelfViewModelDoesNotWriteAccountTest` | `app/src/test/java/com/polinalinen/madre/viewmodel/ShelfViewModelDoesNotWriteAccountTest.kt` | `refresh` не зовёт `restore()`/`refresh()` репозитория; список участников по-прежнему не схлопывается на сбое сети |
| `SettingsShelfTruthUiTest` | `app/src/test/java/com/polinalinen/madre/settingsui/SettingsShelfTruthUiTest.kt` | на экране есть «Уйти с полки · можно вернуться»; «Выйти · книга на телефоне останется» нет; после rotate код виден и формы входа нет ни в одном кадре; свод hard rule №9 |
| `SettingsShelfFailureUiTest` | `app/src/test/java/com/polinalinen/madre/settingsui/SettingsShelfFailureUiTest.kt` | rename/rotate/leave с `OFFLINE` → на экране `NetworkFailure.OFFLINE.message`, и имя полки со списком людей остались на месте (П14) |
| `ShelfPeopleListUiTest` | `app/src/test/java/com/polinalinen/madre/settingsui/ShelfPeopleListUiTest.kt` | имена всех участников видны, у основателя «кто завёл полку» (USER DECISION 4) |
| `SettingsSignOutHomeUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/SettingsSignOutHomeUiTest.kt` | «Выйти · книга на телефоне останется» есть в общих Настройках и только у вошедшего (П4) |

Правятся существующие: `FamilyBookStateTest` (`:73`, `:95` — `Loading()`),
`FamilyBookViewModelTest` (источник состояния сменился), `SettingsIaUiTest`
(свод по hard rule №9 подхватывает новую строку выхода),
`FamilyBookSectionUiTest` (мёртвая ветка удалена).

Готово, когда: `testDebugUnitTest` зелёный; на экране полки rename и rotate не
показывают форму входа ни в одном кадре; имя в Настройках новое после «Назад».

### Коммит 3 — `feat: tap-cycle-controls`

Даёт `TapCycleRow` и `TapCycle`, которыми коммит 4 крутит свою кнопку. После
коммита 2, потому что трогает те же файлы настроек. Закрывает findings № 7 и № 8.

Файлы:

- `ui/components/BookControls.kt` — `TapCycleRow` с `valueColor` (П5),
 `bookAction(repeatable)` и `BookButton(repeatable)` (П6), `TapGate` без изменений.
- `ui/components/TapCycle.kt` — чистая функция круга:
 `fun <T> next(options: List<T>, current: T): T` (неизвестное текущее → первое,
 один элемент → он же). Без Android, поэтому проверяется юнит-тестом.
- `ui/screens/SettingsScreen.kt` — № 1 «Напоминания» на `TapCycleRow` **с
 сохранением `valueColor`** (`sage`/`cocoa`); ветка «не разрешены телефоном»
 остаётся `SettingsRow` с `onClick = null` и `terracotta` — она не выбор
 значения, крутить там нечего (hard rule №8). № 2 «Оформление», № 3 «Как часто
 кормить» (значение остаётся `Ваш ритм: …`, подпись про Levito Madre остаётся);
 `SettingsChoiceDialog` (`:437-477`) удаляется как мёртвый.
- `ui/screens/FeedingFormScreen.kt` — № 5 по П7: `TapCycleRow` «Где стоит
 закваска», `LocationChip` удаляется.
- `ui/screens/RecipeDetailScreen.kt` — № 6: `PortionSelector` становится строкой
 «На сколько печём» со значением `×N семья/семьи/семей`, круг 1…5 →1;
 `portionLabel(n)` живёт дальше как часть `onClickLabel`.
- `ui/photo/PhotoDesigner.kt` — № 7–10 на четыре строки (П8); «Угол» по-прежнему
 появляется только при выбранном оттиске; `DecorGroup`/`DecorChip` удаляются, если
 после этого никем не читаются; hard rule №9 для «← Отмена» (`BackLabel`),
 «Готово» (`BookButton` PRIMARY), «Без оформления» (`BookButton` SECONDARY).
- `CLAUDE.md` — hard rule №9: пятая форма нажатия `TapCycleRow`; `PhotoDesigner`
 уходит из списка файлов, которые ещё держат старые `clickable`.
- `DESIGN-V4.md` §Cycle 28 фича 2 — перечень девяти и исключение «Камера/Галерея».
- `docs/graveyard.md` — Cycle 28: `SettingsChoiceDialog`, пара слов «спокойное /
 живое» рядом, рамка из пяти ячеек порций, штампы-радиокнопки места хранения,
 ряды `DecorChip`. Причина смерти — USER DECISION 5, и что придётся объяснить,
 если возвращать.

Тесты (новые классы):

| Класс | Файл | Что держит |
|---|---|---|
| `TapCycleTest` | `app/src/test/java/com/polinalinen/madre/ui/components/TapCycleTest.kt` | круг замыкается, неизвестное текущее → первое, один элемент → он же |
| `TapCycleRowUiTest` | `app/src/test/java/com/polinalinen/madre/ui/components/TapCycleRowUiTest.kt` | четыре тапа = четыре шага (гейт не глотает, П6); `Role.Button`, `onClickLabel` формата `«подпись: значение»`, ≥48dp; диалога не появляется |
| `SettingsTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/SettingsTapCycleUiTest.kt` | интервал кормления и оформление меняются одним `onClick` без окна; подпись действия у ритма — прежнего формата; ветка «не разрешены телефоном» не крутится |
| `FeedingStorageTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/FeedingStorageTapCycleUiTest.kt` | место хранения — одна строка «Где стоит закваска», тап меняет `Кухня`↔`Холод`, мишень ≥48dp, `Role.Button` (не `RadioButton`) |
| `PortionTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/recipe/PortionTapCycleUiTest.kt` | круг 1…5→1, значение названо словами, одна кнопка вместо пяти |
| `PhotoDesignerTapCycleUiTest` | `app/src/test/java/com/polinalinen/madre/ui/photo/PhotoDesignerTapCycleUiTest.kt` | рамка/тепло/оттиск/угол — четыре строки-круга; «без оттиска» в круге есть; «Угол» без оттиска не показывается |
| `PhotoSourceChooserUiTest` | `app/src/test/java/com/polinalinen/madre/ui/components/PhotoSourceChooserUiTest.kt` | «Камера» и «Галерея» — два отдельных действия, не круг (USER DECISION 5) |
| `SettingsStarterSectionGoldenTest` | `app/src/test/java/com/polinalinen/madre/ui/visual/SettingsStarterSectionGoldenTest.kt` | цвет значения — не текст, и текстовым ассертом он не проверяется; золотой снимок раздела «Закваска» держит `sage`/`cocoa` у «Напоминаний» (finding № 8) |

Переписываются существующие (фича изменена решением Димы, не «чтобы позеленело»):

- `PortionSelectorUiTest` (`:50-70`) — `assertIsSelected` и `contentDescription` по
 каждой ячейке относятся к удалённой рамке из пяти ячеек. Остаётся проверка, что
 значение названо словами и что мишень с палец.
- `SettingsIaUiTest:117-122` — «оба оформления названы сразу» противоречит USER
 DECISION 5. Заменяется на «видно текущее, тап даёт следующее».
- `TapGateTest` — добавить случай `repeatable`.
- `FeedingFormGoldenTest` — золотые в `app/src/test/snapshots` перезаписать
 (`recordRoborazziDebug`): вместо двух штампов теперь строка.

Готово, когда: ни один из девяти выборов не открывает окна; «Камера/Галерея»
не изменилась; `verifyRoborazziDebug` зелёный на перезаписанных золотых; каждое
изменение золотого показано Gemini на визуальном гейте.

### Коммит 4 — `feat: baked-seal-toggle`

Последним: читает починенный аккаунт (коммит 2) и круг из коммита 3. Закрывает
findings № 3, № 4, № 5, № 9, № 10.

Файлы:

- `shelf/ShelfSharePolicy.kt` — П9: демонтаж режима и листа, новая поверхность
 (`DEFAULT_DECISION`, `next`, `labelOf`, `shouldEnqueue`, `wantsPhoto`).
- `sync/ShelfSharePlan.kt` — новый, П16: чистый план постановки в очередь.
- `sync/ShelfSync.kt` — новый, П16: интерфейс из двух методов.
- `sync/SyncRepository.kt` — реализует `ShelfSync`, строит план и исполняет его;
 поведение очереди (имена, `KEEP`, цепочка) не меняется.
- `MadreApplication.kt:74` — тип `syncRepository` меняется на `ShelfSync`.
- `viewmodel/BakingViewModel.kt` — П16: `finish(...)`; автоотправка из
 `advanceStep` (`:259-269`) убрана вместе с чтением prefs; `shareBakeStats`
 становится приватным; П13: `completedAt` и явный `completedAtMillis` в
 `bakeHistoryRepository.record`.
- `data/repository/BakeHistoryRepository.kt` — `record(...)` принимает
 `completedAtMillis` (default сохраняется, схема Room не меняется).
- `ui/photo/PhotoRoad.kt` — новый, П10: чистый автомат дороги.
- `ui/photo/PhotoAttachment.kt` — П10: `onCancelled`, автомат вместо россыпи
 флагов; поведение удачной вклейки не меняется.
- `ui/screens/BakingCompleteScreen.kt` — печать с датой из записи (П13) и без
 `clickable`; под ней один `BookButton(repeatable = true)`, крутящий два
 значения; лист (`:253-299`), штамп «на полке» (`:234-240`) и
 `askDismissed`/`onShelf`/`pendingPhotoShare` уходят; «На главную» ведёт в
 `finish` (П16) и в дорогу кадра при необходимости (П10); `BackHandler` — П12;
 `PastedPhotoPrompt` (`:338`) переводится на `bookAction`.
- `ui/screens/SettingsShelfScreen.kt` — удаление `SettingsShelfShareRow`
 (`:211-214`, `:294-314`) и её `AlertDialog` (`:232-266`).
- `utils/LegacyPrefs.kt` — П15: точный ключ `shelf_share_mode`.
- `navigation/MadreNavHost.kt:230-241` — `onHome` больше не зовёт `exitSession`
 сам: сессию закрывает `finish` в правильном месте порядка, NavHost только
 `popBackStack`.
- `CLAUDE.md` — «Сеть»: решение о полке принимается на экране готовности в
 момент «На главную», а не в `advanceStep`, и настройки, задающей его дефолт,
 в книге нет; `PhotoDesigner` и `BakingCompleteScreen` уходят из списка
 исключений hard rule №9.
- `DESIGN-V4.md` §Cycle 28 фича 3.
- `docs/graveyard.md` — Cycle 28: настройка «Ставить выпечку на полку» вместе с
 режимами `всегда`/`спросить при готовности`, лист «Поставить на полку?», штамп
 «на полке» на экране готовности, автоотправка в момент готовности,
 `ShelfShareDecision.PUT`.

Тесты (новые классы):

| Класс | Файл | Что держит |
|---|---|---|
| `ShelfShareDecisionCycleTest` | `app/src/test/java/com/polinalinen/madre/shelf/ShelfShareDecisionCycleTest.kt` | дефолт — `на полке · с кадром`; круг ровно из двух и замыкается; подписи дословные; режима и чтения prefs в API больше нет |
| `BakingCompleteShelfButtonUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/BakingCompleteShelfButtonUiTest.kt` | дефолтная подпись `на полке · с кадром`; тап → `себе`; четыре тапа = четыре смены (гейта нет); листа «Поставить на полку?» нет; строки «тап — себе» нет; без токена кнопки нет вовсе |
| `BakingFinishOrderTest` | `app/src/test/java/com/polinalinen/madre/viewmodel/BakingFinishOrderTest.kt` | на фейковом `ShelfSync`: кадр записан **до** постановки в очередь, выход — **после** обоих; при `себе` очередь пуста; при `с кадром` `wantsPhoto` доехал до `SyncRepository` (finding № 9) |
| `BakingShareOnceTest` | `app/src/test/java/com/polinalinen/madre/viewmodel/BakingShareOnceTest.kt` | два `finish` подряд → один вызов `shareBakeStat`; `advanceStep` сам по себе не ставит в очередь ничего (finding № 9) |
| `ShelfSharePlanTest` | `app/src/test/java/com/polinalinen/madre/sync/ShelfSharePlanTest.kt` | с кадром — два шага в порядке «факт, кадр»; без кадра — один; имя работы `sync-bake-chain-<id>`, политика `KEEP` |
| `PhotoRoadTest` | `app/src/test/java/com/polinalinen/madre/ui/photo/PhotoRoadTest.kt` | «отмена» звучит ровно один раз на каждый из семи отказов и ни разу после удачной вклейки; двойной `onDismiss` листа источника (`PhotoSourceChooser.kt:124`) не даёт второго сигнала |
| `BakingCompleteMissingPhotoUiTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/BakingCompleteMissingPhotoUiTest.kt` | «На главную» при `с кадром` и без фото открывает выбор источника и **не уходит**; отказ — остались на «Испечено», очередь пуста, подпись кнопки прежняя; удача — кадр, потом одна отправка, потом выход (finding № 3) |
| `BakingCompleteBackIsPrivateTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/BakingCompleteBackIsPrivateTest.kt` | системная «назад» при `на полке · с кадром`: очередь пуста, выбор источника не открылся, сессия закрыта; «На главную» при том же значении — отправка ровно одна (finding № 4) |
| `BakedSealNotAControlTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/BakedSealNotAControlTest.kt` | у печати «ИСПЕЧЕНО» нет `OnClick`; дата в подписи есть |
| `BakedSealDateTest` | `app/src/test/java/com/polinalinen/madre/ui/screens/BakedSealDateTest.kt` | дата взята из `completedAtMillis`, а не из часов: сдвиг часов за полночь и пересоздание композиции её не меняют; при неизвестном ещё времени подписи нет, а не сегодняшней (finding № 5) |
| `LegacyPrefsShelfShareTest` | `app/src/test/java/com/polinalinen/madre/utils/LegacyPrefsShelfShareTest.kt` | `obsoleteKeys` берёт `shelf_share_mode` и **не берёт** `my_name`, `calm_mode`, `coffee_ring_*` (finding № 10) |

Правятся существующие: `ShelfSharePolicyTest` — `:11-32` (снятые функции режима),
`:43-48` (`parse`) и `:50-58` (подписи листа и настройки) удаляются как проверки
удалённой фичи; `:34-41` (`shouldEnqueue`/`wantsPhoto`) остаётся и дополняется
кругом.

Runtime-гейт: отправка «с кадром» и «без кадра» на живом PB; отказ от кадра
ничего не отправляет; системная «назад» ничего не отправляет; повторный вход на
экран готовности не создаёт второй записи.

Готово, когда: до «На главную» на полку не уходит ничего; после — ровно один раз
и ровно выбранное; отказ от кадра оставляет человека на «Испечено» с прежней
подписью кнопки.

---

## Риски

**Р1. Спящие полки копятся.** Коммит 1 перестаёт удалять `families`, и записи
брошенных полок остаются в базе навсегда. Осознанный обмен: альтернатива —
«вернуться некуда» и висящие `bake_stats` с relation в пустоту. Автоматической
сборки нет и в этом цикле не будет: она требует политики хранения чужой
статистики, а такой политики никто не принимал. Зафиксировано ADR в коммите 1.

**Р2. Спящую полку оживляет посторонний.** Правило «вошёл в пустую — стал
владельцем» означает, что вернувшийся основатель может застать полку уже с чужим
владельцем, если кто-то вошёл раньше. Это детерминировано, произносится одной
фразой и лучше альтернативы (полка с владельцем, которого в ней нет: ни
переименовать, ни сменить код). Ловится R5.

**Р3. `Loading` стирает аккаунт.** Корень трёх симптомов сразу: схлопывание
экрана в форму входа, исчезновение нового кода приглашения (через `onDispose` →
`clearInviteCode`) и лишние `refresh` полки. Лечится П1 + П2 + П4; ловится
`FamilyBookLoadingKeepsAccountTest` и `SettingsShelfTruthUiTest`. Обратная
сторона правки: `Loading` перестаёт быть `data object`, поэтому все сравнения
по значению надо найти — их два, `FamilyBookStateTest:73,95`.

**Р4. Гонка в репозитории после того, как писателей стало три.** П1 закрывает её
не «одним потоком», а тремя названными механизмами: mutex вокруг всей
read-modify-write, `revision` для двух не-suspend методов, удаление третьего
писателя (`ShelfViewModel`). Каждый ловится своим тестом
(`FamilyAccountSerializationTest`, `FamilyAccountStaleResponseTest`,
`ShelfViewModelDoesNotWriteAccountTest`). Если бы остался хоть один — правка
выглядела бы починенной и не была бы ею.

**Р5. `TapGate` глотает круг.** См. Ф5 и П6. Без этого пункта фича 3 выглядит
работающей на двух значениях и не работает на пяти — то есть худший вид поломки.
Ловится `TapCycleRowUiTest` (четыре тапа = четыре шага).

**Р6. Крутилка прячет список значений.** Прямое следствие USER DECISION 5 и 9,
названное Димой и принятое им. Хуже всего это у интервала кормления и порций
(по пять значений): вернуть предыдущее можно только пройдя круг. План эту цену
не смягчает и не маскирует — маскировка была бы отменой решения. Если после
живого прогона она окажется невыносимой, это разговор Cycle 29, а не правка
здесь.

**Р7. Перенос отправки в «На главную».** Выпечка, экран готовности которой не
открыли (процесс умер до перехода), на полку теперь не попадёт. Осознанный
обмен: раньше факт уходил без кадра и без спроса, теперь ничего не уходит молча.
Вместе с П12 это значит и то, что смахнувший назад не поделится — и это ровно то
поведение, которого мы хотим. Ловится `BakingCompleteBackIsPrivateTest` +
runtime.

**Р8. Смена дефолта для тех, у кого стояло «спросить».** П15. Не тихая отправка
(значение видно словами, ничего не уходит до «На главную», «назад» уходит
«себе»), но всё-таки смена привычного поведения. Названа вслух здесь и в
`docs/graveyard.md`.

**Р9. Ослабление тестов под видом переделки.** В коммите 3 умирают ассерты
`PortionSelectorUiTest` и `SettingsIaUiTest:117-122`, в коммите 4 —
`ShelfSharePolicyTest:11-32`, `:43-48`, `:50-58`. Каждый — потому что удалена
сама фича, которую он проверял (USER DECISION 5, 6 и 7), и каждый обязан быть
назван в теле коммита и в `docs/graveyard.md`. Всё, что не про удалённую фичу,
остаётся строже или так же.

**Р10. Золотые снимки.** Меняются `FeedingFormGoldenTest`; добавляется
`SettingsStarterSectionGoldenTest` (он и есть проверка цвета значения, текстом её
не сделать). `PhotoDesigner` золотых не имеет, поэтому его переверстка
проверяется только глазами Gemini на визуальном гейте — это надо сказать вслух,
а не надеяться на `verifyRoborazziDebug`.

**Р11. Мёртвый код после четырёх правок.** `SettingsChoiceDialog`, `DecorGroup`,
`DecorChip`, `LocationChip`, ветка `FamilyBookSection:643-685`, большая часть
`ShelfSharePolicy`, `ShelfShareMode`, `ShelfShareDecision.PUT`. Убирать в том же
коммите, иначе следующий цикл будет читать две правды.

---

## Открытые решения (не блокируют код)

- **О1.** ~~Уход последнего распускает полку~~ — **закрыто** коммитом 1: полка
 засыпает, код и статистика живут, вернуться можно. Копирайт
 «Уйти с полки · можно вернуться» становится правдой без оговорок.
- **О2.** ~~«спросить при готовности» после смерти листа~~ — **снято**: настройки
 больше нет вовсе (USER DECISION 7).
- **О3.** «Где стоит закваска» (П7) — единственная новая видимая строка цикла.
 Если Гес захочет других слов, это правка одного литерала и одного ассерта в
 `FeedingStorageTapCycleUiTest`; план не меняется.
- **О4.** `ui/components/PhotoSourceChooser.kt` в этом цикле не правится
 намеренно (П10), поэтому его голый `clickable` (`:124`, `:156`) и скругление
 8dp (`:161`) остаются. Это известный долг, а не пропущенная ошибка; hard rule
 №9 возьмёт файл при следующей его правке, как и записано в `CLAUDE.md`.
- **О5.** Формулировки `onClickLabel` («без оттиска» и т.п.) — служебные подписи
 для TalkBack, не видимый текст.

---

## Не делать

- Kotlin по этому плану пишет Cursor Codex на 108. Ни планировщик, ни Гес, ни судьи.
- `versionName` руками; второй APK в RuStore; правка карточек 6.6.0(35)/6.5.0(34);
 uninstall production.
- Новый визуальный язык кнопки с мокапа: на «Испечено» — существующий `BookButton`.
- Возвращать `emoji` из `recipes.json`, тёмную тему, углы >4dp, хардкод цвета,
 голый `Modifier.clickable` в правленых файлах.
- Возвращать настройку «Ставить выпечку на полку» ни под каким именем.
- Заводить `work-testing` ради одного ассерта (см. П16).
- «Прибирать» `MarginNoteEntity`/`SealedNoteEntity` и трогать схему Room: в этом
 цикле база не меняется вообще, миграций нет.
- Ослаблять тест, чтобы позеленело. Три попытки — и стоп с полным выводом падения.

---

## Гейт плана: три REVISE и как они закрыты

Пакет гейта по `docs/CURSOR-FIRST-WORKFLOW.md` §3, три судьи только читают.
Все трое вернули **VERDICT: REVISE** по редакции 1 плана.

| Судья | Полоса ответственности | Вердикт по редакции 1 |
|---|---|---|
| GLM-5.3 | данные и приватность | **REVISE** |
| DeepSeek V4 Pro | границы и риски | **REVISE** |
| GPT-5.6 Sol | Android и корректность | **REVISE** |

Замечания пришли в эту сессию сведённым списком из десяти пунктов (через Диму,
вместе с новым USER DECISION 7–9), поэтому ниже они привязаны к полосам **по
предмету**, а не как дословная стенограмма трёх ответов. Ни один пункт не закрыт
обещанием: у каждого назван файл правки и тест.

| № | Замечание | Полоса | Чем закрыто | Чем ловится |
|---|---|---|---|---|
| 1 | «Репозиторий получает StateFlow» — намерение, а не проект: не сказано, как сериализуются мутации и что делать с протухшим ответом; двух SSOT так не избежать | Sol | **П1** переписан целиком: поле `account` удалено, единственный держатель — `_state`; `Mutex` вокруг всей read-modify-write; `revision` + `publishFresh` с двумя явными правилами для `signOut` и `clearInviteCode`; третий писатель (`ShelfViewModel`) удалён в **П3** | `FamilyAccountStateFlowTest`, `FamilyAccountSerializationTest`, `FamilyAccountStaleResponseTest`, `ShelfViewModelDoesNotWriteAccountTest` |
| 2 | Уход последнего обязан оставлять спящую полку с кодом, статистикой и возможностью вернуться; уход владельца при живых участниках — детерминированная передача, старый код продолжает работать; нужны и бэкенд-тесты, и живые runtime-проверки | GLM + DeepSeek | **Коммит 1** целиком: `txApp.delete(family)` убран, сортировка `"created,id"`, правило «пустую полку оживляет вошедший»; ADR про накопление спящих полок | 9 python-контрактов в `test_family_leave_dormant.py` + живые R1–R5 |
| 3 | Нет фото: «На главную» открывает Камера/Галерея; отказ/запрет/сбой оставляют человека на «Испечено», не публикуют ничего и не меняют подпись кнопки; удачный кадр сначала сохраняется, потом ровно одна отправка, потом выход — нужны колбэки, файлы и тесты | Sol + GLM | **П10** (отменяет «факт без кадра» из редакции 1) + **Ф6б**: `onCancelled` в `PhotoAttachment.kt`, чистый `PhotoRoad.kt` на семь отказов, порядок — в `finish` (**П16**) | `PhotoRoadTest`, `BakingCompleteMissingPhotoUiTest`, `BakingFinishOrderTest` |
| 4 | Системная «назад» обязана быть приватной: выход как «себе», без отправки и без пикера; «На главную» финализирует выбранное. Проверить оба пути | GLM | **П12**: `BackHandler` больше не синоним «На главную»; комментарий `BakingCompleteScreen.kt:82-84` переписывается | `BakingCompleteBackIsPrivateTest` (обе ветки) |
| 5 | Дата на печати берётся от часов отрисовки; нужна persisted-отметка завершения и местный пояс; проверить полночь и пересоздание | Sol | **П13** + **Ф6**: `completedAt` в ViewModel, явный `completedAtMillis` в `record(...)`, тем же числом кормится `AgedPhoto`; неизвестное время → подписи нет, а не сегодняшняя | `BakedSealDateTest` |
| 6 | Отказ и оффлайн у rename/rotate/leave на экране полки не видны никак | DeepSeek | **П14** + **Ф4б**: строка `NetworkFailure.message` в ветке «уже на полке», тихая строка загрузки, экран не подменяется | `SettingsShelfFailureUiTest` |
| 7 | Кухня/Холод — такой же выбор значения; исключения для него никто не утверждал | DeepSeek | **П7** переписан: штамп-радиокнопка умирает, строка `TapCycleRow` «Где стоит закваска», исключение снято | `FeedingStorageTapCycleUiTest`, перезаписанный `FeedingFormGoldenTest` |
| 8 | Сохранить семантику `valueColor` и не ставить 600 мс `TapGate`; цену кругов из 3–5 значений Дима принял | Sol | **П5** (обязательный `valueColor`, ветка «не разрешены телефоном» остаётся некликаемой) + **П6** (`repeatable`) + **Р6** (цена названа, не смягчается) | `TapCycleRowUiTest`, `SettingsTapCycleUiTest`, `SettingsStarterSectionGoldenTest` |
| 9 | Порядок и «ровно один раз» в WorkManager не описаны файлами и не покрыты тестами | Sol + DeepSeek | **П16**: единственная дверь `BakingViewModel.finish`, три названных слоя идемпотентности, чистый `ShelfSharePlan`, интерфейс `ShelfSync` вместо новой тестовой зависимости | `BakingFinishOrderTest`, `BakingShareOnceTest`, `ShelfSharePlanTest` |
| 10 | Старую настройку убирать только после безопасной миграции существующих установок; подписи, обещающей «спросить», остаться не должно | GLM | **П9** (демонтаж всей поверхности) + **П15** (точный ключ в `LegacyPrefs`, порядок «сначала перестали читать»), смена дефолта для бывших `ASK` названа в **Р8** | `LegacyPrefsShelfShareTest`, `ShelfShareDecisionCycleTest`, компиляция (рисовать удалённые константы нечем) |

Кроме десяти замечаний, редакция 2 несёт новый **USER DECISION 7–9** (удаление
настройки, дефолт-константа, крутилка остаётся формой всех выборов). Из-за него
список выборов значения — девять строк, а не одиннадцать, и коммитов — четыре,
а не три.

---

## VERDICT

**APPROVE_FOR_GATE.**

Десять замечаний гейта закрыты правками плана, а не обещаниями: у каждого назван
файл, решение и тест, и ни одно не оставлено «на усмотрение автора кода». Три
пункта редакции 1 отменены целиком, потому что были неправильны, а не неполны:
старый П7 (исключение для места хранения), старый П9 (настройка задаёт дефолт) и
старый П10 (отказ от кадра публикует факт без кадра). Единственный UNKNOWN
прошлой редакции — О1, «уход последнего распускает полку» — закрыт не оговоркой
в копирайте, а правкой бэкенда.

Порядок коммитов — `полка переживает уход последнего` → `shelf-settings-truth` →
`tap-cycle-controls` → `baked-seal-toggle`; зависимости однонаправленные, каждый
коммит собирается и зелёный сам по себе.

Границы неизменны: Room версии 10 без новых миграций, `recipes.json` не тронут,
новой видимой строки в цикле ровно одна («Где стоит закваска», О3), `versionName`
только через `scripts/release_cycle.py prepare-version` → `6.7.0`, магазин не
трогаем.

PLAN_READY
