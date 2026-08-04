# VEconomy — ядро виртуальной валюты (Forge 1.20.1)

Прослойка экономики: чистый виртуальный баланс игроков + команды + публичный Java API
и события транзакций для других модов. **Никаких** предметов-монет, сундуков, магазинов
и жителей — только баланс, API и команды. Вся экономическая логика (магазины, обменники,
цены) остаётся за вами.

- `modid`: `economy_core`
- Зависимостей от других модов нет, чистый Forge.
- Тесты/код: Java 17, Forge 47.4.22, official mappings.

## Возможности

- Capability `IEconomyCapability` у каждого игрока (баланс + UUID), сохраняется в `.dat` игрока.
- Синхронизация баланса с клиентом пакетом `SyncBalancePacket` (при логине, респауне и каждом изменении).
- При смерти баланс не сбрасывается (`deathReset = false`), можно включить сброс в конфиге.
- Серверный кеш `HashMap<UUID, Double>`, запись в NBT по `PlayerLoggedOutEvent` и автосейв раз в 5 минут.
- Все транзакции пишутся в `logs/economy_transactions.log` (включается в конфиге).
- Полностью настраиваемый конфиг `config/economy-core.toml`.

## Сборка

Требуется JDK 17+ (путь к даемону Gradle указывается в `gradle.properties`).

```
gradlew build
```

Готовый мод: `build/libs/VEconomy-1.20.1-1.0.0.jar`.
Установка: положить jar в папку `mods/` клиента и сервера (Forge 1.20.1, 47.x).

## Команды

| Команда                      | Право | Что делает                                |
|------------------------------|-------|-------------------------------------------|
| `/economy balance`           | 0     | Свой баланс                               |
| `/economy balance <игрок>`   | 2     | Баланс другого онлайн-игрока              |
| `/economy pay <игрок> <сумма>` | 0   | Перевод (комиссия `transferTax`, %)       |
| `/economy set <игрок> <сумма>` | 4   | Принудительно установить баланс           |
| `/economy add <игрок> <сумма>` | 3   | Добавить/вычесть (отрицательная сумма)    |
| `/economy top [страница]`    | 0     | Топ-10 богачей сервера (онлайн-игроки)    |

Алиас: `/eco`.

## Конфиг `config/economy-core.toml`

```toml
[general]
    startingBalance = 100.0        # стартовый баланс
    currencySymbol = "⛃"           # символ валюты
    decimalPlaces = 2              # 2 = ##.##, 1 = ###.#, 0 = целые
    allowNegativeBalance = false   # разрешать минус
    transferTax = 0.0              # комиссия за перевод, %
    deathReset = false             # сбрасывать баланс при смерти
    logTransactions = true         # писать logs/economy_transactions.log

[messages]
    balanceSelf = "§aВаш баланс: §6%s"
    paymentSent = "§aВы отправили §6%s §aигроку §e%s"
    paymentReceived = "§aВы получили §6%s §aот §e%s"
    insufficientFunds = "§cНедостаточно средств!"
```

## API для других модов

```java
import com.valorcraft.economy.api.EconomyAPI;
import com.valorcraft.economy.api.TransactionResult;

// Чтение
double balance = EconomyAPI.getBalance(player);

// Транзакции (только на сервере)
TransactionResult r = EconomyAPI.withdraw(player, 50.0);
TransactionResult r2 = EconomyAPI.deposit(player, 100.0);
TransactionResult r3 = EconomyAPI.transfer(from, to, 25.0); // SUCCESS / INSUFFICIENT_FUNDS / ERROR

// Принудительно установить баланс (без событий Pre/Post)
EconomyAPI.forceSet(player, 1000.0);

// Форматирование под символ и точность из конфига
String text = EconomyAPI.format(1234.5); // "⛃1,234.50"
```

## События Forge (слушайте через `MinecraftForge.EVENT_BUS`)

```java
@SubscribeEvent
public void onPre(EconomyTransactionEvent.Pre event) {
    if (event.getType() == EconomyTransactionEvent.Type.WITHDRAW && event.getAmount() > 100) {
        event.setAmount(100);   // изменить сумму
        event.setCanceled(true); // или отменить транзакцию
    }
}

@SubscribeEvent
public void onPost(EconomyTransactionEvent.Post event) {
    EconomyTransactionEvent.Type type = event.getType();   // DEPOSIT / WITHDRAW / TRANSFER
    double amount = event.getAmount();
    double newBalance = event.getNewBalance();
    Player player = event.getPlayer();
}
```

Порядок: **Pre** (отменяемый, сумма изменяемая) → применение → **Post** (сумма, новый баланс, тип).
Для `TRANSFER` у отправителя, сумма в `Pre` — это сумма перевода (комиссия считается отдельно),
`Post` дополнительно постится получателю как `DEPOSIT`.

## Внутреннее устройство

- `api/` — `EconomyAPI`, `IEconomyCapability`, `TransactionResult`, `EconomyTransactionEvent`.
- `capability/` — регистрация capability, реализация, провайдер (хранит в `.dat` игрока).
- `network/` — канал `main`, пакет синхронизации, отправка баланса на клиент.
- `command/` — команды `/economy`.
- `storage/` — серверный кеш балансов (`BalanceStorage`) и лог транзакций (`TransactionLogger`).
- `config/` — `economy-core.toml`.

## Важно

- API-транзакции должны вызываться на логическом **сервере** (в серверных обработчиках/командах);
  на клиенте баланс читается из синхронизированной capability.
- `top` показывает только онлайн-игроков (кеш живёт на время сессии).
- Отрицательные балансы возможны админ-командой только при `allowNegativeBalance = true`.