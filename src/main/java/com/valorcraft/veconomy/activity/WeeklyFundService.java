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
 * Выплата обрабатывает <b>самый старый незакрытый период</b>, а не только напрямую
 * предыдущую неделю: если за предыдущие недели остались невыплаченные снимки, они
 * обрабатываются по очереди, а любой ручной {@code run} продолжает с самого раннего.
 * Команды {@code preview}/{@code status}/{@code run confirm} по умолчанию смотрят именно
 * на этот снимок (и умеют принимать явный {@code weekId}).
 * <p>
 * Ротация возвращает успешность: при ошибке БД выплата полностью прерывается,
 * {@code distributed_week} не продвигается и снимок не теряется — на следующем
 * интервале попытка повторится. Участие и очки фиксируются при ротации; доля
 * пересчитывается от сохранённых очков на момент выплаты:
 * {@code share = floor(fund * points / totalPoints)} через {@link BigInteger}.
 * <p>
 * Выплата возобновляема и «атомарна»: каждая выплата игроку идемпотентна по ключу
 * {@code weekly:<week>:<uuid>}, а период не закрывается, пока есть строки в статусе
 * не {@code SUCCESS}/{@code DUPLICATE_OPERATION} — повторный {@code run confirm} продолжает
 * с неуспешных. Остаток от деления (округление) уходит в казну только при полном закрытии
 * периода; если начисление казне не пройдёт, период останется незакрытым и будет повторён,
 * чтобы остаток не пропал из учёта.
 * <p>
 * Автозапуск ({@code weeklyFund.autoRun}) по умолчанию выключен; администратор выполняет
 * выплату вручную: {@code /economy admin weekly run confirm}. Первичный запуск не платит —
 * лишь фиксирует текущую неделю, чтобы первая выплата прошла по полной следующей неделе.
 */
public final class WeeklyFundService {

    private static final String DISTRIBUTED_WEEK_KEY = "weekly_fund.distributed_week";
    private static final String TREASURY_PENDING_PREFIX = "weekly_fund.treasury_pending:";
    /** Защита от бесконечных циклов при переборе недель в очереди. */
    private static final int SCAN_LIMIT = 4000;

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

    /** Ручная выплата администратором (не зависит от {@code autoRun}); обрабатывает самый старый незакрытый период. */
    public Map<UUID, Long> runNow() {
        return distributeIfDue(true);
    }

    /** Ручная выплата конкретной недели ({@code /economy admin weekly run <weekId> confirm}). */
    public Map<UUID, Long> runNow(String weekId) {
        WeeklyFund cfg = settings.weeklyFund;
        if (weekId == null || !WeekId.isValid(weekId) || !cfg.enabled || cfg.weeklyAmount <= 0) {
            return Map.of();
        }
        String currentWeek = WeekId.current();
        if (weekId.equals(WeekId.previous(currentWeek))) {
            if (!ensurePrevRotated(weekId, currentWeek)) {
                return Map.of();
            }
        }
        boolean has;
        try {
            has = database.inTransaction(connection -> periods.hasWeek(connection, weekId));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения снимка недели {}", weekId, e);
            return Map.of();
        }
        if (!has) {
            return Map.of();
        }
        return distribute(weekId, currentWeek, cfg);
    }

    /**
     * Выплатить фонд, если есть незакрытые периоды и это разрешено. Возвращает выплаты
     * текущего запуска (игрок → сумма) для уведомлений; пустая карта — если выплаты не было.
     */
    private Map<UUID, Long> distributeIfDue(boolean allowed) {
        WeeklyFund cfg = settings.weeklyFund;
        String currentWeek = WeekId.current();
        String distributed;
        try {
            distributed = database.inTransaction(connection ->
                    MetaRepository.get(connection, database.dialect(), DISTRIBUTED_WEEK_KEY));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения состояния недельного фонда", e);
            return Map.of();
        }
        if (distributed == null) {
            // Первый запуск мода: без накопленной истории «предыдущая неделя» не определена,
            // поэтому выплату не производим — лишь фиксируем текущую неделю.
            markDistributedWeek(currentWeek);
            VEconomyMod.LOGGER.info("Недельный фонд: первичная инициализация без выплаты (неделя {})",
                    currentWeek);
            return Map.of();
        }

        // Независимая ротация: сохраняем снимок завершённой недели и обнуляем накопитель.
        // При ошибке всё прерывается, чтобы неделя не была помечена распределённой вслепую.
        String prev = WeekId.previous(currentWeek);
        if (!ensurePrevRotated(prev, currentWeek)) {
            return Map.of();
        }
        // Пропущенные (офлайн) недели без снимка закрываем как пустые, чтобы очередь шла дальше.
        closeEmptyGaps(distributed, currentWeek);
        // Двигаем распределённую неделю вперёд по уже закрытым периодам (ремонт после сбоев).
        distributed = advanceClosed(distributed, currentWeek);
        // Самый старый незакрытый период.
        String target = oldestOpenWeek(distributed, currentWeek);
        if (target == null) {
            return Map.of();
        }
        if (!cfg.enabled || cfg.weeklyAmount <= 0 || !allowed) {
            return Map.of();
        }
        return distribute(target, currentWeek, cfg);
    }

    private void markDistributedWeek(String weekId) {
        try {
            database.inTransaction(connection -> {
                MetaRepository.set(connection, database.dialect(), DISTRIBUTED_WEEK_KEY, weekId);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки распределённой недели {}", weekId, e);
        }
    }

    // ---------------------------------------------------------------- rotation

    /** Убедиться, что завершённая неделя сохранена снимком. Возвращает успешность: при
     *  ошибке БД {@code distributed_week} трогать нельзя, поэтому прерываем всю выплату. */
    private boolean ensurePrevRotated(String paidWeek, String currentWeek) {
        boolean hasSnapshot;
        try {
            hasSnapshot = database.inTransaction(connection -> periods.hasWeek(connection, paidWeek));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка проверки снимка недели {}", paidWeek, e);
            return false;
        }
        if (hasSnapshot) {
            return true;
        }
        return rotate(paidWeek, currentWeek);
    }

    private boolean rotate(String paidWeek, String currentWeek) {
        WeeklyFund cfg = settings.weeklyFund;
        long minAccountAgeMillis = cfg.minAccountAgeDays <= 0 ? 0 : cfg.minAccountAgeDays * 86_400_000L;
        List<PlayerActivityRow> rows;
        try {
            rows = database.inTransaction(connection ->
                    activityRepository.listWithWeeklyActivity(connection));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения активности для ротации недели {}", paidWeek, e);
            return false;
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

        // Снимок и сброс накопителя в одной транзакции: в случае ошибки не останется
        // ни части снимка, ни потерянных секунд — и неделю можно будет ротировать снова.
        try {
            boolean empty = eligible.isEmpty();
            database.inTransaction(connection -> {
                if (empty) {
                    // Некому платить: помечаем неделю пустой служебной строкой, чтобы она
                    // считалась закрытой и очередь продвигалась дальше.
                    periods.insertEmpty(connection, database.dialect(), paidWeek, now);
                } else {
                    for (Allocation allocation : eligible) {
                        periods.insert(connection, database.dialect(), new WeeklyPeriodRow(
                                paidWeek, allocation.playerId(), allocation.countedSeconds(),
                                allocation.points(), WeeklyPeriodRepository.STATUS_PENDING, 0, null));
                    }
                }
                activityRepository.resetWeekly(connection, currentWeek);
                return null;
            });
            VEconomyMod.LOGGER.info("Недельный фонд: ротация — снимок недели {} на {} игроков",
                    paidWeek, empty ? 0 : eligible.size());
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка ротации снимка недели {}", paidWeek, e);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- queue

    /** Закрыть пропущенные (офлайн) недели без снимка пустой строкой, чтобы очередь двигалась. */
    private void closeEmptyGaps(String distributed, String currentWeek) {
        String prev = WeekId.previous(currentWeek);
        String w = WeekId.next(distributed);
        int guard = 0;
        while (w.compareTo(prev) < 0) {
            if (++guard > SCAN_LIMIT) {
                VEconomyMod.LOGGER.warn("Недельный фонд: слишком большая очередь пропущенных недель, остановка");
                return;
            }
            boolean has;
            try {
                String week = w;
                has = database.inTransaction(connection -> periods.hasWeek(connection, week));
            } catch (DatabaseException e) {
                VEconomyMod.LOGGER.error("Ошибка проверки снимка недели {}", w, e);
                return;
            }
            if (!has) {
                try {
                    long now = System.currentTimeMillis();
                    String week = w;
                    database.inTransaction(connection -> {
                        periods.insertEmpty(connection, database.dialect(), week, now);
                        return null;
                    });
                } catch (DatabaseException e) {
                    VEconomyMod.LOGGER.error("Ошибка закрытия пустой недели {}", w, e);
                    return;
                }
            }
            w = WeekId.next(w);
        }
    }

    /** Продвинуть распределённую неделю вперёд по уже закрытым периодам (в т.ч. ремонт после сбоев). */
    private String advanceClosed(String distributed, String currentWeek) {
        String prev = WeekId.previous(currentWeek);
        String cursor = distributed;
        String w = WeekId.next(cursor);
        int guard = 0;
        while (w.compareTo(prev) <= 0) {
            if (++guard > SCAN_LIMIT) {
                return cursor;
            }
            boolean has;
            boolean all;
            try {
                String week = w;
                has = database.inTransaction(connection -> periods.hasWeek(connection, week));
                all = has && database.inTransaction(connection -> periods.allPaid(connection, week));
            } catch (DatabaseException e) {
                VEconomyMod.LOGGER.error("Ошибка проверки закрытой недели {}", w, e);
                return cursor;
            }
            boolean treasuryPending = isTreasuryPending(w);
            if (!has || !all || treasuryPending) {
                break;
            }
            cursor = w;
            markDistributedWeek(w);
            w = WeekId.next(w);
        }
        return cursor;
    }

    /** Самый старый незакрытый период (не полностью выплачен ИЛИ остаток казны не получен). */
    String oldestOpenWeek(String distributed, String currentWeek) {
        String prev = WeekId.previous(currentWeek);
        String w = WeekId.next(distributed);
        int guard = 0;
        while (w.compareTo(prev) <= 0) {
            if (++guard > SCAN_LIMIT) {
                return null;
            }
            if (isTreasuryPending(w)) {
                return w;
            }
            boolean has;
            boolean open;
            try {
                String week = w;
                has = database.inTransaction(connection -> periods.hasWeek(connection, week));
                open = has && database.inTransaction(connection -> !periods.allPaid(connection, week));
            } catch (DatabaseException e) {
                VEconomyMod.LOGGER.error("Ошибка проверки статуса недели {}", w, e);
                return null;
            }
            if (open) {
                return w;
            }
            w = WeekId.next(w);
        }
        return null;
    }

    // ---------------------------------------------------------------- state

    /** Неделя, которую показывают команды: самый старый незакрытый или последняя завершённая. */
    private String displayWeek(String distributed, String currentWeek) {
        String open = oldestOpenWeek(distributed, currentWeek);
        return open == null ? WeekId.previous(currentWeek) : open;
    }

    /** Что будет выплачено за указанную неделю (без изменения балансов), читая снимок. */
    public List<WeeklyAllocation> preview() {
        String currentWeek = WeekId.current();
        String distributed = readDistributedWeekQuiet();
        return computeAllocationsFor(displayWeek(distributed, currentWeek));
    }

    public List<WeeklyAllocation> preview(String weekId) {
        if (weekId == null || !WeekId.isValid(weekId)) {
            return List.of();
        }
        return computeAllocationsFor(weekId);
    }

    public WeeklyStatus status() {
        WeeklyFund cfg = settings.weeklyFund;
        String currentWeek = WeekId.current();
        String distributed = readDistributedWeekQuiet();
        String target = displayWeek(distributed, currentWeek);
        List<WeeklyAllocation> allocations = computeAllocationsFor(target);
        long totalShare = 0;
        long totalPoints = 0;
        long totalSeconds = 0;
        for (WeeklyAllocation allocation : allocations) {
            totalShare += allocation.share();
            totalPoints += allocation.points();
            totalSeconds += allocation.countedSeconds();
        }
        return new WeeklyStatus(cfg.enabled, cfg.autoRun, cfg.weeklyAmount, currentWeek,
                distributed, WeekId.previous(currentWeek).equals(distributed), target,
                allocations.size(), totalPoints, totalSeconds, totalShare);
    }

    private String readDistributedWeekQuiet() {
        try {
            return database.inTransaction(connection ->
                    MetaRepository.get(connection, database.dialect(), DISTRIBUTED_WEEK_KEY));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения состояния недельного фонда", e);
            return null;
        }
    }

    /** Расчёт по снимку недели: доли пересчитываются от сохранённых очков. */
    private List<WeeklyAllocation> computeAllocationsFor(String weekId) {
        WeeklyFund cfg = settings.weeklyFund;
        long fund = cfg.weeklyAmount;
        List<WeeklyPeriodRow> rows;
        try {
            rows = database.inTransaction(connection -> periods.listByWeek(connection, weekId));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения снимка недели {}", weekId, e);
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
     * Выплатить снимок недели. Возобновляемо: заново пытаются строки не в статусе
     * {@code PAID}; успех или идемпотентный повтор закрывает сроку, любой другой исход
     * оставляет период открытым. Остаток от деления выплачивается в казну только при полном
     * закрытии периода; при неудаче период остаётся незакрытым и будет повторён, чтобы
     * остаток не пропал из учёта.
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
            closeAndAdvance(paidWeek, currentWeek);
            return Map.of();
        }
        long totalPoints = 0;
        for (WeeklyPeriodRow row : rows) {
            totalPoints += row.points();
        }
        if (totalPoints <= 0) {
            // Только служебная пустая строка (некому платить) — закрываем без выплат.
            long now = System.currentTimeMillis();
            for (WeeklyPeriodRow row : rows) {
                if (!WeeklyPeriodRepository.STATUS_PAID.equals(row.status())) {
                    markPaid(paidWeek, row.playerId(), now, null);
                }
            }
            closeAndAdvance(paidWeek, currentWeek);
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
            boolean ok = true;
            if (remainder > 0) {
                ok = creditTreasury(paidWeek, remainder);
                if (!ok) {
                    setTreasuryPending(paidWeek);
                    VEconomyMod.LOGGER.error("Недельный фонд: не удалось начислить остаток {} в казну "
                            + "за неделю {}; период оставлен открытым", remainder, paidWeek);
                }
            }
            if (ok) {
                closeAndAdvance(paidWeek, currentWeek);
            }
        }
        VEconomyMod.LOGGER.info("Недельный фонд {} за неделю {}: игроков {}, выплачено {}, закрыта {}",
                fund, paidWeek, payments.size(), totalPaid, closed);
        return payments;
    }

    /**
     * Закрыть оплаченную неделю и продвинуть распределённую неделю вперёд через закрытые
     * периоды, останавливаясь на первом незакрытом. Так явный {@code run <weekId>} младшей
     * недели не «проглатывает» более старый незакрытый период: он останется в очереди.
     */
    private void closeAndAdvance(String paidWeek, String currentWeek) {
        clearTreasuryPending(paidWeek);
        advanceClosed(readDistributedWeekQuiet(), currentWeek);
    }

    /** Зачислить остаток в казну. Возвращает успех (идемпотентный повтор тоже считается успехом). */
    private boolean creditTreasury(String weekId, long amount) {
        TransactionResult result = accounts.deposit(TreasuryService.TREASURY_UUID, amount,
                TransactionContext.of(TransactionType.WEEKLY_REWARD, null,
                        "weekly-fund:remainder:" + weekId, "weekly:treasury:" + weekId));
        return result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE_OPERATION;
    }

    private boolean isTreasuryPending(String weekId) {
        try {
            String value = database.inTransaction(connection ->
                    MetaRepository.get(connection, database.dialect(), treasuryKey(weekId)));
            return "1".equals(value);
        } catch (DatabaseException e) {
            return false;
        }
    }

    private void setTreasuryPending(String weekId) {
        try {
            database.inTransaction(connection -> {
                MetaRepository.set(connection, database.dialect(), treasuryKey(weekId), "1");
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки неполученного остатка недели {}", weekId, e);
        }
    }

    private void clearTreasuryPending(String weekId) {
        try {
            database.inTransaction(connection -> {
                MetaRepository.set(connection, database.dialect(), treasuryKey(weekId), "");
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка снятия отметки остатка недели {}", weekId, e);
        }
    }

    private static String treasuryKey(String weekId) {
        return TREASURY_PENDING_PREFIX + weekId;
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

    /** Публичный снимок расчётной выплаты за неделю. */
    public record WeeklyAllocation(UUID playerId, long countedSeconds, long points, long share) {
    }

    /** Состояние недельного фонда для административной команды. */
    public record WeeklyStatus(boolean enabled, boolean autoRun, long weeklyAmount,
                               String currentWeek, String distributedWeek,
                               boolean weekDistributed, String targetWeek,
                               int eligiblePlayers, long totalPoints,
                               long totalCountedSeconds, long totalShare) {
    }
}