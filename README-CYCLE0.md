# Мадре v4 — Cycle 0 (Foundation)

Сгенерировано Claude в Cowork, 19.07.2026, на основе madre-v4-plan.md,
подтверждённой ветки `feat/living-culture-v2` (v3.3.0) и реального `master` (v1.9.0)
в github.com/kamenevd/polinalinen-madre.

## Что внутри
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
- `AndroidManifest.xml` — `allowBackup="false"` (закрывает баг v3 #3).
- `assets/recipes.json` — скопирован из хендоффа как есть (source of truth, не менять).
- `res/drawable/*.webp` — hero-фото и иконки из хендоффа (49 файлов).

## Чего НЕ хватает — нужно доделать до первой сборки
1. **`res/mipmap-*/ic_launcher*.png`** — иконки приложения. У меня их нет в бинарном виде
   (web_fetch не может стянуть PNG из GitHub raw в этой песочнице). Скопируйте из
   `app/src/main/res/mipmap-*` реального репозитория (master или feat/living-culture-v2 — идентичны).
2. **`gradle/wrapper/gradle-wrapper.jar`** — бинарник, не пишется через текстовые инструменты.
   Скопируйте из любой ветки репозитория, либо `gradle wrapper` локально.
3. У меня нет Android SDK/gradle в песочнице — эта сборка **не собрана и не протестирована**
   мной. Первый `assembleDebug` — на вашей стороне.
4. Открытые вопросы из madre-v4-plan.md §2 (orb на Home/Starter, трактовка эмодзи,
   pill-паттерн из референса) всё ещё не закрыты — Placeholder-экраны их не решают,
   решение понадобится к началу Cycle 1.

## Дальше (Cycle 1, по madre-v4-plan.md §5)
HomeScreen (RecipeCarousel + StarterStatusCard) и RecipeDetailScreen
(PortionStepper + RecipeScaler-интеграция) — заменяют соответствующие Placeholder'ы.
