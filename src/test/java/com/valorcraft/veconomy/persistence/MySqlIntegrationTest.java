package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.audit.AuditEventRow;
import com.valorcraft.veconomy.audit.AuditRepository;
import com.valorcraft.veconomy.api.EscrowCredit;
import com.valorcraft.veconomy.api.EscrowLookupResult;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowState;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.economy.AccountService;
import com.valorcraft.veconomy.economy.EscrowService;
import com.valorcraft.veconomy.economy.LedgerService;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.EscrowRepository;
import com.valorcraft.veconomy.persistence.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * НАСТОЯЩИЙ интеграционный тест MySQL (нет Docker — падает, а не проскакивает).
 * Включается ТОЛЬКО отдельным профилем {@code gradle mysqlIntegrationTest}
 * (системное свойство {@code veconomy.mysqlIntegration}); обычный {@code test}
 * набор его пропускает. Никакой имитации через SQLite.
 */
@EnabledIfSystemProperty(named = "veconomy.mysqlIntegration", matches = "true")
@Testcontainers
class MySqlIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("veconomy_it")
            .withUsername("veconomy")
            .withPassword("veconomy");

    private static final int LATEST_SCHEMA = 8;

    private String url() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306)
                + "/veconomy_it?useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=UTC&useSSL=false";
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), "veconomy", "veconomy");
    }

    private static EconomySettings mysqlSettings() {
        EconomySettings d = EconomySettings.defaults();
        return new EconomySettings(
                d.currencyNameSingular, d.currencyNameFew, d.currencyNameMany, d.currencySymbol,
                d.decimalPlaces, d.maximumBalance, d.transfersEnabled, d.allowOfflineRecipients,
                d.minimumTransferAmount, d.maximumTransferAmount, d.transferCooldownSeconds,
                "mysql", d.databaseFile, d.busyTimeoutMillis, d.walEnabled,
                MYSQL.getHost(), MYSQL.getMappedPort(3306), "veconomy_it", "veconomy", "veconomy",
                d.mysqlPoolSize, d.broadcastAdminChanges,
                d.activity, d.milestones, d.weeklyFund);
    }

    @Test
    void cleanInstallMigratesToLatestAndRepeatedMigrateIsIdempotent() throws SQLException {
        try (Connection c = connect()) {
            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            assertEquals(LATEST_SCHEMA, MigrationManager.readVersion(c, DatabaseManager.Dialect.MYSQL));
            // Повторный запуск идемпотентен: версия не растёт, ошибок нет.
            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            assertEquals(LATEST_SCHEMA, MigrationManager.readVersion(c, DatabaseManager.Dialect.MYSQL));
        }
    }

    @Test
    void partiallyAppliedV7IsFinishedByRepeatedMigrate() throws Exception {
        try (Connection c = connect()) {
            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            // «Частичный сбой»: версия откачена на 6, а часть объектов v7 отсутствует
            // (DROP column/индекс — как будто прежние ALTER не дошли).
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE audit_events DROP INDEX uk_audit_dedupe");
                st.execute("ALTER TABLE audit_events DROP COLUMN dedupe_key");
                st.execute("UPDATE meta SET value = '6' WHERE meta_key = 'schema_version'");
            }
            assertEquals(6, MigrationManager.readVersion(c, DatabaseManager.Dialect.MYSQL));

            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            assertEquals(LATEST_SCHEMA, MigrationManager.readVersion(c, DatabaseManager.Dialect.MYSQL));
            assertTrue(columnExists(c, "audit_events", "dedupe_key"),
                    "недостающий столбец v7 добавлен повторно");
            assertTrue(indexExists(c, "audit_events", "uk_audit_dedupe"),
                    "уникальный индекс dedupe_key восстановлен");
            assertTrue(columnExists(c, "audit_events", "status"));
            assertTrue(columnExists(c, "audit_events", "counterparty_uuid"));
        }
    }

    @Test
    void compositeIndexesHoldAllColumns() throws SQLException {
        try (Connection c = connect()) {
            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            assertEquals(2, indexColumns(c, "audit_events", "idx_audit_severity"),
                    "индекс severity должен покрывать (severity, created_at)");
            assertEquals(2, indexColumns(c, "audit_events", "idx_audit_player"),
                    "индекс player должен покрывать (player_uuid, created_at)");
        }
    }

    @Test
    void uniqueDedupeKeyRejectsSecondInsert() throws SQLException {
        try (Connection c = connect()) {
            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            AuditRepository audit = new AuditRepository();
            String dedupe = "loop:" + UUID.randomUUID();
            AuditEventRow row = AuditEventRow.signal("SIGNAL_TRANSFER_LOOP", UUID.randomUUID(),
                    null, null, "вставить единожды", dedupe);
            assertEquals(AuditRepository.InsertResult.Status.INSERTED,
                    audit.insert(c, DatabaseManager.Dialect.MYSQL, row).status());
            assertEquals(AuditRepository.InsertResult.Status.DUPLICATE,
                    audit.insert(c, DatabaseManager.Dialect.MYSQL, row).status(),
                    "второй INSERT с тем же dedupe_key должен быть отклонён уникальным индексом");
        }
    }

    @Test
    void maximumLoopDedupeKeyFitsAndReinserts() throws Exception {
        try (Connection c = connect()) {
            MigrationManager.migrate(c, DatabaseManager.Dialect.MYSQL);
            AuditRepository audit = new AuditRepository();
            UUID player = UUID.randomUUID();
            List<String> txIds = java.util.stream.Stream.generate(() -> UUID.randomUUID().toString())
                    .limit(6).sorted().toList();
            // Максимальный canonical-описание цикла без хэша: > 256 символов — в столбец
            // VARCHAR(256) НЕ поместится, поэтому кладём только loop:<sha256> (69 символов).
            String canonical = "loop|" + player + "|" + Long.MAX_VALUE + "|"
                    + String.join(",", txIds);
            assertTrue(canonical.length() > 255, "сырой ключ цикла превышает VARCHAR(256)");
            String key = "loop:" + sha256Hex(canonical);
            assertTrue(key.length() <= 256, "хешированный ключ умещается в VARCHAR(256)");

            AuditEventRow row = AuditEventRow.signal("SIGNAL_TRANSFER_LOOP", player,
                    null, null, "макс. цикл", key);
            AuditRepository.InsertResult first = new AuditRepository().insert(
                    c, DatabaseManager.Dialect.MYSQL, row);
            assertEquals(AuditRepository.InsertResult.Status.INSERTED, first.status());
            AuditEventRow same = AuditEventRow.signal("SIGNAL_TRANSFER_LOOP", player,
                    null, null, "тот же цикл", key);
            assertEquals(AuditRepository.InsertResult.Status.DUPLICATE,
                    new AuditRepository().insert(c, DatabaseManager.Dialect.MYSQL, same).status(),
                    "тот же цикл при повторном скане дедуплицируется тем же ключом");
        }
    }

    @Test
    void parallelIndependentTransactionsCommitIndependently() throws Exception {
        EconomySettings settings = mysqlSettings();
        DatabaseManager manager = new DatabaseManager();
        manager.open(Path.of("mysql-it"), settings);
        AuditRepository audit = new AuditRepository();
        try {
            int threads = 8;
            int perThread = 10;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger inserted = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        AuditEventRow row = AuditEventRow.signal("SIGNAL_TRANSFER_LOOP",
                                UUID.randomUUID(), null, null, "параллельно",
                                "loop:" + UUID.randomUUID());
                        AuditRepository.InsertResult result = manager.inTransaction(c ->
                                audit.insert(c, DatabaseManager.Dialect.MYSQL, row));
                        if (result.status() == AuditRepository.InsertResult.Status.INSERTED) {
                            inserted.incrementAndGet();
                        }
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
            assertEquals(threads * perThread, inserted.get(),
                    "все независимые транзакции в параллельных потоках должны пройти");
        } finally {
            manager.close();
        }
    }

    @Test
    void concurrentSettleOnSameEscrowCreditsExactlyOnce() throws Exception {
        EconomySettings settings = mysqlSettings();
        DatabaseManager manager = new DatabaseManager();
        manager.open(Path.of("mysql-it"), settings);
        try {
            EconomyStack stack = new EconomyStack(manager, settings);
            UUID owner = UUID.randomUUID();
            UUID buyer = UUID.randomUUID();
            UUID treasury = stack.escrowService.treasuryUuid();
            stack.accountService.deposit(owner, 5000, TransactionContext.of(TransactionType.ADMIN_DEPOSIT, null, "сед"));
            assertEquals(5000, stack.accountService.getBalance(owner));

            EscrowResult reserve = stack.escrowService.reserveMoney(owner, 1000, "sale-1",
                    TransactionContext.of(TransactionType.ESCROW_RESERVE, null, "лот"));
            assertTrue(reserve.isSuccess());

            List<EscrowCredit> credits = List.of(
                    new EscrowCredit(buyer, 950, "seller"),
                    new EscrowCredit(treasury, 50, "commission"));

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            AtomicInteger idempotent = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    EscrowResult result = stack.escrowService.settleMoney("sale-1", credits,
                            TransactionContext.of(TransactionType.ESCROW_CAPTURE, null, "расчёт"));
                    switch (result.status()) {
                        case SUCCESS -> successes.incrementAndGet();
                        case ALREADY_SETTLED -> idempotent.incrementAndGet();
                        default -> { }
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

            assertEquals(1, successes.get(), "ровно один поток выполняет расчёт");
            assertTrue(idempotent.get() > 0, "остальные повторы должны быть идемпотентными");
            assertEquals(950, stack.accountService.getBalance(buyer),
                    "покупатель получает ровно одну долю");
            assertEquals(50, stack.accountService.getBalance(treasury), "казна получает ровно одну комиссию");
            assertEquals(0, stack.escrowService.sumReserved());
        } finally {
            manager.close();
        }
    }

    @Test
    void concurrentReserveOnSameReferenceIdExactlyOnce() throws Exception {
        EconomySettings settings = mysqlSettings();
        DatabaseManager manager = new DatabaseManager();
        manager.open(Path.of("mysql-it-reserve"), settings);
        try {
            EconomyStack stack = new EconomyStack(manager, settings);
            UUID owner = UUID.randomUUID();
            stack.accountService.deposit(owner, 5000,
                    TransactionContext.of(TransactionType.ADMIN_DEPOSIT, null, "сед"));
            assertEquals(5000, stack.accountService.getBalance(owner));

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successes = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    EscrowResult result = stack.escrowService.reserveMoney(owner, 1000, "reserve-race-1",
                            TransactionContext.of(TransactionType.ESCROW_RESERVE, null, "лот"));
                    if (result.status() == EscrowResult.Status.SUCCESS) {
                        successes.incrementAndGet();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

            assertEquals(1, successes.get(), "ровно один поток резервирует средства");
            assertEquals(4000, stack.accountService.getBalance(owner), "баланс списан ровно один раз");
            long reserveRows = manager.inTransaction(c ->
                    countTypeForSource(c, TransactionType.ESCROW_RESERVE.name(), owner.toString()));
            assertEquals(1, reserveRows, "ровно одна ledger-запись резервирования");
            EscrowLookupResult lookup = stack.escrowService.findEscrow("reserve-race-1");
            assertEquals(EscrowLookupResult.Status.FOUND, lookup.status());
            assertEquals(EscrowState.RESERVED, lookup.snapshot().state());
            assertEquals(1000, lookup.snapshot().amount());
        } finally {
            manager.close();
        }
    }

    @Test
    void settleVsReleaseRaceSingleFinalTransition() throws Exception {
        EconomySettings settings = mysqlSettings();
        DatabaseManager manager = new DatabaseManager();
        manager.open(Path.of("mysql-it-settle-release"), settings);
        try {
            EconomyStack stack = new EconomyStack(manager, settings);
            UUID owner = UUID.randomUUID();
            UUID buyer = UUID.randomUUID();
            UUID treasury = stack.escrowService.treasuryUuid();
            stack.accountService.deposit(owner, 5000,
                    TransactionContext.of(TransactionType.ADMIN_DEPOSIT, null, "сед"));
            EscrowResult reserve = stack.escrowService.reserveMoney(owner, 1000, "race-2",
                    TransactionContext.of(TransactionType.ESCROW_RESERVE, null, "лот"));
            assertTrue(reserve.isSuccess());
            List<EscrowCredit> credits = List.of(
                    new EscrowCredit(buyer, 950, "seller"),
                    new EscrowCredit(treasury, 50, "commission"));

            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger settleSuccess = new AtomicInteger();
            AtomicInteger releaseSuccess = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                boolean doSettle = t % 2 == 0;
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    EscrowResult result = doSettle
                            ? stack.escrowService.settleMoney("race-2", credits,
                                    TransactionContext.of(TransactionType.ESCROW_CAPTURE, null, "расчёт"))
                            : stack.escrowService.releaseMoney("race-2",
                                    TransactionContext.of(TransactionType.ESCROW_RELEASE, null, "отмена"));
                    if (result.status() == EscrowResult.Status.SUCCESS) {
                        if (doSettle) {
                            settleSuccess.incrementAndGet();
                        } else {
                            releaseSuccess.incrementAndGet();
                        }
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

            assertEquals(1, settleSuccess.get() + releaseSuccess.get(),
                    "ровно один финальный переход (settle ИЛИ release)");
            EscrowLookupResult finalLookup = stack.escrowService.findEscrow("race-2");
            assertEquals(EscrowLookupResult.Status.FOUND, finalLookup.status());
            if (settleSuccess.get() == 1) {
                assertEquals(EscrowState.CAPTURED, finalLookup.snapshot().state());
                assertEquals(950, stack.accountService.getBalance(buyer),
                        "покупатель получает долю ровно один раз");
                assertEquals(50, stack.accountService.getBalance(treasury));
                assertEquals(4000, stack.accountService.getBalance(owner));
            } else {
                assertEquals(EscrowState.RELEASED, finalLookup.snapshot().state());
                assertEquals(5000, stack.accountService.getBalance(owner),
                        "владелец получает все средства обратно ровно один раз");
                assertEquals(0, stack.accountService.getBalance(buyer));
                assertEquals(0, stack.accountService.getBalance(treasury));
            }
        } finally {
            manager.close();
        }
    }

    // ------------------------------------------------------------ helpers

    private static boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (ResultSet rs = c.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean indexExists(Connection c, String table, String index) throws SQLException {
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name != null && index.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Число столбцов составного индекса (SHOW INDEX: по строке на столбец). */
    private static int indexColumns(Connection c, String table, String index) throws SQLException {
        int count = 0;
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static String sha256Hex(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** Число ledger-записей заданного типа для конкретного источника (owner). */
    private static long countTypeForSource(Connection c, String type, String sourceUuid) {
        try (var statement = c.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE transaction_type = ? AND source_uuid = ?")) {
            statement.setString(1, type);
            statement.setString(2, sourceUuid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта ledger-записей", e);
        }
    }

    /** Минимальный стек сервисов (как в TestDb) поверх управляемого соединения MySQL. */
    private static final class EconomyStack {
        final DatabaseManager manager;
        final EconomySettings settings;
        final AccountRepository accounts = new AccountRepository();
        final TransactionRepository transactions = new TransactionRepository();
        final EscrowRepository escrow = new EscrowRepository();
        final LedgerService ledger;
        final AccountService accountService;
        final EscrowService escrowService;

        EconomyStack(DatabaseManager manager, EconomySettings settings) {
            this.manager = manager;
            this.settings = settings;
            this.ledger = new LedgerService(manager, transactions);
            this.accountService = new AccountService(manager, accounts,
                    transactions, ledger, null, settings);
            this.escrowService = new EscrowService(manager, accounts, escrow,
                    accountService, ledger, settings);
        }
    }
}