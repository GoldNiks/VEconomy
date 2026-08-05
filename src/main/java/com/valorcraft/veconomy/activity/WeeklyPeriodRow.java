package com.valorcraft.veconomy.activity;

import java.util.UUID;

/**
 * Строка таблицы {@code weekly_activity_periods}: замороженный план недели для игрока.
 * Помимо учитываемых секунд и итоговых очков содержит разбивку очков (время/дни) и
 * зафиксированную долю фонда {@code share} — она не пересчитывается при повторах.
 * Период считается закрытым, когда все строки недели получили статус {@code PAID}.
 */
public record WeeklyPeriodRow(
        String weekId,
        UUID playerId,
        long countedSeconds,
        long points,
        String status,
        long paidAt,
        String transactionId,
        int activeDays,
        long timePoints,
        long dayPoints,
        long share) {

    /** Конструктор без разбивки очков (тесты/пустые недели): всё очковое — 0, share — 0. */
    public WeeklyPeriodRow(String weekId, UUID playerId, long countedSeconds, long points,
                           String status, long paidAt, String transactionId) {
        this(weekId, playerId, countedSeconds, points, status, paidAt, transactionId,
                0, 0, 0, 0);
    }
}
