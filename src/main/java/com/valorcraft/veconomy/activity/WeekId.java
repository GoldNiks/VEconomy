package com.valorcraft.veconomy.activity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Идентификатор ISO-недели вида {@code 2026-W32}. Неделя начинается с понедельника,
 * используется для недельного фонда и учёта еженедельной активности.
 * <p>
 * Текущая неделя и границы недели считаются в таймзоне конфига недельного фонда
 * ({@link #useZone}): сервисы ставят зону при применении настроек. Для тестов источник
 * текущей даты можно заменить через {@link #useDate}.
 */
public final class WeekId {

    private static final WeekFields ISO = WeekFields.ISO;
    private static final Pattern WEEK = Pattern.compile("(\\d{4})-W(\\d{2})");

    private static volatile ZoneId currentZone = ZoneOffset.UTC;
    private static volatile java.util.function.Supplier<java.time.LocalDate> currentDate;

    private WeekId() {}

    /** Текущая неделя: в зоне конфига, либо из подменённого источника даты (тесты). */
    public static String current() {
        return forDate(currentDate != null ? currentDate.get() : LocalDate.now(currentZone));
    }

    /** Сменить источник текущей даты (для тестов). */
    public static void useDate(java.util.function.Supplier<java.time.LocalDate> supplier) {
        currentDate = supplier;
    }

    /** Задать зону для текущей недели и границ недели (таймзона конфига). */
    public static void useZone(ZoneId zone) {
        currentZone = zone == null ? ZoneOffset.UTC : zone;
    }

    /** Вернуть источник текущей даты к реальному времени, а зону — к UTC (для тестов). */
    public static void resetDate() {
        currentDate = null;
        currentZone = ZoneOffset.UTC;
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

    /** Момент окончания недели (понедельник следующей недели, 00:00 в зоне конфига) в миллисекундах. */
    public static long endMillis(String weekId) {
        return mondayOf(weekId).plusDays(7)
                .atStartOfDay(currentZone).toInstant().toEpochMilli();
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
