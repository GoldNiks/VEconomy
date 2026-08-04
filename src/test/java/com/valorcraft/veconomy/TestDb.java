package com.valorcraft.veconomy;

import com.valorcraft.veconomy.activity.ActivityService;
import com.valorcraft.veconomy.activity.MilestoneRepository;
import com.valorcraft.veconomy.activity.MilestoneService;
import com.valorcraft.veconomy.activity.PlayerActivityRepository;
import com.valorcraft.veconomy.activity.WeeklyFundService;
import com.valorcraft.veconomy.activity.WeeklyPeriodRepository;
import com.valorcraft.veconomy.activity.WeeklyPayoutRepository;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.economy.AccountService;
import com.valorcraft.veconomy.economy.EscrowService;
import com.valorcraft.veconomy.economy.LedgerService;
import com.valorcraft.veconomy.economy.TransferService;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.EscrowRepository;
import com.valorcraft.veconomy.persistence.TransactionRepository;

import java.nio.file.Files;
import java.nio.file.Path;

/** Тестовое окружение: настоящая SQLite в временном файле и готовые сервисы. */
public final class TestDb implements AutoCloseable {

    public final DatabaseManager database;
    public final AccountRepository accounts;
    public final TransactionRepository transactions;
    public final EscrowRepository escrow;
    public final LedgerService ledger;
    public final AccountService accountService;
    public final TransferService transferService;
    public final EscrowService escrowService;
    public final PlayerActivityRepository activityRepository;
    public final ActivityService activityService;
    public final MilestoneService milestoneService;
    public final WeeklyFundService weeklyFundService;
    public final EconomySettings settings;

    public static TestDb create() {
        return create(EconomySettings.defaults());
    }

    public static TestDb create(EconomySettings settings) {
        try {
            Path dir = Files.createTempDirectory("veconomy-test");
            Path dbFile = dir.resolve("test.db");
            DatabaseManager database = new DatabaseManager();
            database.open(dbFile, settings);

            AccountRepository accounts = new AccountRepository();
            TransactionRepository transactions = new TransactionRepository();
            EscrowRepository escrow = new EscrowRepository();
            LedgerService ledger = new LedgerService(database, transactions);
            AccountService accountService = new AccountService(database, accounts, transactions, ledger, settings);
            TransferService transferService = new TransferService(database, accounts, transactions, ledger, settings);
            EscrowService escrowService = new EscrowService(database, accounts, escrow, accountService, ledger, settings);

            PlayerActivityRepository activityRepository = new PlayerActivityRepository();
            MilestoneRepository milestoneRepository = new MilestoneRepository();
            WeeklyPayoutRepository payoutRepository = new WeeklyPayoutRepository();
            WeeklyPeriodRepository periodRepository = new WeeklyPeriodRepository();
            ActivityService activityService = new ActivityService(database, activityRepository, settings);
            MilestoneService milestoneService = new MilestoneService(database, milestoneRepository,
                    accountService, activityService, settings);
            WeeklyFundService weeklyFundService = new WeeklyFundService(database, activityRepository,
                    periodRepository, payoutRepository, accountService, settings);

            return new TestDb(database, accounts, transactions, escrow, ledger,
                    accountService, transferService, escrowService,
                    activityRepository, activityService, milestoneService, weeklyFundService, settings);
        } catch (Exception e) {
            throw new RuntimeException("Не удалось создать тестовую базу", e);
        }
    }

    private TestDb(DatabaseManager database, AccountRepository accounts, TransactionRepository transactions,
                   EscrowRepository escrow, LedgerService ledger, AccountService accountService,
                   TransferService transferService, EscrowService escrowService,
                   PlayerActivityRepository activityRepository, ActivityService activityService,
                   MilestoneService milestoneService, WeeklyFundService weeklyFundService,
                   EconomySettings settings) {
        this.database = database;
        this.accounts = accounts;
        this.transactions = transactions;
        this.escrow = escrow;
        this.ledger = ledger;
        this.accountService = accountService;
        this.transferService = transferService;
        this.escrowService = escrowService;
        this.activityRepository = activityRepository;
        this.activityService = activityService;
        this.milestoneService = milestoneService;
        this.weeklyFundService = weeklyFundService;
        this.settings = settings;
    }

    @Override
    public void close() {
        database.close();
    }
}
