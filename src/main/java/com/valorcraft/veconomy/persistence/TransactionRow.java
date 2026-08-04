package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.api.TransactionType;

import java.util.Map;
import java.util.UUID;

/**
 * Строка таблицы {@code transactions} (журнал операций).
 * <p>
 * {@code sourceBalanceAfter}/{@code targetBalanceAfter} могут быть null для
 * односторонних операций (например, чистое зачисление).
 */
public record TransactionRow(
        String transactionId,
        TransactionType type,
        UUID sourceUuid,
        UUID targetUuid,
        long amountMinor,
        long createdAt,
        UUID actorUuid,
        String reason,
        String idempotencyKey,
        Map<String, String> metadata,
        Long sourceBalanceAfter,
        Long targetBalanceAfter
) {
}
