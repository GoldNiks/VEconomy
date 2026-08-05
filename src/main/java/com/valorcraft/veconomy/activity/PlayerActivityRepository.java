package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Репозиторий активности игроков (таблица {@code player_activity}). */
public final class PlayerActivityRepository {

    private static final String COLUMNS = "player_uuid, first_seen_at, last_seen_at, total_online_seconds, "
            + "total_active_seconds, total_afk_seconds, current_week_id, "
            + "last_activity_at, last_dimension, excluded_from_rewards";

    public Optional<PlayerActivityRow> find(Connection connection, UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM player_activity WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения активности " + playerId, e);
        }
    }

    /** Сохранить снимок активности игрока (вставить или обновить). */
    public void upsert(Connection connection, DatabaseManager.Dialect dialect, PlayerActivityRow row) {
        String sql;
        if (dialect == DatabaseManager.Dialect.MYSQL) {
            sql = "INSERT INTO player_activity (" + COLUMNS + ") "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE last_seen_at = VALUES(last_seen_at), "
                    + "total_online_seconds = VALUES(total_online_seconds), "
                    + "total_active_seconds = VALUES(total_active_seconds), "
                    + "total_afk_seconds = VALUES(total_afk_seconds), "
                    + "current_week_id = VALUES(current_week_id), "
                    + "last_activity_at = VALUES(last_activity_at), "
                    + "last_dimension = VALUES(last_dimension)";
        } else {
            sql = "INSERT OR REPLACE INTO player_activity (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.playerId().toString());
            statement.setLong(2, row.firstSeenAt());
            statement.setLong(3, row.lastSeenAt());
            statement.setLong(4, row.totalOnlineSeconds());
            statement.setLong(5, row.totalActiveSeconds());
            statement.setLong(6, row.totalAfkSeconds());
            statement.setString(7, row.currentWeekId());
            statement.setObject(8, row.lastActivityAt() > 0 ? row.lastActivityAt() : null);
            statement.setString(9, row.lastDimension());
            statement.setInt(10, row.excludedFromRewards() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка сохранения активности " + row.playerId(), e);
        }
    }

    private static PlayerActivityRow map(ResultSet rs) throws SQLException {
        return new PlayerActivityRow(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("first_seen_at"),
                rs.getLong("last_seen_at"),
                rs.getLong("total_online_seconds"),
                rs.getLong("total_active_seconds"),
                rs.getLong("total_afk_seconds"),
                rs.getString("current_week_id"),
                rs.getLong("last_activity_at"),
                rs.getString("last_dimension"),
                rs.getInt("excluded_from_rewards") != 0);
    }
}
