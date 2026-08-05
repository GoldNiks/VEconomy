package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** Репозиторий выплат недельного фонда (таблица {@code weekly_payouts}). */
public final class WeeklyPayoutRepository {

    /** Записать выплату. Идемпотентно по первичному ключу (week_id, player_uuid). */
    public void insert(Connection connection, DatabaseManager.Dialect dialect, WeeklyPayoutRow row) {
        String sql = dialect == DatabaseManager.Dialect.MYSQL
                ? "INSERT IGNORE INTO weekly_payouts (week_id, player_uuid, activity_seconds, points, amount_minor, paid_at, transaction_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)"
                : "INSERT OR IGNORE INTO weekly_payouts (week_id, player_uuid, activity_seconds, points, amount_minor, paid_at, transaction_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.weekId());
            statement.setString(2, row.playerId().toString());
            statement.setLong(3, row.activitySeconds());
            statement.setInt(4, row.points());
            statement.setLong(5, row.amountMinor());
            statement.setLong(6, row.paidAt());
            statement.setString(7, row.transactionId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи недельной выплаты " + row.playerId(), e);
        }
    }

    /** Сумма выплаты игроку за конкретную неделю (для «за прошлую неделю» в {@code /money weekly}). */
    public Optional<Long> amountForWeek(Connection connection, String weekId, UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT amount_minor FROM weekly_payouts WHERE week_id = ? AND player_uuid = ?")) {
            statement.setString(1, weekId);
            statement.setString(2, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения выплаты " + playerId + " за " + weekId, e);
        }
    }
}
