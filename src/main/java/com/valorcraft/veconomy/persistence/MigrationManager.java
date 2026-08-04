package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.economy.TreasuryService;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Миграции схемы базы данных. Версия хранится в {@code PRAGMA user_version}.
 * Каждая миграция выполняется в одной транзакции; после успеха версия повышается.
 * Повторный запуск с уже актуальной версией — безопасный no-op.
 */
public final class MigrationManager {

    /** Все миграции по порядку. Индекс + 1 = версия схемы. */
    private static final String[] MIGRATIONS = {
            // v1: начальная схема
            """
            CREATE TABLE IF NOT EXISTS accounts (
                player_uuid TEXT PRIMARY KEY,
                last_known_name TEXT,
                balance_minor INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 0,
                CHECK (balance_minor >= 0)
            );

            CREATE TABLE IF NOT EXISTS transactions (
                transaction_id TEXT PRIMARY KEY,
                transaction_type TEXT NOT NULL,
                source_uuid TEXT,
                target_uuid TEXT,
                amount_minor INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                actor_uuid TEXT,
                reason TEXT NOT NULL,
                idempotency_key TEXT,
                metadata_json TEXT,
                source_balance_after INTEGER,
                target_balance_after INTEGER,
                CHECK (amount_minor > 0)
            );

            CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_idem
                ON transactions(idempotency_key) WHERE idempotency_key IS NOT NULL;
            CREATE INDEX IF NOT EXISTS idx_transactions_source ON transactions(source_uuid);
            CREATE INDEX IF NOT EXISTS idx_transactions_target ON transactions(target_uuid);
            CREATE INDEX IF NOT EXISTS idx_transactions_created ON transactions(created_at);

            CREATE TABLE IF NOT EXISTS player_activity (
                player_uuid TEXT PRIMARY KEY,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                total_online_seconds INTEGER NOT NULL DEFAULT 0,
                total_active_seconds INTEGER NOT NULL DEFAULT 0,
                total_afk_seconds INTEGER NOT NULL DEFAULT 0,
                current_week_id TEXT,
                weekly_active_seconds INTEGER NOT NULL DEFAULT 0,
                last_activity_at INTEGER,
                last_dimension TEXT,
                excluded_from_rewards INTEGER NOT NULL DEFAULT 0
            );

            CREATE TABLE IF NOT EXISTS claimed_milestones (
                player_uuid TEXT NOT NULL,
                milestone_id TEXT NOT NULL,
                amount_minor INTEGER NOT NULL,
                claimed_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                transaction_id TEXT NOT NULL,
                PRIMARY KEY (player_uuid, milestone_id)
            );

            CREATE TABLE IF NOT EXISTS weekly_payouts (
                week_id TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                activity_seconds INTEGER NOT NULL,
                points INTEGER NOT NULL,
                amount_minor INTEGER NOT NULL,
                paid_at INTEGER NOT NULL,
                transaction_id TEXT NOT NULL,
                PRIMARY KEY (week_id, player_uuid)
            );

            CREATE TABLE IF NOT EXISTS escrow (
                reference_id TEXT PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                amount_minor INTEGER NOT NULL,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                metadata_json TEXT
            );

            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """
    };

    private MigrationManager() {}

    /** Применить недостающие миграции к соединению. */
    public static void migrate(Connection connection) {
        int current = readUserVersion(connection);
        for (int i = current; i < MIGRATIONS.length; i++) {
            int target = i + 1;
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (String sql : splitStatements(MIGRATIONS[i])) {
                        statement.execute(sql);
                    }
                }
                seed(connection, target);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA user_version = " + target);
                }
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    e.addSuppressed(rollbackException);
                }
                throw new DatabaseException("Ошибка миграции схемы до версии " + target, e);
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    /** Разбить скрипт миграции на отдельные SQL-операторы. */
    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        for (String raw : script.split(";")) {
            String sql = raw.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    /** Системные данные, которые обязаны существовать в базе. */
    private static void seed(Connection connection, int version) throws SQLException {
        if (version >= 1) {
            long now = System.currentTimeMillis();
            String treasuryUuid = TreasuryService.TREASURY_UUID.toString();
            try (var statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO accounts (player_uuid, last_known_name, balance_minor, status, created_at, updated_at, version) "
                            + "VALUES (?, ?, 0, ?, ?, ?, 0)")) {
                statement.setString(1, treasuryUuid);
                statement.setString(2, "SYSTEM_TREASURY");
                statement.setString(3, AccountStatus.SYSTEM.name());
                statement.setLong(4, now);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
        }
    }

    private static int readUserVersion(Connection connection) {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Не удалось прочитать версию схемы", e);
        }
    }
}
