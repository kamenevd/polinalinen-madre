# L1 — настройки F и явный выбор движения (7.1.0 / 53)

## Основание и границы
Утверждённые решения пользователя 25.10.2026 09:47/09:48 и SETTINGS-FAMILY.md.
Продолжение существующего L1: профиль Яндекс, шесть строк в группах 2+3+1,
Внешний вид, Уведомления и вибрация, О приложении/QA Dev, единый лист закваски,
монограммы в шапках вкладок. Старые email/password/invite-code UI не монтируются,
данные и legacy API остаются. Семья L10–L25/share/QR — следующий отдельный этап.

## P1
Новая книга: BOOK и reducedMotion=false. Мигрирует только явно сохранённый calm_mode;
существующий reducedMotion имеет приоритет. У NavHost один выбор для page-turn и декора.
Системная длительность анимаций и энергосбережение сами не включают уменьшение движения.
Настоящие hardware/renderer-error/low-fps fallback и геометрия curl не изменяются.
Robolectric PENDING/awaiting-surface не является доказательством работы EGL/GPU.

## Визуальный контракт
Literata/Manrope, F kit, точная тёплая ночная палитра #211B16 и нулевой tint/elevation.
В UI только Утро/Ночь/Авто; Авто следует системной тёмной теме, не часам.
DAY/DUSK и все четыре F палитры сохраняют совместимость со старыми preferences.
Технические сведения и FPS только в QA Dev; «Проверить» посылает настоящий haptic request.

## Приёмка
P1 миграция/сохранённый выбор/scale0; реальные Compose callbacks и persistence;
Auto system-dark, DAY/DUSK, единый лист; полный domain/style/core20;
отдельный настоящий minified QA Java/instrumentation build graph; final offline debug+unit compile.
Оригиналы Compose 412×915 для L1 dawn/night, без crop/repaint и без подмены goldens.
Критичный CI точного head принимает parent; full7.1 review, CI watch и доставка принадлежат parent.
Ни APK 53, ни вся семья/7.1, ни устройство/e2e не считаются выданными/принятыми этим срезом.

## Флаги
Утверждённый L1/P1 доступен как замена настроек. FAMILY_ROSTER_V2=false и другие
серверные/account/OAuth gates не меняются. Backend, устройства и публикация вне scope.
