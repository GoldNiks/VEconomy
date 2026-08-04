# VEconomy — ядро виртуальной валюты (Forge 1.20.1)

Прослойка экономики: чистый виртуальный баланс игроков + команды + публичный Java API
и журнал всех операций.

- `modid`: `economy_core`
- Ядро работает на чистом Forge без обязательных зависимостей. SQLite и MySQL-драйвер
  встраиваются через jarJar.
- Опциональные интеграции (не требуются для работы): FTB Quests, KubeJS, LuckPerms, FTB Ranks.
- Тесты/код: Java 17, Forge 47.4.22, official mappings.
- Серверный мод (`displayTest="IGNORE_SERVER_VERSION"`).

## Возможности

- Баланс хранится в базе данных: **SQLite** (`<мир>/economy/valoreconomy.db`) или
  **MySQL** (внешний сервер, пул HikariCP). Тип задаётся в конфиге — `database.type`.
- Деньги — только `long` в минимальных единицах;
- Каждая операция атомарна: изменение баланса + запись журнала в одной транзакции БД.
- Идемпотентность: повтор с тем же ключом не удваивает операцию.
- Журнал всех операций (`transactions`) с историей по игроку (`/money history`).
- Эскроу-API для аукционов/обменников (резерв → завершение/возврат).
- Одноразовый импорт балансов старого прототипа из `balances.json`.
- **Учёт активности**: сервер считает время в сети, активное время и AFK
  (`/money activity`). AFK — бездействие дольше `activity.afkTimeoutSeconds`.
- **Личные милстоуны**: разовые награды за наигранное активное время
  (например, 1ч → 100, 3ч → 300, 12ч → 1000).
- **Недельный фонд**: раз в ISO-неделю фонд делится между игроками
  пропорционально активному времени за неделю; остаток — в казну.
- Полностью настраиваемый конфиг `config/economy-core.toml`, перечитывается в рантайме.

## Сборка

Требуется JDK 17+ (путь к даемону Gradle указывается в `gradle.properties`).

```
gradlew build
```

Готовый мод (с упакованными драйверами SQLite/MySQL и HikariCP):
`build/libs/VEconomy-1.20.1-1.0.0-all.jar` — именно его класть в папку `mods/` сервера.

Опциональные интеграции компилируются из jar в `libs/` (compile-only, в итоговый jar
не попадают). Имена файлов должны соответствовать `build.gradle`. Jar можно взять из
папки `mods/` целевого сервера.

## Команды

| Команда                                            | Право | Что делает                          |
|----------------------------------------------------|-------|-------------------------------------|
| `/money`                                           | 0     | Свой баланс                         |
| `/money <игрок>`                                   | 2     | Баланс другого игрока               |
| `/balance`, `/bal`                                 | 0     | Алиасы `/money`                     |
| `/money pay <игрок> <сумма>`                       | 0     | Перевод игроку                      |
| `/pay <игрок> <сумма>`                             | 0     | Алиас перевода                      |
| `/money history [страница]`                        | 0     | История своих операций (по 10)      |
| `/money activity`                                  | 0     | Время в сети / активное / AFK, неделя |
| `/economy admin balance get <игрок>`               | 4     | Баланс игрока + статус аккаунта     |
| `/economy admin balance add <игрок> <сумма> <причина>`   | 4 | Зачислить                        |
| `/economy admin balance remove <игрок> <сумма> <причина>` | 4 | Списать                          |
| `/economy admin balance set <игрок> <сумма> <причина>`    | 4 | Установить баланс                 |
| `/economy admin stats`                             | 4     | Статистика экономики                |
| `/economy admin reload`                            | 4     | Перечитать конфиг с диска           |
| `/economy admin weekly status`                     | 4     | Состояние недельного фонда          |
| `/economy admin weekly preview`                    | 4     | Предпросмотр выплаты за неделю      |
| `/economy admin weekly run`                        | 4     | Показать сумму, запросить подтверждение |
| `/economy admin weekly run confirm`                | 4     | Выполнить выплату недельного фонда  |

Для команд `add`/`remove`/`set` причина обязательна. Казна — системный аккаунт,
изменять её баланс командами нельзя.

Права команд: по умолчанию уровень оператора (2 и 4), но при установленном
**LuckPerms** или **FTB Ranks** используются узлы прав:

| Узел | Что даёт |
|------|----------|
| `veconomy.command.balance.other` | `/money <игрок>` |
| `veconomy.command.admin` | `/economy admin ...` |

Если узел не задан — применяется уровень оператора как раньше. Для консоли команды
доступны всегда.

## Интеграции (все опциональные)

- **FTB Quests — награды деньгами.** Два способа:
  - *«Кастомная награда»*: название награды должно быть целиком числом
    (`500`, `1500,50`). Текст-обёртка не поддерживается — случайная награда
    с числом в названии не создаст деньги. Не требует мода на клиенте.
    Отключается флагом `customRewardEnabled`.
  - *Автоначисление по главам*: при завершении любого квеста команда получает
    **фиксированный фонд** за квест, который делится между участниками команды
    поровну (остаток от деления — в казну):

    ```
    фонд квеста = 1000
    1 участник  → 1000
    2 участника → по 500
    5 участников → по 200
    ```

    Размер команды не увеличивает эмиссию — приглашение альтов не печатает
    дополнительные монеты. Суммы задаются в `config/veconomy-quests.json`
    (создаётся автоматически, перечитывается `/economy admin reload`):

    ```json
    {
      "customRewardEnabled": true,
      "defaultPerQuest": 0,
      "chapters": {
        "Глава 1": 100,
        "Глава 2": 200,
        "Глава 3": 400
      },
      "quests": {
        "1234567890123456": 500
      }
    }
    ```

    Название главы должно совпадать с названием в FTB Quests (переименование
    главы меняет сопоставление). Для точности можно задать сумму конкретного
    квеста по его id (виден в редакторе FTB Quests) — она приоритетнее главы.
    Повторное начисление за тот же квест исключено идемпотентным ключом.
    Тип операции — `QUEST_REWARD`, учитывается в статистике эмиссии.
- **KubeJS — биндинг `VEconomy`.** Доступен из любых `server_scripts`, например:

  ```js
  VEconomy.add(player, 500, 'стартовый бонус');
  VEconomy.getBalance(player);          // long в минимальных единицах
  VEconomy.transfer(from, to, 250, 'торг');
  VEconomy.escrowReserve(player, 1000, 'auction:42', 'ставка');
  VEconomy.escrowCapture('auction:42', winner, 'победа');
  VEconomy.escrowRelease('auction:42', 'отмена');
  VEconomy.format(12345);               // "⛃12,345"
  VEconomy.ok(result);                  // result === 'SUCCESS'
  VEconomy.compensatePastQuests();      // разовая компенсация за старый прогресс
  VEconomy.questReward('Глава 1');      // награда за квест в главе
  ```

  Игрок в методах — `Player`, `UUID` или строка (ник/UUID). Методы возвращают код
  статуса (`SUCCESS`, `INSUFFICIENT_FUNDS`, ...) или `false`/`0`.
- **LuckPerms / FTB Ranks** — права на команды (см. таблицу выше).
- **Чат-уведомления** — об административных изменениях баланса оповещаются все
  игроки (настраивается `notifications.broadcastAdminChanges`).

## Компенсация за прогресс до установки мода

Квесты, пройденные **до** установки VEconomy, не начисляют деньги автоматически.
Для разовой компенсации есть готовый скрипт
[`scripts/veconomy-quest-compensation.js`](scripts/veconomy-quest-compensation.js):

- скопировать его в `kubejs/server_scripts/` на сервере;
- настроить `MONEY_PER_QUEST` (сумма за каждый пройденный квест, в минимальных единицах);
- при первом старте сервера игроки получат деньги за уже выполненные квесты.

Скрипт срабатывает только один раз (флаг в `persistentData` мира), плюс каждое
начисление защищено идемпотентным ключом — двойной выдачи не будет.

## Конфиг `config/economy-core.toml`

```toml
[currency]
    nameSingular = "монета"   # «1 монета»
    nameFew = "монеты"        # «2 монеты»
    nameMany = "монет"        # «5 монет»
    symbol = "⛃"              # символ валюты
    decimalPlaces = 0         # 0 = целые, 2 = ##.##
    maximumBalance = 9000000000000  # максимум баланса, минимальные единицы

[transfers]
    enabled = true            # переводы игроков разрешены
    allowOfflineRecipients = true
    minimumAmount = 1
    maximumAmount = 1000000
    cooldownSeconds = 2

[database]
    type = "sqlite"            # "sqlite" (файл) или "mysql" (внешний сервер)
    file = "economy/valoreconomy.db"  # относительно каталога мира (только для sqlite)
    busyTimeoutMillis = 5000   # busy timeout SQLite / таймаут соединения MySQL
    wal = true                 # WAL mode для SQLite

[database.mysql]               # используется только при type = "mysql"
    host = "localhost"
    port = 3306
    database = "veconomy"      # создаётся автоматически при наличии прав
    user = "veconomy"
    password = ""
    poolSize = 5               # размер пула соединений HikariCP

[weeklyFund]                   # недельный фонд (делится по активному времени)
    enabled = true
    weeklyAmount = 100000      # эмиссия за неделю, минимальные единицы
    notify = true              # уведомлять игроков о выплате
    autoRun = false            # автозапуск при смене недели (по умолчанию ВЫКЛючен):
                               # фонд раздаёт администратор: /economy admin weekly run confirm
    minAccountAgeDays = 7      # мин. возраст аккаунта для участия; 0 — без ограничения
    minActiveSeconds = 3600    # мин. активное время за неделю; 0 — без ограничения
    maxCountedHours = 0        # потолок учитываемых часов на игрока; 0 — без потолка
    # Очковые уровни: пары (секунды, очки). Пусто — фонд делится пропорционально времени.
    # Пример: [3600, 10, 10800, 30, 43200, 70] = 1ч→10 очков, 3ч→+30, 12ч→+70.
    pointLevels = []

[activity]                     # учёт времени в сети / активного / AFK
    enabled = true
    afkTimeoutSeconds = 300    # бездействие после которого игрок в AFK
    sampleIntervalTicks = 20   # шаг сэмплирования (20 = 1 сек)
    persistIntervalSeconds = 60
    movementActivityThreshold = 0.5  # метров нужно пройти, чтобы сбросить AFK
                                     # (поворот камеры и микро-дрожание НЕ считаются)

[weeklyFund]                   # недельный фонд (делится по активному времени)
    enabled = true
    weeklyAmount = 100000      # эмиссия за неделю
    notify = true

[notifications]
    broadcastAdminChanges = true  # оповещать всех игроков об админ-изменениях баланса
```

### Переход на MySQL

1. Убедитесь, что MySQL-сервер доступен, а у пользователя есть права на базу
   (или `CREATE DATABASE` — тогда база создастся автоматически).
2. В `config/economy-core.toml` укажите `type = "mysql"` и заполните `[database.mysql]`.
3. Перезапустите сервер: схема создастся автоматически.

> **Внимание:** SQLite-база и MySQL — разные хранилища. При переходе старые балансы
> не переносятся автоматически. Если нужно перенести балансы из предыдущей системы,
> используйте legacy-импорт (`balances.json` в каталоге мира) — он отработает один раз
> при старте на новой базе.

## API для других модов

```java
import com.valorcraft.veconomy.api.EconomyApi;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.EconomyCore;

// Чтение (0, если аккаунта нет)
long balance = EconomyCore.api().getBalance(uuid);

// Операции — все суммы в минимальных единицах
TransactionContext ctx = TransactionContext.of(
        TransactionType.SHOP_PURCHASE, actorUuid, "причина", "idempotency:key");

TransactionResult r = EconomyCore.api().deposit(uuid, 500, ctx);
TransactionResult r2 = EconomyCore.api().withdraw(uuid, 200, ctx);
TransactionResult r3 = EconomyCore.api().transfer(fromUuid, toUuid, 250, ctx);
// r.isSuccess(), r.status() — SUCCESS / INSUFFICIENT_FUNDS / LIMIT_EXCEEDED / ...

// Полная информация об аккаунте
Optional<BalanceSnapshot> account = EconomyCore.api().getAccount(uuid);

// Форматирование под настройки валюты
String text = EconomyCore.formatter().format(12345);      // "⛃12,345"
String plural = EconomyCore.formatter().plural(21);       // "монета"
```

Вызывайте методы на серверном потоке (в серверных обработчиках/командах).
Идемпотентность: при повторном вызове с тем же `idempotencyKey` вернётся
`DUPLICATE_OPERATION` и деньги не будут зачислены повторно.

## Структура проекта

- `api/` — публичные контракты: `EconomyApi`, `EscrowApi`, `TransactionResult`, `BalanceSnapshot`, статусы.
- `config/` — Forge-конфиг `economy-core.toml` + тестируемый снимок `EconomySettings`.
- `persistence/` — `DatabaseManager`, миграции схемы, репозитории, импорт legacy-балансов.
- `economy/` — сервисы: аккаунты, переводы, эскроу, журнал, форматирование, казна.
- `audit/` — статистика экономики (`/economy admin stats`).
- `command/` — команды `/money`, `/pay`, `/economy admin`.
- `event/` — события Forge: регистрация команд, старт/стоп БД, создание аккаунта при входе.
- `integration/` — опциональные интеграции: `ftbquests` (награды), `permissions` (LuckPerms/FTB Ranks).
- `kubejs/` — KubeJS-плагин и биндинг `VEconomy`.
- `libs/` — jar модов для compile-only компиляции интеграций (не коммитится, в `.gitignore`).

## Важно

- База данных создаётся при старте сервера в каталоге мира; файл старого прототипа
  `balances.json` импортируется один раз, см. `MIGRATION.md`.
- Изменять баланс напрямую в БД нельзя — только через `EconomyApi`/сервисы,
  иначе журнал и идемпотентность будут обойдены.
- Публичный API и поведение команд подробно описаны в `ECONOMY_DESIGN.md`.
