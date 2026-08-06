package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.config.EconomySettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Function;

/**
 * Управление подключением к базе данных. Два движка за конфигом {@code database.type}:
 * <ul>
 *   <li><b>SQLite</b> — локальный файл, одно соединение, PRAGMA (WAL, busy timeout).
 *       Используется для разработки и тестов.</li>
 *   <li><b>MySQL</b> — внешний сервер через пул соединений HikariCP (переживает обрывы
 *       простаивающих соединений, допускает параллельные транзакции).</li>
 * </ul>
 * Транзакции денежных операций выполняются через {@link #inTransaction(Function)} и
 * гарантируют атомарность «баланс + журнал».
 */
public final class DatabaseManager {

    public enum Dialect { SQLITE, MYSQL }

    private Dialect dialect;
    private HikariDataSource pool;   // MySQL
    private Connection connection;   // SQLite (одно соединение)
    private Path path;

    /**
     * Раздельный монитор транзакций SQLite. MySQL работает через пул соединений и не
     * должен сериализоваться общим замком базы (см. {@link #inTransaction}): только
     * SQLite с одним соединением требует взаимного исключения транзакций.
     */
    private final Object sqliteTransactionLock = new Object();

    public void open(Path dbPath, EconomySettings settings) {
        if (connection != null || pool != null) {
            throw new IllegalStateException("DatabaseManager уже открыт: " + path);
        }
        if ("mysql".equalsIgnoreCase(settings.dbType)) {
            dialect = Dialect.MYSQL;
            openMySql(dbPath, settings);
        } else {
            dialect = Dialect.SQLITE;
            openSqlite(dbPath, settings);
        }
    }

    private void openSqlite(Path dbPath, EconomySettings settings) {
        this.path = dbPath;
        try {
            Files.createDirectories(dbPath.getParent());
            Class.forName("org.sqlite.JDBC");
            SQLiteConfig config = new SQLiteConfig();
            config.enforceForeignKeys(true);
            config.setBusyTimeout(settings.busyTimeoutMillis);
            config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            config.setJournalMode(settings.walEnabled ? SQLiteConfig.JournalMode.WAL : SQLiteConfig.JournalMode.DELETE);
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath(), config.toProperties());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = " + settings.busyTimeoutMillis);
            }
            MigrationManager.migrate(connection, Dialect.SQLITE);
        } catch (ClassNotFoundException | SQLException | IOException e) {
            throw new DatabaseException("Не удалось открыть SQLite-базу " + dbPath, e);
        }
    }

    private void openMySql(Path dbPath, EconomySettings settings) {
        this.path = dbPath;
        String url = "jdbc:mysql://" + settings.mysqlHost + ":" + settings.mysqlPort + "/" + settings.mysqlDatabase
                + "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=utf8"
                + "&serverTimezone=UTC&useSSL=false";
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            HikariConfig cfg = new HikariConfig();
            cfg.setPoolName("veconomy");
            cfg.setJdbcUrl(url);
            cfg.setUsername(settings.mysqlUser);
            cfg.setPassword(settings.mysqlPassword);
            cfg.setMaximumPoolSize(Math.max(1, settings.mysqlPoolSize));
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(settings.busyTimeoutMillis);
            this.pool = new HikariDataSource(cfg);
            try (Connection c = pool.getConnection()) {
                MigrationManager.migrate(c, Dialect.MYSQL);
            }
        } catch (ClassNotFoundException | SQLException e) {
            if (pool != null) {
                pool.close();
                pool = null;
            }
            throw new DatabaseException("Не удалось подключиться к MySQL: " + url
                    + " (проверьте database.mysql.* и права пользователя)", e);
        }
    }

    public Path path() {
        return path;
    }

    public Dialect dialect() {
        return dialect;
    }

    public boolean isOpen() {
        return connection != null || pool != null;
    }

    /** Версия схемы базы: PRAGMA user_version для SQLite, таблица meta для MySQL. */
    public int schemaVersion() {
        if (dialect == Dialect.MYSQL) {
            try (Connection c = pool.getConnection()) {
                return MigrationManager.readVersion(c, Dialect.MYSQL);
            } catch (SQLException e) {
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

    /**
     * Выполнить работу в одной транзакции. Ошибка откатывает транзакцию.
     * Для SQLite соединение одно — транзакции сериализуются отдельным замком
     * {@code sqliteTransactionLock}; для MySQL соединение берётся из пула, и метод
     * НЕ блокируется общим монитором (параллельные потоки получают разные
     * соединения и независимые транзакции). Один скан никогда не держит единую
     * транзакцию/глобальный замок на всё время работы.
     */
    public <T> T inTransaction(Function<Connection, T> work) {
        if (dialect == Dialect.MYSQL) {
            return inTransactionMySql(work);
        }
        synchronized (sqliteTransactionLock) {
            return inTransactionSqlite(work);
        }
    }

    private <T> T inTransactionSqlite(Function<Connection, T> work) {
        if (connection == null) {
            throw new DatabaseException("База данных не открыта");
        }
        boolean previousAutoCommit = true;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            T result = work.apply(connection);
            connection.commit();
            return result;
        } catch (SQLException e) {
            rollbackQuietly(connection, e);
            throw new DatabaseException("Ошибка транзакции", e);
        } catch (RuntimeException e) {
            rollbackQuietly(connection, e);
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
            }
        }
    }

    private <T> T inTransactionMySql(Function<Connection, T> work) {
        if (pool == null) {
            throw new DatabaseException("База данных не открыта");
        }
        try (Connection c = pool.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException e) {
                rollbackQuietly(c, e);
                throw new DatabaseException("Ошибка транзакции", e);
            } catch (RuntimeException e) {
                rollbackQuietly(c, e);
                throw e;
            } finally {
                try {
                    c.setAutoCommit(previousAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Не удалось получить соединение из пула", e);
        }
    }

    private static void rollbackQuietly(Connection c, Throwable cause) {
        try {
            c.rollback();
        } catch (SQLException rollbackException) {
            cause.addSuppressed(rollbackException);
        }
    }

    public void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new DatabaseException("Ошибка закрытия базы данных", e);
            } finally {
                connection = null;
            }
        }
    }
}
