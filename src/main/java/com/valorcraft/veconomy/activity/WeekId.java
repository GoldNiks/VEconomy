package com.valorcraft.veconomy.activity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Идентификатор ISO-недели вида {@code 2026-W32}. Неделя начинается с понедельника,
 * используется для недельного фонда и учёта еженедельной активности.
 */
public final class WeekId {

    private static final WeekFields ISO = WeekFields.ISO;
    private static final Pattern WEEK = Pattern.compile("(\\d{4})-W(\\d{2})");

    private WeekId() {}

    /** Текущая неделя в UTC. */
    public static String current() {
        return forDate(LocalDate.now(ZoneOffset.UTC));
    }

    public static String forDate(LocalDate date) {
        return String.format("%04d-W%02d",
                date.get(ISO.weekBasedYear()),
                date.get(ISO.weekOfWeekBasedYear()));
    }

    /** Неделя, предшествующая {@code weekId} (например {@code 2026-W32} → {@code 2026-W31}). */
    public static String previous(String weekId) {
        if (!isValid(weekId)) {
            return weekId;
        }
        return forDate(mondayOf(weekId).minusDays(7));
    }

    /** Неделя, следующая за {@code weekId} (например {@code 2026-W32} → {@code 2026-W33}). */
    public static String next(String weekId) {
        if (!isValid(weekId)) {
            return weekId;
        }
        return forDate(mondayOf(weekId).plusDays(7));
    }

    /** Корректный ли идентификатор недели ({@code YYYY-WWW}). */
    public static boolean isValid(String weekId) {
        return weekId != null && WEEK.matcher(weekId).matches();
    }

    private static LocalDate mondayOf(String weekId) {
        Matcher matcher = WEEK.matcher(weekId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Неверный идентификатор недели: " + weekId);
        }
        int year = Integer.parseInt(matcher.group(1));
        int week = Integer.parseInt(matcher.group(2));
        LocalDate firstWeek = LocalDate.of(year, 1, 4);
        LocalDate firstMonday = firstWeek.with(java.time.temporal.ChronoField.DAY_OF_WEEK, 1);
        return firstMonday.plusWeeks(week - 1L);
    }
}
