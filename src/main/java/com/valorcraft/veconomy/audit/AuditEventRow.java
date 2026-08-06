package com.valorcraft.veconomy.audit;

import java.util.UUID;

/**
 * Строка таблицы {@code audit_events}: событие аудита или сигнал подозрительной
 * активности. Детали — простой текст «key=value; ...» (без вложенного JSON).
 * <p>
 * Каждое событие проходит жизненный цикл ({@link ResolutionStatus}): появляется
 * как OPEN, администратор подтверждает (RESOLVED) или отклоняет (DISMISSED)
 * с указанием того, кто и когда это сделал и с каким примечанием.
 * {@code idempotencyKey} уникален для каждого вызова записи: повтор записи
 * (после сбоя базы) не создаёт дубля.
 */
public record AuditEventRow(
        long id,
        String eventType,
        AuditSeverity severity,
        UUID playerId,
        UUID actorId,
        AuditActorType actorType,
        Long amountMinor,
        String details,
        long createdAt,
        String status,
        Long resolvedAt,
        String resolvedBy,
        String resolutionNote,
        String idempotencyKey) {

    /** Новое событие (id и статус OPEN ещё не в базе; idempotencyKey генерируется). */
    public static AuditEventRow newEvent(String eventType, AuditSeverity severity, UUID playerId,
                                         UUID actorId, AuditActorType actorType, Long amountMinor,
                                         String details) {
        return new AuditEventRow(0, eventType, severity, playerId, actorId, actorType,
                amountMinor, details, System.currentTimeMillis(),
                ResolutionStatus.OPEN.name(), null, null, null, UUID.randomUUID().toString());
    }

    /** Сигнал сканера (дедупликация окном, без идемпотентного ключа). */
    public static AuditEventRow signal(String eventType, UUID playerId, Long amountMinor, String details) {
        return new AuditEventRow(0, eventType, AuditSeverity.SUSPICIOUS, playerId, null,
                AuditActorType.SYSTEM, amountMinor, details, System.currentTimeMillis(),
                ResolutionStatus.OPEN.name(), null, null, null, null);
    }

    public boolean open() {
        return ResolutionStatus.OPEN.name().equals(status);
    }
}