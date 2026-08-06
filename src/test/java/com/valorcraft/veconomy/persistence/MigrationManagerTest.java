package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.economy.TreasuryService;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationManagerTest {

    @Test
    void freshDatabaseGetsLatestSchemaVersion() {
        try (TestDb db = TestDb.create()) {
            assertEquals(7, db.database.schemaVersion());
        }
    }

    @Test
    void allTablesExist() {
        try (TestDb db = TestDb.create()) {
            Set<String> tables = new HashSet<>();
            db.database.inTransaction(connection -> {
                try (ResultSet rs = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                    while (rs.next()) {
                        tables.add(rs.getString("TABLE_NAME"));
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            for (String table : Set.of("accounts", "transactions", "player_activity",
                    "claimed_milestones", "weekly_payouts", "weekly_activity_periods",
                    "weekly_fund_treasury", "weekly_activity_days", "weekly_fund_plans",
                    "audit_events", "dimension_visits", "escrow", "meta")) {
                assertTrue(tables.contains(table), "таблица " + table + " должна существовать");
            }
        }
    }

    @Test
    void treasurySystemAccountSeeded() {
        try (TestDb db = TestDb.create()) {
            db.database.inTransaction(connection -> {
                try (ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT status FROM accounts WHERE player_uuid = '" + TreasuryService.TREASURY_UUID + "'")) {
                    assertTrue(rs.next(), "казна должна существовать как системный аккаунт");
                    assertEquals("SYSTEM", rs.getString(1));
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
        }
    }

    @Test
    void rerunningMigrationIsNoOp() {
        try (TestDb db = TestDb.create()) {
            db.database.inTransaction(connection -> {
                MigrationManager.migrate(connection, DatabaseManager.Dialect.SQLITE);
                return null;
            });
            assertEquals(7, db.database.schemaVersion());
        }
    }

    @Test
    void idempotencyKeyUniqueIndexExists() {
        try (TestDb db = TestDb.create()) {
            org.junit.jupiter.api.Assertions.assertThrows(DatabaseException.class, () ->
                    db.database.inTransaction(connection -> {
                        insertTx(connection, "t1", "key1");
                        insertTx(connection, "t2", "key1");
                        return null;
                    }));
        }
    }

    @Test
    void auditV7AddsCounterpartyAndDedupeColumns() {
        try (TestDb db = TestDb.create()) {
            Set<String> columns = new HashSet<>();
            db.database.inTransaction(connection -> {
                try (ResultSet rs = connection.getMetaData().getColumns(null, null, "audit_events", null)) {
                    while (rs.next()) {
                        columns.add(rs.getString("COLUMN_NAME"));
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            assertTrue(columns.contains("counterparty_uuid"), "v7: колонка counterparty_uuid");
            assertTrue(columns.contains("dedupe_key"), "v7: колонка dedupe_key");
        }
    }

    @Test
    void auditDedupeIndexRejectsDuplicateKeysAndAllowsNulls() {
        try (TestDb db = TestDb.create()) {
            int first = db.database.inTransaction(connection -> insertAuditIgnored(connection, "t1", "same-key"));
            assertEquals(1, first);
            // второй INSERT OR IGNORE с тем же ключом — пусто (отклонён частичным индексом)
            int ignored = db.database.inTransaction(connection -> insertAuditIgnored(connection, "t2", "same-key"));
            assertEquals(0, ignored, "повторный dedupe-ключ должен быть отклонён");
            long countAfterDedupeHit = db.database.inTransaction(connection -> countAudit(connection));
            assertEquals(1, countAfterDedupeHit);

            // NULL-ключи частичным уникальным индексом не ограничиваются
            db.database.inTransaction(connection -> {
                insertAuditIgnored(connection, "t3", null);
                insertAuditIgnored(connection, "t4", null);
                return null;
            });
            long countWithNullKeys = db.database.inTransaction(connection -> countAudit(connection));
            assertEquals(3, countWithNullKeys,
                    "несколько событий без dedupe-ключа допустимы");
        }
    }

    @Test
    void auditIndexesCoverSeverityAndDedupe() {
        try (TestDb db = TestDb.create()) {
            Set<String> indexes = new HashSet<>();
            db.database.inTransaction(connection -> {
                try (ResultSet rs = connection.createStatement()
                        .executeQuery("PRAGMA index_list('audit_events')")) {
                    while (rs.next()) {
                        indexes.add(rs.getString("name"));
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                return null;
            });
            assertTrue(indexes.contains("idx_audit_severity"), "индекс по (severity, created_at)");
            assertTrue(indexes.contains("idx_audit_dedupe"), "частичный уникальный индекс dedupe_key");
            assertTrue(indexes.contains("idx_audit_idem"), "уникальный индекс idempotency_key");
        }
    }

    private static int insertAuditIgnored(Connection connection, String id, String dedupeKey) {
        try (var statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO audit_events "
                        + "(event_type, severity, player_uuid, details, created_at, status, idempotency_key, dedupe_key) "
                        + "VALUES ('TEST', 'INFO', NULL, 'r', 1, 'OPEN', ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, dedupeKey);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("тестовый INSERT аудита не удался", e);
        }
    }

    private static long countAudit(Connection connection) {
        try (ResultSet rs = connection.createStatement().executeQuery("SELECT COUNT(*) FROM audit_events")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new DatabaseException("подсчёт аудита не удался", e);
        }
    }

    private static void insertTx(Connection connection, String id, String key) {
        try (var statement = connection.prepareStatement(
                "INSERT INTO transactions (transaction_id, transaction_type, amount_minor, created_at, reason, idempotency_key) "
                        + "VALUES (?, 'SYSTEM_CORRECTION', 1, 1, 'r', ?)")) {
            statement.setString(1, id);
            statement.setString(2, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("тестовый INSERT не удался", e);
        }
    }
}
