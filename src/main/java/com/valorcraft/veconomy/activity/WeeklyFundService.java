package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.EconomySettings.WeeklyFund;
import com.valorcraft.veconomy.economy.AccountService;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.MetaRepository;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Недельный фонд. Один раз за ISO-неделю (при смене недели) фонд делится между игроками
 * пропорционально накопленному за завершённую неделю активному времени (без AFK).
 * <p>
 * Ограничения и механика:
 * <ul>
 *   <li>участвуют только аккаунты старше {@code minAccountAgeDays} и с активностью не ниже
 *       {@code minActiveSeconds};</li>
 *   <li>учитываемое время сверх {@code maxCountedSeconds} не участвует (0 — без потолка);</li>
 *   <li>если заданы {@code pointLevels}, фонд делится по очкам уровней, а не по сырым секундам;</li>
 *   <li>выплата идемпотентна (ключ {@code weekly:<week>:<uuid>}), итог не превышает фонд:
 *       остаток от деления — в казну: {@code share = floor(fund * points / totalPoints)} через
 *       {@link BigInteger}, чтобы избежать обнуления при {@code totalPoints > fund}.</li>
 * </ul>
 * Автозапуск ({@code weeklyFund.autoRun}) по умолчанию выключен; администратор выполняет
 * выплату вручную: {@code /economy admin weekly run confirm}. Первичный запуск не платит —
 * лишь фиксирует текущую неделю, чтобы первая выплата прошла по полной следующей неделе.
 */
public final class WeeklyFundService {

    private static final String DISTRIBUTED_WEEK_KEY = "weekly_fund.distributed_week";

    private final DatabaseManager database;
    private final PlayerActivityRepository activityRepository;
    private final WeeklyPayoutRepository payouts;
    private final AccountService accounts;
    private volatile EconomySettings settings;

    public WeeklyFundService(DatabaseManager database, PlayerActivityRepository activityRepository,
                             WeeklyPayoutRepository payouts, AccountService accounts, EconomySettings settings) {
        this.database = database;
        this.activityRepository = activityRepository;
        this.payouts = payouts;
        this.accounts = accounts;
        this.settings = settings;
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
    }

    // ---------------------------------------------------------------- run

    /** Автоматическая выплата при смене недели (уважает {@code weeklyFund.autoRun}). */
    public Map<UUID, Long> maybeDistribute() {
        return distributeIfDue(settings.weeklyFund.autoRun);
    }

    /** Ручная выплата администратором (не зависит от {@code autoRun}). */
    public Map<UUID, Long> runNow() {
        return distributeIfDue(true);
    }

    /**
     * Выплатить фонд, если наступила новая неделя и это разрешено. Возвращает выплаты
     * текущего запуска (игрок → сумма) для уведомлений; пустая карта — если выплаты не было.
     */
    private Map<UUID, Long> distributeIfDue(boolean allowed) {
        WeeklyFund cfg = settings.weeklyFund;
        if (!cfg.enabled || cfg.weeklyAmount <= 0 || !allowed) {
            return Map.of();
        }
        String currentWeek = WeekId.current();
        String distributed;
        try {
            distributed = database.inTransaction(connection ->
                    MetaRepository.get(connection, database.dialect(), DISTRIBUTED_WEEK_KEY));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения состояния недельного фонда", e);
            return Map.of();
        }
        if (currentWeek.equals(distributed)) {
            return Map.of();
        }
        if (distributed == null) {
            // Первый запуск мода: без накопленной истории активность «предыдущей недели»
            // не определена, поэтому выплату не производим — лишь фиксируем текущую неделю.
            markDistributed(currentWeek);
            VEconomyMod.LOGGER.info("Недельный фонд: первичная инициализация без выплаты (неделя {})",
                    currentWeek);
            return Map.of();
        }
        Map<UUID, Long> payments = distribute(currentWeek);
        markDistributed(currentWeek);
        return payments;
    }

    private void markDistributed(String currentWeek) {
        try {
            database.inTransaction(connection -> {
                MetaRepository.set(connection, database.dialect(), DISTRIBUTED_WEEK_KEY, currentWeek);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки распределённой недели", e);
        }
    }

    // ---------------------------------------------------------------- state

    /** Что будет выплачено за завершённую неделю (без изменения балансов). */
    public List<WeeklyAllocation> preview() {
        return computeAllocations();
    }

    public WeeklyStatus status() {
        WeeklyFund cfg = settings.weeklyFund;
        String currentWeek = WeekId.current();
        String distributed;
        try {
            distributed = database.inTransaction(connection ->
                    MetaRepository.get(connection, database.dialect(), DISTRIBUTED_WEEK_KEY));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения состояния недельного фонда", e);
            distributed = null;
        }
        List<WeeklyAllocation> allocations = computeAllocations();
        long totalShare = 0;
        long totalPoints = 0;
        long totalSeconds = 0;
        for (WeeklyAllocation allocation : allocations) {
            totalShare += allocation.share();
            totalPoints += allocation.points();
            totalSeconds += allocation.countedSeconds();
        }
        return new WeeklyStatus(cfg.enabled, cfg.autoRun, cfg.weeklyAmount, currentWeek,
                distributed, currentWeek.equals(distributed),
                allocations.size(), totalPoints, totalSeconds, totalShare);
    }

    private List<WeeklyAllocation> computeAllocations() {
        WeeklyFund cfg = settings.weeklyFund;
        long fund = cfg.weeklyAmount;
        long now = System.currentTimeMillis();
        long minAccountAgeMillis = cfg.minAccountAgeDays <= 0 ? 0 : cfg.minAccountAgeDays * 86_400_000L;
        List<PlayerActivityRow> rows;
        try {
            rows = database.inTransaction(connection ->
                    activityRepository.listWithWeeklyActivity(connection));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения недельной активности", e);
            return List.of();
        }

        List<Allocation> eligible = new ArrayList<>();
        long totalPoints = 0;
        for (PlayerActivityRow row : rows) {
            if (row.excludedFromRewards()) {
                continue;
            }
            long counted = row.weeklyActiveSeconds();
            if (cfg.maxCountedSeconds > 0 && counted > cfg.maxCountedSeconds) {
                counted = cfg.maxCountedSeconds;
            }
            if (counted <= 0) {
                continue;
            }
            if (cfg.minActiveSeconds > 0 && counted < cfg.minActiveSeconds) {
                continue;
            }
            if (minAccountAgeMillis > 0) {
                long accountAgeMillis = accounts.getAccount(row.playerId())
                        .map(BalanceSnapshot::createdAt).orElse(row.firstSeenAt());
                if (now - accountAgeMillis < minAccountAgeMillis) {
                    continue;
                }
            }
            long points = pointsOf(counted, cfg.pointLevels);
            if (points <= 0) {
                continue;
            }
            eligible.add(new Allocation(row.playerId(), counted, points));
            totalPoints += points;
        }
        if (totalPoints <= 0) {
            return List.of();
        }

        // share = floor(fund * points / totalPoints). Сначала умножаем, потом делим, иначе
        // при totalPoints > fund множитель обнулился бы и весь фонд ушёл в казну.
        BigInteger fundBig = BigInteger.valueOf(fund);
        BigInteger totalBig = BigInteger.valueOf(totalPoints);
        List<WeeklyAllocation> result = new ArrayList<>(eligible.size());
        for (Allocation allocation : eligible) {
            long share = fundBig.multiply(BigInteger.valueOf(allocation.points))
                    .divide(totalBig).longValue();
            if (share <= 0) {
                continue;
            }
            result.add(new WeeklyAllocation(allocation.playerId,
                    allocation.countedSeconds, allocation.points, share));
        }
        result.sort(Comparator.comparingLong(WeeklyAllocation::share).reversed());
        return result;
    }

    private static long pointsOf(long countedSeconds, List<WeeklyFund.PointLevel> levels) {
        if (levels.isEmpty()) {
            return countedSeconds;
        }
        long points = 0;
        for (WeeklyFund.PointLevel level : levels) {
            if (countedSeconds >= level.activeSeconds()) {
                points += level.points();
            }
        }
        return points;
    }

    // ---------------------------------------------------------------- distribution

    private Map<UUID, Long> distribute(String currentWeek) {
        String paidWeek = WeekId.previous(currentWeek);
        List<WeeklyAllocation> allocations = computeAllocations();
        if (allocations.isEmpty()) {
            resetWeekly(currentWeek);
            return Map.of();
        }
        long fund = settings.weeklyFund.weeklyAmount;
        Map<UUID, Long> payments = new HashMap<>();
        long totalPaid = 0;
        long now = System.currentTimeMillis();
        for (WeeklyAllocation allocation : allocations) {
            long share = allocation.share();
            UUID playerId = allocation.playerId();
            TransactionResult result = accounts.deposit(playerId, share,
                    TransactionContext.of(TransactionType.WEEKLY_REWARD, null,
                            "weekly-fund:" + paidWeek, "weekly:" + paidWeek + ":" + playerId));
            if (result.status() == TransactionResult.Status.SUCCESS
                    || result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
                totalPaid += share;
                String txId = result.transactionId();
                database.inTransaction(connection -> {
                    payouts.insert(connection, database.dialect(), new WeeklyPayoutRow(
                            paidWeek, playerId, allocation.countedSeconds(),
                            (int) Math.min(Integer.MAX_VALUE, allocation.points()), share, now, txId));
                    return null;
                });
                if (result.status() == TransactionResult.Status.SUCCESS) {
                    payments.put(playerId, share);
                }
            }
        }
        long remainder = fund - totalPaid;
        if (remainder > 0) {
            accounts.deposit(TreasuryService.TREASURY_UUID, remainder,
                    TransactionContext.of(TransactionType.WEEKLY_REWARD, null,
                            "weekly-fund:remainder:" + paidWeek, "weekly:treasury:" + paidWeek));
        }
        resetWeekly(currentWeek);
        VEconomyMod.LOGGER.info("Недельный фонд {} за неделю {}: игроков {}, выплачено {}",
                fund, paidWeek, payments.size(), totalPaid);
        return payments;
    }

    private void resetWeekly(String currentWeek) {
        try {
            database.inTransaction(connection -> {
                activityRepository.resetWeekly(connection, currentWeek);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка сброса недельной активности", e);
        }
    }

    /** Расчётная выплата игроку за завершённую неделю (внутреннее представление). */
    private record Allocation(UUID playerId, long countedSeconds, long points) {
    }

    /** Публичный снимок расчётной выплаты за завершённую неделю. */
    public record WeeklyAllocation(UUID playerId, long countedSeconds, long points, long share) {
    }

    /** Состояние недельного фонда для административной команды. */
    public record WeeklyStatus(boolean enabled, boolean autoRun, long weeklyAmount,
                               String currentWeek, String distributedWeek, boolean weekDistributed,
                               int eligiblePlayers, long totalPoints,
                               long totalCountedSeconds, long totalShare) {
    }
}