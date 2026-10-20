# Development workflow

## Purpose
Фиксирует только порядок работы над Madre, не требования приложения.

## Requirements

### Requirement: Явные границы разрешения
Агент SHALL выполнять только явно разрешённые действия; планирование OpenSpec не разрешает реализацию и не заменяет обязательное независимое ревью.

#### Scenario: Запрошен только план
- **Given** Дима запросил proposal или настройку tooling
- **When** агент завершает артефакты планирования
- **Then** агент останавливается без правок приложения, сборки, устройства или публикации

### Requirement: Один корень спецификаций
Агент SHALL использовать `/home/claude/projects/madre/openspec` на LXC108 как единственный корень спецификаций Madre, сохраняя существующие workflow-документы и доску Kanban.

#### Scenario: Команда из Telegram
- **Given** Hermes запущен на LXC103 вне проекта
- **When** пользователь вызывает OpenSpec для Madre
- **Then** CLI идёт через `/root/.local/bin/madre-openspec` в LXC108; локальные `/root/openspec` и устаревший clone не изменяются
