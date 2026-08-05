# ROADMAP

Состояние проекта VEconomy (Forge 1.20.1, `com.valorcraft.veconomy`), зафиксированное
в ходе аудита Этапа 0 (после коммита `e1924bc`).

Условные обозначения:

- `DONE` — реализовано, покрыто тестами, принято.
- `PARTIAL` — реализовано частично (есть основа, не хватает частей расширенного MVP).
- `NOT IMPLEMENTED` — отсутствует.
- `DEFERRED` — сознательно отложено / вне рамок.

## Ядро

| Область | Статус | Примечание |
|---------|--------|------------|
| Личные UUID-балансы | DONE | `accounts`, привязка к UUID, имя только для поиска |
| `long` для денег | DONE | минимальные единицы, `CHECK (balance_minor >= 0)` |
| Ledger всех операций | DONE | `transactions`, атомарно с балансом |
| Idempotency keys | DONE | уникальный индекс по `idempotency_key` |
| Атомарные переводы | DONE | `PLAYER_TRANSFER` в одной транзакции |
| `/money`, `/pay`, `/money history` | DONE | + `/balance`-алиасы, `/money activity` |
| Админ `balance get/add/remove/set` | DONE | уровень 4, причина обязательна |
| SQLite и MySQL | DONE | диалект-зависимые миграции, HikariCP |
| SQL-миграции | DONE | идемпотентные, версии 1–4 |
| Economy API | DONE | `EconomyApi`, `TransactionContext/Result` |
| Escrow API | DONE | резерв → завершение/возврат |
| Чат-уведомления об админ-изменениях | DONE | `notifications.broadcastAdminChanges` |

## Активность и награды

| Область | Статус | Примечание |
|---------|--------|------------|
| Учёт онлайна/активного/AFK времени | DONE | `ActivityService`, анти-AFK по перемещению |
| Безопасное сохранение активности | DONE | сброс счётчиков только после commit, pending при ошибке, слитие несохранённых выходов |
| PLAYTIME milestones | PARTIAL | работают пороги по активному времени; нет других типов и админ-команд |
| ADVANCEMENT milestones | NOT IMPLEMENTED | — |
| DIMENSION_VISIT milestones | NOT IMPLEMENTED | нет таблицы посещённых измерений |
| EXTERNAL milestones | NOT IMPLEMENTED | нет trusted API/команды |
| Реестр условий milestone | NOT IMPLEMENTED | есть только связка `checkPlayer` → пороги |
| FTB Quests награды | DONE | custom reward + автоначисление по главам, идемпотентно |
| Компенсация за прошлые квесты | DONE | `compensatePastQuests`, разовый флаг |
| KubeJS биндинг | PARTIAL | деньги/эскроу/компенсация есть; milestone bridge нет |
| Недельная награда | DONE | авторазмер фонда, очки, дни, замороженные планы, retry, остаток в казну |
| `/money weekly` | DONE | конкретные причины, плановая выплата |
| FTB Teams не владеет балансом | DONE | награды делятся по участникам, баланс личный |

## Аккаунты и статусы

| Область | Статус | Примечание |
|---------|--------|------------|
| `AccountStatus` (ACTIVE/FROZEN/SYSTEM) | DONE | колонка `status` в `accounts` |
| `freeze`/`unfreeze` методы | DONE | `AccountService.changeStatus`; команд нет |
| `excluded_from_rewards` | PARTIAL | есть колонка в `player_activity`, читается фондом; нет setter/команд |
| Админ-команды `account info/freeze/unfreeze/exclude-rewards` | NOT IMPLEMENTED | Этап 2 |
| Заморозка блокирует `/pay`/списания | DONE | `checkDisabled` в `AccountService` |
| Заморозка исключает из weekly/автоматических наград | PARTIAL | из weekly — да, из milestones — нет (Этап 1) |

## Аудит и сигналы

| Область | Статус | Примечание |
|---------|--------|------------|
| Таблица audit-событий | NOT IMPLEMENTED | есть только ledger + лог-сообщения |
| Suspicion signals | NOT IMPLEMENTED | Этап 3 |
| Команды `/economy admin audit ...` | NOT IMPLEMENTED | Этап 3 |
| `/economy admin stats` | PARTIAL | supply/игроки/казна/escrow/эмиссия/медиана/переводы есть; нет замороженных/исключённых/сигналов/аггрегата по типам |
| `/economy admin diagnostics` | NOT IMPLEMENTED | Этап 4 |

## Миграция legacy

| Область | Статус | Примечание |
|---------|--------|------------|
| Автоимпорт `balances.json` | DONE | разовый, флаг в `meta`, ledger `LEGACY_IMPORT` |
| Dry-run | NOT IMPLEMENTED | Этап 5 |
| Админ-команда `migrate legacy execute` | NOT IMPLEMENTED | Этап 5 |
| Резервная копия файла, migration ID, force | NOT IMPLEMENTED | Этап 5 |

## Документация, CI

| Область | Статус | Примечание |
|---------|--------|------------|
| `README.md` | DONE | актуален по ядру; milestones обновятся в Этапе 1/6 |
| `MIGRATION.md` | DONE | описан разовый импорт |
| `ECONOMY_DESIGN.md` | DONE | описаны эмиссия, weekly, escrow, казна |
| `DISCOVERY.md` | DONE | история решений |
| `ROADMAP.md` | DONE | этот файл |
| `CHANGELOG.md` | NOT IMPLEMENTED | Этап 6 |
| GitHub Actions CI | NOT IMPLEMENTED | Этап 6 (Java 17, `test`+`build`, jar артефакт) |

## Отложено

| Область | Статус |
|---------|--------|
| Аукцион | DEFERRED |
| Автоматические наказания/блокировки по IP | DEFERRED |
| GUI, предметы-монеты, серверный `/sell` | DEFERRED |