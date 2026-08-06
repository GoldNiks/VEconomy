package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.economy.TreasuryService;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Миграции схемы базы данных. Версия хранится в {@code PRAGMA user_version} (SQLite)
 * либо в таблице {@code meta} (MySQL). Каждая миграция идемпотентна (CREATE IF NOT EXISTS,
 * INSERT IGNORE), поэтому безопасно перезапускать после частичного выполнения.
 */
public final class MigrationManager {

    /** Версия схемы в таблице {@code meta} для MySQL. */
    static final String SCHEMA_VERSION_KEY = "schema_version";

    private MigrationManager() {}

    // ------------------------------------------------------------ scripts

    private static String[] scripts(DatabaseManager.Dialect dialect) {
        return dialect == DatabaseManager.Dialect.MYSQL ? MYSQL_MIGRATIONS : SQLITE_MIGRATIONS;
    }

    /** v1 — начальная схема (SQLite: TEXT-ключи, INSERT OR IGNORE, PRAGMA). */
    private static final String[] SQLITE_MIGRATIONS = {
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
                meta_key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );
            """,
            // v2 — снимки завершённых недель для независимой ротации недельного фонда.
            """
            CREATE TABLE IF NOT EXISTS weekly_activity_periods (
                week_id TEXT NOT NULL,
                player_uuid TEXT NOT NULL,
                counted_seconds INTEGER NOT NULL,
                points INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                paid_at INTEGER,
                transaction_id TEXT,
                PRIMARY KEY (week_id, player_uuid)
            );
            """,
            // v3 — долговечный остаток недельного фонда: сумма и статус перевода в казну.
            // Записывается в статусе PENDING ДО попытки перевода, чтобы остаток не пропал,
            // даже если сам перевод или отметка PAID позже не пройдут.
            """
            CREATE TABLE IF NOT EXISTS weekly_fund_treasury (
                week_id TEXT PRIMARY KEY,
                remainder_amount INTEGER NOT NULL,
                treasury_status TEXT NOT NULL DEFAULT 'PENDING',
                transaction_id TEXT,
                updated_at INTEGER NOT NULL
            );
            """,
            // v4 — автоматический размер фонда, активность по дням и замороженный план выплаты.
            // Активность хранится по ключу (player_uuid, week_id, день): это исключает смешение
            // недель на границе (день относится к своей неделе по дате, а не по счётчику).
            """
            CREATE TABLE IF NOT EXISTS weekly_activity_days (
                player_uuid TEXT NOT NULL,
                week_id TEXT NOT NULL,
                day_key TEXT NOT NULL,
                active_seconds INTEGER NOT NULL,
                PRIMARY KEY (player_uuid, week_id, day_key)
            );

            CREATE TABLE IF NOT EXISTS weekly_fund_plans (
                week_id TEXT PRIMARY KEY,
                fund_amount INTEGER NOT NULL,
                base_fund_amount INTEGER NOT NULL,
                economy_coefficient_bps INTEGER NOT NULL,
                money_supply INTEGER NOT NULL,
                supply_per_eligible INTEGER NOT NULL,
                target_supply_per_eligible INTEGER NOT NULL,
                eligible_players INTEGER NOT NULL,
                total_points INTEGER NOT NULL,
                total_share INTEGER NOT NULL,
                remainder_amount INTEGER NOT NULL,
                payout_status TEXT NOT NULL DEFAULT 'PLANNED',
                planned_at INTEGER NOT NULL,
                auto_payout_at INTEGER,
                paid_at INTEGER
            );

            ALTER TABLE weekly_activity_periods ADD COLUMN active_days INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE weekly_activity_periods ADD COLUMN time_points INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE weekly_activity_periods ADD COLUMN day_points INTEGER NOT NULL DEFAULT 0;
            ALTER TABLE weekly_activity_periods ADD COLUMN share INTEGER NOT NULL DEFAULT 0;
            """,
            // v5 — личные посещения измерений для milestones DIMENSION_VISIT.
            // Нормализованная таблица (одна строка на (игрок, измерение)) вместо
            // истории строкой через запятую: повторный вход не плодит строки,
            // а факт посещения проверяется одним индексным чтением.
            """
            CREATE TABLE IF NOT EXISTS dimension_visits (
                player_uuid TEXT NOT NULL,
                dimension TEXT NOT NULL,
                first_visited_at INTEGER NOT NULL,
                PRIMARY KEY (player_uuid, dimension)
            );
            """,
            // v6 — события аудита и сигналы подозрительной активности. Ledger
            // хранит деньги; здесь — административные действия и сработавшие
            // эвристики с северити и деталями.
            """
            CREATE TABLE IF NOT EXISTS audit_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                severity TEXT NOT NULL,
                player_uuid TEXT,
                actor_uuid TEXT,
                amount_minor INTEGER,
                details TEXT,
                created_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_audit_player ON audit_events(player_uuid, created_at);
            CREATE INDEX IF NOT EXISTS idx_audit_type ON audit_events(event_type, created_at);
            """,
            // v7 — полный набор сигналов, жизненный цикл событий и actor attribution.
            // status/resolved_at/resolved_by/resolution_note — обработка подозрительных
            // событий администратором; idempotency_key защищает от дублей при повторе
            // записи после сбоя; actor_type фиксирует инициатора события.
            """
            ALTER TABLE audit_events ADD COLUMN actor_type VARCHAR(16);
            ALTER TABLE audit_events ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN';
            ALTER TABLE audit_events ADD COLUMN resolved_at BIGINT;
            ALTER TABLE audit_events ADD COLUMN resolved_by VARCHAR(64);
            ALTER TABLE audit_events ADD COLUMN resolution_note VARCHAR(1024);
            ALTER TABLE audit_events ADD COLUMN idempotency_key VARCHAR(64);
            CREATE UNIQUE INDEX IF NOT EXISTS idx_audit_idem ON audit_events(idempotency_key);
            """
    };

    /** v1 — начальная схема для MySQL (VARCHAR-ключи, BIGINT, utf8mb4, INSERT IGNORE). */
    private static final String[] MYSQL_MIGRATIONS = {
            """
            CREATE TABLE IF NOT EXISTS accounts (
                player_uuid VARCHAR(36) PRIMARY KEY,
                last_known_name VARCHAR(64),
                balance_minor BIGINT NOT NULL DEFAULT 0,
                status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                version INT NOT NULL DEFAULT 0,
                CONSTRAINT chk_accounts_balance CHECK (balance_minor >= 0)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS transactions (
                transaction_id VARCHAR(36) PRIMARY KEY,
                transaction_type VARCHAR(32) NOT NULL,
                source_uuid VARCHAR(36),
                target_uuid VARCHAR(36),
                amount_minor BIGINT NOT NULL,
                created_at BIGINT NOT NULL,
                actor_uuid VARCHAR(36),
                reason VARCHAR(255) NOT NULL,
                idempotency_key VARCHAR(255),
                metadata_json TEXT,
                source_balance_after BIGINT,
                target_balance_after BIGINT,
                CONSTRAINT chk_transactions_amount CHECK (amount_minor > 0),
                UNIQUE KEY idx_transactions_idem (idempotency_key),
                KEY idx_transactions_source (source_uuid),
                KEY idx_transactions_target (target_uuid),
                KEY idx_transactions_created (created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS player_activity (
                player_uuid VARCHAR(36) PRIMARY KEY,
                first_seen_at BIGINT NOT NULL,
                last_seen_at BIGINT NOT NULL,
                total_online_seconds BIGINT NOT NULL DEFAULT 0,
                total_active_seconds BIGINT NOT NULL DEFAULT 0,
                total_afk_seconds BIGINT NOT NULL DEFAULT 0,
                current_week_id VARCHAR(20),
                weekly_active_seconds BIGINT NOT NULL DEFAULT 0,
                last_activity_at BIGINT,
                last_dimension VARCHAR(64),
                excluded_from_rewards TINYINT NOT NULL DEFAULT 0
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS claimed_milestones (
                player_uuid VARCHAR(36) NOT NULL,
                milestone_id VARCHAR(64) NOT NULL,
                amount_minor BIGINT NOT NULL,
                claimed_at BIGINT NOT NULL,
                source VARCHAR(32) NOT NULL,
                transaction_id VARCHAR(36) NOT NULL,
                PRIMARY KEY (player_uuid, milestone_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS weekly_payouts (
                week_id VARCHAR(20) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                activity_seconds BIGINT NOT NULL,
                points INT NOT NULL,
                amount_minor BIGINT NOT NULL,
                paid_at BIGINT NOT NULL,
                transaction_id VARCHAR(36) NOT NULL,
                PRIMARY KEY (week_id, player_uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS escrow (
                reference_id VARCHAR(64) PRIMARY KEY,
                owner_uuid VARCHAR(36) NOT NULL,
                amount_minor BIGINT NOT NULL,
                state VARCHAR(16) NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                metadata_json TEXT
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS meta (
                meta_key VARCHAR(64) PRIMARY KEY,
                value VARCHAR(255) NOT NULL
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """,
            // v2 — снимки завершённых недель для независимой ротации недельного фонда.
            """
            CREATE TABLE IF NOT EXISTS weekly_activity_periods (
                week_id VARCHAR(20) NOT NULL,
                player_uuid VARCHAR(36) NOT NULL,
                counted_seconds BIGINT NOT NULL,
                points BIGINT NOT NULL,
                status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                paid_at BIGINT,
                transaction_id VARCHAR(36),
                PRIMARY KEY (week_id, player_uuid)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """,
            // v3 — долговечный остаток недельного фонда (см. комментарий к SQLite-скрипту).
            """
            CREATE TABLE IF NOT EXISTS weekly_fund_treasury (
                week_id VARCHAR(20) NOT NULL,
                remainder_amount BIGINT NOT NULL,
                treasury_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                transaction_id VARCHAR(36),
                updated_at BIGINT NOT NULL,
                PRIMARY KEY (week_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """,
            // v4 — авторазмер фонда, активность по дням и замороженный план (см. SQLite-скрипт).
            """
            CREATE TABLE IF NOT EXISTS weekly_activity_days (
                player_uuid VARCHAR(36) NOT NULL,
                week_id VARCHAR(20) NOT NULL,
                day_key VARCHAR(10) NOT NULL,
                active_seconds BIGINT NOT NULL,
                PRIMARY KEY (player_uuid, week_id, day_key)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS weekly_fund_plans (
                week_id VARCHAR(20) NOT NULL,
                fund_amount BIGINT NOT NULL,
                base_fund_amount BIGINT NOT NULL,
                economy_coefficient_bps INT NOT NULL,
                money_supply BIGINT NOT NULL,
                supply_per_eligible BIGINT NOT NULL,
                target_supply_per_eligible BIGINT NOT NULL,
                eligible_players INT NOT NULL,
                total_points BIGINT NOT NULL,
                total_share BIGINT NOT NULL,
                remainder_amount BIGINT NOT NULL,
                payout_status VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
                planned_at BIGINT NOT NULL,
                auto_payout_at BIGINT,
                paid_at BIGINT,
                PRIMARY KEY (week_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            ALTER TABLE weekly_activity_periods ADD COLUMN active_days INT NOT NULL DEFAULT 0;
            ALTER TABLE weekly_activity_periods ADD COLUMN time_points INT NOT NULL DEFAULT 0;
            ALTER TABLE weekly_activity_periods ADD COLUMN day_points INT NOT NULL DEFAULT 0;
            ALTER TABLE weekly_activity_periods ADD COLUMN share BIGINT NOT NULL DEFAULT 0;
            """,
            // v5 — личные посещения измерений для milestones DIMENSION_VISIT (см. SQLite-скрипт).
            """
            CREATE TABLE IF NOT EXISTS dimension_visits (
                player_uuid VARCHAR(36) NOT NULL,
                dimension VARCHAR(64) NOT NULL,
                first_visited_at BIGINT NOT NULL,
                PRIMARY KEY (player_uuid, dimension)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """,
            // v6 — события аудита и сигналы подозрительной активности (см. SQLite-скрипт).
            """
            CREATE TABLE IF NOT EXISTS audit_events (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                event_type VARCHAR(64) NOT NULL,
                severity VARCHAR(16) NOT NULL,
                player_uuid VARCHAR(36),
                actor_uuid VARCHAR(36),
                amount_minor BIGINT,
                details VARCHAR(1024),
                created_at BIGINT NOT NULL,
                KEY idx_audit_player (player_uuid, created_at),
                KEY idx_audit_type (event_type, created_at)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """,
            // v7 — полный набор сигналов, жизненный цикл и actor attribution (см. SQLite-скрипт).
            """
            ALTER TABLE audit_events
                ADD COLUMN actor_type VARCHAR(16),
                ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
                ADD COLUMN resolved_at BIGINT,
                ADD COLUMN resolved_by VARCHAR(64),
                ADD COLUMN resolution_note VARCHAR(1024),
                ADD COLUMN idempotency_key VARCHAR(64),
                ADD UNIQUE KEY uk_audit_idem (idempotency_key);
            """
    };

    // ------------------------------------------------------------ migrate

    /** Применить недостающие миграции к соединению. */
    public static void migrate(Connection connection, DatabaseManager.Dialect dialect) {
        int current = readVersion(connection, dialect);
        String[] migrations = scripts(dialect);
        for (int i = current; i < migrations.length; i++) {
            int target = i + 1;
            if (dialect == DatabaseManager.Dialect.MYSQL) {
                // DDL в MySQL не транзакционно (implicit commit), поэтому операторы
                // выполняем по одному; все они идемпотентны, повторный запуск безопасен.
                try (Statement statement = connection.createStatement()) {
                    for (String sql : splitStatements(migrations[i])) {
                        statement.execute(sql);
                    }
                    seed(connection, target, dialect);
                    writeVersion(connection, target, dialect);
                } catch (SQLException e) {
                    throw new DatabaseException("Ошибка миграции схемы до версии " + target, e);
                }
            } else {
                try {
                    connection.setAutoCommit(false);
                    try (Statement statement = connection.createStatement()) {
                        for (String sql : splitStatements(migrations[i])) {
                            statement.execute(sql);
                        }
                    }
                    seed(connection, target, dialect);
                    writeVersion(connection, target, dialect);
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
    }

    /** Текущая версия схемы. */
    static int readVersion(Connection connection, DatabaseManager.Dialect dialect) {
        if (dialect == DatabaseManager.Dialect.MYSQL) {
            try {
                ensureMetaTable(connection);
                try (var statement = connection.prepareStatement(
                        "SELECT value FROM meta WHERE meta_key = '" + SCHEMA_VERSION_KEY + "'")) {
                    try (var rs = statement.executeQuery()) {
                        return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
                    }
                }
            } catch (SQLException | NumberFormatException e) {
                throw new DatabaseException("Не удалось прочитать версию схемы", e);
            }
        }
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Не удалось прочитать версию схемы", e);
        }
    }

    private static void writeVersion(Connection connection, int version, DatabaseManager.Dialect dialect) throws SQLException {
        if (dialect == DatabaseManager.Dialect.MYSQL) {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO meta (meta_key, value) VALUES ('" + SCHEMA_VERSION_KEY + "', ?) "
                            + "ON DUPLICATE KEY UPDATE value = VALUES(value)")) {
                statement.setInt(1, version);
                statement.executeUpdate();
            }
        } else {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA user_version = " + version);
            }
        }
    }

    /** Гарантировать существование таблицы {@code meta} (нужна для хранения версии). */
    private static void ensureMetaTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS meta ("
                    + "meta_key VARCHAR(64) PRIMARY KEY, "
                    + "value VARCHAR(255) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    /** Системные данные, которые обязаны существовать в базе. */
    private static void seed(Connection connection, int version, DatabaseManager.Dialect dialect) throws SQLException {
        if (version >= 1) {
            long now = System.currentTimeMillis();
            String treasuryUuid = TreasuryService.TREASURY_UUID.toString();
            String insert = dialect == DatabaseManager.Dialect.MYSQL
                    ? "INSERT IGNORE INTO accounts (player_uuid, last_known_name, balance_minor, status, created_at, updated_at, version) "
                            + "VALUES (?, ?, 0, ?, ?, ?, 0)"
                    : "INSERT OR IGNORE INTO accounts (player_uuid, last_known_name, balance_minor, status, created_at, updated_at, version) "
                            + "VALUES (?, ?, 0, ?, ?, ?, 0)";
            try (var statement = connection.prepareStatement(insert)) {
                statement.setString(1, treasuryUuid);
                statement.setString(2, "SYSTEM_TREASURY");
                statement.setString(3, AccountStatus.SYSTEM.name());
                statement.setLong(4, now);
                statement.setLong(5, now);
                statement.executeUpdate();
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
}
