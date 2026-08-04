package com.valorcraft.veconomy.config;

import com.valorcraft.veconomy.VEconomyMod;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Forge-конфиг {@code config/economy-core.toml}.
 * <p>
 * Значения делятся на секции: currency, transfers, database. Активность, недельный фонд
 * и аудит добавляются в соответствующие этапы разработки. Все денежные значения — целые
 * (минимальные единицы), никаких {@code double}/{@code float} для денег.
 */
public final class EconomyConfig {

    public static final ForgeConfigSpec SPEC;

    // --- currency ---
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_NAME_SINGULAR;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_NAME_FEW;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_NAME_MANY;
    public static final ForgeConfigSpec.ConfigValue<String> CURRENCY_SYMBOL;
    public static final ForgeConfigSpec.IntValue DECIMAL_PLACES;
    public static final ForgeConfigSpec.LongValue MAXIMUM_BALANCE;

    // --- transfers ---
    public static final ForgeConfigSpec.BooleanValue TRANSFERS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ALLOW_OFFLINE_RECIPIENTS;
    public static final ForgeConfigSpec.LongValue MINIMUM_TRANSFER_AMOUNT;
    public static final ForgeConfigSpec.LongValue MAXIMUM_TRANSFER_AMOUNT;
    public static final ForgeConfigSpec.IntValue TRANSFER_COOLDOWN_SECONDS;

    // --- database ---
    public static final ForgeConfigSpec.ConfigValue<String> DB_TYPE;
    public static final ForgeConfigSpec.ConfigValue<String> DATABASE_FILE;
    public static final ForgeConfigSpec.IntValue DB_BUSY_TIMEOUT_MILLIS;
    public static final ForgeConfigSpec.BooleanValue DB_WAL;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_HOST;
    public static final ForgeConfigSpec.IntValue MYSQL_PORT;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_DATABASE;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_USER;
    public static final ForgeConfigSpec.ConfigValue<String> MYSQL_PASSWORD;
    public static final ForgeConfigSpec.IntValue MYSQL_POOL_SIZE;

    // --- notifications ---
    public static final ForgeConfigSpec.BooleanValue NOTIFY_ADMIN_CHANGES;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("Настройки валюты. Все суммы задаются в минимальных единицах (копейках).",
                "decimalPlaces = 0 означает, что 1 единица = 1 монета; decimalPlaces = 2: 100 единиц = 1.00 монеты.")
                .push("currency");
        CURRENCY_NAME_SINGULAR = builder.comment("Склонение «1 монета»").define("nameSingular", "монета");
        CURRENCY_NAME_FEW = builder.comment("Склонение «2 монеты»").define("nameFew", "монеты");
        CURRENCY_NAME_MANY = builder.comment("Склонение «5 монет»").define("nameMany", "монет");
        CURRENCY_SYMBOL = builder.comment("Символ валюты").define("symbol", "⛃");
        DECIMAL_PLACES = builder.comment("Количество десятичных знаков при отображении")
                .defineInRange("decimalPlaces", 0, 0, 10);
        MAXIMUM_BALANCE = builder.comment("Максимальный баланс аккаунта в минимальных единицах")
                .defineInRange("maximumBalance", 9_000_000_000_000L, 1L, Long.MAX_VALUE);
        builder.pop();

        builder.comment("Настройки переводов между игроками.").push("transfers");
        TRANSFERS_ENABLED = builder.comment("Разрешены ли переводы между игроками").define("enabled", true);
        ALLOW_OFFLINE_RECIPIENTS = builder.comment("Разрешены ли переводы офлайн-игрокам (по UUID)")
                .define("allowOfflineRecipients", true);
        MINIMUM_TRANSFER_AMOUNT = builder.comment("Минимальная сумма перевода, минимальные единицы")
                .defineInRange("minimumAmount", 1L, 1L, Long.MAX_VALUE);
        MAXIMUM_TRANSFER_AMOUNT = builder.comment("Максимальная сумма одного перевода, минимальные единицы")
                .defineInRange("maximumAmount", 1_000_000L, 1L, Long.MAX_VALUE);
        TRANSFER_COOLDOWN_SECONDS = builder.comment("Кулдаун между переводами игрока, секунд")
                .defineInRange("cooldownSeconds", 2, 0, 3600);
        builder.pop();

        builder.comment("Настройки базы данных.",
                "Тип 'sqlite' — локальный файл (для разработки и тестов);",
                "тип 'mysql' — внешний сервер через пул соединений HikariCP (для продакшена).",
                "Файл указывается относительно каталога мира (только для sqlite).").push("database");
        DB_TYPE = builder.comment("Тип базы данных: 'sqlite' или 'mysql'").define("type", "sqlite");
        DATABASE_FILE = builder.comment("Путь к файлу SQLite относительно каталога мира")
                .define("file", "economy/valoreconomy.db");
        DB_BUSY_TIMEOUT_MILLIS = builder.comment("Busy timeout SQLite / таймаут соединения MySQL, мс")
                .defineInRange("busyTimeoutMillis", 5000, 100, 120000);
        DB_WAL = builder.comment("Включить WAL mode для SQLite").define("wal", true);

        builder.comment("Параметры MySQL (используются при type='mysql').",
                "База создаётся автоматически, если у пользователя есть права CREATE DATABASE.").push("mysql");
        MYSQL_HOST = builder.comment("Хост MySQL").define("host", "localhost");
        MYSQL_PORT = builder.comment("Порт MySQL").defineInRange("port", 3306, 1, 65535);
        MYSQL_DATABASE = builder.comment("Имя базы данных").define("database", "veconomy");
        MYSQL_USER = builder.comment("Пользователь").define("user", "veconomy");
        MYSQL_PASSWORD = builder.comment("Пароль").define("password", "");
        MYSQL_POOL_SIZE = builder.comment("Размер пула соединений").defineInRange("poolSize", 5, 1, 64);
        builder.pop();
        builder.pop();

        builder.comment("Уведомления в игровой чат.").push("notifications");
        NOTIFY_ADMIN_CHANGES = builder.comment("Оповещать всех игроков об административных изменениях баланса")
                .define("broadcastAdminChanges", true);
        builder.pop();

        SPEC = builder.build();
    }

    private EconomyConfig() {}

    /** Собрать неизменяемый снимок настроек из текущих значений конфига. */
    public static EconomySettings toSettings() {
        return new EconomySettings(
                CURRENCY_NAME_SINGULAR.get(),
                CURRENCY_NAME_FEW.get(),
                CURRENCY_NAME_MANY.get(),
                CURRENCY_SYMBOL.get(),
                DECIMAL_PLACES.get(),
                MAXIMUM_BALANCE.get(),
                TRANSFERS_ENABLED.get(),
                ALLOW_OFFLINE_RECIPIENTS.get(),
                MINIMUM_TRANSFER_AMOUNT.get(),
                MAXIMUM_TRANSFER_AMOUNT.get(),
                TRANSFER_COOLDOWN_SECONDS.get(),
                DB_TYPE.get(),
                DATABASE_FILE.get(),
                DB_BUSY_TIMEOUT_MILLIS.get(),
                DB_WAL.get(),
                MYSQL_HOST.get(),
                MYSQL_PORT.get(),
                MYSQL_DATABASE.get(),
                MYSQL_USER.get(),
                MYSQL_PASSWORD.get(),
                MYSQL_POOL_SIZE.get(),
                NOTIFY_ADMIN_CHANGES.get());
    }

    /**
     * Перечитать файл конфига с диска. Используется командой {@code /economy admin reload}.
     * <p>
     * Перечитываются только значения конфига; соединение с БД и другие фундаментальные
     * параметры не затрагиваются.
     */
    public static void reload() {
        net.minecraftforge.fml.config.ConfigTracker.INSTANCE.loadConfigs(
                ModConfig.Type.COMMON, net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
        VEconomyMod.LOGGER.info("Конфиг economy-core.toml перечитан с диска");
    }

    /** Зарегистрировать конфиг при загрузке мода. */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "economy-core.toml");
    }
}
