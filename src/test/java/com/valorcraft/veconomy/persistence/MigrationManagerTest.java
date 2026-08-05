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
            assertEquals(3, db.database.schemaVersion());
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
                    "weekly_fund_treasury", "escrow", "meta")) {
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
            assertEquals(3, db.database.schemaVersion());
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
