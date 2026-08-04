package com.valorcraft.veconomy.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Служебная таблица {@code meta}: строковые флаги (legacy-импорт, распределённая неделя фонда).
 * SQL-диалект передаётся явно, так как upsert отличается в MySQL и SQLite.
 */
public final class MetaRepository {

    private MetaRepository() {}

    public static String get(Connection connection, DatabaseManager.Dialect dialect, String key) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM meta WHERE meta_key = ?")) {
            statement.setString(1, key);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения meta[" + key + "]", e);
        }
    }

    public static void set(Connection connection, DatabaseManager.Dialect dialect, String key, String value) {
        String sql = dialect == DatabaseManager.Dialect.MYSQL
                ? "INSERT INTO meta (meta_key, value) VALUES (?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value)"
                : "INSERT OR REPLACE INTO meta (meta_key, value) VALUES (?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи meta[" + key + "]", e);
        }
    }
}
