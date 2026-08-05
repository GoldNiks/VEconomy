package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.config.EconomySettings.EconomyTier;
import com.valorcraft.veconomy.config.EconomySettings.PointLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeeklyMathTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    // ------------------------------------------------------------ points

    @Test
    void timePointsBelowFirstThresholdAreZero() {
        List<PointLevel> levels = List.of(new PointLevel(100, 1), new PointLevel(200, 3));
        assertEquals(0, WeeklyMath.timePoints(0, levels));
        assertEquals(0, WeeklyMath.timePoints(99, levels));
    }

    @Test
    void timePointsInterpolateBetweenLevels() {
        List<PointLevel> levels = List.of(new PointLevel(100, 1), new PointLevel(200, 3));
        assertEquals(1, WeeklyMath.timePoints(100, levels));
        assertEquals(2, WeeklyMath.timePoints(150, levels));
        assertEquals(3, WeeklyMath.timePoints(200, levels));
        // выше последнего порога — максимум, порог не поднимает
        assertEquals(3, WeeklyMath.timePoints(1_000_000, levels));
    }

    @Test
    void dayPointsUseLastPassedThreshold() {
        List<PointLevel> levels = List.of(new PointLevel(2, 5), new PointLevel(4, 15), new PointLevel(7, 30));
        assertEquals(0, WeeklyMath.dayPoints(1, levels));
        assertEquals(5, WeeklyMath.dayPoints(2, levels));
        assertEquals(5, WeeklyMath.dayPoints(3, levels));
        assertEquals(15, WeeklyMath.dayPoints(4, levels));
        assertEquals(30, WeeklyMath.dayPoints(7, levels));
        assertEquals(30, WeeklyMath.dayPoints(30, levels));
    }

    // ------------------------------------------------------------ fund

    @Test
    void baseFundScalesWithEligiblePlayers() {
        assertEquals(0, WeeklyMath.baseFund(0, 500));
        assertEquals(1_000, WeeklyMath.baseFund(2, 500));
        // переполнение long исключено
        assertEquals(Long.MAX_VALUE, WeeklyMath.baseFund(3, Long.MAX_VALUE));
    }

    @Test
    void finalFundAppliesCoefficientAndClamps() {
        assertEquals(1_200, WeeklyMath.finalFund(1_000, 12_000, 0, Long.MAX_VALUE));
        assertEquals(1_000, WeeklyMath.finalFund(1_000, 10_000, 0, Long.MAX_VALUE));
        // минимальный фонд поднимает базу вверх, максимальный — срезает
        assertEquals(2_000, WeeklyMath.finalFund(1_000, 10_000, 2_000, Long.MAX_VALUE));
        assertEquals(500, WeeklyMath.finalFund(10_000, 10_000, 0, 500));
    }

    @Test
    void economyCoefficientPicksFirstTierBelowBoundary() {
        List<EconomyTier> tiers = List.of(
                new EconomyTier(70, 12_000),
                new EconomyTier(90, 11_000),
                new EconomyTier(110, 10_000));
        assertEquals(12_000, WeeklyMath.economyCoefficientBps(50, 100, tiers));
        assertEquals(11_000, WeeklyMath.economyCoefficientBps(70, 100, tiers));
        assertEquals(10_000, WeeklyMath.economyCoefficientBps(90, 100, tiers));
        // выше всех границ — последний коэффициент
        assertEquals(10_000, WeeklyMath.economyCoefficientBps(1_000, 100, tiers));
        assertEquals(10_000, WeeklyMath.economyCoefficientBps(100, 0, tiers));
    }

    // ------------------------------------------------------------ zone/day keys

    @Test
    void zoneOfBlankUsesDocumentedDefault() {
        assertEquals(ZoneId.of("Europe/Berlin"), WeeklyMath.zoneOf(null));
        assertEquals(ZoneId.of("Europe/Berlin"), WeeklyMath.zoneOf(""));
    }

    @Test
    void dayKeyDependsOnZone() {
        long instant = Instant.parse("2026-08-02T23:30:00Z").toEpochMilli();
        long utcDay = LocalDate.of(2026, 8, 2).toEpochDay();
        long berlinDay = LocalDate.of(2026, 8, 3).toEpochDay();
        assertEquals(Long.toString(utcDay), WeeklyMath.dayKey(instant, "UTC"));
        assertEquals(Long.toString(berlinDay), WeeklyMath.dayKey(instant, "Europe/Berlin"));
    }

    // ------------------------------------------------------------ day split

    @Test
    void splitIntervalSingleDayIsUntouched() {
        long from = Instant.parse("2026-08-03T10:00:00Z").toEpochMilli();
        long to = Instant.parse("2026-08-03T15:00:00Z").toEpochMilli();
        List<WeeklyMath.DaySegment> segments = WeeklyMath.splitInterval(from, to, BERLIN);
        assertEquals(1, segments.size());
        assertEquals(LocalDate.of(2026, 8, 3), segments.get(0).date());
        assertEquals(from, segments.get(0).fromInclusiveMillis());
        assertEquals(to, segments.get(0).endExclusiveMillis());
    }

    @Test
    void splitAcrossMidnightProducesTwoSegmentsWithExactDurations() {
        long from = Instant.parse("2026-08-02T21:30:00Z").toEpochMilli(); // 23:30 по Берлину
        long to = Instant.parse("2026-08-02T22:30:00Z").toEpochMilli();   // 00:30 следующего дня
        List<WeeklyMath.DaySegment> segments = WeeklyMath.splitInterval(from, to, BERLIN);
        assertEquals(2, segments.size());
        assertEquals(LocalDate.of(2026, 8, 2), segments.get(0).date());
        assertEquals(LocalDate.of(2026, 8, 3), segments.get(1).date());
        // 23:30–24:00 и 00:00–00:30 — по 30 минут
        assertEquals(30 * 60_000L, segments.get(0).endExclusiveMillis() - segments.get(0).fromInclusiveMillis());
        assertEquals(30 * 60_000L, segments.get(1).endExclusiveMillis() - segments.get(1).fromInclusiveMillis());
        assertEquals(to - from,
                segments.get(0).endExclusiveMillis() - segments.get(0).fromInclusiveMillis()
                        + (segments.get(1).endExclusiveMillis() - segments.get(1).fromInclusiveMillis()));
    }

    @Test
    void splitAcrossWeekBoundaryKeepsDatesAndSum() {
        // воскресенье 23:30 → понедельник 00:30 (по Берлину)
        long from = Instant.parse("2026-08-02T21:30:00Z").toEpochMilli();
        long to = Instant.parse("2026-08-02T22:30:00Z").toEpochMilli();
        List<WeeklyMath.DaySegment> segments = WeeklyMath.splitInterval(from, to, BERLIN);
        assertEquals(2, segments.size());
        assertEquals(LocalDate.of(2026, 8, 2), segments.get(0).date());
        assertEquals(LocalDate.of(2026, 8, 3), segments.get(1).date());
        // воскресенье — неделя W31, понедельник — W32
        assertEquals("2026-W31", WeeklyMath.weekOfDay(Long.toString(segments.get(0).date().toEpochDay())));
        assertEquals("2026-W32", WeeklyMath.weekOfDay(Long.toString(segments.get(1).date().toEpochDay())));
    }

    @Test
    void splitHandlesSpringForwardDst() {
        // переход 31.03.2024 02:00→03:00 (CET→CEST): день 31 марта длится 23 часа
        long from = Instant.parse("2024-03-30T21:00:00Z").toEpochMilli(); // 22:00 CET 30.03
        long to = Instant.parse("2024-03-31T01:00:00Z").toEpochMilli();   // 03:00 CEST 31.03
        List<WeeklyMath.DaySegment> segments = WeeklyMath.splitInterval(from, to, BERLIN);
        assertEquals(2, segments.size());
        assertEquals(LocalDate.of(2024, 3, 30), segments.get(0).date());
        assertEquals(LocalDate.of(2024, 3, 31), segments.get(1).date());
        // 22:00–00:00 = 2 часа; 00:00–03:00 = 3 локальных часа = 2 часа реального времени
        assertEquals(7_200_000L, segments.get(0).endExclusiveMillis() - segments.get(0).fromInclusiveMillis());
        assertEquals(7_200_000L, segments.get(1).endExclusiveMillis() - segments.get(1).fromInclusiveMillis());
    }

    @Test
    void splitHandlesFallBackDst() {
        // переход 27.10.2024 03:00→02:00 (CEST→CET): день 27 октября длится 25 часов
        long from = Instant.parse("2024-10-26T20:00:00Z").toEpochMilli(); // 22:00 CEST 26.10
        long to = Instant.parse("2024-10-27T02:00:00Z").toEpochMilli();   // 03:00 CET 27.10
        List<WeeklyMath.DaySegment> segments = WeeklyMath.splitInterval(from, to, BERLIN);
        assertEquals(2, segments.size());
        assertEquals(LocalDate.of(2024, 10, 26), segments.get(0).date());
        assertEquals(LocalDate.of(2024, 10, 27), segments.get(1).date());
        // 22:00–00:00 = 2 часа; 00:00–03:00 = 3 локальных часа = 4 часа реального времени
        assertEquals(7_200_000L, segments.get(0).endExclusiveMillis() - segments.get(0).fromInclusiveMillis());
        assertEquals(14_400_000L, segments.get(1).endExclusiveMillis() - segments.get(1).fromInclusiveMillis());
    }

    @Test
    void splitEmptyOrDegenerateInterval() {
        long from = Instant.parse("2026-08-03T10:00:00Z").toEpochMilli();
        assertTrue(WeeklyMath.splitInterval(from, from, BERLIN).isEmpty());
        assertTrue(WeeklyMath.splitInterval(from + 1000, from, BERLIN).isEmpty());
    }

    @Test
    void splitOverManyDaysPreservesSum() {
        // границы — локальные полуночи по Берлину (июль, UTC+2): ровно 7 полных суток
        long from = Instant.parse("2026-07-27T22:00:00Z").toEpochMilli();
        long to = Instant.parse("2026-08-03T22:00:00Z").toEpochMilli();
        List<WeeklyMath.DaySegment> segments = WeeklyMath.splitInterval(from, to, BERLIN);
        assertEquals(7, segments.size());
        long sum = 0;
        for (WeeklyMath.DaySegment segment : segments) {
            sum += segment.endExclusiveMillis() - segment.fromInclusiveMillis();
        }
        assertEquals(to - from, sum);
        // каждый сегмент — ровно одни сутки
        for (WeeklyMath.DaySegment segment : segments) {
            assertEquals(86_400_000L, segment.endExclusiveMillis() - segment.fromInclusiveMillis());
        }
    }

    // ------------------------------------------------------------ distribution

    private static List<WeeklyMath.Participant> participants(long... points) {
        List<WeeklyMath.Participant> list = new ArrayList<>();
        for (long point : points) {
            list.add(new WeeklyMath.Participant(UUID.randomUUID(), point));
        }
        return list;
    }

    @Test
    void distributesProportionallyWithLargestRemainder() {
        // 2 и 1 очко, фонд 400: floor(400·2/3)=266, floor(400·1/3)=133, остаток 1 — алисе
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        WeeklyMath.Distribution distribution = WeeklyMath.distribute(400,
                List.of(new WeeklyMath.Participant(alice, 2), new WeeklyMath.Participant(bob, 1)), 100);
        assertEquals(267L, distribution.shares()[0]);
        assertEquals(133L, distribution.shares()[1]);
        assertEquals(0L, distribution.remainder());
    }

    @Test
    void distributesWhenTotalPointsExceedFund() {
        // критический случай: сумма очков больше фонда — всё должно распределиться без нулей
        WeeklyMath.Distribution distribution = WeeklyMath.distribute(100, participants(1000, 1000), 100);
        assertEquals(50L, distribution.shares()[0]);
        assertEquals(50L, distribution.shares()[1]);
        assertEquals(0L, distribution.remainder());
    }

    @Test
    void redistributesExcessBeyondCapInBatches() {
        // лимит 40%: третьему игроку полагается 500 из 1000 → урезан до 400,
        // излишек 100 делится между первыми двумя пропорционально их очкам (30:20 → 60:40)
        UUID capped = UUID.randomUUID();
        List<WeeklyMath.Participant> participants = List.of(
                new WeeklyMath.Participant(UUID.randomUUID(), 30),
                new WeeklyMath.Participant(UUID.randomUUID(), 20),
                new WeeklyMath.Participant(capped, 50));
        WeeklyMath.Distribution distribution = WeeklyMath.distribute(1000, participants, 40);
        long total = 0;
        for (int i = 0; i < participants.size(); i++) {
            long share = distribution.shares()[i];
            assertTrue(share <= 400, "доля не может превышать лимит: " + share);
            total += share;
        }
        assertEquals(1000, total + distribution.remainder());
        assertEquals(0L, distribution.remainder(), "весь фонд должен быть распределён");
    }

    @Test
    void everyoneAtCapLeavesRemainder() {
        WeeklyMath.Distribution distribution = WeeklyMath.distribute(100, participants(1, 1), 10);
        assertEquals(10L, distribution.shares()[0]);
        assertEquals(10L, distribution.shares()[1]);
        assertEquals(80L, distribution.remainder());
    }

    @Test
    void singlePlayerTakesWholeFund() {
        WeeklyMath.Distribution distribution = WeeklyMath.distribute(100, participants(7), 100);
        assertEquals(100L, distribution.shares()[0]);
        assertEquals(0L, distribution.remainder());
    }

    @Test
    void distributionIsDeterministicRegardlessOfOrder() {
        List<WeeklyMath.Participant> participants = participants(11, 3, 7, 5, 9, 2);
        WeeklyMath.Distribution first = WeeklyMath.distribute(10_000, participants, 100);
        List<WeeklyMath.Participant> shuffled = new ArrayList<>(participants);
        Collections.shuffle(shuffled);
        WeeklyMath.Distribution second = WeeklyMath.distribute(10_000, shuffled, 100);
        long total = 0;
        for (int i = 0; i < participants.size(); i++) {
            UUID playerId = participants.get(i).playerId();
            long expected = first.shares()[i];
            long actual = 0;
            for (int j = 0; j < shuffled.size(); j++) {
                if (shuffled.get(j).playerId().equals(playerId)) {
                    actual = second.shares()[j];
                }
            }
            assertEquals(expected, actual, "доля игрока не должна зависеть от порядка участников");
            total += expected;
        }
        assertEquals(10_000, total + first.remainder());
        assertEquals(first.remainder(), second.remainder());
    }

    @Test
    void hugeFundDoesNotOverflow() {
        WeeklyMath.Distribution distribution = WeeklyMath.distribute(Long.MAX_VALUE, participants(1, 1), 100);
        long total = distribution.shares()[0] + distribution.shares()[1];
        assertEquals(Long.MAX_VALUE, total + distribution.remainder());
        assertTrue(distribution.shares()[0] > 0 && distribution.shares()[1] > 0);
    }

    @Test
    void stressOneThousandPlayersWithLargeFund() {
        List<WeeklyMath.Participant> participants = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            participants.add(new WeeklyMath.Participant(UUID.randomUUID(), 1 + (i * 37L) % 100));
        }
        long fund = 5_000_000_000L;
        WeeklyMath.Distribution first = WeeklyMath.distribute(fund, participants, 5);
        WeeklyMath.Distribution second = WeeklyMath.distribute(fund, participants, 5);

        long total = 0;
        long cap = fund * 5 / 100;
        for (int i = 0; i < participants.size(); i++) {
            assertTrue(first.shares()[i] >= 0);
            assertTrue(first.shares()[i] <= cap, "доля выше лимита: " + first.shares()[i]);
            assertEquals(first.shares()[i], second.shares()[i], "повторный расчёт должен быть детерминирован");
            total += first.shares()[i];
        }
        assertEquals(fund, total + first.remainder(), "весь фонд должен быть распределён или уйти в остаток");
    }
}
