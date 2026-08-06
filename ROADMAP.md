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
| SQL-миграции | DONE | идемпотентные, версии 1–7 |
| Economy API | DONE | `EconomyApi`, `TransactionContext/Result` |
| Escrow API | DONE | резерв → завершение/возврат |
| Чат-уведомления об админ-изменениях | DONE | `notifications.broadcastAdminChanges` |

## Активность и награды

| Область | Статус | Примечание |
|---------|--------|------------|
| Учёт онлайна/активного/AFK времени | DONE | `ActivityService`, анти-AFK по перемещению |
| Безопасное сохранение активности | DONE | сброс счётчиков только после commit, pending при ошибке, слитие несохранённых выходов |
| PLAYTIME milestones | DONE | пороги по активному времени из TOML, id `playtime:<секунды>` |
| ADVANCEMENT milestones | DONE | событие `AdvancementEarnEvent`, живой прогресс игрока |
| DIMENSION_VISIT milestones | DONE | `dimension_visits`, факт входа в измерение |
| EXTERNAL milestones | DONE | trusted-выдача через `grantExternal` (KubeJS/команда) |
| Реестр условий milestone | DONE | `MilestoneConditionRegistry`, без switch по типам |
| FTB Quests награды | DONE | custom reward + автоначисление по главам, идемпотентно |
| Компенсация за прошлые квесты | DONE | `compensatePastQuests`, разовый флаг |
| KubeJS биндинг | DONE | деньги/эскроу/компенсация + `milestoneGrant`/`milestoneClaimed` |
| Недельная награда | DONE | авторазмер фонда, очки, дни, замороженные планы, retry, остаток в казну |
| `/money weekly` | DONE | конкретные причины, плановая выплата |
| FTB Teams не владеет балансом | DONE | награды делятся по участникам, баланс личный |

## Аккаунты и статусы

| Область | Статус | Примечание |
|---------|--------|------------|
| `AccountStatus` (ACTIVE/FROZEN/SYSTEM) | DONE | колонка `status` в `accounts` |
| `freeze`/`unfreeze` методы | DONE | `AccountService.changeStatus`; команды `account freeze/unfreeze` |
| `excluded_from_rewards` | DONE | setter в `ActivityService`, команды `exclude-rewards`/`include-rewards` |
| Админ-команды `account info/freeze/unfreeze/exclude-rewards` | DONE | Этап 2 |
| Заморозка блокирует `/pay`/списания | DONE | `checkDisabled` в `AccountService` |
| Заморозка исключает из weekly/автоматических наград | DONE | из weekly — да, из milestones — да (ACCOUNT_FROZEN) |

## Аудит и сигналы

> Этап 3 завершён (коммиты «audit: ...» + «feat: complete audit signals and resolution workflow»):
> полный набор сигналов, resolution, actor attribution, retry записи и валидация
> конфига. Остались только расширения 4-го этапа (диагностика).

| Область | Статус | Примечание |
|---------|--------|------------|
| Таблица audit-событий | DONE | `audit_events` (миграции v6–v7), `AuditRepository`, `AuditService` |
| События сервисов | DONE | freeze/unfreeze, exclude-rewards, milestone grant/revoke, weekly payout, balance set |
| Suspicion signals | DONE | 9 эвристик: спам/рондатрип/оверсайз/новые аккаунты/пересылка/петли/частые пары/концентрация/общий получатель |
| Команды `/economy admin audit ...` | DONE | `list/player/signals/suspicious/event/transaction/scan/resolve/dismiss/status` |
| Resolution подозрительных событий | DONE | жизненный цикл OPEN/RESOLVED/DISMISSED, `audit event/transaction/suspicious/resolve/dismiss` |
| Actor attribution | DONE | `actor_type` PLAYER/CONSOLE/SYSTEM/INTEGRATION для всех событий |
| Сбои записи аудита | DONE | `AuditHealth`, retry-очередь с идемпотентными ключами, `audit status` |
| Валидация `veconomy-audit.json` | DONE | диапазоны полей при загрузке/перезагрузке, откат к последней корректной |
| `/economy admin stats` | PARTIAL | supply/игроки/казна/escrow/эмиссия/медиана/переводы есть; нет замороженных/исключённых/сигналов/агрегата по типам |
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