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

    /** Один уровень очков: порог (секунды активного времени за неделю или число активных
     *  дней) → начисляемые очки. Для времени поле {@code activeSeconds} — секунды; для дней —
     *  количество дней, а points — уже достигнутое кумулятивное значение за это количество. */
    public record PointLevel(long activeSeconds, long points) {
    }

    /** Ступень экономического коэффициента: соотношение {@code supply / (target*eligible)}
     *  строго < {@code upperRatioPercent} в процентах → коэффициент {@code coefficientBps}
     *  (базисные пункты, 10000 = 100%). Список отсортирован по возрастанию верхней границы. */
    public record EconomyTier(long upperRatioPercent, long coefficientBps) {
    }

    /** Настройки недельного фонда. */
    public static final class WeeklyFund {
        public final boolean enabled;
        public final boolean notify;
        /** Автоматическая выплата закрытой недели после {@link #payoutDelayHours} часов:
         *  администратору не нужно еженедельно подтверждать обычную выплату. */
        public final boolean autoPayout;
        /** Контрольная задержка (часов) между закрытием недели и автоматической выплатой. */
        public final int payoutDelayHours;
        /** Минимальный возраст аккаунта (в днях) для участия в выплате; 0 — не ограничено. */
        public final long minAccountAgeDays;
        /** Минимум активного времени за неделю (в секундах) для участия; 0 — не ограничено. */
        public final long minActiveSeconds;
        /** Минимум активных дней за неделю для участия. */
        public final int minActiveDays;
        /** Минимум активного времени в день (в секундах), чтобы день считался активным. */
        public final long minActiveDaySeconds;
        /** Базовая сумма фонда на одного подходящего игрока (минимальные единицы). */
        public final long baseAmountPerEligiblePlayer;
        /** Минимальный размер фонда (минимальные единицы). */
        public final long minimumFund;
        /** Максимальный размер фонда (минимальные единицы). */
        public final long maximumFund;
        /** Целевая денежная масса на одного подходящего игрока для коэффициента экономики. */
        public final long targetSupplyPerEligiblePlayer;
        /** Ступени экономического коэффициента (верхняя граница % → коэффициент в БП). */
        public final List<EconomyTier> economyCoefficientTiers;
        /** Очки за активное время: уровни «секунды → очки» (кумулятивные по достижению). */
        public final List<PointLevel> timePointLevels;
        /** Очки за активные дни: уровни «дни → очки». */
        public final List<PointLevel> dayPointLevels;
        /** Максимальная доля фонда на одного игрока (процентов); остаток перераспределяется. */
        public final int maximumPlayerSharePercent;
        /** Временная зона для подсчёта активных дней. */
        public final String timeZone;

        public WeeklyFund(boolean enabled, boolean notify, boolean autoPayout, int payoutDelayHours,
                          long minAccountAgeDays, long minActiveSeconds, int minActiveDays,
                          long minActiveDaySeconds, long baseAmountPerEligiblePlayer,
                          long minimumFund, long maximumFund, long targetSupplyPerEligiblePlayer,
                          List<EconomyTier> economyCoefficientTiers,
                          List<PointLevel> timePointLevels, List<PointLevel> dayPointLevels,
                          int maximumPlayerSharePercent, String timeZone) {
            this.enabled = enabled;
            this.notify = notify;
            this.autoPayout = autoPayout;
            this.payoutDelayHours = Math.max(0, payoutDelayHours);
            this.minAccountAgeDays = Math.max(0, minAccountAgeDays);
            this.minActiveSeconds = Math.max(0, minActiveSeconds);
            this.minActiveDays = Math.max(0, minActiveDays);
            this.minActiveDaySeconds = Math.max(0, minActiveDaySeconds);
            this.baseAmountPerEligiblePlayer = Math.max(0, baseAmountPerEligiblePlayer);
            this.minimumFund = Math.max(0, minimumFund);
            this.maximumFund = Math.max(minimumFund, maximumFund);
            this.targetSupplyPerEligiblePlayer = Math.max(1, targetSupplyPerEligiblePlayer);
            this.economyCoefficientTiers = economyTiers(economyCoefficientTiers);
            this.timePointLevels = pointLevels(timePointLevels, "timePointLevels");
            this.dayPointLevels = pointLevels(dayPointLevels, "dayPointLevels");
            this.maximumPlayerSharePercent = Math.max(1, Math.min(100, maximumPlayerSharePercent));
            this.timeZone = normalizeTimeZone(timeZone);
        }

        /** Непустая строка зоны: пустая строка → Europe/Berlin; неверная зона → ошибка конфига. */
        private static String normalizeTimeZone(String timeZone) {
            if (timeZone == null || timeZone.isBlank()) {
                return "Europe/Berlin";
            }
            try {
                java.time.ZoneId.of(timeZone);
            } catch (java.time.DateTimeException e) {
                throw new IllegalArgumentException(
                        "Неверная временная зона недельного фонда: '" + timeZone + "'", e);
            }
            return timeZone;
        }

        /**
         * Очковые уровни: сортировка по порогу, строгий рост порогов (по дубликатам — ошибка),
         * очки не могут уменьшаться по мере роста порога. Пустой список допустим (очки = 0).
         */
        private static List<PointLevel> pointLevels(List<PointLevel> levels, String name) {
            if (levels == null) {
                return List.of();
            }
            List<PointLevel> sorted = new java.util.ArrayList<>(levels);
            sorted.sort(java.util.Comparator.comparingLong(PointLevel::activeSeconds));
            PointLevel previous = null;
            for (PointLevel level : sorted) {
                if (previous != null) {
                    if (level.activeSeconds() <= previous.activeSeconds()) {
                        throw new IllegalArgumentException("Пороги очковых уровней '" + name
                                + "' должны строго возрастать без повторов: " + level.activeSeconds());
                    }
                    if (level.points() < previous.points()) {
                        throw new IllegalArgumentException("Очки уровней '" + name
                                + "' не могут уменьшаться при росте порога: " + level.points());
                    }
                }
                previous = level;
            }
            return List.copyOf(sorted);
        }

        /**
         * Ступени экономического коэффициента: сортировка по верхней границе, строгий рост
         * границ, положительный коэффициент. Пустой список допустим (коэффициент 100%).
         */
        private static List<EconomyTier> economyTiers(List<EconomyTier> tiers) {
            if (tiers == null) {
                return List.of();
            }
            List<EconomyTier> sorted = new java.util.ArrayList<>(tiers);
            sorted.sort(java.util.Comparator.comparingLong(EconomyTier::upperRatioPercent));
            EconomyTier previous = null;
            for (EconomyTier tier : sorted) {
                if (tier.coefficientBps() <= 0) {
                    throw new IllegalArgumentException(
                            "Коэффициент ступени экономики должен быть положительным: " + tier.coefficientBps());
                }
                if (previous != null && tier.upperRatioPercent() <= previous.upperRatioPercent()) {
                    throw new IllegalArgumentException(
                            "Верхние границы ступеней экономики должны строго возрастать: "
                                    + tier.upperRatioPercent());
                }
                previous = tier;
            }
            return List.copyOf(sorted);
        }

        /** Кумулятивная сверка: очки за время берутся с последнего пройденного порога. */
        public static WeeklyFund defaults() {
            return new WeeklyFund(
                    true, true, true, 6,
                    7, 7_200L, 2, 1_800L,
                    500L, 1_000L, 5_000_000L, 100_000L,
                    List.of(
                            new EconomyTier(70, 12000),
                            new EconomyTier(90, 11000),
                            new EconomyTier(110, 10000),
                            new EconomyTier(140, 8500),
                            new EconomyTier(100_000, 7000)),
                    List.of(
                            new PointLevel(7_200L, 10),
                            new PointLevel(18_000L, 25),
                            new PointLevel(36_000L, 40),
                            new PointLevel(72_000L, 55),
                            new PointLevel(108_000L, 70)),
                    List.of(
                            new PointLevel(2, 5),
                            new PointLevel(3, 10),
                            new PointLevel(4, 15),
                            new PointLevel(5, 20),
                            new PointLevel(6, 25),
                            new PointLevel(7, 30)),
                    10, "Europe/Berlin");
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
