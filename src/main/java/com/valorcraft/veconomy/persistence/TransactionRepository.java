package com.valorcraft.veconomy.persistence;

import com.google.gson.Gson;
import com.valorcraft.veconomy.api.TransactionType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Репозиторий журнала операций (таблица {@code transactions}). */
public final class TransactionRepository {

    private static final Gson GSON = new Gson();

    /** Вставить запись журнала и вернуть её идентификатор. */
    public String insert(Connection connection, TransactionRow row) {
        String id = row.transactionId() != null ? row.transactionId() : UUID.randomUUID().toString();
        try (var statement = connection.prepareStatement(
                "INSERT INTO transactions (transaction_id, transaction_type, source_uuid, target_uuid, amount_minor, "
                        + "created_at, actor_uuid, reason, idempotency_key, metadata_json, source_balance_after, target_balance_after) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, id);
            statement.setString(2, row.type().name());
            statement.setString(3, row.sourceUuid() != null ? row.sourceUuid().toString() : null);
            statement.setString(4, row.targetUuid() != null ? row.targetUuid().toString() : null);
            statement.setLong(5, row.amountMinor());
            statement.setLong(6, row.createdAt());
            statement.setString(7, row.actorUuid() != null ? row.actorUuid().toString() : null);
            statement.setString(8, row.reason());
            statement.setString(9, row.idempotencyKey());
            statement.setString(10, row.metadata() == null || row.metadata().isEmpty()
                    ? null : GSON.toJson(row.metadata()));
            statement.setObject(11, row.sourceBalanceAfter());
            statement.setObject(12, row.targetBalanceAfter());
            statement.executeUpdate();
            return id;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи транзакции", e);
        }
    }

    public Optional<TransactionRow> findById(Connection connection, String transactionId) {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE transaction_id = ?")) {
            statement.setString(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения транзакции " + transactionId, e);
        }
    }

    public Optional<TransactionRow> findByIdempotencyKey(Connection connection, String idempotencyKey) {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка поиска транзакции по идемпотентному ключу", e);
        }
    }

    /** История операций игрока (участник как источник или получатель), новые сверху. */
    public List<TransactionRow> history(Connection connection, UUID playerId, int offset, int limit) {
        List<TransactionRow> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE source_uuid = ? OR target_uuid = ? "
                        + "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
            String uuid = playerId.toString();
            statement.setString(1, uuid);
            statement.setString(2, uuid);
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения истории игрока " + playerId, e);
        }
    }

    public long countAll(Connection connection) {
        try (var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM transactions")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта транзакций", e);
        }
    }

    /** Количество операций конкретного игрока (участник как источник или получатель). */
    public long countForPlayer(Connection connection, UUID playerId) {
        try (var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM transactions WHERE source_uuid = ? OR target_uuid = ?")) {
            String uuid = playerId.toString();
            statement.setString(1, uuid);
            statement.setString(2, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта операций игрока " + playerId, e);
        }
    }

    public long countByTypeSince(Connection connection, TransactionType type, long sinceMillis) {
        return aggregateByTypeSince(connection, type, sinceMillis, "COUNT(*)");
    }

    /** Все переводы между игроками с {@code sinceMillis} (для аудит-эвристик), новые сверху. */
    public List<TransactionRow> transfersSince(Connection connection, long sinceMillis) {
        List<TransactionRow> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE transaction_type = ? AND created_at >= ? "
                        + "ORDER BY created_at DESC")) {
            statement.setString(1, TransactionType.PLAYER_TRANSFER.name());
            statement.setLong(2, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения переводов с " + sinceMillis, e);
        }
    }

    public long sumAmountByTypeSince(Connection connection, TransactionType type, long sinceMillis) {
        return aggregateByTypeSince(connection, type, sinceMillis, "COALESCE(SUM(amount_minor), 0)");
    }

    private long aggregateByTypeSince(Connection connection, TransactionType type, long sinceMillis, String aggregate) {
        try (var statement = connection.prepareStatement(
                "SELECT " + aggregate + " FROM transactions WHERE transaction_type = ? AND created_at >= ?")) {
            statement.setString(1, type.name());
            statement.setLong(2, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка агрегации транзакций по типу " + type, e);
        }
    }

    private static TransactionRow map(ResultSet rs) throws SQLException {
        String sourceUuid = rs.getString("source_uuid");
        String targetUuid = rs.getString("target_uuid");
        String actorUuid = rs.getString("actor_uuid");
        String metadataJson = rs.getString("metadata_json");
        return new TransactionRow(
                rs.getString("transaction_id"),
                TransactionType.valueOf(rs.getString("transaction_type")),
                sourceUuid != null ? UUID.fromString(sourceUuid) : null,
                targetUuid != null ? UUID.fromString(targetUuid) : null,
                rs.getLong("amount_minor"),
                rs.getLong("created_at"),
                actorUuid != null ? UUID.fromString(actorUuid) : null,
                rs.getString("reason"),
                rs.getString("idempotency_key"),
                metadataJson != null ? parseMetadata(metadataJson) : Map.of(),
                nullableLong(rs, "source_balance_after"),
                nullableLong(rs, "target_balance_after"));
    }

    /** Прочитать nullable-столбец как Long (SQL NULL → null, а не 0). */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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
