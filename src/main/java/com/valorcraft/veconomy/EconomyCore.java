package com.valorcraft.veconomy;

import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.EconomyApi;
import com.valorcraft.veconomy.api.EscrowApi;
import com.valorcraft.veconomy.api.EscrowCredit;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.activity.ActivityService;
import com.valorcraft.veconomy.activity.DimensionVisitRepository;
import com.valorcraft.veconomy.activity.MilestoneRepository;
import com.valorcraft.veconomy.activity.MilestoneService;
import com.valorcraft.veconomy.activity.PlayerActivityRepository;
import com.valorcraft.veconomy.activity.WeeklyActivityDayRepository;
import com.valorcraft.veconomy.activity.WeeklyFundPlanRepository;
import com.valorcraft.veconomy.activity.WeeklyFundService;
import com.valorcraft.veconomy.activity.WeeklyPeriodRepository;
import com.valorcraft.veconomy.activity.WeeklyPayoutRepository;
import com.valorcraft.veconomy.activity.WeeklyTreasuryRepository;
import com.valorcraft.veconomy.audit.AuditRepository;
import com.valorcraft.veconomy.audit.AuditService;
import com.valorcraft.veconomy.audit.EconomyStatistics;
import com.valorcraft.veconomy.config.AuditConfig;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.config.MilestoneConfig;
import com.valorcraft.veconomy.economy.AccountService;
import com.valorcraft.veconomy.economy.CurrencyFormatter;
import com.valorcraft.veconomy.economy.EscrowService;
import com.valorcraft.veconomy.economy.LedgerService;
import com.valorcraft.veconomy.economy.TransferService;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.EscrowRepository;
import com.valorcraft.veconomy.persistence.TransactionRepository;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Точка сборки (composition root) слоёв экономики. Инициализируется при старте сервера,
 * останавливается при остановке. Никогда не создаётся второй экземпляр.
 */
public final class EconomyCore {

    private static DatabaseManager database;
    private static AccountService accountService;
    private static TransferService transferService;
    private static LedgerService ledgerService;
    private static EscrowService escrowService;
    private static CurrencyFormatter formatter;
    private static EconomyStatistics statistics;
    private static AuditService auditService;
    private static ActivityService activity;
    private static MilestoneService milestones;
    private static WeeklyFundService weeklyFund;
    private static EconomyApi api;
    private static EscrowApi escrowApi;
    private static EconomySettings settings;

    private EconomyCore() {}

    public static synchronized void start(Path databasePath, EconomySettings initialSettings) {
        if (database != null) {
            throw new IllegalStateException("Экономика уже запущена");
        }
        settings = initialSettings;
        AuditConfig.load(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
        MilestoneConfig.load(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(),
                initialSettings.maximumBalance);
        database = new DatabaseManager();
        database.open(databasePath, initialSettings);

        AccountRepository accountRepository = new AccountRepository();
        TransactionRepository transactionRepository = new TransactionRepository();
        EscrowRepository escrowRepository = new EscrowRepository();

        ledgerService = new LedgerService(database, transactionRepository);
        auditService = new AuditService(database, new AuditRepository(),
                accountRepository, transactionRepository);
        accountService = new AccountService(database, accountRepository, transactionRepository,
                ledgerService, auditService, initialSettings);
        transferService = new TransferService(database, accountRepository, transactionRepository,
                ledgerService, initialSettings);
        escrowService = new EscrowService(database, accountRepository, escrowRepository,
                accountService, ledgerService, initialSettings);
        formatter = new CurrencyFormatter(initialSettings);
        statistics = new EconomyStatistics(database, accountRepository, transactionRepository, escrowRepository);

        PlayerActivityRepository activityRepository = new PlayerActivityRepository();
        MilestoneRepository milestoneRepository = new MilestoneRepository();
        DimensionVisitRepository visitRepository = new DimensionVisitRepository();
        WeeklyPayoutRepository payoutRepository = new WeeklyPayoutRepository();
        WeeklyPeriodRepository periodRepository = new WeeklyPeriodRepository();
        WeeklyTreasuryRepository treasuryRepository = new WeeklyTreasuryRepository();
        WeeklyActivityDayRepository dayRepository = new WeeklyActivityDayRepository();
        WeeklyFundPlanRepository planRepository = new WeeklyFundPlanRepository();
        activity = new ActivityService(database, activityRepository, dayRepository, auditService, initialSettings);
        milestones = new MilestoneService(database, milestoneRepository, accountService, activity,
                visitRepository, auditService, initialSettings);
        weeklyFund = new WeeklyFundService(database, activityRepository, dayRepository, periodRepository,
                treasuryRepository, payoutRepository, planRepository, accountRepository, escrowRepository,
                accountService, auditService, initialSettings);

        // Политика удержания: очистка старых событий аудита при старте
        // (периодически повторяется в ActivityHandlers.onServerTick).
        auditService.prune(AuditConfig.settings().retentionDays());

        api = new EconomyApi() {
            @Override
            public long getBalance(UUID playerId) {
                return accountService.getBalance(playerId);
            }

            @Override
            public TransactionResult deposit(UUID playerId, long amount, TransactionContext context) {
                return accountService.deposit(playerId, amount, context);
            }

            @Override
            public TransactionResult withdraw(UUID playerId, long amount, TransactionContext context) {
                return accountService.withdraw(playerId, amount, context);
            }

            @Override
            public TransactionResult transfer(UUID senderId, UUID recipientId, long amount, TransactionContext context) {
                return transferService.transfer(senderId, recipientId, amount, context);
            }

            @Override
            public Optional<BalanceSnapshot> getAccount(UUID playerId) {
                return accountService.getAccount(playerId);
            }

            @Override
            public boolean has(UUID playerId, long amount) {
                return accountService.has(playerId, amount);
            }
        };

        escrowApi = new EscrowApi() {
            @Override
            public EscrowResult reserveMoney(UUID ownerId, long amount, String referenceId, TransactionContext context) {
                return escrowService.reserveMoney(ownerId, amount, referenceId, context);
            }

            @Override
            public EscrowResult settleMoney(String referenceId,
                                            List<EscrowCredit> credits,
                                            TransactionContext context) {
                return escrowService.settleMoney(referenceId, credits, context);
            }

            @Override
            public EscrowResult captureMoney(String referenceId, UUID recipientId, TransactionContext context) {
                return escrowService.captureMoney(referenceId, recipientId, context);
            }

            @Override
            public EscrowResult releaseMoney(String referenceId, TransactionContext context) {
                return escrowService.releaseMoney(referenceId, context);
            }

            @Override
            public Optional<EscrowSnapshot> findEscrow(String referenceId) {
                return escrowService.findEscrow(referenceId);
            }

            @Override
            public UUID treasuryUuid() {
                return EscrowService.treasuryUuid();
            }
        };

        VEconomyMod.LOGGER.info("Экономика запущена, база: {}", databasePath);
    }

    /** Применить новые (перезагруженные) настройки к сервисам. */
    public static synchronized void applySettings(EconomySettings newSettings) {
        settings = newSettings;
        AuditConfig.load(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get());
        MilestoneConfig.load(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get(),
                newSettings.maximumBalance);
        if (accountService != null) {
            accountService.applySettings(newSettings);
        }
        if (transferService != null) {
            transferService.applySettings(newSettings);
        }
        if (escrowService != null) {
            escrowService.applySettings(newSettings);
        }
        if (formatter != null) {
            formatter.applySettings(newSettings);
        }
        if (activity != null) {
            activity.applySettings(newSettings);
        }
        if (milestones != null) {
            milestones.applySettings(newSettings);
        }
        if (weeklyFund != null) {
            weeklyFund.applySettings(newSettings);
        }
        if (auditService != null) {
            auditService.prune(AuditConfig.settings().retentionDays());
        }
    }

    public static synchronized void shutdown() {
        if (activity != null) {
            activity.persistAll();
        }
        if (auditService != null) {
            // 1. Остановить исполнитель сканирования ДО закрытия базы: колбэки после
            //    остановки не доставляются, исполнитель не ловит исключения закрытого пула.
            auditService.shutdown();
            // 2. Одна ОГРАНИЧЕННАЯ попытка дожать очередь повтора перед закрытием:
            //    что успело — записано; что нет — НЕ «обещаем» сохранить после рестарта.
            auditService.flushPending();
            AuditService.AuditHealth health = auditService.health();
            if (health.pendingRetries() > 0) {
                VEconomyMod.LOGGER.error("При остановке экономики {} событий(я) аудита не записано "
                                + "в базу (последняя ошибка: {}) — после рестарта они не "
                                + "восстанавливаются",
                        health.pendingRetries(), health.lastError());
            }
        }
        if (database != null) {
            // БД закрывается ТОЛЬКО после остановки скана и попытки дожать очередь:
            // ни одна незаписанная строка не теряется по вине закрытого соединения.
            database.close();
            database = null;
        }
        VEconomyMod.LOGGER.info("Экономика остановлена");
    }

    public static boolean isStarted() {
        return database != null;
    }

    public static EconomyApi api() {
        return api;
    }

    public static EscrowApi escrow() {
        return escrowApi;
    }

    public static AccountService accounts() {
        return accountService;
    }

    public static TransferService transfers() {
        return transferService;
    }

    public static LedgerService ledger() {
        return ledgerService;
    }

    public static EscrowService escrowService() {
        return escrowService;
    }

    public static CurrencyFormatter formatter() {
        return formatter;
    }

    public static EconomyStatistics statistics() {
        return statistics;
    }

    public static AuditService audit() {
        return auditService;
    }

    public static ActivityService activity() {
        return activity;
    }

    public static MilestoneService milestones() {
        return milestones;
    }

    public static WeeklyFundService weeklyFund() {
        return weeklyFund;
    }

    public static DatabaseManager database() {
        return database;
    }

    public static EconomySettings settings() {
        return settings;
    }
}
