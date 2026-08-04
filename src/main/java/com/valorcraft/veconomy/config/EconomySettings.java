package com.valorcraft.veconomy.config;

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
                true);
    }
}
