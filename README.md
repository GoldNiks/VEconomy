# VEconomy — ядро виртуальной валюты (Forge 1.20.1)

Прослойка экономики: чистый виртуальный баланс игроков + команды + публичный Java API
и журнал всех операций.

- `modid`: `economy_core`
- Зависимостей от других модов нет, чистый Forge. SQLite встраивается через jarJar.
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

## Команды

| Команда                                            | Право | Что делает                          |
|----------------------------------------------------|-------|-------------------------------------|
| `/money`                                           | 0     | Свой баланс                         |
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

## Важно

- База данных создаётся при старте сервера в каталоге мира; файл старого прототипа
  `balances.json` импортируется один раз, см. `MIGRATION.md`.
- Изменять баланс напрямую в БД нельзя — только через `EconomyApi`/сервисы,
  иначе журнал и идемпотентность будут обойдены.
- Публичный API и поведение команд подробно описаны в `ECONOMY_DESIGN.md`.
