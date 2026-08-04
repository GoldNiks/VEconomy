package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.MilestoneReward;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilestoneServiceTest {

    private static EconomySettings milestonesEnabled() {
        return withMilestones(new EconomySettings.Milestones(true,
                List.of(new MilestoneReward(3600, 100), new MilestoneReward(10800, 300)), true));
    }

    private static EconomySettings withMilestones(EconomySettings.Milestones milestones) {
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
                defaults.activity,
                milestones,
                defaults.weeklyFund);
    }

    private static void setActiveSeconds(TestDb db, UUID player, long seconds) {
        db.database.inTransaction(connection -> {
            db.activityRepository.upsert(connection, DatabaseManager.Dialect.SQLITE,
                    new PlayerActivityRow(player, 1L, 1L, seconds, seconds, 0,
                            WeekId.current(), seconds, 1L, "minecraft:overworld", false));
            return null;
        });
    }

    @Test
    void paysMilestoneOnceWhenThresholdReached() {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 3600);

            var granted = db.milestoneService.checkPlayer(player);
            assertEquals(1, granted.size());
            assertEquals(100, db.accountService.getBalance(player));

            db.milestoneService.checkPlayer(player);
            assertEquals(100, db.accountService.getBalance(player));
        }
    }

    @Test
    void paysAllReachedThresholds() {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 20_000);

            var granted = db.milestoneService.checkPlayer(player);
            assertEquals(2, granted.size());
            assertEquals(400, db.accountService.getBalance(player));
        }
    }

    @Test
    void noRewardBeforeThreshold() {
        try (TestDb db = TestDb.create(milestonesEnabled())) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 100);

            var granted = db.milestoneService.checkPlayer(player);
            assertTrue(granted.isEmpty());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }

    @Test
    void disabledMilestonesPayNothing() {
        try (TestDb db = TestDb.create(withMilestones(new EconomySettings.Milestones(false,
                List.of(new MilestoneReward(3600, 100)), true)))) {
            UUID player = UUID.randomUUID();
            setActiveSeconds(db, player, 3600);

            var granted = db.milestoneService.checkPlayer(player);
            assertTrue(granted.isEmpty());
            assertEquals(0, db.accountService.getBalance(player));
        }
    }
}
