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
 * Недельный фонд. При смене недели активность завершённой недели сохраняется снимком
 * в таблицу {@code weekly_activity_periods} (независимая ротация), после чего недельный
 * накопитель обнуляется — независимо от того, будет ли выплата в этот момент.
 * <p>
 * Выплата читает именно снимок закрытой недели, а не живое накопление {@code player_activity},
 * поэтому {@code preview}/{@code status}/{@code run confirm} корректны даже после сброса
 * счётчиков. Участие и очки фиксируются при ротации; доля пересчитывается от сохранённых
 * очков на момент выплаты: {@code share = floor(fund * points / totalPoints)} через
 * {@link BigInteger} (защита от обнуления при {@code totalPoints > fund}).
 * <p>
 * Выплата возобновляема и «атомарна»: каждая выплата игроку идемпотентна по ключу
 * {@code weekly:<week>:<uuid>}, а период не закрывается, пока есть строки в статусе
 * не {@code SUCCESS}/{@code DUPLICATE_OPERATION} — повторный {@code run confirm} продолжает
 * с неуспешных. Остаток от деления (округление) уходит в казну только при полном закрытии
 * периода, чтобы при возобновлении не дублировать деньги.
 * <p>
 * Автозапуск ({@code weeklyFund.autoRun}) по умолчанию выключен; администратор выполняет
 * выплату вручную: {@code /economy admin weekly run confirm}. Первичный запуск не платит —
 * лишь фиксирует текущую неделю, чтобы первая выплата прошла по полной следующей неделе.
 */
public final class WeeklyFundService {

    private static final String DISTRIBUTED_WEEK_KEY = "weekly_fund.distributed_week";

    private final DatabaseManager database;
    private final PlayerActivityRepository activityRepository;
    private final WeeklyPeriodRepository periods;
    private final WeeklyPayoutRepository payouts;
    private final AccountService accounts;
    private volatile EconomySettings settings;

    public WeeklyFundService(DatabaseManager database, PlayerActivityRepository activityRepository,
                             WeeklyPeriodRepository periods, WeeklyPayoutRepository payouts,
                             AccountService accounts, EconomySettings settings) {
        this.database = database;
        this.activityRepository = activityRepository;
        this.periods = periods;
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
        String currentWeek = WeekId.current();
        String paidWeek = WeekId.previous(currentWeek);
        String distributed = readDistributedWeek();
        if (distributed == null) {
            // Первый запуск мода: без накопленной истории «предыдущая неделя» не определена,
            // поэтому выплату не производим — лишь фиксируем текущую неделю.
            markDistributedWeek(currentWeek);
            VEconomyMod.LOGGER.info("Недельный фонд: первичная инициализация без выплаты (неделя {})",
                    currentWeek);
            return Map.of();
        }
        if (distributed.equals(currentWeek)) {
            return Map.of();
        }

        // Независимая ротация: при смене недели сохраняем снимок завершённой недели
        // (previous(current)) и обнуляем накопитель — даже если выплата сейчас не выполняется
        // (autoRun выключен, фонд отключён). Выплата и preview читают только этот снимок.
        rotateIfNeeded(paidWeek, currentWeek);
        if (!cfg.enabled || cfg.weeklyAmount <= 0 || !allowed) {
            return Map.of();
        }
        return distribute(paidWeek, currentWeek, cfg);
    }

    private String readDistributedWeek() {
        try {
            return database.inTransaction(connection ->
                    MetaRepository.get(connection, database.dialect(), DISTRIBUTED_WEEK_KEY));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения состояния недельного фонда", e);
            return null;
        }
    }

    private void markDistributedWeek(String currentWeek) {
        try {
            database.inTransaction(connection -> {
                MetaRepository.set(connection, database.dialect(), DISTRIBUTED_WEEK_KEY, currentWeek);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки распределённой недели", e);
        }
    }

    // ---------------------------------------------------------------- rotation

    /** Сохранить снимок завершённой недели и обнулить накопитель, если это ещё не сделано. */
    private void rotateIfNeeded(String paidWeek, String currentWeek) {
        try {
            boolean hasSnapshot = database.inTransaction(connection -> periods.hasWeek(connection, paidWeek));
            if (!hasSnapshot) {
                rotate(paidWeek, currentWeek);
            }
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка проверки снимка недели {}", paidWeek, e);
        }
    }

    private void rotate(String paidWeek, String currentWeek) {
        WeeklyFund cfg = settings.weeklyFund;
        long minAccountAgeMillis = cfg.minAccountAgeDays <= 0 ? 0 : cfg.minAccountAgeDays * 86_400_000L;
        List<PlayerActivityRow> rows;
        try {
            rows = database.inTransaction(connection ->
                    activityRepository.listWithWeeklyActivity(connection));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения активности для ротации недели {}", paidWeek, e);
            return;
        }
        long now = System.currentTimeMillis();
        List<Allocation> eligible = new ArrayList<>();
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
        }

        if (eligible.isEmpty()) {
            // Некому платить — обнуляем накопитель и закрываем неделю как пустую, чтобы
            // ротация не повторялась на каждом тике в ожидании ручной выплаты.
            resetWeekly(currentWeek);
            markDistributedWeek(currentWeek);
            VEconomyMod.LOGGER.info("Недельный фонд: ротация недели {} — нет участников", paidWeek);
            return;
        }

        try {
            database.inTransaction(connection -> {
                for (Allocation allocation : eligible) {
                    periods.insert(connection, database.dialect(), new WeeklyPeriodRow(
                            paidWeek, allocation.playerId(), allocation.countedSeconds(),
                            allocation.points(), WeeklyPeriodRepository.STATUS_PENDING, 0, null));
                }
                activityRepository.resetWeekly(connection, currentWeek);
                return null;
            });
            VEconomyMod.LOGGER.info("Недельный фонд: ротация — снимок недели {} на {} игроков",
                    paidWeek, eligible.size());
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка ротации снимка недели {}", paidWeek, e);
        }
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

    // ---------------------------------------------------------------- state

    /** Что будет выплачено за завершённую неделю (без изменения балансов), читая снимок. */
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

    /** Расчёт по снимку завершённой недели: доли пересчитываются от сохранённых очков. */
    private List<WeeklyAllocation> computeAllocations() {
        WeeklyFund cfg = settings.weeklyFund;
        long fund = cfg.weeklyAmount;
        String paidWeek = WeekId.previous(WeekId.current());
        List<WeeklyPeriodRow> rows;
        try {
            rows = database.inTransaction(connection -> periods.listByWeek(connection, paidWeek));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения снимка недели {}", paidWeek, e);
            return List.of();
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        long totalPoints = 0;
        for (WeeklyPeriodRow row : rows) {
            totalPoints += row.points();
        }
        if (totalPoints <= 0) {
            return List.of();
        }
        // share = floor(fund * points / totalPoints). Сначала умножаем, потом делим, иначе
        // при totalPoints > fund множитель обнулился бы и весь фонд ушёл в казну.
        BigInteger fundBig = BigInteger.valueOf(fund);
        BigInteger totalBig = BigInteger.valueOf(totalPoints);
        List<WeeklyAllocation> result = new ArrayList<>(rows.size());
        for (WeeklyPeriodRow row : rows) {
            long share = fundBig.multiply(BigInteger.valueOf(row.points()))
                    .divide(totalBig).longValue();
            if (share <= 0) {
                continue;
            }
            result.add(new WeeklyAllocation(row.playerId(),
                    row.countedSeconds(), row.points(), share));
        }
        result.sort(Comparator.comparingLong(WeeklyAllocation::share).reversed());
        return result;
    }

    // ---------------------------------------------------------------- distribution

    /**
     * Выплатить снимок завершённой недели. Возобновляемо: заново пытаются строки не в
     * статусе {@code PAID}; успех или идемпотентный повтор закрывает сроку, любой другой
     * исход оставляет период открытым. Остаток от деления выплачивается в казну только
     * при полном закрытии периода, поэтому повторный запуск не дублирует деньги.
     */
    private Map<UUID, Long> distribute(String paidWeek, String currentWeek, WeeklyFund cfg) {
        List<WeeklyPeriodRow> rows;
        try {
            rows = database.inTransaction(connection -> periods.listByWeek(connection, paidWeek));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения снимка недели {}", paidWeek, e);
            return Map.of();
        }
        if (rows.isEmpty()) {
            markDistributedWeek(currentWeek);
            return Map.of();
        }
        long totalPoints = 0;
        for (WeeklyPeriodRow row : rows) {
            totalPoints += row.points();
        }
        if (totalPoints <= 0) {
            markDistributedWeek(currentWeek);
            return Map.of();
        }
        long fund = cfg.weeklyAmount;
        BigInteger fundBig = BigInteger.valueOf(fund);
        BigInteger totalBig = BigInteger.valueOf(totalPoints);

        // Итоговый остаток фиксирован для периода: fund минус сумма всех долей строк.
        long grossShares = 0;
        long[] shares = new long[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            long share = fundBig.multiply(BigInteger.valueOf(rows.get(i).points()))
                    .divide(totalBig).longValue();
            shares[i] = share;
            grossShares += share;
        }
        long remainder = Math.max(0, fund - grossShares);

        Map<UUID, Long> payments = new HashMap<>();
        long totalPaid = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < rows.size(); i++) {
            WeeklyPeriodRow row = rows.get(i);
            if (WeeklyPeriodRepository.STATUS_PAID.equals(row.status())) {
                continue;
            }
            UUID playerId = row.playerId();
            long share = shares[i];
            if (share <= 0) {
                // Доля меньше минимальной единицы: выплаты нет, но строку закрываем,
                // чтобы период мог завершиться и неделя продвинулась вперёд.
                markPaid(paidWeek, playerId, now, null);
                continue;
            }
            TransactionResult result = accounts.deposit(playerId, share,
                    TransactionContext.of(TransactionType.WEEKLY_REWARD, null,
                            "weekly-fund:" + paidWeek, "weekly:" + paidWeek + ":" + playerId));
            if (result.status() == TransactionResult.Status.SUCCESS
                    || result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
                totalPaid += share;
                String txId = result.transactionId();
                try {
                    database.inTransaction(connection -> {
                        periods.markPaid(connection, paidWeek, playerId, now, txId);
                        payouts.insert(connection, database.dialect(), new WeeklyPayoutRow(
                                paidWeek, playerId, row.countedSeconds(),
                                (int) Math.min(Integer.MAX_VALUE, row.points()), share, now, txId));
                        return null;
                    });
                } catch (DatabaseException e) {
                    VEconomyMod.LOGGER.error("Ошибка записи выплаты недели {} игроку {}", paidWeek, playerId, e);
                    continue;
                }
                if (result.status() == TransactionResult.Status.SUCCESS) {
                    payments.put(playerId, share);
                }
            } else {
                // Неуспех (заморожен, лимит, ...): период не закрывается, повторный
                // run confirm продолжит выплату с этого игрока.
                markFailed(paidWeek, playerId);
            }
        }

        boolean closed = allPaid(paidWeek);
        if (closed) {
            if (remainder > 0) {
                accounts.deposit(TreasuryService.TREASURY_UUID, remainder,
                        TransactionContext.of(TransactionType.WEEKLY_REWARD, null,
                                "weekly-fund:remainder:" + paidWeek, "weekly:treasury:" + paidWeek));
            }
            markDistributedWeek(currentWeek);
        }
        VEconomyMod.LOGGER.info("Недельный фонд {} за неделю {}: игроков {}, выплачено {}, закрыта {}",
                fund, paidWeek, payments.size(), totalPaid, closed);
        return payments;
    }

    private void markPaid(String weekId, UUID playerId, long paidAt, String txId) {
        try {
            database.inTransaction(connection -> {
                periods.markPaid(connection, weekId, playerId, paidAt, txId);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки выплаты недели {} игроку {}", weekId, playerId, e);
        }
    }

    private void markFailed(String weekId, UUID playerId) {
        try {
            database.inTransaction(connection -> {
                periods.markFailed(connection, weekId, playerId);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки неуспешной выплаты недели {} игроку {}",
                    weekId, playerId, e);
        }
    }

    private boolean allPaid(String weekId) {
        try {
            return database.inTransaction(connection -> periods.allPaid(connection, weekId));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка проверки завершения недели {}", weekId, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- helpers

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

    /** Участник снимка недели: учитываемое время и очки (внутреннее представление). */
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