package com.valorcraft.veconomy.activity;

import java.util.UUID;

/**
 * Строка таблицы {@code weekly_activity_days}: активное время игрока за конкретную неделю
 * и день. Ключ (player_uuid, week_id, day_key) — активность никогда не смешивает недели:
 * день относится к своей неделе по дате, а не по текущему счётчику.
 *
 * @param playerId     игрок
 * @param weekId       неделя (ISO, см. {@link WeekId})
 * @param dayKey       день в формате эпохального дня (календарная дата в {@code timeZone})
 * @param activeSeconds активное время за этот день
 */
public record WeeklyActivityDayRow(UUID playerId, String weekId, String dayKey, long activeSeconds) {
}
