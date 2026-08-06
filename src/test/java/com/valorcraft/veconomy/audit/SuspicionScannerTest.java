package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.AuditConfig;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.economy.TreasuryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SuspicionScannerTest {

    private Path configDir;

    @BeforeEach
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("veconomy-audit-test");
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(configDir.resolve(AuditConfig.FILE_NAME));
        // Без файла load() создаёт шаблон и возвращает значения по умолчанию.
        AuditConfig.load(configDir);
    }

    /** Настройки без кулдауна переводов: эвристики работают на серии переводов подряд. */
    private static EconomySettings noCooldown() {
        EconomySettings d = EconomySettings.defaults();
        return new EconomySettings(
                d.currencyNameSingular, d.currencyNameFew, d.currencyNameMany,
                d.currencySymbol, d.decimalPlaces, d.maximumBalance,
                d.transfersEnabled, d.allowOfflineRecipients,
                d.minimumTransferAmount, d.maximumTransferAmount, 0,
                d.dbType, d.databaseFile, d.busyTimeoutMillis, d.walEnabled,
                d.mysqlHost, d.mysqlPort, d.mysqlDatabase,
                d.mysqlUser, d.mysqlPassword, d.mysqlPoolSize,
                d.broadcastAdminChanges);
    }

    private static String config(int spamCount, int roundTripExchanges, long oversizedAmount,
                                 long newAccountTransferAmount) {
        return "{\"signals\":{\"enabled\":true,\"windowMinutes\":1440,"
                + "\"transferSpamCount\":" + spamCount + ","
                + "\"roundTripExchanges\":" + roundTripExchanges + ","
                + "\"oversizedTransferAmount\":" + oversizedAmount + ","
                + "\"newAccountDays\":3650,"
                + "\"newAccountTransferAmount\":" + newAccountTransferAmount + "}}";
    }

    private void writeConfig(String json) throws IOException {
        Files.writeString(configDir.resolve(AuditConfig.FILE_NAME), json, StandardCharsets.UTF_8);
        AuditConfig.load(configDir);
    }

    private static void fund(TestDb db, UUID player, long amount) {
        TransactionResult result = db.accountService.deposit(player, amount,
                TransactionContext.of(TransactionType.ADMIN_DEPOSIT, null, "старт"));
        assertTrue(result.isSuccess(), "депозит должен пройти: " + result.status());
    }

    private static void transfer(TestDb db, UUID from, UUID to, long amount) {
        TransactionResult result = db.transferService.transfer(from, to, amount,
                TransactionContext.of(TransactionType.PLAYER_TRANSFER, null, "тест"));
        assertTrue(result.isSuccess(), "перевод должен пройти: " + result.status());
    }

    private static List<AuditEventRow> signals(TestDb db) {
        return db.auditService.signals(1000);
    }

    private static boolean hasSignal(TestDb db, String type, UUID player) {
        return signals(db).stream().anyMatch(s -> type.equals(s.eventType()) && player.equals(s.playerId()));
    }

    /** Состарить аккаунт, чтобы он перестал считаться «свежим» (созданным недавно). */
    private static void ageAccount(TestDb db, UUID player, long days) {
        db.database.inTransaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE accounts SET created_at = ? WHERE player_uuid = ?")) {
                statement.setLong(1, System.currentTimeMillis() - days * 86_400_000L);
                statement.setString(2, player.toString());
                statement.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
    }

    @Test
    void transferSpamSignalsAllParticipants() throws IOException {
        writeConfig(config(2, 100_000, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, alice, bob, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(2, summary.spamSignals());
            assertEquals(2, summary.total());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_SPAM, alice));
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_SPAM, bob));
        }
    }

    @Test
    void belowSpamThresholdWritesNothing() throws IOException {
        writeConfig(config(3, 100_000, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, alice, bob, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(0, summary.total());
            assertTrue(signals(db).isEmpty());
        }
    }

    @Test
    void roundTripSignalsPairWithBothDirections() throws IOException {
        writeConfig(config(100_000, 2, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, bob, alice, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.roundTripSignals());
            assertEquals(1, summary.total());
            // Сигнал пишется на один из участников пары (детерминированно по сортированному ключу
            // пары); у кого именно — зависит от порядка пары, поэтому проверяем пару в деталях.
            List<AuditEventRow> roundTrips = signals(db).stream()
                    .filter(s -> AuditEventType.SIGNAL_ROUNDTRIP.equals(s.eventType()))
                    .toList();
            assertEquals(1, roundTrips.size());
            AuditEventRow signal = roundTrips.get(0);
            assertTrue(alice.equals(signal.playerId()) || bob.equals(signal.playerId()));
            String partner = alice.equals(signal.playerId()) ? bob.toString() : alice.toString();
            assertTrue(signal.details().contains(partner),
                    "детали должны содержать второго участника пары: " + signal.details());
        }
    }

    @Test
    void oneDirectionalFlowsAreNotRoundTrips() throws IOException {
        writeConfig(config(100_000, 2, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, alice, bob, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(0, summary.roundTripSignals());
        }
    }

    @Test
    void oversizedSignalsRecipientOnly() throws IOException {
        writeConfig(config(100_000, 100_000, 1000, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 500);   // ниже порога
            transfer(db, alice, bob, 1500);  // выше порога

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.oversizedSignals());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_OVERSIZED, bob));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_OVERSIZED, alice));
        }
    }

    @Test
    void newAccountSignalsFreshSideOnly() throws IOException {
        writeConfig(config(100_000, 100_000, 1_000_000_000L, 500));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            ageAccount(db, alice, 4000);   // старше окна newAccountDays=3650 — не «свежий»
            transfer(db, alice, bob, 700);   // свежая сторона — bob (получатель)
            transfer(db, bob, carol, 600);   // свежая сторона — bob (отправитель)

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.newAccountSignals());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, bob));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, alice));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, carol));
        }
    }

    @Test
    void treasuryIsNeverAFreshSide() throws IOException {
        writeConfig(config(100_000, 100_000, 1_000_000_000L, 500));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, TreasuryService.TREASURY_UUID, 700);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.newAccountSignals());
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, TreasuryService.TREASURY_UUID),
                    "казна не должна считаться свежим аккаунтом");
        }
    }

    @Test
    void repeatedScanDoesNotDuplicateSignals() throws IOException {
        writeConfig(config(2, 100_000, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, alice, bob, 100);

            SuspicionScanner.ScanSummary first = db.auditService.scanAll();
            assertEquals(2, first.total());
            long before = db.auditService.count();

            SuspicionScanner.ScanSummary second = db.auditService.scanAll();
            assertEquals(0, second.total());
            assertEquals(before, db.auditService.count());
            SuspicionScanner.ScanSummary playerScan = db.auditService.scanPlayer(alice);
            assertEquals(0, playerScan.total());
        }
    }

    @Test
    void scanPlayerScopesToThatPlayer() throws IOException {
        writeConfig(config(2, 100_000, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, alice, bob, 100);
            transfer(db, bob, carol, 100);
            transfer(db, bob, carol, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanPlayer(alice);
            assertEquals(1, summary.spamSignals(), "спам: только alice в скоупе");
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_SPAM, alice));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_SPAM, bob));
        }
    }

    @Test
    void disabledConfigWritesNothing() throws IOException {
        writeConfig("{\"signals\":{\"enabled\":false,\"windowMinutes\":30,\"transferSpamCount\":1,"
                + "\"roundTripExchanges\":1,\"oversizedTransferAmount\":1,"
                + "\"newAccountDays\":7,\"newAccountTransferAmount\":1}}");
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 1);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(0, summary.total());
            assertTrue(signals(db).isEmpty());
        }
    }
}
