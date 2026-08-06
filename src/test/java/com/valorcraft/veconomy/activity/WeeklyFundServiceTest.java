package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.PointLevel;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.MetaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyFundServiceTest {

    private static final String DISTRIBUTED_WEEK_KEY = "weekly_fund.distributed_week";

    /**
     * Очки за время 1:1 (интерполяция по уровням (1;1) → (10 000;10 000)): для секунд
     * в [1, 10 000] очки равны секундам. Выше 10 000 очки не растут.
     */
    private static final List<PointLevel> ONE_TO_ONE = List.of(
            new PointLevel(1, 1), new PointLevel(10_000, 10_000));

    /** Фонд со сдвигом автовыплаты: ротация закрывает неделю, но платит не сразу. */
    private static EconomySettings fundWithDelay(long weeklyAmount, int payoutDelayHours) {
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
                new EconomySettings.WeeklyFund(true, true, true, payoutDelayHours,
                        0, 0, 0, 0,
                        weeklyAmount / 2, weeklyAmount, weeklyAmount, 1_000_000L,
                        List.of(), ONE_TO_ONE, List.of(), 100, "Europe/Berlin"));
    }

    /** Залить активность в закрытую неделю (предыдущую от текущей): строку дней + строку активности. */
    private static void seedWeekly(TestDb db, UUID player, long seconds) {
        seedWeekly(db, player, seconds, false, 1L);
    }

    private static void seedWeekly(TestDb db, UUID player, long seconds, boolean excluded) {
        seedWeekly(db, player, seconds, excluded, 1L);
    }

    private static void seedWeekly(TestDb db, UUID player, long seconds, boolean excluded, long firstSeen) {
        String week = WeekId.previous(WeekId.current());
        db.database.inTransaction(connection -> {
            db.activityRepository.upsert(connection, DatabaseManager.Dialect.SQLITE,
                    new PlayerActivityRow(player, firstSeen, firstSeen, seconds, seconds, 0,
                            week, 0L, "minecraft:overworld", excluded));
            db.dayRepository.addSeconds(connection, DatabaseManager.Dialect.SQLITE, player, week, "1", seconds);
            return null;
        });
    }

    /** Залить активность по нескольким дням (для подсчёта активных дней). */
    private static void seedDays(TestDb db, UUID player, long perDaySeconds, int days) {
        String week = WeekId.previous(WeekId.current());
        db.database.inTransaction(connection -> {
            for (int i = 0; i < days; i++) {
                db.dayRepository.addSeconds(connection, DatabaseManager.Dialect.SQLITE,
                        player, week, Integer.toString(i), perDaySeconds);
            }
            return null;
        });
    }

    /** Залить активность в произвольную неделю (для тестов ротации по фиксированной дате). */
    private static void seedDay(TestDb db, UUID player, String weekId, String dayKey, long seconds) {
        db.database.inTransaction(connection -> {
            db.dayRepository.addSeconds(connection, DatabaseManager.Dialect.SQLITE,
                    player, weekId, dayKey, seconds);
            return null;
        });
    }

    /**
     * Смоделировать положение после завершения позапрошлой недели: распределена позапрошлая,
     * накопленная активность в {@code weekly_activity_days} принадлежит прошлой неделе и при
     * ротации будет сохранена снимком для {@code previous(current)}.
     */
    private static void markSnapshotDue(TestDb db) {
        db.database.inTransaction(connection -> {
            MetaRepository.set(connection, DatabaseManager.Dialect.SQLITE,
                    DISTRIBUTED_WEEK_KEY, WeekId.previous(WeekId.previous(WeekId.current())));
            return null;
        });
    }

    /**
     * Фонд недели всегда равен {@code weeklyAmount}: база — половина на игрока, минимум и
     * максимум равны фонду. Без коэффициента экономики (нет ступеней), без ограничения доли
     * на игрока (100%), без очков за дни.
     */
    private static EconomySettings fund(long weeklyAmount) {
        return fund(weeklyAmount, 0, 0, 0, ONE_TO_ONE);
    }

    private static EconomySettings fund(long weeklyAmount, long minActiveSeconds, int minActiveDays,
                                        long minAccountAgeDays, List<PointLevel> timeLevels) {
        return fund(weeklyAmount, minActiveSeconds, minActiveDays, minAccountAgeDays, timeLevels, true);
    }

    private static EconomySettings fund(long weeklyAmount, long minActiveSeconds, int minActiveDays,
                                        long minAccountAgeDays, List<PointLevel> timeLevels, boolean autoPayout) {
        return fund(weeklyAmount, minActiveSeconds, minActiveDays, minAccountAgeDays, timeLevels,
                autoPayout, 100);
    }

    private static EconomySettings fund(long weeklyAmount, long minActiveSeconds, int minActiveDays,
                                        long minAccountAgeDays, List<PointLevel> timeLevels, boolean autoPayout,
                                        int maximumPlayerSharePercent) {
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
                new EconomySettings.WeeklyFund(true, true, autoPayout, 0,
                        minAccountAgeDays, minActiveSeconds, minActiveDays, 0,
                        weeklyAmount / 2, weeklyAmount, weeklyAmount, 1_000_000L,
                        List.of(), timeLevels, List.of(), maximumPlayerSharePercent, "Europe/Berlin"));
    }

    @Test
    void distributesProportionallyToActivity() {
        try (TestDb db = TestDb.create(fund(400))) {
            markSnapshotDue(db);
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
    void payoutWritesAuditEvents() {
        try (TestDb db = TestDb.create(fund(400))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 300);
            seedWeekly(db, bob, 100);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(2, payments.size());

            java.util.List<com.valorcraft.veconomy.audit.AuditEventRow> rows = db.auditService.recent(10);
            assertEquals(2, rows.size(), "на каждую успешную выплату должно быть событие аудита");
            long amountOf = rows.stream()
                    .filter(r -> com.valorcraft.veconomy.audit.AuditEventType.WEEKLY_PAYOUT.equals(r.eventType())
                            && alice.equals(r.playerId()))
                    .mapToLong(r -> r.amountMinor() == null ? -1 : r.amountMinor())
                    .sum();
            assertEquals(300L, amountOf);
            assertTrue(rows.stream().anyMatch(r ->
                            com.valorcraft.veconomy.audit.AuditEventType.WEEKLY_PAYOUT.equals(r.eventType())
                                    && bob.equals(r.playerId()) && r.amountMinor() == 100L),
                    "для bob должна быть запись WEEKLY_PAYOUT на 100");
        }
    }

    @Test
    void distributesWhenTotalActivityExceedsFund() {
        // Критический случай: totalActive > fund. Доли считаются по очкам, не по секундам на юнит.
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 1000);
            seedWeekly(db, bob, 1000);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(2, payments.size());
            assertEquals(50L, payments.get(alice));
            assertEquals(50L, payments.get(bob));
            assertEquals(50, db.accountService.getBalance(alice));
            assertEquals(50, db.accountService.getBalance(bob));
        }
    }

    @Test
    void payoutRunsOnlyOncePerWeek() {
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 50);

            Map<UUID, Long> first = db.weeklyFundService.maybeDistribute();
            assertEquals(1, first.size());

            Map<UUID, Long> second = db.weeklyFundService.maybeDistribute();
            assertTrue(second.isEmpty());
            // единственный участник получает весь фонд
            assertEquals(100, db.accountService.getBalance(alice));
        }
    }

    @Test
    void remainderGoesToTreasury() {
        // лимит доли 30%: каждый из троих получает 30 (поровну, но не больше лимита),
        // нераздаваемый целыми единицами остаток 100 − 90 = 10 уходит в казну
        try (TestDb db = TestDb.create(fund(100, 0, 0, 0, ONE_TO_ONE, true, 30))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            seedWeekly(db, alice, 60);
            seedWeekly(db, bob, 60);
            seedWeekly(db, carol, 60);

            db.weeklyFundService.maybeDistribute();
            assertEquals(30, db.accountService.getBalance(alice));
            assertEquals(30, db.accountService.getBalance(bob));
            assertEquals(30, db.accountService.getBalance(carol));
            assertEquals(10, db.accountService.getBalance(TreasuryService.TREASURY_UUID));
        }
    }

    @Test
    void fundDoesNotExceedConfiguredAmount() {
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 1000);
            seedWeekly(db, bob, 1000);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            long paid = payments.values().stream().mapToLong(Long::longValue).sum();
            assertEquals(100, paid);
            assertEquals(100,
                    db.accountService.getBalance(alice)
                            + db.accountService.getBalance(bob)
                            + db.accountService.getBalance(TreasuryService.TREASURY_UUID));
        }
    }

    @Test
    void excludedPlayerGetsNothing() {
        try (TestDb db = TestDb.create(fund(200))) {
            markSnapshotDue(db);
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

    @Test
    void firstLaunchInitializesWithoutPayout() {
        try (TestDb db = TestDb.create(fund(100))) {
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 50);

            // ключ distributed_week отсутствует → первичная инициализация без выплаты
            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertTrue(payments.isEmpty());
            assertEquals(0, db.accountService.getBalance(alice));

            // повторный вызов в той же неделе — тоже без выплаты (снимок уже создан при инициализации)
            assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());
            assertEquals(0, db.accountService.getBalance(alice));
        }
    }

    /**
     * Блокер: первая полная неделя после установки мода должна выплатиться автоматически.
     * Первый запуск помечает распределённой предыдущую неделю (а не текущую), поэтому снимок
     * текущей (первой полной) недели попадает в очередь и платится при переходе на следующую.
     */
    @Test
    void firstFullWeekIsPaidAutomaticallyAfterRollover() {
        java.time.LocalDate monday = java.time.LocalDate.of(2026, 8, 3); // 2026-W32
        WeekId.useDate(() -> monday);
        try {
            try (TestDb db = TestDb.create(fund(100))) {
                // первый запуск на неделе W32: инициализация без выплаты, распределена W31
                assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());

                // накопление активности в первую полную неделю W32
                UUID alice = UUID.randomUUID();
                seedDay(db, alice, "2026-W32", "1", 100);

                // переход на следующую неделю W33
                WeekId.useDate(() -> monday.plusDays(7));
                Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
                assertEquals(1, payments.size(), "первая полная неделя должна выплатиться автоматически");
                assertEquals(100L, payments.get(alice));
                assertEquals(100, db.accountService.getBalance(alice));

                // неделя W32 распределена, больше платить нечего
                assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());
                assertEquals(100, db.accountService.getBalance(alice));
            }
        } finally {
            WeekId.resetDate();
        }
    }

    @Test
    void respectsMinimumActiveSeconds() {
        try (TestDb db = TestDb.create(fund(100, 100, 0, 0, ONE_TO_ONE))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID below = UUID.randomUUID();
            seedWeekly(db, alice, 200);
            seedWeekly(db, below, 50);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(1, payments.size());
            assertEquals(100, db.accountService.getBalance(alice));
            assertEquals(0, db.accountService.getBalance(below));
        }
    }

    @Test
    void respectsMinimumActiveDays() {
        try (TestDb db = TestDb.create(fund(100, 0, 2, 0, ONE_TO_ONE))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedDays(db, alice, 100, 2);
            seedDays(db, bob, 100, 1);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(1, payments.size());
            assertEquals(100, db.accountService.getBalance(alice));
            assertEquals(0, db.accountService.getBalance(bob));
        }
    }

    @Test
    void pointLevelsDriveTheSplit() {
        // уровни: 100с → 1 очко, 300с → 3 очка (интерполяция). Алиса: 3 очка, Боб: 1 очко.
        try (TestDb db = TestDb.create(fund(100, 0, 0, 0,
                List.of(new PointLevel(100, 1), new PointLevel(300, 3))))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 300);
            seedWeekly(db, bob, 100);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(75L, payments.get(alice));
            assertEquals(25L, payments.get(bob));
        }
    }

    @Test
    void respectsMinimumAccountAge() {
        long now = System.currentTimeMillis();
        try (TestDb db = TestDb.create(fund(100, 0, 0, 7, ONE_TO_ONE))) {
            markSnapshotDue(db);
            UUID young = UUID.randomUUID();
            UUID old = UUID.randomUUID();
            seedWeekly(db, young, 50, false, now);
            seedWeekly(db, old, 50, false, 1L);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(1, payments.size());
            assertEquals(0, db.accountService.getBalance(young));
            assertEquals(100, db.accountService.getBalance(old));
        }
    }

    @Test
    void manualRunWorksEvenWhenAutoRunDisabled() {
        try (TestDb db = TestDb.create(fund(100, 0, 0, 0, ONE_TO_ONE, false))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 50);

            // автовыплата выключена: ротация создаёт снимок, но не платит
            assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());
            Map<UUID, Long> payments = db.weeklyFundService.runNow();
            assertEquals(1, payments.size());
            assertEquals(100L, payments.get(alice));
        }
    }

    @Test
    void previewReportsSnapshotAllocationsWithoutPaying() {
        try (TestDb db = TestDb.create(fund(100, 0, 0, 0, ONE_TO_ONE, false))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 60);

            // автозапуск выключен: ротация создаёт снимок, но не платит
            assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());
            List<WeeklyFundService.WeeklyAllocation> allocations = db.weeklyFundService.preview();
            assertEquals(1, allocations.size());
            assertEquals(alice, allocations.get(0).playerId());
            assertEquals(60, allocations.get(0).countedSeconds());
            assertEquals(60, allocations.get(0).points());
            assertEquals(100, allocations.get(0).share());
            assertEquals(0, db.accountService.getBalance(alice));

            WeeklyFundService.WeeklyStatus status = db.weeklyFundService.status();
            assertEquals(1, status.eligiblePlayers());
            assertEquals(100, status.totalShare());

            db.weeklyFundService.runNow();
            assertEquals(100, db.accountService.getBalance(alice));
        }
    }

    @Test
    void rotationSnapshotsPreviousWeekAndIgnoresNewWeekActivity() {
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 100);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(100L, payments.get(alice));
            assertEquals(100, db.accountService.getBalance(alice));

            // новая активность текущей недели не меняет снимок закрытой недели
            seedDay(db, alice, WeekId.current(), "1", 40);
            List<WeeklyFundService.WeeklyAllocation> allocations = db.weeklyFundService.preview();
            assertEquals(1, allocations.size());
            assertEquals(100, allocations.get(0).countedSeconds());

            // неделя уже распределена — повторная выплата не происходит
            assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());
            WeeklyFundService.WeeklyStatus status = db.weeklyFundService.status();
            assertTrue(status.weekDistributed());
        }
    }

    /**
     * Вставить вручную снимок недели со строкой в статусе ожидания выплаты.
     * План недели тоже фиксируется (замороженные доли читаются из строк периода).
     */
    private static void seedPeriod(TestDb db, String weekId, UUID player, long seconds, long share) {
        WeeklyPeriodRepository periods = new WeeklyPeriodRepository();
        db.database.inTransaction(connection -> {
            db.planRepository.insert(connection, DatabaseManager.Dialect.SQLITE, new WeeklyFundPlanRow(
                    weekId, share, share, WeeklyMath.BPS_100_PERCENT, 0, 0, 1_000_000L,
                    1, seconds, share, 0, WeeklyFundPlanRow.STATUS_PLANNED, 1L, null, null));
            periods.insert(connection, DatabaseManager.Dialect.SQLITE, new WeeklyPeriodRow(
                    weekId, player, seconds, seconds, WeeklyPeriodRepository.STATUS_PENDING, 0, null,
                    1, seconds, 0, share));
            return null;
        });
    }

    /** Установить распределённую неделю (состояние после завершения указанной недели). */
    private static void markDistributed(TestDb db, String weekId) {
        db.database.inTransaction(connection -> {
            MetaRepository.set(connection, DatabaseManager.Dialect.SQLITE,
                    DISTRIBUTED_WEEK_KEY, weekId);
            return null;
        });
    }

    /**
     * Блокер: если по какой-то причине пропущены две недели (W1 и W2 уже сохранены снимками
     * в очереди), выплата должна брать самую старую незакрытую (W1), а не предыдущую от текущей.
     */
    @Test
    void paysOldestUnclosedPeriodFirst() {
        try (TestDb db = TestDb.create(fund(100))) {
            String current = WeekId.current();
            String w2 = WeekId.previous(current);
            String w1 = WeekId.previous(w2);
            // распределена неделя до W1 — значит W1 и W2 ещё не обработаны, но уже сняты в очередь
            markDistributed(db, WeekId.previous(w1));
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedPeriod(db, w1, alice, 50, 100);
            seedPeriod(db, w2, bob, 50, 100);

            Map<UUID, Long> first = db.weeklyFundService.runNow();
            assertEquals(1, first.size());
            assertEquals(100L, first.get(alice), "выплата должна взять самую старую неделю W1");
            assertEquals(100, db.accountService.getBalance(alice));
            assertEquals(0, db.accountService.getBalance(bob), "младшая неделя W2 ещё не обработана");

            Map<UUID, Long> second = db.weeklyFundService.runNow();
            assertEquals(1, second.size());
            assertEquals(100L, second.get(bob), "следующая выплата берёт W2");
            assertEquals(100, db.accountService.getBalance(bob));

            assertTrue(db.weeklyFundService.runNow().isEmpty(), "после обеих недель больше нечего платить");
        }
    }

    /**
     * Пропущенная (офлайн) неделя без снимка не должна блокировать выплату более новых периодов:
     * она закрывается как пустая, и очередь продвигается дальше.
     */
    @Test
    void emptyGapWeekDoesNotBlockNewerPayouts() {
        try (TestDb db = TestDb.create(fund(100))) {
            String current = WeekId.current();
            String w2 = WeekId.previous(current);
            String w1 = WeekId.previous(w2);
            // распределена неделя до W1; W1 — пропущена (снимка нет), W2 — есть участник
            markDistributed(db, WeekId.previous(w1));
            UUID bob = UUID.randomUUID();
            seedPeriod(db, w2, bob, 50, 100);

            // первый runNow закрывает пустой W1 как закрытую пустую неделю и платит W2
            Map<UUID, Long> first = db.weeklyFundService.runNow();
            assertEquals(100L, first.get(bob));
            assertEquals(100, db.accountService.getBalance(bob));
            assertTrue(db.weeklyFundService.runNow().isEmpty());
        }
    }

    /**
     * Выплата возобновляема: доля игрока, не прошедшая из-за лимита баланса, остаётся в снимке;
     * после освобождения места повторный запуск доплачивает без дублей остальным.
     */
    @Test
    void resumablePaymentContinuesAfterFailure() {
        long max = EconomySettings.defaults().maximumBalance;
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 60);
            seedWeekly(db, bob, 40);
            db.accountService.createOrTouch(bob, "bob");
            // боб на пределе: его доля 40 уже не влезает (max − 39 + 40 = max + 1)
            db.accountService.deposit(bob, max - 39,
                    TransactionContext.of(TransactionType.ADMIN_DEPOSIT, null, "test", "test:bob:fill"));

            // первый запуск: алиса выплачена, боб — нет; период остаётся открытым
            Map<UUID, Long> first = db.weeklyFundService.runNow();
            assertEquals(1, first.size());
            assertEquals(60, db.accountService.getBalance(alice));
            assertEquals(max - 39, db.accountService.getBalance(bob));
            assertTrue(!db.weeklyFundService.status().weekDistributed());

            // повторный запуск с пределом — выплаты нет, период всё ещё открыт
            assertTrue(db.weeklyFundService.runNow().isEmpty());
            assertTrue(!db.weeklyFundService.status().weekDistributed());

            // освободили место: повторный run доплачивает бобу и закрывает период
            db.accountService.withdraw(bob, 1,
                    TransactionContext.of(TransactionType.ADMIN_WITHDRAW, null, "test", "test:bob:free"));
            Map<UUID, Long> retry = db.weeklyFundService.runNow();
            assertEquals(1, retry.size());
            assertEquals(40L, retry.get(bob));
            assertEquals(60, db.accountService.getBalance(alice));
            assertEquals(max, db.accountService.getBalance(bob));
            // казне при возобновлении дублировать нечего: 60+40 = весь фонд
            assertEquals(0, db.accountService.getBalance(TreasuryService.TREASURY_UUID));
            assertTrue(db.weeklyFundService.status().weekDistributed());
        }
    }

    /**
     * Блокер: игрок онлайн через границу недели, между сэмплом и границей не сохранялся.
     * Первый persist уже в понедельник: воскресная часть сессии должна попасть в план
     * старой недели, понедельничная — остаться в текущей. Потерянных минут быть не должно.
     */
    @Test
    void onlineSessionAcrossWeekBoundarySavesSundayToOldWeekAndMondayToNew() {
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/Berlin");
        long boundary = java.time.ZonedDateTime.of(2026, 8, 10, 0, 0, 0, 0, zone)
                .toInstant().toEpochMilli();
        long start = boundary - 3600_000L;            // вс 23:00
        long end = boundary + 1800_000L;              // пн 00:30
        try (TestDb db = TestDb.create(fund(100))) {
            // распределена неделя до W32, чтобы очередь закрытия начиналась с W32
            markDistributed(db, "2026-W31");
            WeekId.useDate(() -> java.time.LocalDate.of(2026, 8, 10)); // текущая неделя W33
            try {
                UUID alice = UUID.randomUUID();
                db.activityService.onPlayerJoinedAt(alice, "minecraft:overworld", start);
                // без сохранений до границы; перед сэмплом отмечаем активность
                db.activityService.onPlayerActiveAt(alice, end);
                db.activityService.sampleAt(end);
                // первый persist уже в понедельник
                db.activityService.persistAllAt(end);

                Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
                assertEquals(1, payments.size());
                assertEquals(100L, payments.get(alice));

                // снимок W32 содержит только воскресную часть сессии
                List<WeeklyFundService.WeeklyAllocation> allocations =
                        db.weeklyFundService.preview("2026-W32");
                assertEquals(1, allocations.size());
                assertEquals(3600, allocations.get(0).countedSeconds(),
                        "воскресные часы должны попасть в план завершённой недели");

                // понедельничная часть осталась в текущей неделе, а не в снимке
                long mondaySeconds = db.database.inTransaction(connection ->
                        db.dayRepository.listByWeekAndPlayer(connection, "2026-W33", alice)
                                .stream().mapToLong(WeeklyActivityDayRow::activeSeconds).sum());
                assertEquals(1800, mondaySeconds,
                        "понедельничные минуты не должны попасть в прошлую неделю");
            } finally {
                WeekId.resetDate();
            }
        }
    }

    /** Блокер: кэш прогноза жив (TTL 30с), а игрок успел стать подходящим после его
     *  расчёта. Он отсутствует в кэшированном прогнозе — сервис не должен показывать
     *  «недостаточно очков», а должен пересчитать прогноз один раз. */
    @Test
    void eligiblePlayerMissingFromStaleForecastCacheGetsFreshForecast() {
        try (TestDb db = TestDb.create(fund(100, 100, 0, 0, ONE_TO_ONE))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedDay(db, alice, WeekId.current(), "1", 50); // пока недостаточно: 50 < 100

            // первый вызов строит кэш прогноза без Алисы (не проходит минимум)
            WeeklyFundService.WeeklyPlayerInfo before = db.weeklyFundService.playerWeekly(alice);
            assertFalse(before.eligible());
            assertEquals(WeeklyFundService.NotEligibleReason.MIN_ACTIVE_SECONDS, before.reason());

            // активность выросла до подходящей; кэш прогноза ещё жив (TTL 30с)
            seedDay(db, alice, WeekId.current(), "1", 200);
            WeeklyFundService.WeeklyPlayerInfo after = db.weeklyFundService.playerWeekly(alice);
            assertTrue(after.eligible(),
                    "подходящий игрок вне кэша должен получить свежий прогноз, а не «недостаточно очков»");
            assertEquals(100, after.projectedShare());
        }
    }

    /** Блокер: в период задержки автовыплаты «за прошлую неделю» читается замороженная
     *  доля из снимка периодов, а не нуль из ещё не записанной выплаты. */
    @Test
    void lastWeekAccrualReadsFrozenShareWhilePayoutIsDelayed() {
        try (TestDb db = TestDb.create(fundWithDelay(100, 6))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 100);

            // ротация закрыла неделю, но автовыплата ещё не наступила (задержка 6ч)
            assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());

            WeeklyFundService.WeeklyPlayerInfo info = db.weeklyFundService.playerWeekly(alice);
            assertEquals(100, info.lastWeekAccrued(),
                    "замороженная доля должна читаться из закрытого периода");
            assertTrue(info.lastWeekAutoPayoutAt() > System.currentTimeMillis(),
                    "момент автовыплаты должен быть в будущем");

            // после реальной выплаты «за прошлую неделю» читается из выплат
            db.weeklyFundService.runNow();
            assertEquals(100, db.accountService.getBalance(alice));
            WeeklyFundService.WeeklyPlayerInfo paid = db.weeklyFundService.playerWeekly(alice);
            assertEquals(100, paid.lastWeekAccrued());
        }
    }

    /**
     * Блокер: если перевод остатка в казну не удался, период не должен закрываться —
     * иначе остаток исчезнет из учёта. Неделя остаётся открытой и повторяется после
     * устранения причины.
     */
    @Test
    void treasuryRemainderFailureKeepsPeriodOpenAndRetries() {
        long max = EconomySettings.defaults().maximumBalance;
        try (TestDb db = TestDb.create(fund(100, 0, 0, 0, ONE_TO_ONE, true, 30))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            seedWeekly(db, alice, 60);
            seedWeekly(db, bob, 60);
            seedWeekly(db, carol, 60);
            // лимит доли 30%: каждый получает 30, остаток 10 в казну уже не влезет (казну заполнили)
            db.accountService.deposit(TreasuryService.TREASURY_UUID, max,
                    TransactionContext.of(TransactionType.ADMIN_DEPOSIT, null, "test", "test:treasury:fill"));

            Map<UUID, Long> first = db.weeklyFundService.runNow();
            // игроки выплачены, но период НЕ закрыт: остаток казне не ушёл
            assertEquals(30, db.accountService.getBalance(alice));
            assertEquals(30, db.accountService.getBalance(bob));
            assertEquals(30, db.accountService.getBalance(carol));
            assertTrue(!db.weeklyFundService.status().weekDistributed(),
                    "при неудаче перевода остатка период должен остаться открытым");

            // освободить место в казне и повторить — остаток наконец уходит, период закрывается
            db.accountService.withdraw(TreasuryService.TREASURY_UUID, 10,
                    TransactionContext.of(TransactionType.ADMIN_WITHDRAW, null, "test", "test:treasury:free"));
            Map<UUID, Long> retry = db.weeklyFundService.runNow();
            assertTrue(retry.isEmpty(), "игроки уже выплачены повторно");
            // казна: max − 10 (свободное место) + 10 (остаток) = max
            assertEquals(max, db.accountService.getBalance(TreasuryService.TREASURY_UUID));
            assertTrue(db.weeklyFundService.status().weekDistributed());
        }
    }

    /**
     * Блокер: аудит выплаты не дублируется после «краша» между депозитом и записью состояния.
     * Повторный запуск той же недели идёт по DUPLICATE-пути (тот же idempotency-ключ), но
     * стабильный dedupe-ключ аудита даёт ровно одно событие WEEKLY_PAYOUT на игрока.
     */
    @Test
    void payoutAuditNotDuplicatedAfterStateWriteCrashAndRetry() {
        try (TestDb db = TestDb.create(fund(400))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 300);
            String paidWeek = WeekId.previous(WeekId.current());

            Map<UUID, Long> first = db.weeklyFundService.maybeDistribute();
            assertEquals(1, first.size());
            assertEquals(1, payoutAuditCount(db, alice));

            // «Краш» между депозитом и записью состояния: период снова PENDING, план PLANNED,
            // распределённая неделя откатывается назад — очередь выплат снова видит эту неделю.
            db.database.inTransaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "UPDATE weekly_activity_periods SET status = 'PENDING', transaction_id = NULL, paid_at = NULL "
                                + "WHERE player_uuid = ?")) {
                    statement.setString(1, alice.toString());
                    statement.executeUpdate();
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            db.database.inTransaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "UPDATE weekly_fund_plans SET payout_status = 'PLANNED', paid_at = NULL "
                                + "WHERE week_id = ?")) {
                    statement.setString(1, paidWeek);
                    statement.executeUpdate();
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            markDistributed(db, WeekId.previous(paidWeek));

            // Повторный запуск той же недели: депозит возвращает DUPLICATE (тот же ключ),
            // состояние записывается, аудит — ровно одно событие на игрока.
            Map<UUID, Long> retry = db.weeklyFundService.runNow();
            assertEquals(1, retry.size());
            assertEquals(1, payoutAuditCount(db, alice),
                    "повтор выплаты после сбоя не должен дублировать аудит-событие");
            // единственный игрок получает весь фонд, повторный депозит не засчитан
            assertEquals(400, db.accountService.getBalance(alice));
        }
    }

    private static long payoutAuditCount(TestDb db, UUID player) {
        return db.auditService.byPlayer(player, 100).stream()
                .filter(r -> com.valorcraft.veconomy.audit.AuditEventType.WEEKLY_PAYOUT.equals(r.eventType()))
                .count();
    }
}
