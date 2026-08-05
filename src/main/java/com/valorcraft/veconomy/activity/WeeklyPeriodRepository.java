package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий снимков недельной активности (таблица {@code weekly_activity_periods}).
 * При ротации недели активность закрытой недели сохраняется сюда, а выплата фонда
 * читает именно этот снимок. Статус строки ведёт состояние выплаты игрока:
 * {@link #STATUS_PENDING} → {@link #STATUS_PAID} при успехе либо {@link #STATUS_FAILED}
 * при неуспехе; период остаётся открытым, пока есть не-{@code PAID} строки.
 */
public final class WeeklyPeriodRepository {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_FAILED = "FAILED";

    /** Служебный маркер пустой недели (некому платить): строка сразу в статусе {@code PAID}. */
    public static final UUID EMPTY_WEEK = new UUID(0L, 0L);

    private static final String COLUMNS = "week_id, player_uuid, counted_seconds, points, status, paid_at, transaction_id, "
            + "active_days, time_points, day_points, share";

    /** Есть ли снимок для недели (в любом статусе). */
    public boolean hasWeek(Connection connection, String weekId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM weekly_activity_periods WHERE week_id = ? LIMIT 1")) {
            statement.setString(1, weekId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка проверки снимка недели " + weekId, e);
        }
    }

    /** Все строки снимка недели. */
    public List<WeeklyPeriodRow> listByWeek(Connection connection, String weekId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM weekly_activity_periods WHERE week_id = ?")) {
            statement.setString(1, weekId);
            try (ResultSet rs = statement.executeQuery()) {
                List<WeeklyPeriodRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(map(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения снимка недели " + weekId, e);
        }
    }

    /** Строка снимка недели для конкретного игрока (для «за прошлую неделю» в {@code /money weekly}). */
    public Optional<WeeklyPeriodRow> findByWeekAndPlayer(Connection connection, String weekId, UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM weekly_activity_periods WHERE week_id = ? AND player_uuid = ?")) {
            statement.setString(1, weekId);
            statement.setString(2, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения снимка игрока " + playerId + " за " + weekId, e);
        }
    }

    /** Добавить строку снимка. Идемпотентно по первичному ключу (week_id, player_uuid). */
    public void insert(Connection connection, DatabaseManager.Dialect dialect, WeeklyPeriodRow row) {
        String sql = dialect == DatabaseManager.Dialect.MYSQL
                ? "INSERT IGNORE INTO weekly_activity_periods (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                : "INSERT OR IGNORE INTO weekly_activity_periods (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.weekId());
            statement.setString(2, row.playerId().toString());
            statement.setLong(3, row.countedSeconds());
            statement.setLong(4, row.points());
            statement.setString(5, row.status());
            setNullableLong(statement, 6, row.paidAt() > 0 ? row.paidAt() : null);
            statement.setString(7, row.transactionId());
            statement.setInt(8, row.activeDays());
            statement.setLong(9, row.timePoints());
            statement.setLong(10, row.dayPoints());
            statement.setLong(11, row.share());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи снимка недели " + row.weekId(), e);
        }
    }

    /** Отметить строку выплаченной (успех или идемпотентный повтор). */
    public void markPaid(Connection connection, String weekId, UUID playerId, long paidAt, String transactionId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE weekly_activity_periods SET status = ?, paid_at = ?, transaction_id = ? "
                        + "WHERE week_id = ? AND player_uuid = ?")) {
            statement.setString(1, STATUS_PAID);
            setNullableLong(statement, 2, paidAt > 0 ? paidAt : null);
            statement.setString(3, transactionId);
            statement.setString(4, weekId);
            statement.setString(5, playerId.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка отметки выплаты " + playerId + " за " + weekId, e);
        }
    }

    /** Отметить строку неуспешной выплаты (период остаётся открытым). */
    public void markFailed(Connection connection, String weekId, UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE weekly_activity_periods SET status = ? WHERE week_id = ? AND player_uuid = ?")) {
            statement.setString(1, STATUS_FAILED);
            statement.setString(2, weekId);
            statement.setString(3, playerId.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка отметки неуспешной выплаты " + playerId + " за " + weekId, e);
        }
    }

    /**
     * Закрыт ли период: не осталось строк в статусе ожидания или неуспеха.
     * Пустая неделя (некому платить) считается закрытой.
     */
    public boolean allPaid(Connection connection, String weekId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM weekly_activity_periods WHERE week_id = ? AND status <> ?")) {
            statement.setString(1, weekId);
            statement.setString(2, STATUS_PAID);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) == 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка проверки завершения недели " + weekId, e);
        }
    }

    /** Вставить служебную строку пустой недели (сразу {@code PAID}), чтобы неделя считалась закрытой. */
    public void insertEmpty(Connection connection, DatabaseManager.Dialect dialect, String weekId, long paidAt) {
        insert(connection, dialect,
                new WeeklyPeriodRow(weekId, EMPTY_WEEK, 0, 0, STATUS_PAID, paidAt, null));
    }

    private static WeeklyPeriodRow map(ResultSet rs) throws SQLException {
        return new WeeklyPeriodRow(
                rs.getString("week_id"),
                UUID.fromString(rs.getString("player_uuid")),
                rs.getLong("counted_seconds"),
                rs.getLong("points"),
                rs.getString("status"),
                rs.getLong("paid_at"),
                rs.getString("transaction_id"),
                rs.getInt("active_days"),
                rs.getLong("time_points"),
                rs.getLong("day_points"),
                rs.getLong("share"));
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }
}