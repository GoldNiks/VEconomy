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
            + "resolution_note, idempotency_key, counterparty_uuid, dedupe_key";

    /**
     * Вставить событие и вернуть id. Обычный {@code INSERT} без IGNORE: нарушение
     * уникального ключа ({@code idempotency_key}/{@code dedupe_key}) распознаётся по
     * ошибке драйвера и возвращается как {@link InsertResult.Status#DUPLICATE}, а настоящие
     * ошибки базы (другие SQL-исключения) пробрасываются как {@link DatabaseException} —
     * INSERT IGNORE молча глотал их как «пусто», маскируя реальные сбои записи.
     */
    public InsertResult insert(Connection connection, DatabaseManager.Dialect dialect, AuditEventRow row) {
        String sql = "INSERT INTO audit_events (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
            statement.setString(14, row.counterpartyUuid() != null ? row.counterpartyUuid().toString() : null);
            statement.setString(15, row.dedupeKey());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                return InsertResult.inserted(keys.next() ? keys.getLong(1) : -1);
            }
        } catch (SQLException e) {
            if (isDuplicateKey(dialect, e)) {
                // Повтор (тот же idempotency_key или dedupe_key): строка уже есть — события
                // не создано. Исключение суржимается, вызывающий трактует это как ок.
                return InsertResult.duplicated();
            }
            throw new DatabaseException("Ошибка записи аудит-события", e);
        }
    }

    /**
     * Дупликат ключа vs настоящая ошибка — СТРОГО: только подтверждённое нарушение
     * уникальности кастомных ключей дедупликации. Широкий SQLite код 19 (SQLITE_CONSTRAINT)
     * НЕ считается дубликатом: он покрывает и NOT NULL, и CHECK, и FK — маскировать
     * настоящие сбои записи нельзя. Признаются только extended-коды уникальности
     * (2067 UNIQUE, 1555 PRIMARY KEY). MySQL — код 1062 И совпадение имени ограничения
     * с известными уникальными индексами audit_events ({@code uk_audit_dedupe},
     * {@code uk_audit_idem}) в сообщении драйвера; реакция на другой unique-индекс вряд
     * ли вообще достижима, но отделяется как не-дупликат.
     */
    private static boolean isDuplicateKey(DatabaseManager.Dialect dialect, SQLException e) {
        if (dialect == DatabaseManager.Dialect.MYSQL) {
            if (e.getErrorCode() != 1062) {
                return false;
            }
            String message = e.getMessage() == null ? "" : e.getMessage();
            return message.contains("uk_audit_idem") || message.contains("uk_audit_dedupe");
        }
        if (e instanceof org.sqlite.SQLiteException s) {
            int code = s.getResultCode() == null ? -1 : s.getResultCode().code;
            // Только confirmed unique/primary-key нарушения; все прочие коды (включая
            // широкий 19, NOT NULL/CHECK/FK и busy/ioerror) — это ошибка базы.
            return code == 2067 || code == 1555;
        }
        return false;
    }

    /** Итог вставки: строка вставлена либо отклонена дубликатом ключа. */
    public record InsertResult(Status status, long id) {

        public enum Status { INSERTED, DUPLICATE }

        public static InsertResult inserted(long id) {
            return new InsertResult(Status.INSERTED, id);
        }

        public static InsertResult duplicated() {
            return new InsertResult(Status.DUPLICATE, -1);
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

    /**
     * Перевести сигнал в обработанное состояние со СТРУКТУРИРОВАННЫМ результатом.
     * Обрабатываются ТОЛЬКО открытые сигналы подозрительной активности:
     * <ul>
     *   <li>события с id нет — {@link ResolveResult.Status#NOT_FOUND};</li>
     *   <li>событие есть, но severity ниже SUSPICIOUS — {@link ResolveResult.Status#NOT_SUSPICIOUS};</li>
     *   <li>сигнал уже обработан — {@link ResolveResult.Status#ALREADY_REVIEWED} (повторный
     *       resolve НЕ перезаписывает resolved_at/resolved_by/note — решение уже принято);</li>
     *   <li>иначе - обновление одним UPDATE с гвардой {@code severity='SUSPICIOUS' AND status='OPEN'}
     *       (проверка и изменение одним оператором — без гонок) и {@link ResolveResult.Status#SUCCESS}.</li>
     * </ul>
     */
    public ResolveResult resolve(Connection connection, long id, ResolutionStatus status,
                                 String resolvedBy, String note, long now) {
        try (PreparedStatement lookup = connection.prepareStatement(
                "SELECT severity, status FROM audit_events WHERE id = ?")) {
            lookup.setLong(1, id);
            try (ResultSet rs = lookup.executeQuery()) {
                if (!rs.next()) {
                    return new ResolveResult(ResolveResult.Status.NOT_FOUND);
                }
                String severity = rs.getString("severity");
                if (severity == null || !AuditSeverity.SUSPICIOUS.name().equals(severity)) {
                    return new ResolveResult(ResolveResult.Status.NOT_SUSPICIOUS);
                }
                String current = rs.getString("status");
                if (current != null && !ResolutionStatus.OPEN.name().equals(current)) {
                    return new ResolveResult(ResolveResult.Status.ALREADY_REVIEWED);
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка проверки аудит-события " + id, e);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE audit_events SET status = ?, resolved_at = ?, resolved_by = ?, "
                        + "resolution_note = ? WHERE id = ? AND severity = 'SUSPICIOUS' "
                        + "AND status = 'OPEN'")) {
            statement.setString(1, status.name());
            statement.setLong(2, now);
            statement.setString(3, resolvedBy);
            statement.setString(4, note);
            statement.setLong(5, id);
            statement.executeUpdate();
            return new ResolveResult(ResolveResult.Status.SUCCESS);
        } catch (SQLException e) {
            throw new DatabaseException("Ошибка обработки аудит-события " + id, e);
        }
    }

    public long count(Connection connection) {
        return count(connection, null);
    }

    /**
     * Удалить события старше {@code cutoffMillis}. Удаление идёт по северити и опирается
     * на индекс {@code (severity, created_at)}: пакеты по одному северити используют индекс
     * дважды, и удаление не спотыкается о другие нагрузки.
     */
    public long prune(Connection connection, long cutoffMillis) {
        long removed = 0;
        for (AuditSeverity severity : AuditSeverity.values()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM audit_events WHERE severity = ? AND created_at < ?")) {
                statement.setString(1, severity.name());
                statement.setLong(2, cutoffMillis);
                removed += statement.executeUpdate();
            } catch (SQLException e) {
                throw new DatabaseException("Ошибка очистки старых аудит-событий", e);
            }
        }
        return removed;
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
            String counterparty = rs.getString("counterparty_uuid");
            // amount_minor может быть SQL NULL (событие без суммы): читаем сразу и сохраняем
            // признак NULL прежде, чем следующий getXXX в строке перезапишет wasNull().
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
                    rs.getString("idempotency_key"),
                    counterparty != null ? UUID.fromString(counterparty) : null,
                    rs.getString("dedupe_key")));
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