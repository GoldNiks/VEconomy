package com.valorcraft.veconomy.activity;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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
}
