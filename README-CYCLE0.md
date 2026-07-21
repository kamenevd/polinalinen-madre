# Мадре v4 — Cycle 0 (Foundation)

Сгенерировано Claude в Cowork, 19.07.2026, на основе madre-v4-plan.md,
подтверждённой ветки `feat/living-culture-v2` (v3.3.0) и реального `master` (v1.9.0)
в github.com/kamenevd/polinalinen-madre.

> **Обновлено 21.07.2026**: этот файл описывал только Cycle 0 и был давно неактуален —
> ниже оставлена историческая часть (что именно принёс Cycle 0), но раздел «чего не
> хватает» и «дальше» переписаны под реальное текущее состояние ветки. Проект сейчас
> заметно дальше Cycle 1: пройдены Cycle 1–3, плюс отдельный проход по build-плотине,
> двум оставшимся экранам, перфомансу и accessibility — см. «Текущий статус» ниже.

## Что внутри (Cycle 0, как было)
- Gradle-проект (`settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`) —
  compileSdk/targetSdk 35, minSdk 26, Compose + Navigation Compose + Room + WorkManager + Coil.
- `ui/theme/` — Color.kt/Type.kt/Theme.kt, "Warm Paper", **только светлая тема**.
- `model/BakingModel.kt`, `model/RecipeScaler.kt` — портированы 1:1 из v3
  (хеши идентичны на master и feat/living-culture-v2 — код не менялся, я его не трогал).
- `sourdough/SourdoughProfile.kt` — портировано из feat/living-culture-v2
  (пакет `ui.livingculture` → `sourdough`, GrowthChart-визуализация НЕ перенесена
  согласно decision #12 "NO chart").
- `data/db/` — новая Room-схема (User/SourdoughConfig/Feeding), синглтон через
  `MadreApplication` (закрывает баг v3 #1: db.close() в onCleared()).
- `data/repository/` — новый слой (RecipeRepository, SourdoughRepository), IO на Dispatchers.IO
  (закрывает баг v3 #6: blocking I/O на main thread).
- `navigation/` — Navigation Compose с 8 маршрутами (заменяет `Screen` sealed class из v3).
- `ui/screens/PlaceholderScreens.kt` — временные заглушки на все 8 экранов, чтобы граф
  навигации собирался и проверялся на эмуляторе до начала Cycle 1.
  **(удалён 21.07.2026 — оба оставшихся экрана теперь реальные, см. ниже)**
- `AndroidManifest.xml` — `allowBackup="false"` (закрывает баг v3 #3).
- `assets/recipes.json` — скопирован из хендоффа как есть (source of truth, не менять).
- `res/drawable/*.webp` — hero-фото и иконки из хендоффа (49 файлов).

## Текущий статус (21.07.2026)

Все 8 экранов — реальные, ни одного плейсхолдера не осталось:

| Экран | Статус |
|---|---|
| Home | ✅ готов («Живая книга», Cycle 1) |
| Recipe Detail | ✅ готов (RecipeScaler-интеграция, полный текст рецепта всегда виден, Cycle 1) |
| Baking Timer | ✅ готов |
| Starter Diary | ✅ готов, реальные данные из Room (Cycle 3) |
| Feeding Form | ✅ готов, реальные данные из Room (Cycle 3) |
| Settings | ✅ готов |
| Shelf + Book Stats | ✅ готовы (своя книга — реальные данные, книги друзей — заглушка до сервера, это осознанно вне скоупа) |
| Baking Complete | ✅ готов («Сургучная печать»); мокап **ещё не согласован** с Димой/Полиной — визуал считать черновым |
| Notifications | ✅ готов («Записки на полях», лента реальных данных вместо тумблеров — своего push пока нет); мокап **ещё не согласован** |

Build-плотина (пункты 1–3 из старого списка «чего не хватает», см. ниже) закрыта
полностью — `./gradlew :app:assembleDebug` проверен вживую, собирает настоящий APK.

Добавлены unit-тесты на математику (`RecipeScalerTest`, `SourdoughProfileTest`) —
их не было ни в v3, ни в начале v4.

## Чего не хватало на момент Cycle 0 — и что с этим стало
1. **`res/mipmap-*/ic_launcher*.png`** — ✅ сделано 21.07.2026: реальные PNG на
   всех плотностях (mdpi–xxxhdpi), растеризованы из существующего vector-артворка.
   Adaptive-icon XML по-прежнему приоритетнее на API26+, PNG — корректный fallback.
2. **`gradle/wrapper/gradle-wrapper.jar`** — ✅ сделано 21.07.2026: настоящий wrapper
   через `gradle wrapper --gradle-version 8.4`, `./gradlew` работает без CI-обхода.
3. **Сборка не была протестирована** — ✅ сделано 21.07.2026: `assembleDebug` собран
   и проверен локально (не только в CI), реальный APK на выходе.
4. **Открытые вопросы из madre-v4-plan.md §2** (orb, эмодзи, pill-паттерн) — ✅ закрыты
   ещё 20.07.2026, см. «Решённые вопросы» в DESIGN-V4.md: orb-виджета нет (дышащая
   страница + виньетка вместо него), эмодзи не рендерятся нигде, pill — только
   в селекторе порций.

## Дальше
Открытых пунктов «до первой сборки» больше нет. Что реально осталось на будущее:
- Согласовать мокапы экранов 6 (Baking Complete) и 7 (Notifications) с Димой/Полиной —
  текущая реализация построена строго по текстовому описанию в DESIGN-V4.md, но
  визуальные детали считаются черновыми до ревью.
- Реальные push-уведомления (WorkManager) — сознательно отложены, см. комментарий
  в AndroidManifest.xml; когда появятся, лента в NotificationsScreen станет источником
  текста для push, а не наоборот.
- Синхронизация книг друзей на Полке — нужен сервер, отдельная задача вне этого репозитория.
