package com.valorcraft.veconomy.activity;

import java.util.UUID;

/** Строка таблицы {@code weekly_payouts}: выплата недельного фонда игроку за неделю. */
public record WeeklyPayoutRow(
        String weekId,
        UUID playerId,
        long activitySeconds,
        int points,
        long amountMinor,
        long paidAt,
        String transactionId) {
}
