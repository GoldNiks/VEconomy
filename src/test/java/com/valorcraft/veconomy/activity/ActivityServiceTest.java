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

    /** Настройки по умолчанию с другим учётом активности. */
    private static EconomySettings withActivity(EconomySettings.Activity activity) {
        EconomySettings d = EconomySettings.defaults();
        return new EconomySettings(
                d.currencyNameSingular, d.currencyNameFew, d.currencyNameMany,
                d.currencySymbol, d.decimalPlaces, d.maximumBalance,
                d.transfersEnabled, d.allowOfflineRecipients,
                d.minimumTransferAmount, d.maximumTransferAmount, d.transferCooldownSeconds,
                d.dbType, d.databaseFile, d.busyTimeoutMillis, d.walEnabled,
                d.mysqlHost, d.mysqlPort, d.mysqlDatabase, d.mysqlUser, d.mysqlPassword, d.mysqlPoolSize,
                d.broadcastAdminChanges, activity, d.milestones, d.weeklyFund);
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
    void exclusionFlagSetAndCleared() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();

            assertEquals(RewardExclusionStatus.NOT_EXCLUDED,
                    db.activityService.excludedFromRewards(player));
            AccountFlagUpdateResult set = db.activityService.setExcludedFromRewards(player, true);
            assertEquals(AccountFlagUpdateResult.Status.SUCCESS, set.status());
            assertTrue(set.resultingValue());
            assertEquals(RewardExclusionStatus.EXCLUDED,
                    db.activityService.excludedFromRewards(player));
            AccountFlagUpdateResult clear = db.activityService.setExcludedFromRewards(player, false);
            assertEquals(AccountFlagUpdateResult.Status.SUCCESS, clear.status());
            assertFalse(clear.resultingValue());
            assertEquals(RewardExclusionStatus.NOT_EXCLUDED,
                    db.activityService.excludedFromRewards(player));
        }
    }

    @Test
    void exclusionCheckFailureIsFailClosed() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.database.close();

            // Ошибка базы при чтении флага: UNKNOWN, а не «не исключён» (fail-closed).
            assertEquals(RewardExclusionStatus.UNKNOWN,
                    db.activityService.excludedFromRewards(player));
            // Ошибка базы при установке: изменение не применено, результат не выглядит успехом.
            AccountFlagUpdateResult result =
                    db.activityService.setExcludedFromRewards(player, true);
            assertEquals(AccountFlagUpdateResult.Status.DATABASE_ERROR, result.status());
            assertFalse(result.isSuccess());
        }
    }

    @Test
    void exclusionFlagPersistsAcrossPersist() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.activityService.onPlayerJoinedAt(player, DIMENSION, 1_000_000L);
            db.activityService.sampleAt(1_001_000L);
            db.activityService.setExcludedFromRewards(player, true);
            db.activityService.persistAll();

            assertEquals(RewardExclusionStatus.EXCLUDED,
                    db.activityService.excludedFromRewards(player));
        }
    }

    @Test
    void exclusionFlagWorksWithoutActivityRow() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();

            // Игрок без записи активности: флаг создаёт минимальную запись.
            assertTrue(db.activityService.setExcludedFromRewards(player, true).isSuccess());
            assertEquals(RewardExclusionStatus.EXCLUDED,
                    db.activityService.excludedFromRewards(player));
        }
    }

    @Test
    void activityDisabledTracksNothing() {
        // Учёт выключен: сессия не создаётся, время не копится ни в строке, ни в днях.
        try (TestDb db = TestDb.create(withActivity(
                new EconomySettings.Activity(false, 300, 20, 60, 0.5)))) {
            UUID player = UUID.randomUUID();
            long start = 1_000_000L;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, start);
            keepActive(db, player, DIMENSION);
            db.activityService.sampleAt(start + 1_000);
            db.activityService.onPlayerLeftAt(player, start + 10_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(0, info.totalOnlineSeconds());
            assertEquals(0, info.totalActiveSeconds());
            assertEquals(0, daySeconds(db, WeekId.current()));
        }
    }

    /** Горячее выключение: накопленное фиксируется ровно до момента выключения, время после — нет. */
    @Test
    void hotDisableFinalizesUpToDisableMoment() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long join = System.currentTimeMillis() - 1_000_000;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, join);
            keepActive(db, player, DIMENSION);
            db.activityService.sampleAt(join + 60_000);
            long disableAt = join + 120_000;
            db.activityService.applySettingsAt(withActivity(
                    new EconomySettings.Activity(false, 300, 20, 60, 0.5)), disableAt);

            ActivityService.ActivityInfo afterDisable = db.activityService.info(player).orElseThrow();
            assertEquals(120, afterDisable.totalOnlineSeconds(),
                    "до момента выключения учтено полностью");
            assertEquals(120, afterDisable.totalActiveSeconds());

            db.activityService.sampleAt(disableAt + 60_000);
            ActivityService.ActivityInfo duringDisable = db.activityService.info(player).orElseThrow();
            assertEquals(120, duringDisable.totalOnlineSeconds(),
                    "выключенный период не начисляется");
            assertEquals(120, duringDisable.totalActiveSeconds());

            long enableAt = disableAt + 1_000_000;
            db.activityService.applySettingsAt(withActivity(
                    new EconomySettings.Activity(true, 300, 20, 60, 0.5)), enableAt);
            keepActive(db, player, DIMENSION);
            db.activityService.sampleAt(enableAt + 30_000);
            db.activityService.persistAllAt(enableAt + 30_000);

            ActivityService.ActivityInfo afterEnable = db.activityService.info(player).orElseThrow();
            assertEquals(150, afterEnable.totalOnlineSeconds(),
                    "после повторного включения отсчёт возобновляется с момента включения");
            assertEquals(150, afterEnable.totalActiveSeconds());
            assertEquals(150, daySeconds(db, WeekId.current()));
        }
    }

    /** Выход во время выключенного учёта: приостановленная сессия не воскресает при включении. */
    @Test
    void logoutDuringDisabledDropPausedSession() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long join = System.currentTimeMillis() - 1_000_000;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, join);
            keepActive(db, player, DIMENSION);
            db.activityService.sampleAt(join + 1_000);
            long disableAt = join + 1_000;
            db.activityService.applySettingsAt(withActivity(
                    new EconomySettings.Activity(false, 300, 20, 60, 0.5)), disableAt);
            db.activityService.onPlayerLeftAt(player, disableAt + 5_000);
            long enableAt = disableAt + 500_000;
            db.activityService.applySettingsAt(withActivity(
                    new EconomySettings.Activity(true, 300, 20, 60, 0.5)), enableAt);
            keepActive(db, player, DIMENSION);
            db.activityService.sampleAt(enableAt + 1_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(1, info.totalOnlineSeconds(),
                    "после выхода в выключенный период новый отсчёт не должен начаться");
            assertEquals(1, info.totalActiveSeconds());
            assertEquals(1, daySeconds(db, WeekId.current()));
        }
    }

    /** Повторное включение-выключение: цикл не теряет и не дублирует секунды. */
    @Test
    void hotSwitchCycleNeverLosesOrDuplicatesSeconds() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            long t = System.currentTimeMillis() - 500_000;
            db.activityService.onPlayerJoinedAt(player, DIMENSION, t);
            EconomySettings.Activity off = new EconomySettings.Activity(false, 300, 20, 60, 0.5);
            EconomySettings.Activity on = new EconomySettings.Activity(true, 300, 20, 60, 0.5);

            // Окно 10с учёта → выкл (момент переключения совпадает с последним сэмплом,
            // чтобы финализация не добавляла лишнюю секунду) → 10с выключено → вкл → 10с
            // учёта → выкл → 10с выключено → выход.
            keepActive(db, player, DIMENSION);
            db.activityService.sampleAt(t + 10_000);
            db.activityService.applySettingsAt(withActivity(off), t + 10_000);
            db.activityService.sampleAt(t + 20_000);
            db.activityService.applySettingsAt(withActivity(on), t + 20_000);
            keepActive(db, player, DIMENSION);
            db.activityService.sampleAt(t + 30_000);
            db.activityService.applySettingsAt(withActivity(off), t + 30_000);
            db.activityService.sampleAt(t + 40_000);
            db.activityService.onPlayerLeftAt(player, t + 40_000);

            ActivityService.ActivityInfo info = db.activityService.info(player).orElseThrow();
            assertEquals(20, info.totalOnlineSeconds(), "учтены только два включённых окна по 10с");
            assertEquals(20, info.totalActiveSeconds());
            assertEquals(20, daySeconds(db, WeekId.current()));
        }
    }

    @Test
    void exclusionNoChangeReportsExplicitStatus() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            assertTrue(db.activityService.setExcludedFromRewards(player, true).isSuccess());
            assertEquals(AccountFlagUpdateResult.Status.NO_CHANGES,
                    db.activityService.setExcludedFromRewards(player, true).status(),
                    "повторное исключение в том же состоянии — явный NO_CHANGES");
            assertEquals(AccountFlagUpdateResult.Status.SUCCESS,
                    db.activityService.setExcludedFromRewards(player, false).status());
            assertEquals(AccountFlagUpdateResult.Status.PLAYER_NOT_FOUND,
                    db.activityService.setExcludedFromRewards(null, true).status());
        }
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