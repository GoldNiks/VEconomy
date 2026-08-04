package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyFundServiceTest {

    private static void seedWeekly(TestDb db, UUID player, long seconds) {
        seedWeekly(db, player, seconds, false);
    }

    private static void seedWeekly(TestDb db, UUID player, long seconds, boolean excluded) {
        db.database.inTransaction(connection -> {
            db.activityRepository.upsert(connection, DatabaseManager.Dialect.SQLITE,
                    new PlayerActivityRow(player, 1L, 1L, seconds, seconds, 0,
                            WeekId.current(), seconds, 1L, "minecraft:overworld", excluded));
            return null;
        });
    }

    private static EconomySettings fund(long weeklyAmount) {
        EconomySettings defaults = EconomySettings.defaults();
        return new EconomySettings(
                defaults.currencyNameSingular, defaults.currencyNameFew, defaults.currencyNameMany,
                defaults.currencySymbol, defaults.decimalPlaces, defaults.maximumBalance,
                defaults.transfersEnabled, defaults.allowOfflineRecipients,
                defaults.minimumTransferAmount, defaults.maximumTransferAmount,
                defaults.transferCooldownSeconds,
                defaults.dbType, defaults.databaseFile, defaults.busyTimeoutMillis, defaults.walEnabled,
                defaults.mysqlHost, defaults.mysqlPort, defaults.mysqlDatabase,
                defaults.mysqlUser, defaults.mysqlPassword, defaults.mysqlPoolSize,
                defaults.broadcastAdminChanges,
                defaults.activity, defaults.milestones,
                new EconomySettings.WeeklyFund(true, weeklyAmount, true));
    }

    @Test
    void distributesProportionallyToActivity() {
        try (TestDb db = TestDb.create(fund(400))) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 300);
            seedWeekly(db, bob, 100);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(300L, payments.get(alice));
            assertEquals(100L, payments.get(bob));
            assertEquals(300, db.accountService.getBalance(alice));
            assertEquals(100, db.accountService.getBalance(bob));
        }
    }

    @Test
    void payoutRunsOnlyOncePerWeek() {
        try (TestDb db = TestDb.create(fund(100))) {
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 50);

            Map<UUID, Long> first = db.weeklyFundService.maybeDistribute();
            assertEquals(1, first.size());

            Map<UUID, Long> second = db.weeklyFundService.maybeDistribute();
            assertTrue(second.isEmpty());
            assertEquals(100, db.accountService.getBalance(alice));
        }
    }

    @Test
    void remainderGoesToTreasury() {
        try (TestDb db = TestDb.create(fund(100))) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 30);
            seedWeekly(db, bob, 30);

            db.weeklyFundService.maybeDistribute();
            // perSecond = 100 / 60 = 1 → по 30 каждому, остаток 40 в казну
            assertEquals(30, db.accountService.getBalance(alice));
            assertEquals(30, db.accountService.getBalance(bob));
            assertEquals(40, db.accountService.getBalance(TreasuryService.TREASURY_UUID));
        }
    }

    @Test
    void fundDoesNotExceedConfiguredAmount() {
        try (TestDb db = TestDb.create(fund(100))) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 1000);
            seedWeekly(db, bob, 1000);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            long paid = payments.values().stream().mapToLong(Long::longValue).sum();
            // сумма у игроков + казна не превышает фонд
            assertEquals(100,
                    db.accountService.getBalance(alice)
                            + db.accountService.getBalance(bob)
                            + db.accountService.getBalance(TreasuryService.TREASURY_UUID));
            assertTrue(paid <= 100);
        }
    }

    @Test
    void excludedPlayerGetsNothing() {
        try (TestDb db = TestDb.create(fund(200))) {
            UUID normal = UUID.randomUUID();
            UUID excluded = UUID.randomUUID();
            seedWeekly(db, normal, 100);
            seedWeekly(db, excluded, 100, true);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(1, payments.size());
            assertEquals(200, db.accountService.getBalance(normal));
            assertEquals(0, db.accountService.getBalance(excluded));
        }
    }
}
