package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.config.EconomySettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityServiceTest {

    private static final String DIMENSION = "minecraft:overworld";

    /** Настройки по умолчанию с другой зоной фонда. */
    private static EconomySettings withTimeZone(String timeZone) {
        EconomySettings d = EconomySettings.defaults();
        EconomySettings.WeeklyFund wf = d.weeklyFund;
        EconomySettings.WeeklyFund custom = new EconomySettings.WeeklyFund(
                wf.enabled, wf.notify, wf.autoPayout, wf.payoutDelayHours,
                wf.minAccountAgeDays, wf.minActiveSeconds, wf.minActiveDays, wf.minActiveDaySeconds,
                wf.baseAmountPerEligiblePlayer, wf.minimumFund, wf.maximumFund,
                wf.targetSupplyPerEligiblePlayer, wf.economyCoefficientTiers,
                wf.timePointLevels, wf.dayPointLevels, wf.maximumPlayerSharePercent, timeZone);
        return new EconomySettings(
                d.currencyNameSingular, d.currencyNameFew, d.currencyNameMany,
                d.currencySymbol, d.decimalPlaces, d.maximumBalance,
                d.transfersEnabled, d.allowOfflineRecipients,
                d.minimumTransferAmount, d.maximumTransferAmount, d.transferCooldownSeconds,
                d.dbType, d.databaseFile, d.busyTimeoutMillis, d.walEnabled,
                d.mysqlHost, d.mysqlPort, d.mysqlDatabase, d.mysqlUser, d.mysqlPassword, d.mysqlPoolSize,
                d.broadcastAdminChanges, d.activity, d.milestones, custom);
    }

    /** Сделать сессию активной на всём интервале: сброс AFK реальным движением (порог 0.5 м). */
    private static void keepActive(TestDb db, UUID player, String dimension) {
        db.activityService.onPlayerMove(player, 1, 64, 0, 0, 0, dimension);
        db.activityService.onPlayerMove(player, 2, 64, 0, 0, 0, dimension);
    }

    /** Секунды активности за неделю из таблицы дней (0, если строк нет). */
    private static long daySeconds(TestDb db, String weekId) {
        return db.database.inTransaction(connection ->
                db.dayRepository.listByWeek(connection, weekId).stream()
                        .mapToLong(WeeklyActivityDayRow::activeSeconds).sum());
    }

    @Test
    void sessionAcrossLocalMidnightSplitsIntoTwoDayRows() {
        // понедельник 23:30 → вторник 00:30 (Берлин, UTC+2): час сессии делится поровну
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = Instant.parse("2026-08-03T21:30:00Z").toEpochMilli();
            long end = Instant.parse("2026-08-03T22:30:00Z").toEpochMilli();
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            keepActive(db, player, DIMENSION);
            db.activityService.onPlayerLeftAt(player, end);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(3_600, info.totalActiveSeconds());
            assertEquals(0, info.totalAfkSeconds());
            List<WeeklyActivityDayRow> rows = db.database.inTransaction(connection ->
                    db.dayRepository.listByWeekAndPlayer(connection, "2026-W32", player));
            assertEquals(2, rows.size());
            assertEquals(1_800, rows.get(0).activeSeconds());
            assertEquals(1_800, rows.get(1).activeSeconds());
        }
    }

    @Test
    void sessionAcrossWeekBoundaryAttributesEachDayToItsWeek() {
        // воскресенье 23:30 (неделя W31) → понедельник 00:30 (неделя W32)
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = Instant.parse("2026-08-02T21:30:00Z").toEpochMilli();
            long end = Instant.parse("2026-08-02T22:30:00Z").toEpochMilli();
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            keepActive(db, player, DIMENSION);
            db.activityService.onPlayerLeftAt(player, end);

            assertEquals(1_800, daySeconds(db, "2026-W31"));
            assertEquals(1_800, daySeconds(db, "2026-W32"));
        }
    }

    @Test
    void dayBoundaryFollowsConfiguredTimeZone() {
        // 2026-08-02 19:00Z — по Берлину ещё 02.08, по Токио (UTC+9) уже 03.08 (понедельник)
        try (TestDb db = TestDb.create(withTimeZone("Asia/Tokyo"))) {
            UUID player = UUID.randomUUID();
            long start = Instant.parse("2026-08-02T19:00:00Z").toEpochMilli();
            long end = Instant.parse("2026-08-02T20:30:00Z").toEpochMilli();
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            keepActive(db, player, DIMENSION);
            db.activityService.onPlayerLeftAt(player, end);

            // вся сессия относится к одному дню в зоне конфига (03.08, W32)
            List<WeeklyActivityDayRow> rows = db.database.inTransaction(connection ->
                    db.dayRepository.listByWeek(connection, "2026-W32"));
            assertEquals(1, rows.size());
            assertEquals(Long.toString(LocalDate.of(2026, 8, 3).toEpochDay()), rows.get(0).dayKey());
            assertEquals(5_400, rows.get(0).activeSeconds());
            assertEquals(0, daySeconds(db, "2026-W31"));
        }
    }

    @Test
    void afkSecondsAreNotCountedToAnyDay() {
        // активны только первые 300 секунд (таймаут AFK), остаток сессии в дни не попадает
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = Instant.parse("2026-08-03T10:00:00Z").toEpochMilli();
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.onPlayerLeftAt(player, start + 301_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(301, info.totalOnlineSeconds());
            assertEquals(300, info.totalActiveSeconds());
            assertEquals(1, info.totalAfkSeconds());
            assertEquals(300, daySeconds(db, "2026-W32"));
        }
    }

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

    @Test
    void persistFailureKeepsActivityInMemoryAndWritesOnceOnRetry() {
        // Упавшая транзакция (база недоступна) не должна стирать счётчики: следующая
        // успешная попытка записывает их ровно один раз, без дублей и потерь.
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 10_000);

            // база недоступна: persist обязан вернуть false и НЕ очистить память
            db.database.close();
            assertFalse(db.activityService.persistAll());

            // база снова доступна: повторный persist записывает накопленное ровно один раз
            db.database.open(db.database.path(), db.settings);
            assertTrue(db.activityService.persistAll());
            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(10, info.totalOnlineSeconds());
            assertEquals(10, info.totalActiveSeconds());

            // ещё один persist не дублирует запись
            assertTrue(db.activityService.persistAll());
            ActivityService.ActivityInfo after = db.activityService.info(player).orElseThrow();
            assertEquals(10, after.totalOnlineSeconds());
            assertEquals(10, after.totalActiveSeconds());
        }
    }

    @Test
    void failedLogoutSaveKeepsDataPendingUntilNextPersist() {
        // Ошибка базы при выходе игрока не должна выбрасывать его данные: сессия
        // удерживается в памяти и попадает в базу на следующем успешном сохранении.
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 5_000);

            db.database.close();
            // выход при недоступной базе: данные удержаны, исключения нет
            db.activityService.onPlayerLeftAt(player, start + 7_000);

            db.database.open(db.database.path(), db.settings);
            assertTrue(db.activityService.persistAll());
            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(7, info.totalOnlineSeconds());
            assertEquals(7, info.totalActiveSeconds());
            assertEquals(0, info.totalAfkSeconds());
        }
    }

    @Test
    void doubleLogoutDuringDbOutageMergesPendingSessions() {
        // Два выхода одного игрока до успешного сохранения не должны терять первый:
        // несохранённые сессии сливаются и записываются одной суммой.
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            db.activityService.sampleAt(start + 5_000);

            db.database.close();
            // первый выход при недоступной базе: 7 секунд удержаны в памяти
            db.activityService.onPlayerLeftAt(player, start + 7_000);
            // повторный вход и выход до восстановления базы: 7 секунд второй сессии
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start + 8_000);
            db.activityService.sampleAt(start + 13_000);
            db.activityService.onPlayerLeftAt(player, start + 15_000);

            db.database.open(db.database.path(), db.settings);
            assertTrue(db.activityService.persistAll());
            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(14, info.totalOnlineSeconds());
            assertEquals(14, info.totalActiveSeconds());
        }
    }
}