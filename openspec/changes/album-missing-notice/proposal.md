# Недоступное фото: короткое сообщение

## Зачем
SCREENS-7.0.3 §5: ссылка на пропавший файл остаётся в альбоме, но тап не должен молчать.

## Что меняем
Только PhotoGalleryScreen: локальный PaperSnackbar «Файл не найден» при неудачной проверке PhotoStore.isReadable в момент нажатия. Один активный показ без очереди повторных тапов; отмена при STOP, disposal и открытии настоящего фото. Существующий просмотр и Back не перекрываются.
Версия 7.1.23 (76), последний UI-микропункт перед отдельно согласованной финальной QA-пачкой.

## Готово, когда
Новые настоящие Compose/NavHost/Room-тесты доказывают missing/unreadable tap → сообщение без viewer; удаление файла после composition проверяется заново при тапе; читаемый JPEG открывается; Back/STOP/disposal/restore не возвращают старое сообщение. Повторы не создают очередь. Ссылки, строки Room, JPEG, счётчики, фильтры и месячные ключи сохраняются. TextLayout сообщения без overflow при 320dp, fontScale 1/2 во всех четырёх атмосферах.
Локально: compileDebug/compileRelease, новые случаи, AlbumSource*, AlbumMonth*, FeedingDraftNavigationUiTest и BookPlanningUiTest; полный гейт и одно ревью принадлежат интегратору.

## Флаг
Нового флага нет: завершённая локальная обратная связь существующего альбома. Существующие флаги не меняются.

## Вне рамок
Остальные SCREENS отложены до 7.2. Не меняем API GalleryPhoto, данные, Nav/VM, kit/components, схемы, зависимости, permissions, старые тесты, workflow. Нет push, render/device, APK/QA, login/QR, backend/services, release/RuStore.
