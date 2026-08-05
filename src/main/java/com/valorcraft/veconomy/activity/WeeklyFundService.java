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
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.AccountRow;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.EscrowRepository;
import com.valorcraft.veconomy.persistence.MetaRepository;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Недельный фонд.
 * <p>
 * <b>Закрытие недели</b> происходит независимо от выплаты: активность завершённой недели
 * читается из таблицы {@code weekly_activity_days} (активность привязана к дню, поэтому
 * недели на границе никогда не смешиваются), после чего план — размер фонда, коэффициент
 * экономики, денежная масса, очки, доли и остаток — <b>замораживается</b> в таблицах
 * {@code weekly_fund_plans} и {@code weekly_activity_periods}. Ни дальнейшая активность,
 * ни изменение конфига, ни изменение денежной массы не меняют план.
 * <p>
 * <b>Автономность:</b> если включён {@code weeklyFund.autoPayout}, план помечается временем
 * автоматической выплаты ({@code planned_at + payoutDelayHours}) и выплачивается сам после
 * контрольной задержки. Ручной {@code run confirm} платит сразу. Неуспешные строки
 * (замороженный игрок, лимит) оставляют период открытым — повторный запуск продолжает
 * с сохранённых долей, остаток казне начисляется только при полном закрытии.
 * <p>
 * Денежная масса для коэффициента экономики считается по существующей модели total supply:
 * все личные балансы (включая казну как системный аккаунт) плюс зарезервированный эскроу.
 * <p>
 * Выплата возобновляема и идемпотентна: каждая выплата игроку идемпотентна по ключу
 * {@code weekly:<week>:<uuid>}; ошибка БД не продвигает {@code distributed_week}.
 */
public final class WeeklyFundService {

    private static final String DISTRIBUTED_WEEK_KEY = "weekly_fund.distributed_week";
    /** Защита от бесконечных циклов при переборе недель в очереди. */
    private static final int SCAN_LIMIT = 4000;

    private final DatabaseManager database;
    private final PlayerActivityRepository activityRepository;
    private final WeeklyActivityDayRepository days;
    private final WeeklyPeriodRepository periods;
    private final WeeklyTreasuryRepository treasury;
    private final WeeklyPayoutRepository payouts;
    private final WeeklyFundPlanRepository plans;
    private final AccountRepository accounts;
    private final EscrowRepository escrow;
    private final AccountService accountService;
    private volatile EconomySettings settings;

    public WeeklyFundService(DatabaseManager database, PlayerActivityRepository activityRepository,
                             WeeklyActivityDayRepository days, WeeklyPeriodRepository periods,
                             WeeklyTreasuryRepository treasury, WeeklyPayoutRepository payouts,
                             WeeklyFundPlanRepository plans, AccountRepository accounts,
                             EscrowRepository escrow, AccountService accountService,
                             EconomySettings settings) {
        this.database = database;
        this.activityRepository = activityRepository;
        this.days = days;
        this.periods = periods;
        this.treasury = treasury;
        this.payouts = payouts;
        this.plans = plans;
        this.accounts = accounts;
        this.escrow = escrow;
        this.accountService = accountService;
        this.settings = settings;
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
    }

    // ---------------------------------------------------------------- run

    /** Автоматическая выплата (уважает {@code autoPayout} и контрольную задержку). */
    public Map<UUID, Long> maybeDistribute() {
        return distributeIfDue(false);
    }

    /** Ручная выплата администратором: самый старый незакрытый период, без задержки. */
    public Map<UUID, Long> runNow() {
        return distributeIfDue(true);
    }

    /** Ручная выплата конкретной недели ({@code /economy admin weekly run <weekId> confirm}). */
    public Map<UUID, Long> runNow(String weekId) {
        WeeklyFund cfg = settings.weeklyFund;
        if (weekId == null || !WeekId.isValid(weekId) || !cfg.enabled) {
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
        return distribute(weekId, currentWeek);
    }

    /**
     * Обычный цикл: закрытие недели → план → (авто) выплата. Возвращает выплаты текущего
     * запуска; пустая карта — если выплаты не было.
     */
    private Map<UUID, Long> distributeIfDue(boolean manual) {
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
            // поэтому выплату не производим. Чтобы первая полная неделя не потерялась, помечаем
            // распределённой НЕ текущую, а предыдущую неделю.
            String prev = WeekId.previous(currentWeek);
            boolean ok = markFirstWeek(prev);
            VEconomyMod.LOGGER.info("Недельный фонд: первичная инициализация без выплаты (распределена неделя {})",
                    prev);
            return ok ? Map.of() : Map.of();
        }

        // Независимая ротация: сохраняем план завершённой недели и обнуляем накопитель.
        String prev = WeekId.previous(currentWeek);
        if (!ensurePrevRotated(prev, currentWeek)) {
            return Map.of();
        }
        // Пропущенные (офлайн) недели без снимка закрываем как пустые, чтобы очередь шла дальше.
        closeEmptyGaps(distributed, currentWeek);
        // Двигаем распределённую неделю вперёд по уже закрытым периодам.
        distributed = advanceClosed(distributed, currentWeek);
        // Самый старый незакрытый период.
        String target = oldestOpenWeek(distributed, currentWeek);
        if (target == null) {
            return Map.of();
        }
        if (!cfg.enabled) {
            return Map.of();
        }
        if (!manual) {
            // Автоматический режим: платим только после контрольной задержки и только при autoPayout.
            if (!cfg.autoPayout) {
                return Map.of();
            }
            WeeklyFundPlanRow plan = readPlanQuiet(target);
            if (plan == null || plan.autoPayoutAt() == null
                    || System.currentTimeMillis() < plan.autoPayoutAt()) {
                return Map.of();
            }
        }
        return distribute(target, currentWeek);
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

    /**
     * Первичная инициализация: помечаем распределённой предыдущую неделю и создаём её закрытый
     * пустой снимок. Текущая неделя (первая полная) остаётся накапливаться без ротации и будет
     * автоматически выплачена при переходе на следующую. Возвращает успешность записи.
     */
    private boolean markFirstWeek(String prevWeek) {
        try {
            long now = System.currentTimeMillis();
            database.inTransaction(connection -> {
                periods.insertEmpty(connection, database.dialect(), prevWeek, now);
                plans.insert(connection, database.dialect(), new WeeklyFundPlanRow(
                        prevWeek, 0, 0, WeeklyMath.BPS_100_PERCENT, 0, 0, 0, 0, 0, 0, 0,
                        WeeklyFundPlanRow.STATUS_PAID, now, null, now));
                MetaRepository.set(connection, database.dialect(), DISTRIBUTED_WEEK_KEY, prevWeek);
                return null;
            });
            return true;
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка первичной инициализации недельного фонда (неделя {})",
                    prevWeek, e);
            return false;
        }
    }

    // ---------------------------------------------------------------- rotation

    /** Убедиться, что завершённая неделя сохранена снимком. */
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

    /**
     * Закрыть неделю: рассчитать и заморозить план (фонд, коэффициент, очки, доли) и записать
     * снимок периодов. Все чтения — в одной транзакции, запись — в другой: при ошибке ни
     * части плана, ни потерянных секунд не остаётся.
     */
    private boolean rotate(String paidWeek, String currentWeek) {
        WeeklyFund cfg = settings.weeklyFund;
        long now = System.currentTimeMillis();
        PlanComputation plan;
        try {
            plan = database.inTransaction(connection -> computePlan(connection, paidWeek, cfg, now));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка расчёта плана недели {}", paidWeek, e);
            return false;
        }

        try {
            database.inTransaction(connection -> {
                if (plan.isEmpty()) {
                    periods.insertEmpty(connection, database.dialect(), paidWeek, now);
                }
                plans.insert(connection, database.dialect(), plan.toPlanRow(now, cfg.payoutDelayHours, cfg.autoPayout));
                if (!plan.isEmpty()) {
                    for (int i = 0; i < plan.eligible().size(); i++) {
                        Eligible eligible = plan.eligible().get(i);
                        periods.insert(connection, database.dialect(), new WeeklyPeriodRow(
                                paidWeek, eligible.playerId(), eligible.countedSeconds(),
                                eligible.totalPoints(), WeeklyPeriodRepository.STATUS_PENDING, 0, null,
                                eligible.activeDays(), eligible.timePoints(), eligible.dayPoints(),
                                plan.shares()[i]));
                    }
                }
                activityRepository.resetWeekly(connection, currentWeek);
                return null;
            });
            VEconomyMod.LOGGER.info("Недельный фонд: ротация — план недели {} на {} игроков, фонд {}",
                    paidWeek, plan.eligibleCount(), plan.fundAmount());
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка записи плана недели {}", paidWeek, e);
            return false;
        }
        return true;
    }

    /**
     * Чистый расчёт плана для недели: агрегация активности по дням, условия участия, размер
     * фонда, коэффициент экономики, очки и доли. Не записывает ничего в базу.
     */
    private PlanComputation computePlan(Connection connection, String weekId, WeeklyFund cfg, long now) {
        List<WeeklyActivityDayRow> dayRows = days.listByWeek(connection, weekId);
        Map<UUID, Aggregated> aggregated = new HashMap<>();
        for (WeeklyActivityDayRow row : dayRows) {
            Aggregated value = aggregated.computeIfAbsent(row.playerId(), id -> new Aggregated());
            value.totalSeconds += row.activeSeconds();
            if (cfg.minActiveDaySeconds <= 0 || row.activeSeconds() >= cfg.minActiveDaySeconds) {
                value.activeDays++;
            }
        }
        long minAccountAgeMillis = cfg.minAccountAgeDays <= 0 ? 0 : cfg.minAccountAgeDays * 86_400_000L;

        List<Eligible> eligible = new ArrayList<>();
        for (Map.Entry<UUID, Aggregated> entry : aggregated.entrySet()) {
            UUID playerId = entry.getKey();
            Aggregated data = entry.getValue();
            PlayerActivityRow activity = activityRepository.find(connection, playerId).orElse(null);
            if (activity != null && activity.excludedFromRewards()) {
                continue;
            }
            AccountRow account = accounts.find(connection, playerId).orElse(null);
            if (account != null && account.status() == com.valorcraft.veconomy.api.AccountStatus.FROZEN) {
                continue;
            }
            if (cfg.minActiveSeconds > 0 && data.totalSeconds < cfg.minActiveSeconds) {
                continue;
            }
            if (cfg.minActiveDays > 0 && data.activeDays < cfg.minActiveDays) {
                continue;
            }
            if (minAccountAgeMillis > 0) {
                long created = account != null ? account.createdAt()
                        : (activity != null ? activity.firstSeenAt() : now);
                if (now - created < minAccountAgeMillis) {
                    continue;
                }
            }
            long timePoints = WeeklyMath.timePoints(data.totalSeconds, cfg.timePointLevels);
            long dayPoints = WeeklyMath.dayPoints(data.activeDays, cfg.dayPointLevels);
            long points = timePoints + dayPoints;
            if (points <= 0) {
                continue;
            }
            eligible.add(new Eligible(playerId, data.totalSeconds, data.activeDays,
                    timePoints, dayPoints, points));
        }

        int count = eligible.size();
        long baseFund = WeeklyMath.baseFund(count, cfg.baseAmountPerEligiblePlayer);
        long moneySupply = accounts.sumBalance(connection, false) + escrow.sumReserved(connection);
        long supplyPerEligible = count > 0 ? moneySupply / count : 0;
        long coefficientBps = WeeklyMath.economyCoefficientBps(
                supplyPerEligible, cfg.targetSupplyPerEligiblePlayer, cfg.economyCoefficientTiers);
        long fundAmount = WeeklyMath.finalFund(baseFund, coefficientBps, cfg.minimumFund, cfg.maximumFund);

        long totalPoints = 0;
        for (Eligible e : eligible) {
            totalPoints += e.totalPoints();
        }
        long[] shares = new long[eligible.size()];
        long remainder = 0;
        if (count > 0 && fundAmount > 0 && totalPoints > 0) {
            List<WeeklyMath.Participant> participants = new ArrayList<>(count);
            for (Eligible e : eligible) {
                participants.add(new WeeklyMath.Participant(e.playerId(), e.totalPoints()));
            }
            WeeklyMath.Distribution distribution = WeeklyMath.distribute(
                    fundAmount, participants, cfg.maximumPlayerSharePercent);
            shares = distribution.shares();
            remainder = distribution.remainder();
        }
        long totalShare = fundAmount - remainder;
        return new PlanComputation(weekId, baseFund, coefficientBps, moneySupply, supplyPerEligible,
                cfg.targetSupplyPerEligiblePlayer, fundAmount, eligible, shares, totalPoints,
                totalShare, remainder);
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
                        plans.insert(connection, database.dialect(), new WeeklyFundPlanRow(
                                week, 0, 0, WeeklyMath.BPS_100_PERCENT, 0, 0, 0, 0, 0, 0, 0,
                                WeeklyFundPlanRow.STATUS_PAID, now, null, now));
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

    /** Продвинуть распределённую неделю вперёд по уже закрытым периодам. */
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
            boolean treasuryPending;
            try {
                String week = w;
                treasuryPending = database.inTransaction(connection -> treasury.hasPending(connection, week));
            } catch (DatabaseException e) {
                VEconomyMod.LOGGER.error("Ошибка проверки остатка недели {}", w, e);
                return cursor;
            }
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
            boolean treasuryPending;
            try {
                String week = w;
                treasuryPending = database.inTransaction(connection -> treasury.hasPending(connection, week));
            } catch (DatabaseException e) {
                VEconomyMod.LOGGER.error("Ошибка проверки остатка недели {}", w, e);
                return null;
            }
            if (treasuryPending) {
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

    /** Что будет выплачено за указанную неделю (без изменения балансов), читая замороженный план. */
    public List<WeeklyAllocation> preview() {
        String currentWeek = WeekId.current();
        String distributed = readDistributedWeekQuiet();
        return preview(displayWeek(distributed, currentWeek));
    }

    public List<WeeklyAllocation> preview(String weekId) {
        if (weekId == null || !WeekId.isValid(weekId)) {
            return List.of();
        }
        return computeAllocationsFor(weekId);
    }

    /** Прогноз текущей недели (живой расчёт из таблицы дней, не источник истины). */
    public WeeklyForecast forecast() {
        WeeklyFund cfg = settings.weeklyFund;
        String currentWeek = WeekId.current();
        long now = System.currentTimeMillis();
        try {
            PlanComputation plan = database.inTransaction(connection ->
                    computePlan(connection, currentWeek, cfg, now));
            List<WeeklyForecast.PlayerForecast> players = new ArrayList<>(plan.eligible().size());
            for (int i = 0; i < plan.eligible().size(); i++) {
                Eligible eligible = plan.eligible().get(i);
                players.add(new WeeklyForecast.PlayerForecast(eligible.playerId(),
                        eligible.countedSeconds(), eligible.activeDays(), eligible.timePoints(),
                        eligible.dayPoints(), eligible.totalPoints(), plan.shares()[i]));
            }
            players.sort(java.util.Comparator.comparingLong(WeeklyForecast.PlayerForecast::share).reversed());
            return new WeeklyForecast(currentWeek, plan.fundAmount(), plan.baseFund(),
                    plan.coefficientBps(), plan.moneySupply(), plan.supplyPerEligible(),
                    plan.eligibleCount(), plan.totalPoints(), plan.totalShare(), players);
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка прогноза недельного фонда", e);
            return new WeeklyForecast(currentWeek, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }

    /** Прогноз по игроку для {@code /money weekly}: участие, очки и примерная доля. */
    public Optional<WeeklyForecast.PlayerForecast> forecastFor(UUID playerId) {
        WeeklyForecast forecast = forecast();
        for (WeeklyForecast.PlayerForecast candidate : forecast.players()) {
            if (candidate.playerId().equals(playerId)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** Состояние фонда для административной команды. */
    public WeeklyStatus status() {
        WeeklyFund cfg = settings.weeklyFund;
        String currentWeek = WeekId.current();
        String distributed = readDistributedWeekQuiet();
        String target = displayWeek(distributed, currentWeek);
        WeeklyFundPlanRow plan = readPlanQuiet(target);
        List<WeeklyAllocation> allocations = computeAllocationsFor(target);
        long totalShare = 0;
        long totalPoints = 0;
        long totalSeconds = 0;
        for (WeeklyAllocation allocation : allocations) {
            totalShare += allocation.share();
            totalPoints += allocation.points();
            totalSeconds += allocation.countedSeconds();
        }
        String payoutStatus = plan == null ? WeeklyFundPlanRow.STATUS_PLANNED : plan.payoutStatus();
        Long autoPayoutAt = plan == null ? null : plan.autoPayoutAt();
        long fundAmount = plan == null ? 0 : plan.fundAmount();
        return new WeeklyStatus(cfg.enabled, cfg.autoPayout, cfg.payoutDelayHours, fundAmount,
                currentWeek, distributed, WeekId.previous(currentWeek).equals(distributed), target,
                allocations.size(), totalPoints, totalSeconds, totalShare, payoutStatus,
                autoPayoutAt, WeekId.endMillis(currentWeek));
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

    /** Расчёт по замороженному плану: доли берутся как есть, ничего не пересчитывается. */
    private List<WeeklyAllocation> computeAllocationsFor(String weekId) {
        try {
            return database.inTransaction(connection -> {
                List<WeeklyPeriodRow> rows = periods.listByWeek(connection, weekId);
                List<WeeklyAllocation> result = new ArrayList<>(rows.size());
                for (WeeklyPeriodRow row : rows) {
                    result.add(new WeeklyAllocation(row.playerId(), row.countedSeconds(),
                            row.activeDays(), row.points(), row.timePoints(), row.dayPoints(),
                            row.share()));
                }
                result.sort(java.util.Comparator.comparingLong(WeeklyAllocation::share).reversed());
                return result;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения снимка недели {}", weekId, e);
            return List.of();
        }
    }

    private WeeklyFundPlanRow readPlanQuiet(String weekId) {
        try {
            return database.inTransaction(connection -> plans.find(connection, weekId).orElse(null));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения плана недели {}", weekId, e);
            return null;
        }
    }

    // ---------------------------------------------------------------- distribution

    /**
     * Выплатить снимок недели по замороженному плану. Возобновляемо: заново пытаются строки не
     * в статусе {@code PAID}; доли берутся из плана. Остаток в казну начисляется только при
     * полном закрытии; при неудаче период остаётся незакрытым и будет повторён.
     */
    private Map<UUID, Long> distribute(String paidWeek, String currentWeek) {
        WeeklyFundPlanRow plan;
        List<WeeklyPeriodRow> rows;
        try {
            PlanAndRows data = database.inTransaction(connection ->
                    new PlanAndRows(plans.find(connection, paidWeek).orElse(null),
                            periods.listByWeek(connection, paidWeek)));
            plan = data.plan();
            rows = data.rows();
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения плана недели {}", paidWeek, e);
            return Map.of();
        }
        if (rows.isEmpty() || plan == null || plan.fundAmount() <= 0) {
            closeAndAdvance(paidWeek, currentWeek);
            return Map.of();
        }

        Map<UUID, Long> payments = new HashMap<>();
        long totalPaid = 0;
        long now = System.currentTimeMillis();
        for (WeeklyPeriodRow row : rows) {
            if (WeeklyPeriodRepository.STATUS_PAID.equals(row.status())) {
                continue;
            }
            UUID playerId = row.playerId();
            long share = row.share();
            if (share <= 0) {
                markPaid(paidWeek, playerId, now, null);
                continue;
            }
            TransactionResult result = accountService.deposit(playerId, share,
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
            long remainder = plan.remainderAmount();
            if (remainder > 0) {
                ok = creditTreasury(paidWeek, remainder);
                if (!ok) {
                    VEconomyMod.LOGGER.error("Недельный фонд: не удалось начислить остаток {} в казну "
                            + "за неделю {}; период оставлен открытым", remainder, paidWeek);
                }
            }
            if (ok) {
                markPlanPaid(paidWeek, now);
                closeAndAdvance(paidWeek, currentWeek);
            }
        }
        VEconomyMod.LOGGER.info("Недельный фонд {} за неделю {}: игроков {}, выплачено {}, закрыта {}",
                plan.fundAmount(), paidWeek, payments.size(), totalPaid, closed);
        return payments;
    }

    /** Закрыть оплаченную неделю и продвинуть распределённую неделю вперёд. */
    private void closeAndAdvance(String paidWeek, String currentWeek) {
        advanceClosed(readDistributedWeekQuiet(), currentWeek);
    }

    private void markPlanPaid(String weekId, long paidAt) {
        try {
            database.inTransaction(connection -> {
                plans.markPaid(connection, weekId, paidAt);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка отметки плана недели {} завершённым", weekId, e);
        }
    }

    /**
     * Зачислить остаток в казну. Сначала статус фиксируется как {@code PENDING}, затем выполняется
     * перевод и при успехе помечается {@code PAID}. Возвращает успех (идемпотентный повтор тоже успех).
     */
    private boolean creditTreasury(String weekId, long amount) {
        long now = System.currentTimeMillis();
        try {
            database.inTransaction(connection -> {
                treasury.upsertPending(connection, database.dialect(), weekId, amount, now);
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Недельный фонд: не удалось зафиксировать остаток недели {}",
                    weekId, e);
            return false;
        }
        TransactionResult result = accountService.deposit(TreasuryService.TREASURY_UUID, amount,
                TransactionContext.of(TransactionType.WEEKLY_REWARD, null,
                        "weekly-fund:remainder:" + weekId, "weekly:treasury:" + weekId));
        boolean success = result.status() == TransactionResult.Status.SUCCESS
                || result.status() == TransactionResult.Status.DUPLICATE_OPERATION;
        if (success) {
            try {
                String txId = result.transactionId();
                long paidNow = System.currentTimeMillis();
                database.inTransaction(connection -> {
                    treasury.markPaid(connection, weekId, txId, paidNow);
                    return null;
                });
            } catch (DatabaseException e) {
                VEconomyMod.LOGGER.error("Недельный фонд: перевод остатка выполнен, но отметка выплаты "
                        + "казне за неделю {} не записана; период останется для повторной проверки",
                        weekId, e);
                return false;
            }
        }
        return success;
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

    // ---------------------------------------------------------------- records

    /** Участник плана: учитываемое время, активные дни и очки (внутреннее представление). */
    private record Eligible(UUID playerId, long countedSeconds, int activeDays,
                            long timePoints, long dayPoints, long totalPoints) {
    }

    /** Агрегат активности игрока за неделю. */
    private static final class Aggregated {
        long totalSeconds;
        int activeDays;
    }

    /** Результат чистого расчёта плана (ничего не записывает). */
    private record PlanComputation(String weekId, long baseFund, long coefficientBps,
                                   long moneySupply, long supplyPerEligible,
                                   long targetSupplyPerEligible, long fundAmount,
                                   List<Eligible> eligible, long[] shares, long totalPoints,
                                   long totalShare, long remainder) {

        boolean isEmpty() {
            return eligible.isEmpty();
        }

        int eligibleCount() {
            return eligible.size();
        }

        WeeklyFundPlanRow toPlanRow(long plannedAt, long payoutDelayHours, boolean autoPayout) {
            long autoPayoutAt = 0;
            if (autoPayout) {
                autoPayoutAt = plannedAt + payoutDelayHours * 3600_000L;
            }
            return new WeeklyFundPlanRow(weekId, fundAmount, baseFund, coefficientBps,
                    moneySupply, supplyPerEligible, targetSupplyPerEligible, eligibleCount(),
                    totalPoints, totalShare, remainder,
                    isEmpty() ? WeeklyFundPlanRow.STATUS_PAID : WeeklyFundPlanRow.STATUS_PLANNED,
                    plannedAt, autoPayout ? autoPayoutAt : null, isEmpty() ? plannedAt : null);
        }
    }

    /** Контейнер чтения плана + строк периода в одной транзакции. */
    private record PlanAndRows(WeeklyFundPlanRow plan, List<WeeklyPeriodRow> rows) {
    }

    /** Публичный снимок расчётной выплаты за неделю. */
    public record WeeklyAllocation(UUID playerId, long countedSeconds, int activeDays, long points,
                                   long timePoints, long dayPoints, long share) {
    }

    /** Прогноз недельного фонда (текущая неделя, живой расчёт). */
    public record WeeklyForecast(String weekId, long fundAmount, long baseFund,
                                 long economyCoefficientBps, long moneySupply, long supplyPerEligible,
                                 int eligiblePlayers, long totalPoints, long totalShare,
                                 List<PlayerForecast> players) {

        /** Прогноз по конкретному игроку: участие, очки и примерная доля. */
        public record PlayerForecast(UUID playerId, long activeSeconds, int activeDays,
                                     long timePoints, long dayPoints, long totalPoints, long share) {
        }

        public List<PlayerForecast> players() {
            return players;
        }
    }

    /** Состояние недельного фонда для административной команды. */
    public record WeeklyStatus(boolean enabled, boolean autoPayout, int payoutDelayHours,
                               long fundAmount, String currentWeek, String distributedWeek,
                               boolean weekDistributed, String targetWeek, int eligiblePlayers,
                               long totalPoints, long totalCountedSeconds, long totalShare,
                               String payoutStatus, Long autoPayoutAt, long weekEndMillis) {
    }
}
