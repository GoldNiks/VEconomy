package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.api.AccountStatus;

import java.util.UUID;

/** Строка таблицы {@code accounts}. */
public record AccountRow(
        UUID playerId,
        String lastKnownName,
        long balanceMinor,
        AccountStatus status,
        long createdAt,
        long updatedAt,
        int version
) {
}
