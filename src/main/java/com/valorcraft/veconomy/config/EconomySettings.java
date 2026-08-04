package com.valorcraft.veconomy.config;

import java.util.List;

/**
 * Неизменяемый снимок настроек экономики. Не зависит от Minecraft и Forge,
 * поэтому легко тестируется. Строится из Forge-конфига ({@link EconomyConfig}).
 */
public final class EconomySettings {

    // --- currency ---
    public final String currencyNameSingular;
    public final String currencyNameFew;
    public final String currencyNameMany;
    public final String currencySymbol;
    public final int decimalPlaces;
    public final long maximumBalance;

    // --- transfers ---
    public final boolean transfersEnabled;
    public final boolean allowOfflineRecipients;
    public final long minimumTransferAmount;
    public final long maximumTransferAmount;
    public final int transferCooldownSeconds;

    // --- database ---
    public final String dbType;
    public final String databaseFile;
    public final int busyTimeoutMillis;
    public final boolean walEnabled;
    public final String mysqlHost;
    public final int mysqlPort;
    public final String mysqlDatabase;
    public final String mysqlUser;
    public final String mysqlPassword;
    public final int mysqlPoolSize;

    // --- notifications ---
    public final boolean broadcastAdminChanges;

    // --- activity / earnings ---
    public final Activity activity;
    public final Milestones milestones;
    public final WeeklyFund weeklyFund;

    public EconomySettings(
            String currencyNameSingular,
            String currencyNameFew,
            String currencyNameMany,
            String currencySymbol,
            int decimalPlaces,
            long maximumBalance,
            boolean transfersEnabled,
            boolean allowOfflineRecipients,
            long minimumTransferAmount,
            long maximumTransferAmount,
            int transferCooldownSeconds,
            String dbType,
            String databaseFile,
            int busyTimeoutMillis,
            boolean walEnabled,
            String mysqlHost,
            int mysqlPort,
            String mysqlDatabase,
            String mysqlUser,
            String mysqlPassword,
            int mysqlPoolSize,
            boolean broadcastAdminChanges,
            Activity activity,
            Milestones milestones,
            WeeklyFund weeklyFund) {
        this.currencyNameSingular = currencyNameSingular;
        this.currencyNameFew = currencyNameFew;
        this.currencyNameMany = currencyNameMany;
        this.currencySymbol = currencySymbol;
        this.decimalPlaces = Math.max(0, decimalPlaces);
        this.maximumBalance = maximumBalance;
        this.transfersEnabled = transfersEnabled;
        this.allowOfflineRecipients = allowOfflineRecipients;
        this.minimumTransferAmount = minimumTransferAmount;
        this.maximumTransferAmount = maximumTransferAmount;
        this.transferCooldownSeconds = transferCooldownSeconds;
        this.dbType = dbType;
        this.databaseFile = databaseFile;
        this.busyTimeoutMillis = busyTimeoutMillis;
        this.walEnabled = walEnabled;
        this.mysqlHost = mysqlHost;
        this.mysqlPort = mysqlPort;
        this.mysqlDatabase = mysqlDatabase;
        this.mysqlUser = mysqlUser;
        this.mysqlPassword = mysqlPassword;
        this.mysqlPoolSize = mysqlPoolSize;
        this.broadcastAdminChanges = broadcastAdminChanges;
        this.activity = activity == null ? Activity.defaults() : activity;
        this.milestones = milestones == null ? Milestones.defaults() : milestones;
        this.weeklyFund = weeklyFund == null ? WeeklyFund.defaults() : weeklyFund;
    }

    /** Конструктор для тестов: активность/милстоуны/фонд выключены по умолчанию. */
    public EconomySettings(
            String currencyNameSingular,
            String currencyNameFew,
            String currencyNameMany,
            String currencySymbol,
            int decimalPlaces,
            long maximumBalance,
            boolean transfersEnabled,
            boolean allowOfflineRecipients,
            long minimumTransferAmount,
            long maximumTransferAmount,
            int transferCooldownSeconds,
            String dbType,
            String databaseFile,
            int busyTimeoutMillis,
            boolean walEnabled,
            String mysqlHost,
            int mysqlPort,
            String mysqlDatabase,
            String mysqlUser,
            String mysqlPassword,
            int mysqlPoolSize,
            boolean broadcastAdminChanges) {
        this(currencyNameSingular, currencyNameFew, currencyNameMany, currencySymbol,
                decimalPlaces, maximumBalance, transfersEnabled, allowOfflineRecipients,
                minimumTransferAmount, maximumTransferAmount, transferCooldownSeconds,
                dbType, databaseFile, busyTimeoutMillis, walEnabled,
                mysqlHost, mysqlPort, mysqlDatabase, mysqlUser, mysqlPassword, mysqlPoolSize,
                broadcastAdminChanges,
                Activity.defaults(), Milestones.defaults(), WeeklyFund.defaults());
    }

    /** Один порог милстоуна: порог в секундах активного времени и награда в минимальных единицах. */
    public record MilestoneReward(long thresholdSeconds, long amountMinor) {
    }

    /** Настройки учёта активности и AFK. */
    public static final class Activity {
        public final boolean enabled;
        public final int afkTimeoutSeconds;
        public final int sampleIntervalTicks;
        public final int persistIntervalSeconds;
        /** Минимальное суммарное перемещение (метров) для сброса AFK. Поворот камеры не считается. */
        public final double movementActivityThreshold;

        public Activity(boolean enabled, int afkTimeoutSeconds, int sampleIntervalTicks,
                        int persistIntervalSeconds, double movementActivityThreshold) {
            this.enabled = enabled;
            this.afkTimeoutSeconds = afkTimeoutSeconds;
            this.sampleIntervalTicks = Math.max(1, sampleIntervalTicks);
            this.persistIntervalSeconds = Math.max(1, persistIntervalSeconds);
            this.movementActivityThreshold = Math.max(0.0, movementActivityThreshold);
        }

        public static Activity defaults() {
            return new Activity(true, 300, 20, 60, 0.5);
        }
    }

    /** Настройки личных милстоунов за наигранное время. */
    public static final class Milestones {
        public final boolean enabled;
        public final List<MilestoneReward> rewards;

        public Milestones(boolean enabled, List<MilestoneReward> rewards, boolean notify) {
            this.enabled = enabled;
            this.rewards = rewards == null ? List.of() : List.copyOf(rewards);
            this.notify = notify;
        }

        public static Milestones defaults() {
            return new Milestones(true, List.of(
                    new MilestoneReward(3600, 100),
                    new MilestoneReward(10800, 300),
                    new MilestoneReward(43200, 1000),
                    new MilestoneReward(86400, 2500)), true);
        }

        public final boolean notify;
    }

    /** Настройки недельного фонда. */
    public static final class WeeklyFund {
        public final boolean enabled;
        public final long weeklyAmount;
        public final boolean notify;
        /** Автоматический запуск выплаты при смене недели. По умолчанию выключен — фонд
         *  раздаётся администратором вручную через {@code /economy admin weekly run confirm}. */
        public final boolean autoRun;
        /** Минимальный возраст аккаунта (в днях) для участия в выплате; 0 — не ограничено. */
        public final long minAccountAgeDays;
        /** Минимум активного времени за неделю (в секундах) для участия; 0 — не ограничено. */
        public final long minActiveSeconds;
        /** Потолок учитываемых за неделю активных секунд на игрока; 0 — без потолка. */
        public final long maxCountedSeconds;
        /** Очковые уровни. Если список непустой, фонд делится пропорционально очкам,
         *  а не сырым секундам: за каждый пройденный порог начисляются очки уровня. */
        public final List<PointLevel> pointLevels;

        public WeeklyFund(boolean enabled, long weeklyAmount, boolean notify, boolean autoRun,
                          long minAccountAgeDays, long minActiveSeconds, long maxCountedSeconds,
                          List<PointLevel> pointLevels) {
            this.enabled = enabled;
            this.weeklyAmount = Math.max(0, weeklyAmount);
            this.notify = notify;
            this.autoRun = autoRun;
            this.minAccountAgeDays = Math.max(0, minAccountAgeDays);
            this.minActiveSeconds = Math.max(0, minActiveSeconds);
            this.maxCountedSeconds = Math.max(0, maxCountedSeconds);
            this.pointLevels = pointLevels == null ? List.of() : List.copyOf(pointLevels);
        }

        /** Один очковый уровень: порог активных секунд за неделю → начисляемые очки. */
        public record PointLevel(long activeSeconds, long points) {
        }

        public static WeeklyFund defaults() {
            return new WeeklyFund(true, 100_000L, true, false, 7, 3_600L, 0, List.of());
        }
    }

    /** Множитель перевода минимальных единиц в отображаемые (10^decimalPlaces). */
    public long displayDivisor() {
        long divisor = 1;
        for (int i = 0; i < decimalPlaces; i++) {
            divisor *= 10;
        }
        return divisor;
    }

    /** Рекомендуемые значения для первой тестовой версии. */
    public static EconomySettings defaults() {
        return new EconomySettings(
                "монета", "монеты", "монет", "⛃",
                0, 9_000_000_000_000L,
                true, true, 1L, 1_000_000L, 2,
                "sqlite", "economy/valoreconomy.db", 5000, true,
                "localhost", 3306, "veconomy", "veconomy", "", 5,
                true,
                Activity.defaults(), Milestones.defaults(), WeeklyFund.defaults());
    }
}
