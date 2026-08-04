package com.valorcraft.veconomy.persistence;

import com.google.gson.Gson;
import com.valorcraft.veconomy.api.EscrowState;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Репозиторий эскроу (таблица {@code escrow}). */
public final class EscrowRepository {

    private static final Gson GSON = new Gson();

    public void insert(Connection connection, EscrowRow row) {
        try (var statement = connection.prepareStatement(
                "INSERT INTO escrow (reference_id, owner_uuid, amount_minor, state, created_at, updated_at, metadata_json) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, row.referenceId());
            statement.setString(2, row.ownerUuid().toString());
            statement.setLong(3, row.amountMinor());
            statement.setString(4, row.state().name());
            statement.setLong(5, row.createdAt());
            statement.setLong(6, row.updatedAt());
            statement.setString(7, row.metadata() == null || row.metadata().isEmpty()
                    ? null : GSON.toJson(row.metadata()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка создания эскроу-записи " + row.referenceId(), e);
        }
    }

    public Optional<EscrowRow> find(Connection connection, String referenceId) {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM escrow WHERE reference_id = ?")) {
            statement.setString(1, referenceId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения эскроу-записи " + referenceId, e);
        }
    }

    public boolean updateState(Connection connection, String referenceId, EscrowState state, long now) {
        try (var statement = connection.prepareStatement(
                "UPDATE escrow SET state = ?, updated_at = ? WHERE reference_id = ?")) {
            statement.setString(1, state.name());
            statement.setLong(2, now);
            statement.setString(3, referenceId);
            return statement.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка обновления эскроу-записи " + referenceId, e);
        }
    }

    /** Сумма всех зарезервированных (не финализированных) средств. */
    public long sumReserved(Connection connection) {
        try (var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COALESCE(SUM(amount_minor), 0) FROM escrow WHERE state = 'RESERVED'")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта зарезервированных средств", e);
        }
    }

    private static EscrowRow map(ResultSet rs) throws SQLException {
        String metadataJson = rs.getString("metadata_json");
        return new EscrowRow(
                rs.getString("reference_id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getLong("amount_minor"),
                EscrowState.valueOf(rs.getString("state")),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                metadataJson != null ? parseMetadata(metadataJson) : Map.of());
    }

    private static Map<String, String> parseMetadata(String json) {
        try {
            Map<?, ?> map = GSON.fromJson(json, Map.class);
            if (map == null) {
                return Map.of();
            }
            Map<String, String> result = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        } catch (RuntimeException e) {
            return Map.of();
        }
    }
}
