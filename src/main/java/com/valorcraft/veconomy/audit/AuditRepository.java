package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.persistence.DatabaseException;

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

    private static final String COLUMNS = "event_type, severity, player_uuid, actor_uuid, "
            + "amount_minor, details, created_at";

    /** Вставить событие и вернуть его id. */
    public long insert(Connection connection, AuditEventRow row) {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO audit_events (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, row.eventType());
            statement.setString(2, row.severity().name());
            statement.setString(3, row.playerId() != null ? row.playerId().toString() : null);
            statement.setString(4, row.actorId() != null ? row.actorId().toString() : null);
            if (row.amountMinor() != null) {
                statement.setLong(5, row.amountMinor());
            } else {
                statement.setNull(5, java.sql.Types.BIGINT);
            }
            statement.setString(6, row.details());
            statement.setLong(7, row.createdAt());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка записи аудит-события", e);
        }
    }

    /** Последние события (новые сверху), опционально фильтр по игроку и северити. */
    public List<AuditEventRow> list(Connection connection, int limit) {
        return list(connection, null, null, limit);
    }

    public List<AuditEventRow> list(Connection connection, UUID playerId, AuditSeverity severity, int limit) {
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

    public long count(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM audit_events")) {
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка подсчёта аудит-событий", e);
        }
    }

    private static List<AuditEventRow> mapRows(ResultSet rs) throws SQLException {
        List<AuditEventRow> rows = new ArrayList<>();
        while (rs.next()) {
            String player = rs.getString("player_uuid");
            String actor = rs.getString("actor_uuid");
            long amount = rs.getLong("amount_minor");
            rows.add(new AuditEventRow(
                    rs.getLong("id"),
                    rs.getString("event_type"),
                    AuditSeverity.valueOf(rs.getString("severity")),
                    player != null ? UUID.fromString(player) : null,
                    actor != null ? UUID.fromString(actor) : null,
                    rs.wasNull() ? null : amount,
                    rs.getString("details"),
                    rs.getLong("created_at")));
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
