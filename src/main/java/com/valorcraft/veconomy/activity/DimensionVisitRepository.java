package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.DatabaseManager.Dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Репозиторий личных посещений измерений (таблица {@code dimension_visits}). */
public final class DimensionVisitRepository {

    /** Записать посещение измерения игроком. Идемпотентно по (player_uuid, dimension). */
    public void recordVisit(Connection connection, Dialect dialect, UUID playerId, String dimension, long visitedAt) {
        String sql = dialect == Dialect.MYSQL
                ? "INSERT IGNORE INTO dimension_visits (player_uuid, dimension, first_visited_at) VALUES (?, ?, ?)"
                : "INSERT OR IGNORE INTO dimension_visits (player_uuid, dimension, first_visited_at) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, dimension);
            statement.setLong(3, visitedAt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи посещения измерения " + playerId, e);
        }
    }

    /** Посещал ли игрок измерение (и когда — первый раз). */
    public java.util.Optional<DimensionVisitRow> find(Connection connection, UUID playerId, String dimension) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, dimension, first_visited_at FROM dimension_visits "
                        + "WHERE player_uuid = ? AND dimension = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, dimension);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(new DimensionVisitRow(
                            UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                }
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения посещений измерений " + playerId, e);
        }
    }

    /** Все посещённые измерения игрока (для статистики/аудита). */
    public java.util.List<DimensionVisitRow> listByPlayer(Connection connection, UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, dimension, first_visited_at FROM dimension_visits "
                        + "WHERE player_uuid = ? ORDER BY first_visited_at")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                java.util.List<DimensionVisitRow> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    rows.add(new DimensionVisitRow(
                            UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения посещений измерений " + playerId, e);
        }
    }
}
