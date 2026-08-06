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

    /** Конфиг с нейтральными (не срабатывающими) порогами + точечные override. */
    private static String tuned(String overrides) {
        return "{\"signals\":{\"enabled\":true,\"windowMinutes\":1440,"
                + "\"transferSpamCount\":100000,\"roundTripExchanges\":100000,"
                + "\"oversizedTransferAmount\":1000000000,\"newAccountDays\":3650,"
                + "\"newAccountTransferAmount\":1000000000,\"rapidForwardAmount\":1000000000,"
                + "\"rapidForwardWindowMinutes\":5,\"transferLoopLength\":100,"
                + "\"highPairFrequencyExchanges\":100000,\"newAccountConcentrationSources\":5000,"
                + "\"repeatedDestinationTransfers\":100000,"
                + overrides + "}}";
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
    void transferSpamSignalsOnlyOutgoingSender() throws IOException {
        writeConfig(config(2, 100_000, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, alice, bob, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.spamSignals(), "сигнал пишется только отправителю");
            assertEquals(1, summary.total());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_SPAM, alice));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_SPAM, bob),
                    "получатель не считается спамером по входящим переводам");
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
    void roundTripSignalsEachParticipantWithCommonIncident() throws IOException {
        writeConfig(config(100_000, 2, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, bob, alice, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(2, summary.roundTripSignals(), "событие пишется каждому участнику пары");
            assertEquals(2, summary.total());
            List<AuditEventRow> roundTrips = signals(db).stream()
                    .filter(s -> AuditEventType.SIGNAL_ROUNDTRIP.equals(s.eventType()))
                    .toList();
            assertEquals(2, roundTrips.size());
            assertTrue(roundTrips.stream().anyMatch(s -> alice.equals(s.playerId())));
            assertTrue(roundTrips.stream().anyMatch(s -> bob.equals(s.playerId())));
            AuditEventRow aliceEvent = roundTrips.stream().filter(s -> alice.equals(s.playerId()))
                    .findFirst().orElseThrow();
            AuditEventRow bobEvent = roundTrips.stream().filter(s -> bob.equals(s.playerId()))
                    .findFirst().orElseThrow();
            assertEquals(incidentOf(aliceEvent.details()), incidentOf(bobEvent.details()),
                    "у обоих участников один и тот же incident id");
            assertTrue(aliceEvent.details().contains(bob.toString()),
                    "детали должны содержать второго участника пары: " + aliceEvent.details());
            assertTrue(bobEvent.details().contains(alice.toString()),
                    "детали должны содержать второго участника пары: " + bobEvent.details());
        }
    }

    private static String incidentOf(String details) {
        return details.substring(details.indexOf("incident="));
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
    void oversizedSignalsSenderOnly() throws IOException {
        writeConfig(config(100_000, 100_000, 1000, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 500);   // ниже порога
            transfer(db, alice, bob, 1500);  // выше порога

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.oversizedSignals());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_OVERSIZED, alice),
                    "субъект сигнала — отправитель");
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_OVERSIZED, bob));
        }
    }

    @Test
    void newAccountSignalsEachFreshSide() throws IOException {
        writeConfig(config(100_000, 100_000, 1_000_000_000L, 500));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            ageAccount(db, alice, 4000);   // старше окна newAccountDays=3650 — не «свежий»
            transfer(db, alice, bob, 700);   // свежая сторона — bob (получатель)
            transfer(db, bob, carol, 600);   // свежие стороны — bob (отправитель) и carol (получатель)

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(2, summary.newAccountSignals(), "событие пишется каждой свежей стороне");
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, bob));
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, carol));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, alice));
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
            assertEquals(1, first.total(), "только исходящий спам отправителя");
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
    void roundTripEachParticipantSeenFromPlayerScan() throws IOException {
        writeConfig(config(100_000, 2, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, bob, alice, 100);

            // второй участник видит своё событие через scanPlayer (без полного сканирования)
            SuspicionScanner.ScanSummary summary = db.auditService.scanPlayer(bob);
            assertEquals(1, summary.roundTripSignals());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_ROUNDTRIP, bob));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_ROUNDTRIP, alice),
                    "scanPlayer пишет событие только сканируемому игроку");

            // повтор в том же окне не плодит событий
            assertEquals(0, db.auditService.scanPlayer(bob).total());
            assertEquals(1, db.auditService.signals(100).stream()
                    .filter(s -> AuditEventType.SIGNAL_ROUNDTRIP.equals(s.eventType()))
                    .count());
        }
    }

    @Test
    void roundTripPairsAreIndependent() throws IOException {
        writeConfig(config(100_000, 2, 1_000_000_000L, 1_000_000_000L));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            fund(db, carol, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, bob, alice, 100);
            transfer(db, alice, carol, 100);
            transfer(db, carol, alice, 100);

            // пары A–B и A–C независимы: у alice по одному событию на пару
            SuspicionScanner.ScanSummary summary = db.auditService.scanPlayer(alice);
            assertEquals(2, summary.roundTripSignals());
            assertEquals(2, db.auditService.signals(100).stream()
                    .filter(s -> AuditEventType.SIGNAL_ROUNDTRIP.equals(s.eventType())
                            && alice.equals(s.playerId()))
                    .count());
        }
    }

    @Test
    void newAccountBothSidesOfOneTransferWriteTwoEvents() throws IOException {
        writeConfig(config(100_000, 100_000, 1_000_000_000L, 500));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            transfer(db, alice, bob, 700); // обе стороны свежие

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(2, summary.newAccountSignals());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, alice));
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT, bob));
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

    @Test
    void rapidForwardingSignalsIntermediateNode() throws IOException {
        writeConfig(tuned("\"rapidForwardAmount\":500,\"rapidForwardWindowMinutes\":5"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            transfer(db, alice, bob, 1000);   // крупный перевод...
            transfer(db, bob, carol, 1000);   // ...тут же пересылается дальше

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.rapidForwardingSignals());
            assertEquals(1, summary.total());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_RAPID_FORWARDING, bob),
                    "сигнал должен писаться на промежуточный узел");
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_RAPID_FORWARDING, alice));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_RAPID_FORWARDING, carol));
        }
    }

    @Test
    void transferLoopSignalsAllCycleMembers() throws IOException {
        writeConfig(tuned("\"transferLoopLength\":3"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            fund(db, carol, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, bob, carol, 100);
            transfer(db, carol, alice, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(3, summary.transferLoopSignals());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_LOOP, alice));
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_LOOP, bob));
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_LOOP, carol));
        }
    }

    @Test
    void chainWithoutCycleIsNotALoop() throws IOException {
        writeConfig(tuned("\"transferLoopLength\":3"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, bob, carol, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(0, summary.transferLoopSignals());
            assertEquals(0, summary.total());
        }
    }

    @Test
    void scanPlayerSeesCycleThroughRestrictedGraph() throws IOException {
        // Полный граф A→B→C→A: при персональном скан(B) цикл обязан быть виден,
        // хотя собраны только локальные рёбра B и ограниченный граф участников.
        writeConfig(tuned("\"transferLoopLength\":3"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            fund(db, carol, 100_000);
            transfer(db, alice, bob, 100);
            transfer(db, bob, carol, 100);
            transfer(db, carol, alice, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanPlayer(bob);
            assertEquals(1, summary.transferLoopSignals(),
                    "при scanPlayer инцидент пишется только сканируемому игроку");
            assertEquals(1, summary.total());
            assertTrue(summary.limited(), "персональный граф помечается «ограничено»");
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_LOOP, bob));
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_LOOP, alice),
                    "сканируемый игрок не должен рисовать события остальным участникам");
            assertFalse(hasSignal(db, AuditEventType.SIGNAL_TRANSFER_LOOP, carol));
        }
    }

    @Test
    void transferLoopDetailsContainOrderedTxIdsAndAmounts() throws IOException {
        writeConfig(tuned("\"transferLoopLength\":3"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            fund(db, carol, 100_000);
            transfer(db, alice, bob, 111);
            transfer(db, bob, carol, 222);
            transfer(db, carol, alice, 333);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(3, summary.transferLoopSignals());
            List<AuditEventRow> loopRows = signals(db).stream()
                    .filter(s -> AuditEventType.SIGNAL_TRANSFER_LOOP.equals(s.eventType()))
                    .toList();
            assertEquals(3, loopRows.size());
            String details = loopRows.get(0).details();
            assertTrue(details.contains("participants=["), details);
            assertTrue(details.contains("txs=["), "инцидент должен нести упорядоченные txId: " + details);
            assertTrue(details.contains("111") && details.contains("222") && details.contains("333"),
                    "суммы рёбер цикла в инциденте: " + details);
            // Инцидент-суффикс ключа (после "|") у всех трёх участников один и тот же.
            String incidentPart = loopRows.get(0).dedupeKey()
                    .substring(loopRows.get(0).dedupeKey().lastIndexOf('|') + 1);
            assertTrue(loopRows.stream().allMatch(r -> r.dedupeKey().endsWith("|" + incidentPart)),
                    "события одного цикла дедуплицируются общим инцидент-суффиксом");
        }
    }

    @Test
    void distinctLoopsOfSamePlayerDoNotCollapse() throws IOException {
        writeConfig(tuned("\"transferLoopLength\":3"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            UUID dana = UUID.randomUUID();
            UUID eric = UUID.randomUUID();
            for (UUID p : new UUID[]{alice, bob, carol, dana, eric}) {
                fund(db, p, 100_000);
            }
            // Два независимых цикла, оба через Bob: A→B→C→A и D→B→E→D.
            transfer(db, alice, bob, 100);
            transfer(db, bob, carol, 100);
            transfer(db, carol, alice, 100);
            transfer(db, dana, bob, 100);
            transfer(db, bob, eric, 100);
            transfer(db, eric, dana, 100);

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            // Каждый участник каждого цикла получает событие: 3 + 3 = 6.
            assertEquals(6, summary.transferLoopSignals());
            List<AuditEventRow> bobEvents = signals(db).stream()
                    .filter(s -> AuditEventType.SIGNAL_TRANSFER_LOOP.equals(s.eventType()) && bob.equals(s.playerId()))
                    .toList();
            assertEquals(2, bobEvents.size(),
                    "bob участвует в двух разных циклах — два разных инцидента");
            assertFalse(bobEvents.get(0).dedupeKey().equals(bobEvents.get(1).dedupeKey()),
                    "циклы с разным составом txId не схлопываются в один");
        }
    }

    @Test
    void rapidForwardingPairCarriesBothTxIds() throws IOException {
        writeConfig(tuned("\"rapidForwardAmount\":500,\"rapidForwardWindowMinutes\":5"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            fund(db, alice, 100_000);
            fund(db, bob, 100_000);
            transfer(db, alice, bob, 1000);
            transfer(db, bob, carol, 1000);

            db.auditService.scanAll();
            List<AuditEventRow> rapid = signals(db).stream()
                    .filter(s -> AuditEventType.SIGNAL_RAPID_FORWARDING.equals(s.eventType()))
                    .toList();
            assertEquals(1, rapid.size());
            String details = rapid.get(0).details();
            assertTrue(details.contains("inTx="), details);
            assertTrue(details.contains("outTx="), details);
            assertTrue(details.contains("deltaMillis="), details);
        }
    }

    @Test
    void maxTransfersPerScanLimitsAndFlagsSummary() throws IOException {
        // Предел в 2 перевода: анализируется новейшая часть окна, сводка помечается.
        writeConfig(tuned("\"maxTransfersPerScan\":2"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            for (int i = 0; i < 5; i++) {
                transfer(db, alice, bob, 100);
            }

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertTrue(summary.limited(), "при превышении максимума сводка помечается «ограничено»");
        }
    }

    @Test
    void highPairFrequencySignalsEachSide() throws IOException {
        writeConfig(tuned("\"highPairFrequencyExchanges\":10"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            for (int i = 0; i < 10; i++) {
                transfer(db, alice, bob, 100);
            }

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            // Вариант A: событие пишется каждому участнику пары, с общим incident-id.
            assertEquals(2, summary.highPairFrequencySignals());
            assertEquals(2, summary.total());
            List<AuditEventRow> pairSignals = signals(db).stream()
                    .filter(s -> AuditEventType.SIGNAL_HIGH_PAIR_FREQUENCY.equals(s.eventType()))
                    .toList();
            assertEquals(2, pairSignals.size());
            assertTrue(alice.equals(pairSignals.get(0).playerId())
                    || bob.equals(pairSignals.get(0).playerId()));
            assertTrue(alice.equals(pairSignals.get(1).playerId())
                    || bob.equals(pairSignals.get(1).playerId()));
            assertTrue(pairSignals.stream().map(AuditEventRow::playerId).distinct().count() == 2,
                    "оба участника пары получают отдельное событие");
            String incidentA = incidentOf(pairSignals, alice);
            String incidentB = incidentOf(pairSignals, bob);
            assertEquals(incidentA, incidentB, "события сторон ссылаются на общий incident");
        }
    }

    /** Из деталей сигнала HIGH_PAIR извлекается incident=… */
    private static String incidentOf(List<AuditEventRow> rows, UUID player) {
        for (AuditEventRow row : rows) {
            if (player.equals(row.playerId()) && row.details() != null) {
                for (String token : row.details().split(";")) {
                    if (token.startsWith("incident=")) {
                        return token.substring("incident=".length());
                    }
                }
            }
        }
        return null;
    }

    @Test
    void newAccountConcentrationSignalsFreshRecipient() throws IOException {
        writeConfig(tuned("\"newAccountConcentrationSources\":5,\"newAccountDays\":3650"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID carol = UUID.randomUUID();
            for (int i = 0; i < 5; i++) {
                UUID sender = UUID.randomUUID();
                fund(db, sender, 100_000);
                transfer(db, sender, carol, 100);
            }

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.newAccountConcentrationSignals());
            assertEquals(1, summary.total());
            assertTrue(hasSignal(db, AuditEventType.SIGNAL_NEW_ACCOUNT_CONCENTRATION, carol));
        }
    }

    @Test
    void repeatedDestinationSignalsSender() throws IOException {
        writeConfig(tuned("\"repeatedDestinationTransfers\":10"));
        try (TestDb db = TestDb.create(noCooldown())) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            fund(db, alice, 100_000);
            for (int i = 0; i < 10; i++) {
                transfer(db, alice, bob, 100);
            }

            SuspicionScanner.ScanSummary summary = db.auditService.scanAll();
            assertEquals(1, summary.repeatedDestinationSignals());
            assertEquals(1, summary.total());
            List<AuditEventRow> shared = signals(db).stream()
                    .filter(s -> AuditEventType.SIGNAL_REPEATED_SHARED_DESTINATION.equals(s.eventType()))
                    .toList();
            assertEquals(1, shared.size());
            assertTrue(alice.equals(shared.get(0).playerId()));
            assertTrue(shared.get(0).details().contains(bob.toString()),
                    "детали должны содержать получателя: " + shared.get(0).details());
        }
    }
}
