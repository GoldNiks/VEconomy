package com.valorcraft.veconomy.audit;

import java.util.UUID;

/**
 * Строка таблицы {@code audit_events}: событие аудита или сигнал подозрительной
 * активности. Детали — простой текст «key=value; ...» (без вложенного JSON).
 */
public record AuditEventRow(
        long id,
        String eventType,
        AuditSeverity severity,
        UUID playerId,
        UUID actorId,
        Long amountMinor,
        String details,
        long createdAt) {
}