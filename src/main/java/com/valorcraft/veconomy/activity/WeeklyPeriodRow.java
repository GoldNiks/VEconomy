package com.valorcraft.veconomy.activity;

import java.util.UUID;

/**
 * Строка таблицы {@code weekly_activity_periods}: снимок недельной активности игрока
 * (учитываемые секунды и очки) и статус выплаты. Период считается закрытым, когда
 * все строки недели получили статус {@code PAID}.
 */
public record WeeklyPeriodRow(
        String weekId,
        UUID playerId,
        long countedSeconds,
        long points,
        String status,
        long paidAt,
        String transactionId) {
}
