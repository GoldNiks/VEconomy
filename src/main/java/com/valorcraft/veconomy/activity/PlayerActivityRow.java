package com.valorcraft.veconomy.activity;

import java.util.UUID;

/** Строка таблицы {@code player_activity}: накопленное время игрока. */
public record PlayerActivityRow(
        UUID playerId,
        long firstSeenAt,
        long lastSeenAt,
        long totalOnlineSeconds,
        long totalActiveSeconds,
        long totalAfkSeconds,
        String currentWeekId,
        long weeklyActiveSeconds,
        long lastActivityAt,
        String lastDimension,
        boolean excludedFromRewards) {
}
