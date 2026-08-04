package com.valorcraft.veconomy.api;

import java.util.Map;
import java.util.UUID;

/**
 * Контекст денежной операции. Обязательные поля: тип и причина.
 * <p>
 * {@code idempotencyKey} — необязательный ключ идемпотентности: повторное выполнение
 * операции с тем же ключом не создаст повторного начисления (база гарантирует уникальность).
 * <p>
 * {@code metadata} — необязательные технические поля для аудита (не показываются игрокам).
 */
public record TransactionContext(
        TransactionType type,
        UUID actorId,
        String reason,
        String idempotencyKey,
        Map<String, String> metadata
) {

    public TransactionContext {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Компактный конструктор без идемпотентного ключа и метаданных. */
    public static TransactionContext of(TransactionType type, UUID actorId, String reason) {
        return new TransactionContext(type, actorId, reason, null, Map.of());
    }

    /** Конструктор с идемпотентным ключом. */
    public static TransactionContext of(TransactionType type, UUID actorId, String reason, String idempotencyKey) {
        return new TransactionContext(type, actorId, reason, idempotencyKey, Map.of());
    }
}
