package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Репозиторий активности по дням (таблица {@code weekly_activity_days}).
 * Записи инкрементально пополняются при сохранении сессий; закрытие недели читает
 * весь снимок {@code (player, day)} и строит из него очки/доли плана.
 */
public final class WeeklyActivityDayRepository {

    /** Прибавить активные секунды к дню игрока (или создать строку, если дня ещё нет). */
    public void addSeconds(Connection connection, DatabaseManager.Dialect dialect, UUID playerId,
                           String weekId, String dayKey, long seconds) {
        if (seconds <= 0) {
            return;
        }
        if (dialect == DatabaseManager.Dialect.MYSQL) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO weekly_activity_days (player_uuid, week_id, day_key, active_seconds) "
                            + "VALUES (?, ?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE active_seconds = active_seconds + VALUES(active_seconds)")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, weekId);
                statement.setString(3, dayKey);
                statement.setLong(4, seconds);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new DatabaseException("Ошибка накопления активности дня " + playerId + " " + dayKey, e);
            }
            return;
        }
        // SQLite: upsert-прибавление через INSERT OR IGNORE + UPDATE (иначе нет синтаксиса ON CONFLICT ADD).
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO weekly_activity_days (player_uuid, week_id, day_key, active_seconds) "
                        + "VALUES (?, ?, ?, ?)")) {
            insert.setString(1, playerId.toString());
            insert.setString(2, weekId);
            insert.setString(3, dayKey);
            insert.setLong(4, seconds);
            int created = insert.executeUpdate();
            if (created == 0) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE weekly_activity_days SET active_seconds = active_seconds + ? "
                                + "WHERE player_uuid = ? AND week_id = ? AND day_key = ?")) {
                    update.setLong(1, seconds);
                    update.setString(2, playerId.toString());
                    update.setString(3, weekId);
                    update.setString(4, dayKey);
                    update.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка накопления активности дня " + playerId + " " + dayKey, e);
        }
    }

    /** Все записи активности за неделю (закрытие недели / прогноз текущей). */
    public List<WeeklyActivityDayRow> listByWeek(Connection connection, String weekId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, week_id, day_key, active_seconds FROM weekly_activity_days WHERE week_id = ?")) {
            statement.setString(1, weekId);
            try (ResultSet rs = statement.executeQuery()) {
                List<WeeklyActivityDayRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new WeeklyActivityDayRow(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("week_id"),
                            rs.getString("day_key"),
                            rs.getLong("active_seconds")));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения активности дня недели " + weekId, e);
        }
    }
}
