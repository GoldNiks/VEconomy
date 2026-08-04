package com.valorcraft.veconomy.activity;

import java.util.UUID;

/** Строка таблицы {@code claimed_milestones}: выданный игроку этап. */
public record MilestoneRow(
        UUID playerId,
        String milestoneId,
        long amountMinor,
        long claimedAt,
        String source,
        String transactionId) {
}
