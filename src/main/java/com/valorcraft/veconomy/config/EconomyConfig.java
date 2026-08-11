package com.valorcraft.veconomy.config;

import com.valorcraft.veconomy.VEconomyMod;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

import java.util.List;

/**
 * Forge-конфиг {@code config/VMods/VEconomy/economy-core.toml}.
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
    public static final ForgeConfigSpec.BooleanValue WEEKLY_FUND_NOTIFY;
    public static final ForgeConfigSpec.BooleanValue WEEKLY_FUND_AUTO_PAYOUT;
    public static final ForgeConfigSpec.IntValue WEEKLY_FUND_PAYOUT_DELAY_HOURS;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MIN_ACCOUNT_AGE_DAYS;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MIN_ACTIVE_SECONDS;
    public static final ForgeConfigSpec.IntValue WEEKLY_FUND_MIN_ACTIVE_DAYS;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MIN_ACTIVE_DAY_SECONDS;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_BASE_PER_PLAYER;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MINIMUM_FUND;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_MAXIMUM_FUND;
    public static final ForgeConfigSpec.LongValue WEEKLY_FUND_TARGET_SUPPLY_PER_PLAYER;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> WEEKLY_FUND_ECONOMY_TIERS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> WEEKLY_FUND_TIME_POINT_LEVELS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> WEEKLY_FUND_DAY_POINT_LEVELS;
    public static final ForgeConfigSpec.IntValue WEEKLY_FUND_MAX_PLAYER_SHARE_PERCENT;
    public static final ForgeConfigSpec.ConfigValue<String> WEEKLY_FUND_TIME_ZONE;

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
                .defineInRange("maximumBalance", 1_000_000L, 1L, Long.MAX_VALUE);
        builder.pop();

        builder.comment("Настройки переводов между игроками.").push("transfers");
        TRANSFERS_ENABLED = builder.comment("Разрешены ли переводы между игроками").define("enabled", true);
        ALLOW_OFFLINE_RECIPIENTS = builder.comment("Разрешены ли переводы офлайн-игрокам (по UUID)")
                .define("allowOfflineRecipients", true);
        MINIMUM_TRANSFER_AMOUNT = builder.comment("Минимальная сумма перевода, минимальные единицы")
                .defineInRange("minimumAmount", 1L, 1L, Long.MAX_VALUE);
        MAXIMUM_TRANSFER_AMOUNT = builder.comment("Максимальная сумма одного перевода, минимальные единицы")
                .defineInRange("maximumAmount", 100_000L, 1L, Long.MAX_VALUE);
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
                "Пример: [3600, 10, 10800, 25, 43200, 60] = 1ч→10, 3ч→25, 12ч→60.",
                "Каждый порог выплачивается игроку ровно один раз.").push("milestones");
        MILESTONES_ENABLED = builder.comment("Включить награды за время")
                .define("enabled", true);
        MILESTONE_REWARDS = builder.comment("Пары (секунды, награда)").defineList("rewards",
                List.of(3600, 10, 10800, 25, 43200, 60, 86400, 125),
                element -> element instanceof Integer integer && integer > 0);
        MILESTONES_NOTIFY = builder.comment("Уведомлять игрока о получении награды")
                .define("notify", true);
        builder.pop();

        builder.comment("Недельный фонд.",
                "Каждую неделю (ISO-неделя, понедельник 00:00 в timeZone) закрытый период раздаётся между",
                "подходящими игроками по очкам (активное время + активные дни).",
                "Размер фонда рассчитывается автоматически: базовая сумма на одного подходящего",
                "игрока умножается на число подходящих игроков, умножается на коэффициент экономики",
                "и зажимается между minimumFund и maximumFund.",
                "Коэффициент зависит от денежной массы на одного подходящего игрока относительно",
                "targetSupplyPerEligiblePlayer. Ступени задаются парами (верхняя_граница_%, коэфф_в_БП);",
                "БП 10000 = 100%. Пример ('менее 70% → 120%') = 70,12000.",
                "Очки за время и дни: пары (секунды/дни, очки). Время от 2ч до 30ч даёт до 70 очков,",
                "число активных дней (мин активного времени в день — minActiveDaySeconds) — до 30.",
                "Автоматическая выплата после payoutDelayHours часов с закрытия недели.").push("weeklyFund");
        WEEKLY_FUND_ENABLED = builder.comment("Включить недельный фонд").define("enabled", true);
        WEEKLY_FUND_NOTIFY = builder.comment("Уведомлять игрока о выплате")
                .define("notify", true);
        WEEKLY_FUND_AUTO_PAYOUT = builder.comment(
                "Автоматически выплачивать закрытую неделю после контрольной задержки")
                .define("autoPayout", true);
        WEEKLY_FUND_PAYOUT_DELAY_HOURS = builder.comment(
                "Контрольная задержка (часов) между закрытием недели и автоматической выплатой")
                .defineInRange("payoutDelayHours", 6, 0, 24 * 7);
        WEEKLY_FUND_MIN_ACCOUNT_AGE_DAYS = builder.comment(
                "Минимальный возраст аккаунта (дней) для участия; 0 — без ограничения")
                .defineInRange("minAccountAgeDays", 7L, 0L, 100_000L);
        WEEKLY_FUND_MIN_ACTIVE_SECONDS = builder.comment(
                "Минимум активного времени за неделю (секунд) для участия")
                .defineInRange("minActiveSeconds", 7_200L, 0L, Long.MAX_VALUE);
        WEEKLY_FUND_MIN_ACTIVE_DAYS = builder.comment(
                "Минимум активных дней за неделю для участия")
                .defineInRange("minActiveDays", 2, 0, 7);
        WEEKLY_FUND_MIN_ACTIVE_DAY_SECONDS = builder.comment(
                "Минимум активного времени в день (секунд), чтобы день считался активным")
                .defineInRange("minActiveDaySeconds", 1_800L, 0L, 86_400L);
        WEEKLY_FUND_BASE_PER_PLAYER = builder.comment(
                "Базовая сумма фонда на одного подходящего игрока (минимальные единицы)")
                .defineInRange("baseAmountPerEligiblePlayer", 50L, 0L, Long.MAX_VALUE);
        WEEKLY_FUND_MINIMUM_FUND = builder.comment(
                "Минимальный размер фонда (минимальные единицы)")
                .defineInRange("minimumFund", 100L, 0L, Long.MAX_VALUE);
        WEEKLY_FUND_MAXIMUM_FUND = builder.comment(
                "Максимальный размер фонда (минимальные единицы)")
                .defineInRange("maximumFund", 10_000L, 0L, Long.MAX_VALUE);
        WEEKLY_FUND_TARGET_SUPPLY_PER_PLAYER = builder.comment(
                "Целевая денежная масса на одного подходящего игрока (минимальные единицы)")
                .defineInRange("targetSupplyPerEligiblePlayer", 2_000L, 1L, Long.MAX_VALUE);
        WEEKLY_FUND_ECONOMY_TIERS = builder.comment(
                "Ступени экономического коэффициента: пары (вверхняя_граница_%, коэфф_в_БП). ",
                "Порядок важен: берётся первая ступень, где соотношение supply/цель ниже границы.",
                "Пример: 70,12000, 90,11000, 110,10000, 140,8500, 100000,7000")
                .defineList("economyCoefficientTiers",
                        List.of(70, 12000, 90, 11000, 110, 10000, 140, 8500, 100000, 7000),
                        element -> element instanceof Integer integer && integer > 0);
        WEEKLY_FUND_TIME_POINT_LEVELS = builder.comment(
                "Очки за активное время: пары (секунды, очки). Берётся последний пройденный порог.",
                "Пример: 7200,10, 18000,25, 36000,40, 72000,55, 108000,70")
                .defineList("timePointLevels",
                        List.of(7200, 10, 18000, 25, 36000, 40, 72000, 55, 108000, 70),
                        element -> element instanceof Integer integer && integer > 0);
        WEEKLY_FUND_DAY_POINT_LEVELS = builder.comment(
                "Очки за активные дни: пары (дни, очки). Берётся последний пройденный порог.",
                "Пример: 2,5, 3,10, 4,15, 5,20, 6,25, 7,30")
                .defineList("dayPointLevels",
                        List.of(2, 5, 3, 10, 4, 15, 5, 20, 6, 25, 7, 30),
                        element -> element instanceof Integer integer && integer > 0);
        WEEKLY_FUND_MAX_PLAYER_SHARE_PERCENT = builder.comment(
                "Максимальная доля фонда на одного игрока (процентов); излишек перераспределяется")
                .defineInRange("maximumPlayerSharePercent", 10, 1, 100);
        WEEKLY_FUND_TIME_ZONE = builder.comment(
                "Временная зона для подсчёта активных дней, например 'Europe/Berlin'")
                .define("timeZone", "Europe/Berlin");
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
                        WEEKLY_FUND_NOTIFY.get(),
                        WEEKLY_FUND_AUTO_PAYOUT.get(),
                        WEEKLY_FUND_PAYOUT_DELAY_HOURS.get(),
                        WEEKLY_FUND_MIN_ACCOUNT_AGE_DAYS.get(),
                        WEEKLY_FUND_MIN_ACTIVE_SECONDS.get(),
                        WEEKLY_FUND_MIN_ACTIVE_DAYS.get(),
                        WEEKLY_FUND_MIN_ACTIVE_DAY_SECONDS.get(),
                        WEEKLY_FUND_BASE_PER_PLAYER.get(),
                        WEEKLY_FUND_MINIMUM_FUND.get(),
                        WEEKLY_FUND_MAXIMUM_FUND.get(),
                        WEEKLY_FUND_TARGET_SUPPLY_PER_PLAYER.get(),
                        toTiers(WEEKLY_FUND_ECONOMY_TIERS.get()),
                        toPointLevels(WEEKLY_FUND_TIME_POINT_LEVELS.get()),
                        toPointLevels(WEEKLY_FUND_DAY_POINT_LEVELS.get()),
                        WEEKLY_FUND_MAX_PLAYER_SHARE_PERCENT.get(),
                        WEEKLY_FUND_TIME_ZONE.get()));
    }

    /** Преобразовать плоский список (сек, очки, сек, очки…) в очковые уровни. */
    private static List<EconomySettings.PointLevel> toPointLevels(List<? extends Integer> flat) {
        List<EconomySettings.PointLevel> levels = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            long seconds = flat.get(i);
            long points = flat.get(i + 1);
            if (seconds > 0 && points > 0) {
                levels.add(new EconomySettings.PointLevel(seconds, points));
            }
        }
        levels.sort(java.util.Comparator.comparingLong(EconomySettings.PointLevel::activeSeconds));
        return List.copyOf(levels);
    }

    /** Преобразовать плоский список (граница_%, коэфф_в_БП, …) в ступени коэффициента. */
    private static List<EconomySettings.EconomyTier> toTiers(List<? extends Integer> flat) {
        List<EconomySettings.EconomyTier> tiers = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            long upperPercent = flat.get(i);
            long coefficientBps = flat.get(i + 1);
            if (upperPercent > 0 && coefficientBps > 0) {
                tiers.add(new EconomySettings.EconomyTier(upperPercent, coefficientBps));
            }
        }
        tiers.sort(java.util.Comparator.comparingLong(EconomySettings.EconomyTier::upperRatioPercent));
        return List.copyOf(tiers);
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
        VEconomyMod.LOGGER.info("Конфиг {} перечитан с диска", ConfigPaths.ECONOMY_CONFIG);
    }

    /** Зарегистрировать конфиг при загрузке мода. */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, ConfigPaths.ECONOMY_CONFIG);
    }
}
