package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.config.EconomySettings.EconomyTier;
import com.valorcraft.veconomy.config.EconomySettings.PointLevel;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    /** Ключ календарного дня (эпохальный день) для {@code weekly_activity_days} в заданной зоне. */
    public static String dayKey(long epochMillis, String timeZone) {
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone);
        } catch (RuntimeException e) {
            zone = ZoneId.of("Europe/Berlin");
        }
        return Long.toString(Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toEpochDay());
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
     * игрока (в процентах). Детерминировано: порядок сортировки по UUID не влияет на сумму.
     * Излишек сверх лимита перераспределяется между теми, кто ещё не достиг лимита; остаток,
     * который нельзя распределить целыми единицами, возвращается как нераспределённый.
     */
    public static Distribution distribute(long fund, List<Participant> participants, int maximumPlayerSharePercent) {
        int count = participants.size();
        long[] shares = new long[count];
        long totalPoints = 0;
        for (Participant p : participants) {
            totalPoints += p.points();
        }
        if (fund <= 0 || totalPoints <= 0) {
            return new Distribution(shares, fund);
        }
        long cap = BigInteger.valueOf(fund)
                .multiply(BigInteger.valueOf(maximumPlayerSharePercent))
                .divide(BigInteger.valueOf(100))
                .min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
        if (cap < 0) {
            cap = fund;
        }
        // Базовый пропорциональный расчёт с ограничением сверху.
        for (int i = 0; i < count; i++) {
            shares[i] = Math.min(rawShare(fund, participants.get(i).points(), totalPoints), cap);
        }
        long remaining = fund - sum(shares);
        // Перераспределение остатка: детерминированный порядок — очки по убыванию, UUID по возрастанию.
        List<Integer> order = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingLong(i -> -participants.get(i).points())
                .thenComparing(i -> participants.get(i).playerId()));
        int guard = 0;
        while (remaining > 0 && guard++ < 1_000_000) {
            boolean gave = false;
            for (int i : order) {
                if (remaining <= 0) {
                    break;
                }
                if (shares[i] < cap) {
                    shares[i]++;
                    remaining--;
                    gave = true;
                }
            }
            if (!gave) {
                break;
            }
        }
        return new Distribution(shares, remaining);
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
