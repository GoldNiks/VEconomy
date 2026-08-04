# VEconomy — ядро виртуальной валюты (Forge 1.20.1)

Прослойка экономики: чистый виртуальный баланс игроков + команды + публичный Java API
и журнал всех операций.

- `modid`: `economy_core`
- Ядро работает на чистом Forge без обязательных зависимостей. SQLite встраивается через jarJar.
- Опциональные интеграции (не требуются для работы): FTB Quests, KubeJS, LuckPerms, FTB Ranks.
- Тесты/код: Java 17, Forge 47.4.22, official mappings.
- Серверный мод (`side="SERVER"`).

## Возможности

- Баланс хранится в **SQLite** (`<мир>/economy/valoreconomy.db`).
- Деньги — только `long` в минимальных единицах;
- Каждая операция атомарна: изменение баланса + запись журнала в одной транзакции БД.
- Идемпотентность: повтор с тем же ключом не удваивает операцию.
- Журнал всех операций (`transactions`) с историей по игроку (`/money history`).
- Эскроу-API для аукционов/обменников (резерв → завершение/возврат).
- Одноразовый импорт балансов старого прототипа из `balances.json`.
- Полностью настраиваемый конфиг `config/economy-core.toml`, перечитывается в рантайме.

## Сборка

Требуется JDK 17+ (путь к даемону Gradle указывается в `gradle.properties`).

```
gradlew build
```

Готовый мод (с упакованным SQLite-драйвером):
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
| `/economy admin balance get <игрок>`               | 4     | Баланс игрока + статус аккаунта     |
| `/economy admin balance add <игрок> <сумма> <причина>`   | 4 | Зачислить                        |
| `/economy admin balance remove <игрок> <сумма> <причина>` | 4 | Списать                          |
| `/economy admin balance set <игрок> <сумма> <причина>`    | 4 | Установить баланс                 |
| `/economy admin stats`                             | 4     | Статистика экономики                |
| `/economy admin reload`                            | 4     | Перечитать конфиг с диска           |

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

- **FTB Quests — награды деньгами.** Используется встроенный тип награды
  «Custom Reward» (не требует установки мода на клиента). Сумма берётся из названия
  награды — первое число в заголовке: `500`, `1500 монет`, `Вознаграждение 2500`.
  Если числа в названии нет — награда игнорируется. Повторное начисление исключено
  идемпотентным ключом. Тип операции в журнале — `QUEST_REWARD`.
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
    file = "economy/valoreconomy.db"  # относительно каталога мира
    busyTimeoutMillis = 5000
    wal = true

[notifications]
    broadcastAdminChanges = true  # оповещать всех игроков об админ-изменениях баланса
```

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
