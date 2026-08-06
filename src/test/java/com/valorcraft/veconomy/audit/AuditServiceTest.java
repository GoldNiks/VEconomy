package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.config.MilestoneConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditServiceTest {

    @Test
    void recordPersistsEventAndRecentIsNewestFirst() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO,
                    player, actor, 0L, "reason=тест");
            db.auditService.record(AuditEventType.MILESTONE_GRANTED, AuditSeverity.WARNING,
                    player, null, 2500L, "milestone=test");

            List<AuditEventRow> recent = db.auditService.recent(10);
            assertEquals(2, recent.size());
            AuditEventRow first = recent.get(0);
            assertEquals(AuditEventType.MILESTONE_GRANTED, first.eventType());
            assertEquals(AuditSeverity.WARNING, first.severity());
            assertEquals(player, first.playerId());
            assertNull(first.actorId());
            assertEquals(2500L, first.amountMinor());
            assertEquals("milestone=test", first.details());
            assertTrue(first.id() > 0, "id должен быть назначен базой");
        }
    }

    @Test
    void byPlayerFiltersOnlyThatPlayer() {
        try (TestDb db = TestDb.create()) {
            UUID p1 = UUID.randomUUID();
            UUID p2 = UUID.randomUUID();
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO, p1, null, null, "a");
            db.auditService.record(AuditEventType.ACCOUNT_UNFROZEN, AuditSeverity.INFO, p2, null, null, "b");
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO, p1, null, null, "c");

            List<AuditEventRow> rows = db.auditService.byPlayer(p1, 10);
            assertEquals(2, rows.size());
            assertTrue(rows.stream().allMatch(r -> p1.equals(r.playerId())));
        }
    }

    @Test
    void signalsReturnsOnlySuspicious() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO, player, null, null, "a");
            db.auditService.record(AuditEventType.SIGNAL_TRANSFER_SPAM, AuditSeverity.SUSPICIOUS,
                    player, null, null, "transfers=12");

            List<AuditEventRow> signals = db.auditService.signals(10);
            assertEquals(1, signals.size());
            assertEquals(AuditEventType.SIGNAL_TRANSFER_SPAM, signals.get(0).eventType());
            assertEquals(AuditSeverity.SUSPICIOUS, signals.get(0).severity());
        }
    }

    @Test
    void countReflectsRows() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO, player, null, null, "a");
            db.auditService.record(AuditEventType.ACCOUNT_UNFROZEN, AuditSeverity.INFO, player, null, null, "b");
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO, player, null, null, "c");
            assertEquals(3, db.auditService.count());
        }
    }

    @Test
    void freezeAndUnfreezeWriteAuditEvents() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.accountService.deposit(player, 500,
                    com.valorcraft.veconomy.api.TransactionContext.of(
                            com.valorcraft.veconomy.api.TransactionType.ADMIN_DEPOSIT, null, "старт"));

            assertTrue(db.accountService.freeze(player, "нарушение правил").isSuccess());
            assertTrue(db.accountService.unfreeze(player, "апелляция").isSuccess());

            List<AuditEventRow> rows = db.auditService.byPlayer(player, 10);
            assertEquals(3, rows.size(), "депозит, заморозка и разморозка — по одному событию");
            assertEquals(AuditEventType.ACCOUNT_UNFROZEN, rows.get(0).eventType());
            assertTrue(rows.get(0).details().contains("апелляция"));
            assertEquals(AuditEventType.ACCOUNT_FROZEN, rows.get(1).eventType());
            assertTrue(rows.get(1).details().contains("нарушение правил"));
            assertEquals(AuditEventType.ADMIN_BALANCE_CHANGE, rows.get(2).eventType(),
                    "админ-депозит фиксируется событием изменения баланса");
            assertTrue(rows.get(2).details().contains("op=ADD"));
        }
    }

    @Test
    void failedFreezeWritesNothing() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            assertFalse(db.accountService.freeze(player, "тест").isSuccess());
            assertTrue(db.auditService.byPlayer(player, 10).isEmpty(),
                    "событие аудита не должно писаться без подтверждённой смены статуса");
        }
    }

    @Test
    void exclusionChangeWritesAuditEvent() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            assertTrue(db.activityService.setExcludedFromRewards(player, true).isSuccess());
            assertTrue(db.activityService.setExcludedFromRewards(player, false).isSuccess());

            List<AuditEventRow> rows = db.auditService.byPlayer(player, 10);
            assertEquals(2, rows.size());
            assertEquals(AuditEventType.EXCLUSION_CHANGED, rows.get(0).eventType());
            assertTrue(rows.get(0).details().contains("excluded=false"));
            assertTrue(rows.get(1).details().contains("excluded=true"));
        }
    }

    @Test
    void failedExclusionChangeWritesNoAudit() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.database.close();

            var result = db.activityService.setExcludedFromRewards(player, true);
            assertEquals(com.valorcraft.veconomy.activity.AccountFlagUpdateResult.Status.DATABASE_ERROR,
                    result.status());
            db.database.open(db.database.path(), db.settings);
            assertTrue(db.auditService.byPlayer(player, 10).isEmpty(),
                    "событие аудита не должно писаться без подтверждённого изменения флага");
        }
    }

    @Test
    void activityInfoExclusionUnknownDoesNotFailOpen() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.database.close();

            assertEquals(com.valorcraft.veconomy.activity.RewardExclusionStatus.UNKNOWN,
                    db.activityService.excludedFromRewards(player));
        }
    }

    @Test
    void milestoneGrantWritesAuditEvent() throws IOException {
        Path dir = Files.createTempDirectory("veconomy-milestones");
        Files.writeString(dir.resolve(MilestoneConfig.FILE_NAME),
                """
                {"milestones": [
                  {"id":"event_bonus","type":"EXTERNAL","amount":1000,"enabled":true,
                   "requirements":{"channel":"events"}}
                ]}
                """, StandardCharsets.UTF_8);
        try (TestDb db = TestDb.create()) {
            MilestoneConfig.load(dir, db.settings.maximumBalance);
            db.milestoneService.applySettings(db.settings);

            UUID player = UUID.randomUUID();
            com.valorcraft.veconomy.activity.MilestoneService.MilestoneGrantResult result =
                    db.milestoneService.grantExternal(player, "event_bonus", "key-1");
            assertEquals(com.valorcraft.veconomy.activity.MilestoneService.MilestoneGrantResult.Status.GRANTED,
                    result.status());

            List<AuditEventRow> rows = db.auditService.byPlayer(player, 10);
            assertEquals(1, rows.size());
            assertEquals(AuditEventType.MILESTONE_GRANTED, rows.get(0).eventType());
            assertEquals(1000L, rows.get(0).amountMinor());
            assertTrue(rows.get(0).details().contains("event_bonus"));
        }
    }

    @Test
    void recordAfterDatabaseClosedDoesNotThrow() {
        UUID player = UUID.randomUUID();
        TestDb db = TestDb.create();
        db.close();
        // Запись аудита — «лучший усилия»: ошибки только логируются, исключение не летит.
        db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO, player, null, null, "x");
    }

    @Test
    void actorTypeAttributionTracksPlayerAndConsole() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            db.accountService.deposit(player, 500,
                    com.valorcraft.veconomy.api.TransactionContext.of(
                            com.valorcraft.veconomy.api.TransactionType.ADMIN_DEPOSIT, null, "старт"));

            assertTrue(db.accountService.freeze(player, "нарушение", actor).isSuccess());
            db.auditService.record(AuditEventType.ACCOUNT_UNFROZEN, AuditSeverity.INFO,
                    player, null, null, "консоль");

            List<AuditEventRow> rows = db.auditService.byPlayer(player, 10);
            // Новые сверху: последним записан UNFROZEN (консоль), раньше — FROZEN (игрок),
            // ещё раньше — админ-депозит (консоль).
            assertEquals(AuditActorType.CONSOLE, rows.get(0).actorType());
            assertNull(rows.get(0).actorId());
            assertEquals(AuditActorType.PLAYER, rows.get(1).actorType());
            assertEquals(actor, rows.get(1).actorId());
            assertEquals(AuditActorType.CONSOLE, rows.get(2).actorType());
            assertEquals(AuditEventType.ADMIN_BALANCE_CHANGE, rows.get(2).eventType());
        }
    }

    @Test
    void resolveAndDismissDriveLifecycle() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.auditService.record(AuditEventType.SIGNAL_TRANSFER_SPAM, AuditSeverity.SUSPICIOUS,
                    player, null, null, "transfers=50");
            AuditEventRow signal = db.auditService.signals(10).get(0);
            assertTrue(signal.open());

            assertTrue(db.auditService.resolve(signal.id(), ResolutionStatus.RESOLVED,
                    "admin", "проверено"));
            AuditEventRow resolved = db.auditService.event(signal.id()).orElseThrow();
            assertEquals(ResolutionStatus.RESOLVED.name(), resolved.status());
            assertEquals("admin", resolved.resolvedBy());
            assertTrue(resolved.resolvedAt() > 0);
            assertTrue(db.auditService.openSignals(10).isEmpty());
            assertEquals(1, db.auditService.countByStatus(ResolutionStatus.RESOLVED));

            assertFalse(db.auditService.resolve(signal.id(), ResolutionStatus.DISMISSED,
                            "admin", "ложное срабатывание"),
                    "уже обработанный сигнал нельзя изменить повторно (только OPEN)");

            db.auditService.record(AuditEventType.SIGNAL_ROUNDTRIP, AuditSeverity.SUSPICIOUS,
                    player, null, null, "круг");
            AuditEventRow second = db.auditService.signals(10).stream()
                    .filter(AuditEventRow::open)
                    .findFirst().orElseThrow();
            assertTrue(db.auditService.resolve(second.id(), ResolutionStatus.DISMISSED,
                    "admin", "ложное срабатывание"));
            AuditEventRow dismissed = db.auditService.event(second.id()).orElseThrow();
            assertEquals(ResolutionStatus.DISMISSED.name(), dismissed.status());
            assertEquals(1, db.auditService.countByStatus(ResolutionStatus.DISMISSED));

            assertFalse(db.auditService.resolve(999_999L, ResolutionStatus.DISMISSED, "admin", null),
                    "несуществующее событие нельзя обработать");
        }
    }

    @Test
    void idempotentInsertDoesNotDuplicateRows() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            AuditEventRow row = AuditEventRow.newEvent(AuditEventType.ACCOUNT_FROZEN,
                    AuditSeverity.INFO, player, null, AuditActorType.CONSOLE, 0L, "ключ-тест");
            AuditRepository repository = new AuditRepository();
            db.database.inTransaction(connection -> {
                repository.insert(connection, db.database.dialect(), row);
                return null;
            });
            long secondInsertId = db.database.inTransaction(connection ->
                    repository.insert(connection, db.database.dialect(), row).id());

            assertEquals(-1L, secondInsertId, "повторная вставка с тем же ключом не создаёт строки");
            assertEquals(AuditRepository.InsertResult.Status.DUPLICATE,
                    db.database.inTransaction(connection ->
                            repository.insert(connection, db.database.dialect(), row).status()),
                    "повторная вставка должна быть распознана как дубликат");
            assertEquals(1, db.auditService.count());
        }
    }

    @Test
    void failedWriteIsQueuedAndFlushedAfterRecovery() {
        TestDb db = TestDb.create();
        try {
            UUID player = UUID.randomUUID();
            db.database.close();

            AuditService.AuditWriteResult result = db.auditService.record(
                    AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO, player, null, null, "x");
            assertFalse(result.written());
            AuditService.AuditHealth health = db.auditService.health();
            assertEquals(1, health.failedWrites(), "сбой записи должен фиксироваться");
            assertEquals(1, health.pendingRetries(), "событие не должно быть потеряно");

            db.database.open(db.database.path(), db.settings);
            int flushed = db.auditService.flushPending();
            assertEquals(1, flushed);
            assertEquals(0, db.auditService.health().pendingRetries());
            assertEquals(1, db.auditService.count(), "отложенное событие должно записаться после восстановления");
        } finally {
            db.close();
        }
    }

    @Test
    void adminBalanceChangeRecordsAddRemoveSet() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            db.accountService.deposit(player, 100, com.valorcraft.veconomy.api.TransactionContext.of(
                    com.valorcraft.veconomy.api.TransactionType.ADMIN_DEPOSIT, actor, "пополнение"));
            db.accountService.withdraw(player, 30, com.valorcraft.veconomy.api.TransactionContext.of(
                    com.valorcraft.veconomy.api.TransactionType.ADMIN_WITHDRAW, actor, "списание"));
            db.accountService.setBalance(player, 120, com.valorcraft.veconomy.api.TransactionContext.of(
                    com.valorcraft.veconomy.api.TransactionType.ADMIN_SET_ADJUSTMENT, actor, "установка"));

            List<AuditEventRow> changes = db.auditService.byPlayer(player, 10).stream()
                    .filter(r -> AuditEventType.ADMIN_BALANCE_CHANGE.equals(r.eventType()))
                    .toList();
            assertEquals(3, changes.size());
            // Новые сверху: SET, REMOVE, ADD.
            AuditEventRow set = changes.get(0);
            assertEquals(50L, set.amountMinor(), "сумма события — дельта баланса");
            assertEquals(actor, set.actorId());
            assertEquals(AuditActorType.PLAYER, set.actorType());
            assertTrue(set.details().contains("op=SET"), set.details());
            assertTrue(set.details().contains("old=70"), set.details());
            assertTrue(set.details().contains("new=120"), set.details());
            assertTrue(set.details().contains("delta=50"), set.details());
            assertTrue(set.details().contains("установка"));
            assertTrue(changes.get(1).details().contains("op=REMOVE"), changes.get(1).details());
            assertTrue(changes.get(1).details().contains("delta=30"));
            assertTrue(changes.get(2).details().contains("op=ADD"), changes.get(2).details());
            assertTrue(changes.get(2).details().contains("delta=100"));
        }
    }

    @Test
    void adminBalanceSetWithoutChangeWritesNothing() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.accountService.deposit(player, 100, com.valorcraft.veconomy.api.TransactionContext.of(
                    com.valorcraft.veconomy.api.TransactionType.ADMIN_DEPOSIT, null, "старт"));
            long before = db.auditService.count();

            var result = db.accountService.setBalance(player, 100,
                    com.valorcraft.veconomy.api.TransactionContext.of(
                            com.valorcraft.veconomy.api.TransactionType.ADMIN_SET_ADJUSTMENT, null, "без изменений"));
            assertTrue(result.isSuccess());
            assertEquals(before, db.auditService.count(),
                    "set на тот же баланс не должен плодить событие");
        }
    }

    @Test
    void failedWithdrawWritesNoAdminChange() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.accountService.deposit(player, 100, com.valorcraft.veconomy.api.TransactionContext.of(
                    com.valorcraft.veconomy.api.TransactionType.ADMIN_DEPOSIT, null, "старт"));

            assertFalse(db.accountService.withdraw(player, 200,
                    com.valorcraft.veconomy.api.TransactionContext.of(
                            com.valorcraft.veconomy.api.TransactionType.ADMIN_WITHDRAW, null, "слишком много"))
                    .isSuccess());
            List<AuditEventRow> changes = db.auditService.byPlayer(player, 10).stream()
                    .filter(r -> AuditEventType.ADMIN_BALANCE_CHANGE.equals(r.eventType()))
                    .toList();
            assertEquals(1, changes.size(), "неуспешное списание не пишется в аудит");
        }
    }

    @Test
    void nonAdminDepositWritesNoAdminChange() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.accountService.deposit(player, 100, com.valorcraft.veconomy.api.TransactionContext.of(
                    com.valorcraft.veconomy.api.TransactionType.MILESTONE_REWARD, null, "веха"));
            assertTrue(db.auditService.byPlayer(player, 10).isEmpty(),
                    "не-админские операции не пишут ADMIN_BALANCE_CHANGE");
        }
    }

    @Test
    void exclusionNoChangeWritesNoAudit() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            assertTrue(db.activityService.setExcludedFromRewards(player, true).isSuccess());
            assertEquals(com.valorcraft.veconomy.activity.AccountFlagUpdateResult.Status.NO_CHANGES,
                    db.activityService.setExcludedFromRewards(player, true).status());
            assertEquals(1, db.auditService.byPlayer(player, 10).size(),
                    "повторное исключение без изменения флага не пишет событие");
        }
    }

    @Test
    void exclusionUnknownPlayerIsNotFound() {
        try (TestDb db = TestDb.create()) {
            assertEquals(com.valorcraft.veconomy.activity.AccountFlagUpdateResult.Status.PLAYER_NOT_FOUND,
                    db.activityService.setExcludedFromRewards(null, true).status());
        }
    }

    @Test
    void nullAmountMinorReadsBackAsNull() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO,
                    player, null, null, "без суммы");
            AuditEventRow row = db.auditService.recent(10).get(0);
            assertNull(row.amountMinor(), "NULL-сумма должна читаться как NULL, а не 0");
        }
    }

    @Test
    void pruneRemovesOnlyEventsOlderThanRetention() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            db.auditService.record(AuditEventType.ACCOUNT_FROZEN, AuditSeverity.INFO,
                    player, null, null, "старое");
            db.auditService.record(AuditEventType.ACCOUNT_UNFROZEN, AuditSeverity.INFO,
                    player, null, null, "свежее");
            // состарить первое событие на двое суток назад
            db.database.inTransaction(connection -> {
                try (var statement = connection.prepareStatement(
                        "UPDATE audit_events SET created_at = ? WHERE event_type = ?")) {
                    statement.setLong(1, System.currentTimeMillis() - 2 * 86_400_000L);
                    statement.setString(2, AuditEventType.ACCOUNT_FROZEN);
                    statement.executeUpdate();
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });

            long removed = db.auditService.prune(1);
            assertEquals(1, removed, "старее суток — только старое событие");
            assertEquals(1, db.auditService.count());
            assertEquals(AuditEventType.ACCOUNT_UNFROZEN, db.auditService.recent(10).get(0).eventType());
        }
    }

    @Test
    void signalDedupeKeyRejectsDuplicateInsert() {
        try (TestDb db = TestDb.create()) {
            UUID player = UUID.randomUUID();
            AuditEventRow signal = AuditEventRow.signal(AuditEventType.SIGNAL_TRANSFER_SPAM,
                    player, null, null, "transfers=3", "spam|" + player + "|1");
            AuditRepository repository = new AuditRepository();
            db.database.inTransaction(connection -> {
                repository.insert(connection, db.database.dialect(), signal);
                return null;
            });
            AuditRepository.InsertResult second = db.database.inTransaction(connection ->
                    repository.insert(connection, db.database.dialect(), signal));

            assertEquals(AuditRepository.InsertResult.Status.DUPLICATE, second.status(),
                    "повторная вставка того же окна распознаётся как дубликат");
            assertEquals(1, db.auditService.count());
        }
    }

    @Test
    void asynchronousScanCompletesAndDeliversOutcome() throws InterruptedException {
        try (TestDb db = TestDb.create()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<SuspicionScanner.ScanSummary> received = new AtomicReference<>();
            AuditService.ScanAccept accept = db.auditService.scanAllAsync(new AuditService.ScanOutcome() {
                @Override
                public void completed(SuspicionScanner.ScanSummary summary) {
                    received.set(summary);
                    done.countDown();
                }

                @Override
                public void failed(String error) {
                    done.countDown();
                }
            });
            assertEquals(AuditService.ScanAccept.ACCEPTED, accept);
            assertTrue(done.await(10, TimeUnit.SECONDS), "фоновый скан должен завершиться");
            assertNotNull(received.get(), "успешный скан доставляет сводку");
        }
    }

    @Test
    void asynchronousScanWhileRunningReportsBusy() throws InterruptedException {
        try (TestDb db = TestDb.create()) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AuditService.ScanOutcome noop = new AuditService.ScanOutcome() {
                @Override
                public void completed(SuspicionScanner.ScanSummary summary) {
                }

                @Override
                public void failed(String error) {
                }
            };
            assertEquals(AuditService.ScanAccept.ACCEPTED,
                    db.auditService.submitScan(() -> {
                        started.countDown();
                        try {
                            release.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return SuspicionScanner.ScanSummary.zero();
                    }, noop));
            assertTrue(started.await(10, TimeUnit.SECONDS), "первый скан должен стартовать");
            assertEquals(AuditService.ScanAccept.BUSY,
                    db.auditService.scanAllAsync(noop),
                    "пока идёт скан, повторный запрос отвечает BUSY, а не блокирует и не дублирует");
            release.countDown();
            awaitTerminal(db.auditService);
        }
    }

    @Test
    void asynchronousScanPopUpErrorDelivered() throws InterruptedException {
        try (TestDb db = TestDb.create()) {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>();
            db.auditService.submitScan(() -> {
                throw new RuntimeException("boom");
            }, new AuditService.ScanOutcome() {
                @Override
                public void completed(SuspicionScanner.ScanSummary summary) {
                    done.countDown();
                }

                @Override
                public void failed(String error) {
                    failure.set(error);
                    done.countDown();
                }
            });
            assertTrue(done.await(10, TimeUnit.SECONDS), "сбойный скан должен завершиться");
            assertNotNull(failure.get(), "сбой доставляется как ошибка");
        }
    }

    private static void awaitTerminal(AuditService service) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (service.scanPhase() != AuditService.ScanPhase.IDLE) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("скан не вернулся в IDLE");
            }
            Thread.sleep(20);
        }
    }
}
