package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.config.EconomySettings;
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
 * Управление подключением к SQLite: открытие с правильными PRAGMA (WAL, foreign keys,
 * busy timeout), транзакции и корректное закрытие при остановке сервера.
 * <p>
 * Первая версия работает на серверном потоке (команды и события Forge), поэтому
 * достаточно одного соединения. Транзакции денежных операций выполняются через
 * {@link #inTransaction(Function)} и гарантируют атомарность «баланс + журнал».
 */
public final class DatabaseManager {

    private Connection connection;
    private Path path;

    public void open(Path dbPath, EconomySettings settings) {
        if (connection != null) {
            throw new IllegalStateException("DatabaseManager уже открыт: " + path);
        }
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
            MigrationManager.migrate(connection);
        } catch (ClassNotFoundException | SQLException | IOException e) {
            throw new DatabaseException("Не удалось открыть базу данных " + dbPath, e);
        }
    }

    public Path path() {
        return path;
    }

    public boolean isOpen() {
        return connection != null;
    }

    /**
     * Выполнить работу в одной транзакции. Ошибка откатывает транзакцию.
     * <p>
     * Метод синхронизирован: соединение одно, и параллельные потоки не должны
     * перемешивать операторы. Дополнительно целостность защищена полем {@code version}
     * (optimistic locking).
     */
    public synchronized <T> T inTransaction(Function<Connection, T> work) {
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
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            throw new DatabaseException("Ошибка транзакции", e);
        } catch (RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackException) {
                e.addSuppressed(rollbackException);
            }
            throw e;
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException ignored) {
            }
        }
    }

    /** Версия схемы базы (PRAGMA user_version). */
    public int schemaVersion() {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Не удалось прочитать версию схемы", e);
        }
    }

    public void close() {
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
