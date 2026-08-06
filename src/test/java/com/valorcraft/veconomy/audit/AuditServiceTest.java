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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals(2, rows.size());
            assertEquals(AuditEventType.ACCOUNT_UNFROZEN, rows.get(0).eventType());
            assertTrue(rows.get(0).details().contains("апелляция"));
            assertEquals(AuditEventType.ACCOUNT_FROZEN, rows.get(1).eventType());
            assertTrue(rows.get(1).details().contains("нарушение правил"));
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
            assertTrue(db.activityService.setExcludedFromRewards(player, true));
            assertFalse(db.activityService.setExcludedFromRewards(player, false));

            List<AuditEventRow> rows = db.auditService.byPlayer(player, 10);
            assertEquals(2, rows.size());
            assertEquals(AuditEventType.EXCLUSION_CHANGED, rows.get(0).eventType());
            assertTrue(rows.get(0).details().contains("excluded=false"));
            assertTrue(rows.get(1).details().contains("excluded=true"));
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
}
