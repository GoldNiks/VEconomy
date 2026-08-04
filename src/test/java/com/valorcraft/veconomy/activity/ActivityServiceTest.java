package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityServiceTest {

    @Test
    void accumulatesOnlineAndActiveSeconds() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, "minecraft:overworld", start);
            db.activityService.sampleAt(start + 1_000);
            db.activityService.sampleAt(start + 3_000);
            db.activityService.sampleAt(start + 6_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(6, info.totalOnlineSeconds());
            assertEquals(6, info.totalActiveSeconds());
            assertEquals(0, info.totalAfkSeconds());
            assertFalse(info.afkNow());
        }
    }

    @Test
    void marksAfkAfterTimeoutAndCountsAfkSeconds() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, "minecraft:overworld", start);
            // активное время до порога + превышение
            db.activityService.sampleAt(start + 301_000);
            assertTrue(db.activityService.isAfk(player));
            db.activityService.sampleAt(start + 302_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(302, info.totalOnlineSeconds());
            assertEquals(301, info.totalActiveSeconds());
            assertEquals(1, info.totalAfkSeconds());
            assertTrue(info.afkNow());
        }
    }

    @Test
    void movementCancelsAfk() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, "minecraft:overworld", start);
            db.activityService.sampleAt(start + 301_000);
            assertTrue(db.activityService.isAfk(player));

            db.activityService.onPlayerMove(player, 10, 64, 10, 0, 0, "minecraft:overworld");
            assertFalse(db.activityService.isAfk(player));
            db.activityService.sampleAt(start + 302_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(302, info.totalActiveSeconds());
            assertEquals(0, info.totalAfkSeconds());
            assertFalse(info.afkNow());
        }
    }

    @Test
    void persistFlushesCountersToDatabase() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, "minecraft:overworld", start);
            db.activityService.sampleAt(start + 10_000);
            db.activityService.persistAll();

            // счётчик в базе — 10 секунд
            long total = db.activityService.info(player).orElseThrow().totalActiveSeconds();
            assertEquals(10, total);
        }
    }

    @Test
    void leavingSessionPersists() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, "minecraft:overworld", start);
            db.activityService.sampleAt(start + 5_000);
            db.activityService.onPlayerLeft(player);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(5, info.totalOnlineSeconds());
            assertEquals(5, info.totalActiveSeconds());
        }
    }
}
