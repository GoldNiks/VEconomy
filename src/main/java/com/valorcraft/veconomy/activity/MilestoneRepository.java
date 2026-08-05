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

    /** Идентификаторы всех выданных этапов игроку (без фильтра источника). */
    public Set<String> claimedIds(Connection connection, UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT milestone_id FROM claimed_milestones WHERE player_uuid = ?")) {
            statement.setString(1, playerId.toString());
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

    /** Одна выдача этапа игроку (пусто, если не выдавался). */
    public java.util.Optional<MilestoneRow> find(Connection connection, UUID playerId, String milestoneId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, milestone_id, amount_minor, claimed_at, source, transaction_id "
                        + "FROM claimed_milestones WHERE player_uuid = ? AND milestone_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, milestoneId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(new MilestoneRow(
                            UUID.fromString(rs.getString(1)), rs.getString(2),
                            rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6)));
                }
                return java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения милстоуна " + playerId, e);
        }
    }

    /** Все выдачи этапов игроку (для команды {@code list <игрок>}). */
    public java.util.List<MilestoneRow> claims(Connection connection, UUID playerId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_uuid, milestone_id, amount_minor, claimed_at, source, transaction_id "
                        + "FROM claimed_milestones WHERE player_uuid = ? ORDER BY claimed_at")) {
            statement.setString(1, playerId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                java.util.List<MilestoneRow> rows = new java.util.ArrayList<>();
                while (rs.next()) {
                    rows.add(new MilestoneRow(
                            UUID.fromString(rs.getString(1)), rs.getString(2),
                            rs.getLong(3), rs.getLong(4), rs.getString(5), rs.getString(6)));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения милстоунов " + playerId, e);
        }
    }

    /** Снять отметку о выдаче (revoke). Не удаляет ledger-запись и не трогает баланс. */
    public void revoke(Connection connection, UUID playerId, String milestoneId) {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM claimed_milestones WHERE player_uuid = ? AND milestone_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, milestoneId);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка отзыва милстоуна " + playerId, e);
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
