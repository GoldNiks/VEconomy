package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.WeeklyFund.PointLevel;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.MetaRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyFundServiceTest {

    private static final String DISTRIBUTED_WEEK_KEY = "weekly_fund.distributed_week";

    private static void seedWeekly(TestDb db, UUID player, long seconds) {
        seedWeekly(db, player, seconds, false, 1L);
    }

    private static void seedWeekly(TestDb db, UUID player, long seconds, boolean excluded) {
        seedWeekly(db, player, seconds, excluded, 1L);
    }

    private static void seedWeekly(TestDb db, UUID player, long seconds, boolean excluded, long firstSeen) {
        db.database.inTransaction(connection -> {
            db.activityRepository.upsert(connection, DatabaseManager.Dialect.SQLITE,
                    new PlayerActivityRow(player, firstSeen, firstSeen, seconds, seconds, 0,
                            WeekId.current(), seconds, 1L, "minecraft:overworld", excluded));
            return null;
        });
    }

    /**
     * Смоделировать положение после завершения позапрошлой недели: распределена позапрошлая,
     * накопленная активность в {@code weekly_active_seconds} принадлежит прошлой неделе и при
     * ротации будет сохранена снимком для {@code previous(current)}.
     */
    private static void markSnapshotDue(TestDb db) {
        db.database.inTransaction(connection -> {
            MetaRepository.set(connection, DatabaseManager.Dialect.SQLITE,
                    DISTRIBUTED_WEEK_KEY, WeekId.previous(WeekId.previous(WeekId.current())));
            return null;
        });
    }

    /** Фонд без ограничений, автозапуск включён. */
    private static EconomySettings fund(long weeklyAmount) {
        return fund(weeklyAmount, 0, 0, 0, List.of());
    }

    private static EconomySettings fund(long weeklyAmount, long minActive, long maxCounted,
                                        long minAccountAgeDays, List<PointLevel> levels) {
        return fund(weeklyAmount, minActive, maxCounted, minAccountAgeDays, levels, true);
    }

    private static EconomySettings fund(long weeklyAmount, long minActive, long maxCounted,
                                        long minAccountAgeDays, List<PointLevel> levels, boolean autoRun) {
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
                new EconomySettings.WeeklyFund(true, weeklyAmount, true, autoRun,
                        minAccountAgeDays, minActive, maxCounted, levels));
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
    void distributesWhenTotalActivityExceedsFund() {
        // Критический случай: totalActive > fund. Старая формула perSecond=fund/total дала бы 0.
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
        // floor(100*60/70)=85, floor(100*10/70)=14 → остаток 1 в казну
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 60);
            seedWeekly(db, bob, 10);

            db.weeklyFundService.maybeDistribute();
            assertEquals(85, db.accountService.getBalance(alice));
            assertEquals(14, db.accountService.getBalance(bob));
            assertEquals(1, db.accountService.getBalance(TreasuryService.TREASURY_UUID));
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

            // повторный вызов в той же неделе — тоже без выплаты
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
                UUID alice = java.util.UUID.randomUUID();
                seedWeekly(db, alice, 100);

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
        try (TestDb db = TestDb.create(fund(100, 100, 0, 0, List.of()))) {
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
    void respectsMaxCountedSeconds() {
        // потолок 100 секунд: у алисы учитывается только 100 → оба получают поровну
        try (TestDb db = TestDb.create(fund(100, 0, 100, 0, List.of()))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 300);
            seedWeekly(db, bob, 100);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(50L, payments.get(alice));
            assertEquals(50L, payments.get(bob));
        }
    }

    @Test
    void pointLevelsDriveTheSplit() {
        // уровни: 100с → 1 очко, 200с → ещё 2 очка. Алиса: 3 очка, Боб: 1 очко.
        try (TestDb db = TestDb.create(fund(100, 0, 0, 0,
                List.of(new PointLevel(100, 1), new PointLevel(200, 2))))) {
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
        try (TestDb db = TestDb.create(fund(100, 0, 0, 7, List.of()))) {
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
        try (TestDb db = TestDb.create(EconomySettings.defaults())) {
            // defaults: autoRun=false, фонд включён, минимум активности 1ч (3600с)
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 4000);

            assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());
            Map<UUID, Long> payments = db.weeklyFundService.runNow();
            assertEquals(1, payments.size());
            assertEquals(100_000L, payments.get(alice));
        }
    }

    @Test
    void previewReportsSnapshotAllocationsWithoutPaying() {
        EconomySettings settings = fund(100, 0, 0, 0, List.of(), false);
        try (TestDb db = TestDb.create(settings)) {
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
            assertTrue(status.eligiblePlayers() == 1);
            assertTrue(status.totalShare() == 100);

            db.weeklyFundService.runNow();
            assertEquals(100, db.accountService.getBalance(alice));
        }
    }

    @Test
    void rotationSnapshotsPreviousWeekAndResetsAccumulator() {
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            seedWeekly(db, alice, 100);

            Map<UUID, Long> payments = db.weeklyFundService.maybeDistribute();
            assertEquals(100L, payments.get(alice));
            assertEquals(100, db.accountService.getBalance(alice));

            // накопитель недели сброшен после ротации
            long weeklyLeft = db.database.inTransaction(connection ->
                    db.activityRepository.find(connection, alice)
                            .map(PlayerActivityRow::weeklyActiveSeconds).orElse(-1L));
            assertEquals(0, weeklyLeft);

            // новая активность текущей недели не меняет снимок закрытой недели
            seedWeekly(db, alice, 40);
            List<WeeklyFundService.WeeklyAllocation> allocations = db.weeklyFundService.preview();
            assertEquals(1, allocations.size());
            assertEquals(100, allocations.get(0).countedSeconds());

            // неделя уже распределена — повторная выплата не происходит
            assertTrue(db.weeklyFundService.maybeDistribute().isEmpty());
            WeeklyFundService.WeeklyStatus status = db.weeklyFundService.status();
            assertTrue(status.weekDistributed());
        }
    }

    /** Вставить вручную снимок недели со строкой в статусе ожидания выплаты. */
    private static void seedPeriod(TestDb db, String weekId, UUID player, long seconds) {
        WeeklyPeriodRepository periods = new WeeklyPeriodRepository();
        db.database.inTransaction(connection -> {
            periods.insert(connection, DatabaseManager.Dialect.SQLITE, new WeeklyPeriodRow(
                    weekId, player, seconds, seconds, WeeklyPeriodRepository.STATUS_PENDING, 0, null));
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
            seedPeriod(db, w1, alice, 50);
            seedPeriod(db, w2, bob, 50);

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
            seedPeriod(db, w2, bob, 50);

            // первый runNow закрывает пустой W1 как закрытую пустую неделю и платит W2
            Map<UUID, Long> first = db.weeklyFundService.runNow();
            assertEquals(100L, first.get(bob));
            assertEquals(100, db.accountService.getBalance(bob));
            assertTrue(db.weeklyFundService.runNow().isEmpty());
        }
    }

    @Test
    void resumablePaymentContinuesAfterFailure() {
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 60);
            seedWeekly(db, bob, 40);
            db.accountService.createOrTouch(bob, "bob");
            db.accountService.freeze(bob, "тест");

            // первый запуск: алиса выплачена, замороженный боб — нет; период остаётся открытым
            Map<UUID, Long> first = db.weeklyFundService.runNow();
            assertEquals(1, first.size());
            assertEquals(60, db.accountService.getBalance(alice));
            assertEquals(0, db.accountService.getBalance(bob));
            assertTrue(!db.weeklyFundService.status().weekDistributed());

            // повторный запуск с замороженным бобом — выплаты нет, период всё ещё открыт
            assertTrue(db.weeklyFundService.runNow().isEmpty());
            assertTrue(!db.weeklyFundService.status().weekDistributed());

            // разморозили: повторный run доплачивает бобу и закрывает период
            db.accountService.unfreeze(bob, "тест");
            Map<UUID, Long> retry = db.weeklyFundService.runNow();
            assertEquals(1, retry.size());
            assertEquals(40L, retry.get(bob));
            assertEquals(60, db.accountService.getBalance(alice));
            assertEquals(40, db.accountService.getBalance(bob));
            // казне при возобновлении дублировать нечего: 60+40 = весь фонд
            assertEquals(0, db.accountService.getBalance(TreasuryService.TREASURY_UUID));
            assertTrue(db.weeklyFundService.status().weekDistributed());
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
        try (TestDb db = TestDb.create(fund(100))) {
            markSnapshotDue(db);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            seedWeekly(db, alice, 60);
            seedWeekly(db, bob, 10);
            // заполнить казну до предела: остаток 1 в казну уже не влезет (60+10=70 → остаток 1)
            db.accountService.deposit(TreasuryService.TREASURY_UUID, max,
                    com.valorcraft.veconomy.api.TransactionContext.of(
                            com.valorcraft.veconomy.api.TransactionType.ADMIN_DEPOSIT, null, "test", "test:treasury:fill"));

            Map<UUID, Long> first = db.weeklyFundService.runNow();
            // игроки выплачены, но период НЕ закрыт: остаток казне не ушёл
            assertEquals(85, db.accountService.getBalance(alice));
            assertEquals(14, db.accountService.getBalance(bob));
            assertTrue(!db.weeklyFundService.status().weekDistributed(),
                    "при неудаче перевода остатка период должен остаться открытым");

            // освободить место в казне и повторить — остаток наконец уходит, период закрывается
            db.accountService.withdraw(TreasuryService.TREASURY_UUID, 1,
                    com.valorcraft.veconomy.api.TransactionContext.of(
                            com.valorcraft.veconomy.api.TransactionType.ADMIN_WITHDRAW, null, "test", "test:treasury:free"));
            Map<UUID, Long> retry = db.weeklyFundService.runNow();
            assertTrue(retry.isEmpty(), "игроки уже выплачены повторно");
            // казна: max − 1 (свободное место) + 1 (остаток) = max
            assertEquals(max, db.accountService.getBalance(TreasuryService.TREASURY_UUID));
            assertTrue(db.weeklyFundService.status().weekDistributed());
        }
    }
}