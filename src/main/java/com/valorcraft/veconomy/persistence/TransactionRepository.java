package com.valorcraft.veconomy.persistence;

import com.google.gson.Gson;
import com.valorcraft.veconomy.api.TransactionType;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * Переводы окна с жёстким лимитом прямо в SQL ({@code LIMIT}): для скана загружается
     * не больше {@code limit} строк, дополнительные строки для определения «ограничено»
     * не читаются в память. Порядок стабильный: {@code created_at}, затем
     * {@code transaction_id} (новые сверху, tie-break по id) — между прогонами окна
     * результат воспроизводим.
     */
    public List<TransactionRow> transfersSinceLimited(Connection connection, long sinceMillis,
                                                      int limit) {
        List<TransactionRow> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE transaction_type = ? AND created_at >= ? "
                        + "ORDER BY created_at DESC, transaction_id DESC LIMIT ?")) {
            statement.setString(1, TransactionType.PLAYER_TRANSFER.name());
            statement.setLong(2, sinceMillis);
            statement.setInt(3, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения ограниченного набора переводов с "
                    + sinceMillis, e);
        }
    }

    public long sumAmountByTypeSince(Connection connection, TransactionType type, long sinceMillis) {
        return aggregateByTypeSince(connection, type, sinceMillis, "COALESCE(SUM(amount_minor), 0)");
    }

    /**
     * Переводы одного игрока с {@code sinceMillis} (участник с любой стороны) — для
     * {@code scanPlayer}: эвристики получают только строки этого игрока, а не весь журнал.
     */
    public List<TransactionRow> transfersSinceForPlayer(Connection connection, UUID playerId,
                                                        long sinceMillis) {
        List<TransactionRow> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE transaction_type = ? AND created_at >= ? "
                        + "AND (source_uuid = ? OR target_uuid = ?) ORDER BY created_at DESC")) {
            String uuid = playerId.toString();
            statement.setString(1, TransactionType.PLAYER_TRANSFER.name());
            statement.setLong(2, sinceMillis);
            statement.setString(3, uuid);
            statement.setString(4, uuid);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения переводов игрока " + playerId, e);
        }
    }

    /**
     * Переводы одного игрока с окна {@code sinceMillis} с жёстким лимитом ПРЯМО В SQL:
     * в память читается не больше {@code limit} строк (дополнительные для определения
     * «ограничено» не выгружаются). Порядок стабильный и совпадает с полным вариантом:
     * {@code created_at DESC}, затем {@code transaction_id DESC} — между прогонами окна
     * результат воспроизводим, а {@code scanPlayer} не выгружает всю историю игрока.
     */
    public List<TransactionRow> transfersSinceForPlayerLimited(Connection connection, UUID playerId,
                                                               long sinceMillis, int limit) {
        List<TransactionRow> result = new ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT * FROM transactions WHERE transaction_type = ? AND created_at >= ? "
                        + "AND (source_uuid = ? OR target_uuid = ?) "
                        + "ORDER BY created_at DESC, transaction_id DESC LIMIT ?")) {
            String uuid = playerId.toString();
            statement.setString(1, TransactionType.PLAYER_TRANSFER.name());
            statement.setLong(2, sinceMillis);
            statement.setString(3, uuid);
            statement.setString(4, uuid);
            statement.setInt(5, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения ограниченных переводов игрока "
                    + playerId, e);
        }
    }

    /**
     * Переводы, обе стороны которых входят в {@code participants} — ограниченный граф
     * вокруг игрока для {@code scanPlayer}: loop-эвристика получает дополнительные
     * рёбра (A→B→C→A виден при сканировании B), но НЕ весь журнал аудита. Отправители
     * и получатели разбиваются на слайсы по {@link #MAX_IN_PARAMETERS} НЕЗАВИСИМО,
     * и запросы выполняются для всех пар слайсов (перевод между UUID из разных пакетов
     * не теряется); дубликаты по transaction_id убираются. Для множества больше
     * {@code maxParticipants} анализ ограничивается первыми UUID — это защита от
     * неограниченного расширения графа (MySQL и SQLite ограничивают число параметров).
     */
    public List<TransactionRow> transfersBetween(Connection connection, Collection<UUID> participants,
                                                 long sinceMillis) {
        return transfersBetween(connection, participants, sinceMillis, Integer.MAX_VALUE);
    }

    /**
     * Вариант с пределом числа участников: используются первые {@code maxParticipants}
     * UUID (в стабильном порядке обхода коллекции), остальные в граф не попадают.
     */
    public List<TransactionRow> transfersBetween(Connection connection, Collection<UUID> participants,
                                                 long sinceMillis, int maxParticipants) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }
        List<UUID> unique = participants.stream().distinct().toList();
        if (unique.size() > maxParticipants) {
            unique = unique.subList(0, maxParticipants);
        }
        Map<String, TransactionRow> byId = new java.util.LinkedHashMap<>();
        for (int srcStart = 0; srcStart < unique.size(); srcStart += MAX_IN_PARAMETERS) {
            int srcEnd = Math.min(unique.size(), srcStart + MAX_IN_PARAMETERS);
            List<UUID> sourceSlice = unique.subList(srcStart, srcEnd);
            for (int tgtStart = 0; tgtStart < unique.size(); tgtStart += MAX_IN_PARAMETERS) {
                int tgtEnd = Math.min(unique.size(), tgtStart + MAX_IN_PARAMETERS);
                List<UUID> targetSlice = unique.subList(tgtStart, tgtEnd);
                String sql = "SELECT * FROM transactions WHERE transaction_type = ? "
                        + "AND created_at >= ? AND source_uuid IN ("
                        + inPlaceholders(sourceSlice.size()) + ") AND target_uuid IN ("
                        + inPlaceholders(targetSlice.size()) + ")";
                try (var statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    statement.setString(index++, TransactionType.PLAYER_TRANSFER.name());
                    statement.setLong(index++, sinceMillis);
                    for (UUID id : sourceSlice) {
                        statement.setString(index++, id.toString());
                    }
                    for (UUID id : targetSlice) {
                        statement.setString(index++, id.toString());
                    }
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            TransactionRow row = map(rs);
                            byId.putIfAbsent(row.transactionId(), row);
                        }
                    }
                } catch (SQLException e) {
                    throw new DatabaseException("Ошибка чтения переводов графа участников", e);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /**
     * Рёбра персонального графа с ЖЁСТКИМ общим лимитом количества строк
     * ({@code maxRows}) и без повторной выгрузки переводов самого игрока:
     * в SQL-запросах переводы, где участвует {@code focalPlayer}, исключаются
     * (условия {@code source_uuid <> ? AND target_uuid <> ?}) — они уже загружены
     * отдельным персональным запросом и не должны съедать бюджет.
     * <p>
     * Каждый пакет по слайсам участников выполняется с {@code LIMIT остаток+1}:
     * бюджет уменьшается после каждого пакета, при его исчерпании запросы
     * прекращаются, а результат помечается «ограничено». Дубликаты по
     * {@code transaction_id} удаляются, итоговый список не превышает {@code maxRows}.
     */
    public LimitedRows transfersBetweenLimited(Connection connection, Collection<UUID> participants,
                                               UUID focalPlayer, long sinceMillis, int maxRows) {
        if (participants == null || participants.isEmpty() || maxRows <= 0) {
            return new LimitedRows(List.of(), false);
        }
        List<UUID> unique = participants.stream().distinct().toList();
        Map<String, TransactionRow> byId = new java.util.LinkedHashMap<>();
        int remaining = maxRows;
        boolean limited = false;
        outer:
        for (int srcStart = 0; srcStart < unique.size(); srcStart += MAX_IN_PARAMETERS) {
            int srcEnd = Math.min(unique.size(), srcStart + MAX_IN_PARAMETERS);
            List<UUID> sourceSlice = unique.subList(srcStart, srcEnd);
            for (int tgtStart = 0; tgtStart < unique.size(); tgtStart += MAX_IN_PARAMETERS) {
                int tgtEnd = Math.min(unique.size(), tgtStart + MAX_IN_PARAMETERS);
                List<UUID> targetSlice = unique.subList(tgtStart, tgtEnd);
                if (remaining <= 0) {
                    // Бюджет исчерпан — дальнейшие запросы НЕ выполняются; то, что
                    // просмотрены ещё не все пары слайсов, означает сужение области
                    // графа (ограничение), а не «всё прочитано».
                    limited = true;
                    break outer;
                }
                // LIMIT остаток+1: если в этом пакете больше строк, чем осталось
                // в бюджете — это РЕАЛЬНОЕ превышение (не догадка).
                String sql = "SELECT * FROM transactions WHERE transaction_type = ? "
                        + "AND created_at >= ? AND source_uuid IN (" + inPlaceholders(sourceSlice.size())
                        + ") AND target_uuid IN (" + inPlaceholders(targetSlice.size())
                        + ") AND source_uuid <> ? AND target_uuid <> ? "
                        + "ORDER BY created_at DESC, transaction_id DESC LIMIT ?";
                try (var statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    statement.setString(index++, TransactionType.PLAYER_TRANSFER.name());
                    statement.setLong(index++, sinceMillis);
                    for (UUID id : sourceSlice) {
                        statement.setString(index++, id.toString());
                    }
                    for (UUID id : targetSlice) {
                        statement.setString(index++, id.toString());
                    }
                    statement.setString(index++, focalPlayer.toString());
                    statement.setString(index++, focalPlayer.toString());
                    statement.setInt(index, remaining + 1);
                    int batchAdded = 0;
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            TransactionRow row = map(rs);
                            if (byId.containsKey(row.transactionId())) {
                                continue;
                            }
                            if (batchAdded >= remaining) {
                                // LIMIT = остаток+1: лишняя строка означает, что в графе
                                // строк больше, чем позволяет бюджет — реальное ограничение.
                                limited = true;
                                break outer;
                            }
                            byId.put(row.transactionId(), row);
                            batchAdded++;
                        }
                    }
                    remaining -= batchAdded;
                } catch (SQLException e) {
                    throw new DatabaseException("Ошибка чтения лимитированного графа участников", e);
                }
            }
        }
        return new LimitedRows(new ArrayList<>(byId.values()), limited);
    }

    /** Результат лимитированного запроса графа: строки и признак «ограничено». */
    public record LimitedRows(List<TransactionRow> rows, boolean limited) {
    }

    private static String inPlaceholders(int size) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < size; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        return sql.toString();
    }

    /** Максимум элементов в IN-списке (порог достаточно низкий для обоих диалектов). */
    private static final int MAX_IN_PARAMETERS = 200;

    /**
     * Число исходящих переводов по отправителю с {@code sinceMillis} (SQL-агрегация:
     * счётчик не выгружает весь журнал в память). Опционально — только один отправитель.
     */
    public java.util.Map<UUID, Integer> outgoingTransferCountsBySource(Connection connection,
                                                                       long sinceMillis, UUID onlySource) {
        java.util.Map<UUID, Integer> counts = new java.util.HashMap<>();
        String sql = "SELECT source_uuid, COUNT(*) FROM transactions "
                + "WHERE transaction_type = ? AND created_at >= ? AND source_uuid IS NOT NULL "
                + (onlySource != null ? "AND source_uuid = ?" : "")
                + " GROUP BY source_uuid";
        try (var statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, TransactionType.PLAYER_TRANSFER.name());
            statement.setLong(index++, sinceMillis);
            if (onlySource != null) {
                statement.setString(index, onlySource.toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    counts.put(UUID.fromString(rs.getString(1)), rs.getInt(2));
                }
            }
            return counts;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка SQL-агрегации исходящих переводов", e);
        }
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
