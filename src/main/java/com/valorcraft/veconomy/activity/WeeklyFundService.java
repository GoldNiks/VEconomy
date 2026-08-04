package com.valorcraft.veconomy.activity;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.economy.AccountService;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.MetaRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Недельный фонд. Один раз за ISO-неделю (при смене недели) фонд делится между игроками
 * пропорционально накопленному за завершённую неделю активному времени (без AFK).
 * Выплата идемпотентна (ключ {@code weekly:<week>:<uuid>}), итог не превышает фонд:
 * остаток от деления — в казну. После распределения недельные счётчики обнуляются.
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

    /**
     * Выплатить фонд, если наступила новая неделя. Возвращает выплаты текущего запуска
     * (игрок → сумма) для уведомлений; пустая карта — если выплата не производилась.
     */
    public Map<UUID, Long> maybeDistribute() {
        if (!settings.weeklyFund.enabled || settings.weeklyFund.weeklyAmount <= 0) {
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
        Map<UUID, Long> payments = distribute(currentWeek);
        try {
            database.inTransaction(connection -> {
                MetaRepository.set(connection, database.dialect(), DISTRIBUTED_WEEK_KEY, currentWeek);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки распределённой недели", e);
        }
        return payments;
    }

    private Map<UUID, Long> distribute(String currentWeek) {
        String paidWeek = WeekId.previous(currentWeek);
        Map<UUID, Long> payments = new HashMap<>();
        try {
            List<PlayerActivityRow> rows = database.inTransaction(connection ->
                    activityRepository.listWithWeeklyActivity(connection));
            long fund = settings.weeklyFund.weeklyAmount;
            long totalActive = 0;
            for (PlayerActivityRow row : rows) {
                if (!row.excludedFromRewards()) {
                    totalActive += row.weeklyActiveSeconds();
                }
            }
            if (totalActive <= 0) {
                resetWeekly(currentWeek);
                return payments;
            }
            long perSecond = fund / totalActive;
            long totalPaid = 0;
            long now = System.currentTimeMillis();
            for (PlayerActivityRow row : rows) {
                if (row.excludedFromRewards() || row.weeklyActiveSeconds() <= 0) {
                    continue;
                }
                long share = perSecond * row.weeklyActiveSeconds();
                if (share <= 0) {
                    continue;
                }
                UUID playerId = row.playerId();
                TransactionResult result = accounts.deposit(playerId, share,
                        TransactionContext.of(TransactionType.WEEKLY_REWARD, null,
                                "weekly-fund:" + paidWeek, "weekly:" + paidWeek + ":" + playerId));
                if (result.status() == TransactionResult.Status.SUCCESS
                        || result.status() == TransactionResult.Status.DUPLICATE_OPERATION) {
                    totalPaid += share;
                    String txId = result.transactionId();
                    database.inTransaction(connection -> {
                        payouts.insert(connection, database.dialect(), new WeeklyPayoutRow(
                                paidWeek, playerId, row.weeklyActiveSeconds(), 0, share, now, txId));
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
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка распределения недельного фонда", e);
        }
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
}
