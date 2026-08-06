package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Репозиторий аудит-событий (таблица {@code audit_events}). */
public final class AuditRepository {

    private static final String COLUMNS = "event_type, severity, player_uuid, actor_uuid, actor_type, "
            + "amount_minor, details, created_at, status, resolved_at, resolved_by, "
            + "resolution_note, idempotency_key";

    /**
     * Вставить событие и вернуть его id. Идемпотентно по {@code idempotency_key}:
     * повторная вставка того же ключа (повтор после сбоя записи) игнорируется
     * и возвращает -1 — дубликат не создаётся.
     */
    public long insert(Connection connection, DatabaseManager.Dialect dialect, AuditEventRow row) {
        String sql = dialect == DatabaseManager.Dialect.MYSQL
                ? "INSERT IGNORE INTO audit_events (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                : "INSERT OR IGNORE INTO audit_events (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, row.eventType());
            statement.setString(2, row.severity().name());
            statement.setString(3, row.playerId() != null ? row.playerId().toString() : null);
            statement.setString(4, row.actorId() != null ? row.actorId().toString() : null);
            statement.setString(5, row.actorType() == null ? null : row.actorType().name());
            if (row.amountMinor() != null) {
                statement.setLong(6, row.amountMinor());
            } else {
                statement.setNull(6, java.sql.Types.BIGINT);
            }
            statement.setString(7, row.details());
            statement.setLong(8, row.createdAt());
            statement.setString(9, row.status() == null ? ResolutionStatus.OPEN.name() : row.status());
            if (row.resolvedAt() != null) {
                statement.setLong(10, row.resolvedAt());
            } else {
                statement.setNull(10, java.sql.Types.BIGINT);
            }
            statement.setString(11, row.resolvedBy());
            statement.setString(12, row.resolutionNote());
            statement.setString(13, row.idempotencyKey());
            int updated = statement.executeUpdate();
            if (updated == 0) {
                // INSERT OR IGNORE / INSERT IGNORE отклонил повтор (тот же idempotency_key):
                // строк не прибавилось, и getGeneratedKeys вернёт ключ предыдущей вставки.
                return -1;
            }
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи аудит-события", e);
        }
    }

    /** Последние события (новые сверху), опционально фильтр по игроку, северити и статусу. */
    public List<AuditEventRow> list(Connection connection, int limit) {
        return list(connection, null, null, null, limit);
    }

    public List<AuditEventRow> list(Connection connection, UUID playerId, AuditSeverity severity, int limit) {
        return list(connection, playerId, severity, null, limit);
    }

    public List<AuditEventRow> list(Connection connection, UUID playerId, AuditSeverity severity,
                                    String status, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, " + COLUMNS + " FROM audit_events");
        List<Object> params = new ArrayList<>();
        if (playerId != null) {
            sql.append(" WHERE player_uuid = ?");
            params.add(playerId.toString());
        }
        if (severity != null) {
            sql.append(playerId == null ? " WHERE" : " AND").append(" severity = ?");
            params.add(severity.name());
        }
        if (status != null) {
            sql.append(playerId == null && severity == null ? " WHERE" : " AND")
                    .append(" status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setString(i + 1, String.valueOf(params.get(i)));
            }
            statement.setInt(params.size() + 1, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                return mapRows(rs);
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения аудит-событий", e);
        }
    }

    /** Есть ли сигнал типа {@code type} для игрока после {@code sinceMillis} (для дедупликации). */
    public boolean existsTypeForPlayerSince(Connection connection, String type, UUID playerId, long sinceMillis) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM audit_events WHERE event_type = ? AND player_uuid = ? AND created_at >= ? LIMIT 1")) {
            statement.setString(1, type);
            statement.setString(2, playerId.toString());
            statement.setLong(3, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка проверки дубля аудит-сигнала", e);
        }
    }

    /** Перевести событие в обработанное состояние; возвращает true, если событие существовало. */
    public boolean resolve(Connection connection, long id, ResolutionStatus status,
                           String resolvedBy, String note, long now) {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE audit_events SET status = ?, resolved_at = ?, resolved_by = ?, "
                        + "resolution_note = ? WHERE id = ?")) {
            statement.setString(1, status.name());
            statement.setLong(2, now);
            statement.setString(3, resolvedBy);
            statement.setString(4, note);
            statement.setLong(5, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка обработки аудит-события " + id, e);
        }
    }

    public long count(Connection connection) {
        return count(connection, null);
    }

    public long count(Connection connection, String status) {
        String sql = status == null
                ? "SELECT COUNT(*) FROM audit_events"
                : "SELECT COUNT(*) FROM audit_events WHERE status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (status != null) {
                statement.setString(1, status);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта аудит-событий", e);
        }
    }

    private static List<AuditEventRow> mapRows(ResultSet rs) throws SQLException {
        List<AuditEventRow> rows = new ArrayList<>();
        while (rs.next()) {
            String player = rs.getString("player_uuid");
            String actor = rs.getString("actor_uuid");
            String actorType = rs.getString("actor_type");
            long amount = rs.getLong("amount_minor");
            boolean amountNull = rs.wasNull();
            long resolvedAt = rs.getLong("resolved_at");
            boolean resolvedAtNull = rs.wasNull();
            rows.add(new AuditEventRow(
                    rs.getLong("id"),
                    rs.getString("event_type"),
                    AuditSeverity.valueOf(rs.getString("severity")),
                    player != null ? UUID.fromString(player) : null,
                    actor != null ? UUID.fromString(actor) : null,
                    actorType != null ? AuditActorType.valueOf(actorType)
                            : (actor != null ? AuditActorType.PLAYER : AuditActorType.CONSOLE),
                    amountNull ? null : amount,
                    rs.getString("details"),
                    rs.getLong("created_at"),
                    rs.getString("status") != null ? rs.getString("status") : ResolutionStatus.OPEN.name(),
                    resolvedAtNull ? null : resolvedAt,
                    rs.getString("resolved_by"),
                    rs.getString("resolution_note"),
                    rs.getString("idempotency_key")));
        }
        return rows;
    }

    public Optional<AuditEventRow> findById(Connection connection, long id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, " + COLUMNS + " FROM audit_events WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                List<AuditEventRow> rows = mapRows(rs);
                return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка чтения аудит-события " + id, e);
        }
    }
}