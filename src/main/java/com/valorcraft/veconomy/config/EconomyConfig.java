package com.valorcraft.veconomy.config;

import com.valorcraft.veconomy.VEconomyMod;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

/**
 * Forge-конфиг {@code config/economy-core.toml}.
 * <p>
 * Значения делятся на секции: currency, transfers, database, activity, milestones,
 * weeklyFund, notifications. Все денежные значения — целые (минимальные единицы),
 * никаких {@code double}/{@code float} для денег.
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

    // --- activity ---
    public static final ForgeConfigSpec.BooleanValue ACTIVITY_ENABLED;
    public static final ForgeConfigSpec.IntValue AFK_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue ACTIVITY_SAMPLE_TICKS;
    public static final ForgeConfigSpec.IntValue ACTIVITY_PERSIST_SECONDS;
    public static final ForgeConfigSpec.DoubleValue ACTIVITY_MOVE_THRESHOLD;

    // --- milestones ---
    public static final ForgeConfigSpec.BooleanValue MILESTONES_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> MILESTONE_REWARDS;
    public static final ForgeConfigSpec.BooleanValue MILESTONES_NOTIFY;

    // --- weekly fund ---
    public static final ForgeConfigSpec.BooleanValue WEEKLY_FUND_ENABLED;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_AMOUNT;
    public static final ForgeConfigSpec.BooleanValue WEEKLY_FUND_NOTIFY;
    public static final ForgeConfigSpec.BooleanValue WEEKLY_FUND_AUTO_RUN;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MIN_ACCOUNT_AGE_DAYS;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MIN_ACTIVE_SECONDS;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MAX_COUNTED_HOURS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> WEEKLY_FUND_POINT_LEVELS;

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

        builder.comment("Учёт активности игроков.",
                "Сервер считает, сколько времени игрок находится в сети, сколько активен",
                "и сколько простоял в AFK. AFK определяется как отсутствие существенного",
                "перемещения (movementActivityThreshold метров) и реальных действий (блоки,",
                "предметы, контейнеры, атаки, чат). Простой поворот камеры активностью не считается.",
                "Активность используется для милстоунов и недельного фонда.",
                "Данные пишутся в таблицу player_activity.").push("activity");
        ACTIVITY_ENABLED = builder.comment("Включить учёт активности").define("enabled", true);
        AFK_TIMEOUT_SECONDS = builder.comment("Бездействие в секундах, после которого игрок считается AFK")
                .defineInRange("afkTimeoutSeconds", 300, 10, 7200);
        ACTIVITY_SAMPLE_TICKS = builder.comment("Через сколько тиков обновлять счётчики (20 = 1 секунда)")
                .defineInRange("sampleIntervalTicks", 20, 20, 1200);
        ACTIVITY_PERSIST_SECONDS = builder.comment("Как часто записывать активность в базу (секунды)")
                .defineInRange("persistIntervalSeconds", 60, 5, 3600);
        ACTIVITY_MOVE_THRESHOLD = builder.comment(
                "Сколько метров должен пройти игрок, чтобы счётчик активности считался живым",
                "(анти-AFK). Рекомендуется 0.25–1.0; 0 — отключить требование перемещения.")
                .defineInRange("movementActivityThreshold", 0.5, 0.0, 100.0);
        builder.pop();

        builder.comment("Личные милстоуны за наигранное время.",
                "Список reward — пары (секунды активного времени, награда в минимальных единицах).",
                "Пример: [3600, 100, 10800, 300, 43200, 1000] = 1ч→100, 3ч→300, 12ч→1000.",
                "Каждый порог выплачивается игроку ровно один раз.").push("milestones");
        MILESTONES_ENABLED = builder.comment("Включить награды за время")
                .define("enabled", true);
        MILESTONE_REWARDS = builder.comment("Пары (секунды, награда)").defineList("rewards",
                List.of(3600, 100, 10800, 300, 43200, 1000, 86400, 2500),
                element -> element instanceof Integer integer && integer > 0);
        MILESTONES_NOTIFY = builder.comment("Уведомлять игрока о получении награды")
                .define("notify", true);
        builder.pop();

        builder.comment("Недельный фонд.",
                "Каждую неделю (ISO-неделя, понедельник 00:00 UTC) фонд делится между игроками",
                "пропорционально их активности за завершённую неделю (без AFK).",
                "Участие ограничено: минимальный возраст аккаунта, минимум активного времени",
                "и потолок учитываемых часов. Можно делить не по сырым секундам, а по очковым",
                "уровням (pointLevels): пары (секунды активного времени, очки).",
                "Автоматический запуск по умолчанию ВЫКЛЮЧЕН: выплату выполняет администратор",
                "командой /economy admin weekly run confirm. Остаток от деления — в казну.").push("weeklyFund");
        WEEKLY_FUND_ENABLED = builder.comment("Включить недельный фонд").define("enabled", true);
        WEEKLY_FUND_AMOUNT = builder.comment("Размер фонда в минимальных единицах (эмиссия за неделю)")
                .defineInRange("weeklyAmount", 100_000L, 0L, Long.MAX_VALUE);
        WEEKLY_FUND_NOTIFY = builder.comment("Уведомлять игрока о выплате")
                .define("notify", true);
        WEEKLY_FUND_AUTO_RUN = builder.comment(
                "Автоматически раздавать фонд в момент смены недели. false — только вручную",
                "(/economy admin weekly run confirm)")
                .define("autoRun", false);
        WEEKLY_FUND_MIN_ACCOUNT_AGE_DAYS = builder.comment(
                "Минимальный возраст аккаунта (дней) для участия; 0 — без ограничения")
                .defineInRange("minAccountAgeDays", 7L, 0L, 100_000L);
        WEEKLY_FUND_MIN_ACTIVE_SECONDS = builder.comment(
                "Минимум активного времени за неделю (секунд) для участия; 0 — без ограничения")
                .defineInRange("minActiveSeconds", 3_600L, 0L, Long.MAX_VALUE);
        WEEKLY_FUND_MAX_COUNTED_HOURS = builder.comment(
                "Потолок учитываемых часов активности за неделю на игрока; 0 — без потолка")
                .defineInRange("maxCountedHours", 0L, 0L, 24L * 7L);
        WEEKLY_FUND_POINT_LEVELS = builder.comment(
                "Очковые уровни: пары (секунды, очки) через запятую. Если список пустой, фонд",
                "делится пропорционально времени. Пример: [3600, 10, 10800, 30, 43200, 70] =",
                "1ч даёт 10 очков, 3ч — ещё 30, 12ч — ещё 70.")
                .defineList("pointLevels", List.of(), element -> element instanceof Integer integer && integer > 0);
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
                NOTIFY_ADMIN_CHANGES.get(),
                new EconomySettings.Activity(
                        ACTIVITY_ENABLED.get(),
                        AFK_TIMEOUT_SECONDS.get(),
                        ACTIVITY_SAMPLE_TICKS.get(),
                        ACTIVITY_PERSIST_SECONDS.get(),
                        ACTIVITY_MOVE_THRESHOLD.get()),
                new EconomySettings.Milestones(
                        MILESTONES_ENABLED.get(),
                        toRewards(MILESTONE_REWARDS.get()),
                        MILESTONES_NOTIFY.get()),
                new EconomySettings.WeeklyFund(
                        WEEKLY_FUND_ENABLED.get(),
                        WEEKLY_FUND_AMOUNT.get(),
                        WEEKLY_FUND_NOTIFY.get(),
                        WEEKLY_FUND_AUTO_RUN.get(),
                        WEEKLY_FUND_MIN_ACCOUNT_AGE_DAYS.get(),
                        WEEKLY_FUND_MIN_ACTIVE_SECONDS.get(),
                        WEEKLY_FUND_MAX_COUNTED_HOURS.get() * 3600L,
                        toPointLevels(WEEKLY_FUND_POINT_LEVELS.get())));
    }

    /** Преобразовать плоский список (сек, очки, сек, очки…) в очковые уровни. */
    private static List<EconomySettings.WeeklyFund.PointLevel> toPointLevels(List<? extends Integer> flat) {
        List<EconomySettings.WeeklyFund.PointLevel> levels = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            long seconds = flat.get(i);
            long points = flat.get(i + 1);
            if (seconds > 0 && points > 0) {
                levels.add(new EconomySettings.WeeklyFund.PointLevel(seconds, points));
            }
        }
        levels.sort(java.util.Comparator.comparingLong(EconomySettings.WeeklyFund.PointLevel::activeSeconds));
        return List.copyOf(levels);
    }

    /** Преобразовать плоский список (сек, награда, сек, награда…) в пары порогов. */
    private static List<EconomySettings.MilestoneReward> toRewards(List<? extends Integer> flat) {
        List<EconomySettings.MilestoneReward> rewards = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            long seconds = flat.get(i);
            long amount = flat.get(i + 1);
            if (seconds > 0 && amount > 0) {
                rewards.add(new EconomySettings.MilestoneReward(seconds, amount));
            }
        }
        rewards.sort(java.util.Comparator.comparingLong(EconomySettings.MilestoneReward::thresholdSeconds));
        return List.copyOf(rewards);
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
