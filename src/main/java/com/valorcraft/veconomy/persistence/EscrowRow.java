package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.api.EscrowState;

import java.util.Map;
import java.util.UUID;

/** Строка таблицы {@code escrow}. */
public record EscrowRow(
        String referenceId,
        UUID ownerUuid,
        long amountMinor,
        EscrowState state,
        long createdAt,
        long updatedAt,
        Map<String, String> metadata
) {
}
