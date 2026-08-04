package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityServiceTest {

    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void accumulatesOnlineAndActiveSeconds() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
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
    void splitsIntervalAtAfkTimeoutBoundary() {
        // Интервал делится на границе таймаута: активна только часть до lastActiveAt + timeout,
        // всё после границы относится к AFK — без переоценки активного времени.
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            // активна часть до таймаута (300с) + 1с после границы уже AFK
            db.activityService.sampleAt(start + 301_000);
            assertTrue(db.activityService.isAfk(player));
            db.activityService.sampleAt(start + 302_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(302, info.totalOnlineSeconds());
            assertEquals(300, info.totalActiveSeconds());
            assertEquals(2, info.totalAfkSeconds());
            assertTrue(info.afkNow());
        }
    }

    @Test
    void substantialMovementCancelsAfk() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 301_000);
            assertTrue(db.activityService.isAfk(player));

            // первый вызов лишь фиксирует позицию
            db.activityService.onPlayerMove(player, 0, 64, 0, 0, 0, DIMENSION);
            // перемещение на 1 метр — больше порога (0.5)
            db.activityService.onPlayerMove(player, 1, 64, 0, 0, 0, DIMENSION);
            assertFalse(db.activityService.isAfk(player));
            db.activityService.sampleAt(start + 302_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(302, info.totalOnlineSeconds());
            // 1с до границы таймаута учтена AFK после возврата активности
            assertEquals(301, info.totalActiveSeconds());
            assertEquals(1, info.totalAfkSeconds());
            assertFalse(info.afkNow());
        }
    }

    @Test
    void cameraRotationDoesNotCancelAfk() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 301_000);
            assertTrue(db.activityService.isAfk(player));

            // поворот камеры на месте не должен сбрасывать AFK
            db.activityService.onPlayerMove(player, 0, 0, 0, 180, 45, DIMENSION);
            db.activityService.onPlayerMove(player, 0, 0, 0, 270, -45, DIMENSION);
            assertTrue(db.activityService.isAfk(player));
        }
    }

    @Test
    void tinyJitterBelowThresholdDoesNotCancelAfk() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 301_000);
            assertTrue(db.activityService.isAfk(player));

            // подрагивание меньше порога (0.5) активностью не считается
            db.activityService.onPlayerMove(player, 0, 0, 0, 0, 0, DIMENSION);
            db.activityService.onPlayerMove(player, 0.1, 0, 0, 0, 0, DIMENSION);
            db.activityService.onPlayerMove(player, 0.2, 0, 0, 0, 0, DIMENSION);
            assertTrue(db.activityService.isAfk(player));
        }
    }

    @Test
    void persistFlushesCountersToDatabase() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 10_000);
            db.activityService.persistAll();

            // счётчик в базе — 10 секунд
            long total = db.activityService.info(player).orElseThrow().totalActiveSeconds();
            assertEquals(10, total);
        }
    }

    @Test
    void leavingSessionPersistsSampledAndTail() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 5_000);
            // выход через 2 секунды после последнего сэмпла: остаток тоже сохраняется
            db.activityService.onPlayerLeftAt(player, start + 7_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(7, info.totalOnlineSeconds());
            assertEquals(7, info.totalActiveSeconds());
        }
    }

    @Test
    void logoutDoesNotLoseUnsampledTail() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            // без промежуточного сэмпла — вся сессия должна попасть в базу
            db.activityService.onPlayerLeftAt(player, start + 10_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(10, info.totalOnlineSeconds());
            assertEquals(10, info.totalActiveSeconds());
            assertEquals(0, info.totalAfkSeconds());
        }
    }
}