package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.config.EconomySettings.EconomyTier;
import com.valorcraft.veconomy.config.EconomySettings.PointLevel;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Чистая математика недельного фонда: очки, размер фонда, экономический коэффициент
 * и перераспределение долей. Не зависит от базы данных и конфигурации сервера, поэтому
 * полностью покрывается модульными тестами. Все денежные расчёты — целочисленные (long),
 * умножение — через {@link BigInteger} без переполнения.
 */
public final class WeeklyMath {

    /** 100% в базисных пунктах. */
    public static final long BPS_100_PERCENT = 10_000L;

    /** Зона по умолчанию для подсчёта дней/недель фонда, задокументированная в конфиге. */
    public static final String DEFAULT_TIME_ZONE = "Europe/Berlin";

    /** Разобрать зону из строки конфига. Пустая строка — {@link #DEFAULT_TIME_ZONE}. */
    public static ZoneId zoneOf(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneId.of(DEFAULT_TIME_ZONE);
        }
        return ZoneId.of(timeZone);
    }

    /** Ключ календарного дня (эпохальный день) для {@code weekly_activity_days} в заданной зоне. */
    public static String dayKey(long epochMillis, String timeZone) {
        return Long.toString(Instant.ofEpochMilli(epochMillis).atZone(zoneOf(timeZone))
                .toLocalDate().toEpochDay());
    }

    // ------------------------------------------------------------ day split

    /** Сегмент интервала, приходящийся на один локальный день (полуночи по зоне). */
    public record DaySegment(LocalDate date, long fromInclusiveMillis, long endExclusiveMillis) {
    }

    /**
     * Разбить полуоткрытый интервал {@code [fromInclusive, endExclusive)} на отрезки по локальным
     * полуночам зоны. Границы считаются через {@code LocalDate.atStartOfDay(zone)}, поэтому дни
     * длиной 23/25 часов (переход на летнее/зимнее время) учитываются точно, а не делением
     * на 86 400 секунд. Сумма длин сегментов всегда равна исходной длине интервала.
     */
    public static List<DaySegment> splitInterval(long fromInclusiveMillis, long endExclusiveMillis, ZoneId zone) {
        List<DaySegment> segments = new ArrayList<>();
        if (endExclusiveMillis <= fromInclusiveMillis) {
            return segments;
        }
        ZonedDateTime cursor = Instant.ofEpochMilli(fromInclusiveMillis).atZone(zone);
        long from = fromInclusiveMillis;
        while (from < endExclusiveMillis) {
            LocalDate date = cursor.toLocalDate();
            ZonedDateTime nextMidnight = cursor.plusDays(1).with(LocalTime.MIN);
            long boundary = nextMidnight.toInstant().toEpochMilli();
            if (boundary <= from) {
                // Защита от бесконечного цикла (зона без полуночи в конкретном дне и т.п.).
                boundary = endExclusiveMillis;
            }
            long end = Math.min(endExclusiveMillis, boundary);
            segments.add(new DaySegment(date, from, end));
            from = end;
            cursor = nextMidnight;
        }
        return segments;
    }

    /** ISO-неделя, которой принадлежит календарный день (для атрибуции активности на границе недель). */
    public static String weekOfDay(String dayKey) {
        try {
            return WeekId.forDate(LocalDate.ofEpochDay(Long.parseLong(dayKey)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private WeeklyMath() {}

    // ------------------------------------------------------------ points

    /**
     * Очки за активное время за неделю. Ниже первого порога — 0; между соседними порогами —
     * линейная интерполяция целыми числами; выше последнего — максимум (порог не поднимает).
     */
    public static long timePoints(long activeSeconds, List<PointLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return 0;
        }
        PointLevel first = levels.get(0);
        if (activeSeconds < first.activeSeconds()) {
            return 0;
        }
        for (int i = 0; i + 1 < levels.size(); i++) {
            PointLevel low = levels.get(i);
            PointLevel high = levels.get(i + 1);
            if (activeSeconds <= high.activeSeconds()) {
                long spanSeconds = high.activeSeconds() - low.activeSeconds();
                if (spanSeconds <= 0) {
                    return high.points();
                }
                long deltaPoints = high.points() - low.points();
                return low.points() + deltaPoints * (activeSeconds - low.activeSeconds()) / spanSeconds;
            }
        }
        return levels.get(levels.size() - 1).points();
    }

    /** Очки за число активных дней: последний пройденный порог; дней меньше первого — 0. */
    public static long dayPoints(long activeDays, List<PointLevel> levels) {
        if (levels == null || levels.isEmpty() || activeDays <= 0) {
            return 0;
        }
        long points = 0;
        for (PointLevel level : levels) {
            if (activeDays >= level.activeSeconds()) {
                points = level.points();
            } else {
                break;
            }
        }
        return points;
    }

    // ------------------------------------------------------------ fund

    /** Базовый фонд: базовая сумма на одного подходящего игрока × число подходящих. */
    public static long baseFund(int eligiblePlayers, long baseAmountPerEligiblePlayer) {
        if (eligiblePlayers <= 0 || baseAmountPerEligiblePlayer <= 0) {
            return 0;
        }
        BigInteger value = BigInteger.valueOf(baseAmountPerEligiblePlayer)
                .multiply(BigInteger.valueOf(eligiblePlayers));
        return value.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    /** Итоговый фонд: база × коэффициент (в БП), зажатый в [minimumFund, maximumFund]. */
    public static long finalFund(long baseFund, long coefficientBps, long minimumFund, long maximumFund) {
        if (baseFund <= 0 || coefficientBps <= 0) {
            return 0;
        }
        BigInteger scaled = BigInteger.valueOf(baseFund)
                .multiply(BigInteger.valueOf(coefficientBps))
                .divide(BigInteger.valueOf(BPS_100_PERCENT));
        long value = scaled.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        return Math.max(minimumFund, Math.min(maximumFund, value));
    }

    /**
     * Экономический коэффициент (в БП): по соотношению денежной массы на одного подходящего
     * игрока к целевому значению. Берётся первая ступень, где соотношение (в процентах)
     * строго меньше верхней границы; если ни одна не подошла — последний коэффициент.
     */
    public static long economyCoefficientBps(long supplyPerEligible, long targetSupplyPerEligible,
                                             List<EconomyTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return BPS_100_PERCENT;
        }
        if (targetSupplyPerEligible <= 0) {
            return BPS_100_PERCENT;
        }
        BigInteger ratioPercent = BigInteger.valueOf(supplyPerEligible)
                .multiply(BigInteger.valueOf(100))
                .divide(BigInteger.valueOf(targetSupplyPerEligible));
        for (EconomyTier tier : tiers) {
            if (ratioPercent.compareTo(BigInteger.valueOf(tier.upperRatioPercent())) < 0) {
                return tier.coefficientBps();
            }
        }
        return tiers.get(tiers.size() - 1).coefficientBps();
    }

    // ------------------------------------------------------------ distribution

    /** Доля фонда на одного игрока без ограничений: floor(fund × points / totalPoints). */
    public static long rawShare(long fund, long playerPoints, long totalPoints) {
        if (fund <= 0 || playerPoints <= 0 || totalPoints <= 0) {
            return 0;
        }
        BigInteger value = BigInteger.valueOf(fund)
                .multiply(BigInteger.valueOf(playerPoints))
                .divide(BigInteger.valueOf(totalPoints));
        return value.min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    /**
     * Распределить фонд между участниками по очкам с ограничением максимальной доли на
     * игрока (в процентах). Детерминировано: результат не зависит от порядка участников.
     * <p>
     * Алгоритм пакетный — без помонетных циклов: первичные пропорциональные доли (floor),
     * затем остаток перераспределяется пропорционально очкам ещё не достигших лимита
     * цельными раундами (каждый раунд снимает с очереди минимум одного игрока у лимита,
     * так что число раундов не больше числа участников). Нераздаваемый целыми единицами
     * остаток распределяется по наибольшему дробному остатку (при равенстве — по UUID):
     * это тоже пакетная операция, по одной единице на игрока сверху доли. Сложность
     * определяется числом игроков, а не размером фонда.
     */
    public static Distribution distribute(long fund, List<Participant> participants, int maximumPlayerSharePercent) {
        int count = participants.size();
        long[] shares = new long[count];
        if (fund <= 0 || count == 0) {
            return new Distribution(shares, fund);
        }
        long totalPoints = 0;
        for (Participant participant : participants) {
            if (participant.points() > 0) {
                totalPoints += participant.points();
            }
        }
        if (totalPoints <= 0) {
            return new Distribution(shares, fund);
        }
        long cap = BigInteger.valueOf(fund)
                .multiply(BigInteger.valueOf(maximumPlayerSharePercent))
                .divide(BigInteger.valueOf(100))
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        if (cap < 0) {
            cap = fund;
        }
        // Первичные пропорциональные доли; достигающие лимита сразу фиксируются на нём.
        boolean[] capped = new boolean[count];
        for (int i = 0; i < count; i++) {
            long share = rawShare(fund, participants.get(i).points(), totalPoints);
            if (share >= cap) {
                share = cap;
                capped[i] = true;
            }
            shares[i] = share;
        }
        long remaining = fund - sum(shares);

        // Пакетное перераспределение остатка между не достигшими лимита.
        int guard = 0;
        while (remaining > 0 && guard++ <= count + 1) {
            long activePoints = 0;
            for (int i = 0; i < count; i++) {
                if (!capped[i]) {
                    activePoints += participants.get(i).points();
                }
            }
            if (activePoints <= 0) {
                break;
            }
            long roundRemaining = remaining;
            long sumGiven = 0;
            long excess = 0;
            boolean cappedAny = false;
            for (int i = 0; i < count; i++) {
                if (capped[i]) {
                    continue;
                }
                long give = rawShare(roundRemaining, participants.get(i).points(), activePoints);
                if (give <= 0) {
                    continue;
                }
                long updated = shares[i] + give;
                if (updated >= cap) {
                    excess += updated - cap;
                    shares[i] = cap;
                    capped[i] = true;
                    cappedAny = true;
                } else {
                    shares[i] = updated;
                }
                sumGiven += give;
            }
            remaining = (roundRemaining - sumGiven) + excess;
            if (!cappedAny) {
                // Больше никого нельзя ограничить сверху — остаток раздаётся по дробным остаткам.
                break;
            }
        }

        remaining = distributeLargestRemainder(remaining, participants, capped, shares, count);
        return new Distribution(shares, remaining);
    }

    /** Раздать остаток по одной единице участникам с наибольшим дробным остатком (UUID — при равенстве). */
    private static long distributeLargestRemainder(long remaining, List<Participant> participants,
                                                   boolean[] capped, long[] shares, int count) {
        if (remaining <= 0) {
            return remaining;
        }
        long activePoints = 0;
        for (int i = 0; i < count; i++) {
            if (!capped[i]) {
                activePoints += participants.get(i).points();
            }
        }
        if (activePoints <= 0) {
            return remaining;
        }
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (!capped[i] && participants.get(i).points() > 0) {
                order.add(i);
            }
        }
        final long shareRemaining = remaining;
        final long shareActivePoints = activePoints;
        order.sort(Comparator
                .<Integer>comparingLong(i -> remainderFraction(shareRemaining,
                        participants.get(i).points(), shareActivePoints))
                .reversed()
                .thenComparing(i -> participants.get(i).playerId()));
        for (int i : order) {
            if (remaining <= 0) {
                break;
            }
            shares[i] += 1;
            remaining -= 1;
        }
        return remaining;
    }

    /** Дробная часть доли {@code remaining × points / activePoints} в виде целого {0 … activePoints-1}. */
    private static long remainderFraction(long remaining, long points, long activePoints) {
        return BigInteger.valueOf(remaining)
                .multiply(BigInteger.valueOf(points))
                .mod(BigInteger.valueOf(activePoints)).longValue();
    }

    private static long sum(long[] values) {
        long sum = 0;
        for (long value : values) {
            sum += value;
        }
        return sum;
    }

    /** Участник распределения: игрок с итоговыми очками. */
    public record Participant(UUID playerId, long points) {
    }

    /** Результат распределения: доли в том же порядке, что и участники, и нераспределённый остаток. */
    public record Distribution(long[] shares, long remainder) {
    }
}
