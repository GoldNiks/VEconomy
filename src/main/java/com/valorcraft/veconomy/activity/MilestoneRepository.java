package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Репозиторий личных этапов (таблица {@code claimed_milestones}). */
public final class MilestoneRepository {

    /** Идентификаторы уже выданных этапов игроку из указанного источника. */
    public Set<String> claimedIds(Connection connection, UUID playerId, String source) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT milestone_id FROM claimed_milestones WHERE player_uuid = ? AND source = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, source);
            try (ResultSet rs = statement.executeQuery()) {
                Set<String> ids = new HashSet<>();
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
                return ids;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения милстоунов " + playerId, e);
        }
    }

    /** Отметить этап выданным. Идемпотентно по первичному ключу (player_uuid, milestone_id). */
    public void claim(Connection connection, DatabaseManager.Dialect dialect, MilestoneRow row) {
        String sql = dialect == DatabaseManager.Dialect.MYSQL
                ? "INSERT IGNORE INTO claimed_milestones (player_uuid, milestone_id, amount_minor, claimed_at, source, transaction_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT OR IGNORE INTO claimed_milestones (player_uuid, milestone_id, amount_minor, claimed_at, source, transaction_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, row.playerId().toString());
            statement.setString(2, row.milestoneId());
            statement.setLong(3, row.amountMinor());
            statement.setLong(4, row.claimedAt());
            statement.setString(5, row.source());
            statement.setString(6, row.transactionId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи милстоуна " + row.playerId(), e);
        }
    }
}
