package com.valorcraft.veconomy.activity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeekIdTest {

    @Test
    void formatsKnownDates() {
        assertEquals("2026-W32", WeekId.forDate(LocalDate.of(2026, 8, 5)));
        assertEquals("2026-W01", WeekId.forDate(LocalDate.of(2026, 1, 1)));
    }

    @Test
    void previousStepsBackSevenDays() {
        assertEquals("2026-W31", WeekId.previous("2026-W32"));
        assertEquals("2025-W52", WeekId.previous("2026-W01"));
    }

    @Test
    void previousAndNextRoundTripAcrossYear() {
        assertEquals("2026-W01", WeekId.next("2025-W52"));
        assertEquals("2026-W32", WeekId.next(WeekId.previous("2026-W32")));
    }

    @Test
    void useDateDrivesCurrent() {
        try {
            WeekId.useDate(() -> LocalDate.of(2026, 8, 5));
            assertEquals("2026-W32", WeekId.current());
        } finally {
            WeekId.resetDate();
        }
    }

    @Test
    void endMillisUsesConfiguredZone() {
        try {
            WeekId.useZone(ZoneOffset.UTC);
            assertEquals(Instant.parse("2026-08-10T00:00:00Z").toEpochMilli(),
                    WeekId.endMillis("2026-W32"));
            // понедельник 00:00 в Берлине (летнее время, UTC+2) — это 22:00 UTC воскресенья
            WeekId.useZone(ZoneId.of("Europe/Berlin"));
            assertEquals(Instant.parse("2026-08-09T22:00:00Z").toEpochMilli(),
                    WeekId.endMillis("2026-W32"));
        } finally {
            WeekId.resetDate();
        }
    }

    @Test
    void endMillisCrossesYearInZone() {
        try {
            WeekId.useZone(ZoneOffset.UTC);
            // 2026-W01 начинается в понедельник 29.12.2025
            assertEquals(Instant.parse("2025-12-29T00:00:00Z").toEpochMilli(),
                    WeekId.endMillis("2025-W52"));
        } finally {
            WeekId.resetDate();
        }
    }

    @Test
    void currentUsesConfiguredZone() {
        try {
            ZoneId berlin = ZoneId.of("Europe/Berlin");
            WeekId.useZone(berlin);
            assertEquals(WeekId.forDate(LocalDate.now(berlin)), WeekId.current());
        } finally {
            WeekId.resetDate();
        }
    }

    @Test
    void resetDateRestoresUtc() {
        WeekId.useDate(() -> LocalDate.of(2026, 8, 5));
        WeekId.useZone(ZoneId.of("Europe/Berlin"));
        WeekId.resetDate();
        assertEquals(Instant.parse("2026-08-10T00:00:00Z").toEpochMilli(),
                WeekId.endMillis("2026-W32"));
    }
}
