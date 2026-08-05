package com.valorcraft.veconomy.activity;

import java.util.UUID;

/** Строка таблицы {@code dimension_visits}: первое посещение измерения игроком. */
public record DimensionVisitRow(UUID playerId, String dimension, long firstVisitedAt) {
}
