package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {

    private TestDb db;

    @BeforeEach
    void setUp() {
        db = TestDb.create();
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static TransactionContext ctx(TransactionType type, String reason) {
        return TransactionContext.of(type, null, reason);
    }

    @Test
    void depositCreditsBalanceAndWritesLedger() {
        UUID player = UUID.randomUUID();
        TransactionResult result = db.accountService.deposit(player, 500, ctx(TransactionType.ADMIN_DEPOSIT, "тест"));
        assertTrue(result.isSuccess());
        assertEquals(500, db.accountService.getBalance(player));
        assertEquals(1, db.ledger.countAll());
    }

    @Test
    void withdrawDebitsBalance() {
        UUID player = UUID.randomUUID();
        db.accountService.deposit(player, 500, ctx(TransactionType.ADMIN_DEPOSIT, "тест"));
        TransactionResult result = db.accountService.withdraw(player, 200, ctx(TransactionType.ADMIN_WITHDRAW, "тест"));
        assertTrue(result.isSuccess());
        assertEquals(300, db.accountService.getBalance(player));
    }

    @Test
    void insufficientFunds() {
        UUID player = UUID.randomUUID();
        db.accountService.deposit(player, 100, ctx(TransactionType.ADMIN_DEPOSIT, "тест"));
        TransactionResult result = db.accountService.withdraw(player, 200, ctx(TransactionType.ADMIN_WITHDRAW, "тест"));
        assertEquals(TransactionResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(100, db.accountService.getBalance(player));
    }

    @Test
    void negativeAmountRejected() {
        UUID player = UUID.randomUUID();
        assertEquals(TransactionResult.Status.INVALID_AMOUNT,
                db.accountService.deposit(player, -5, ctx(TransactionType.ADMIN_DEPOSIT, "тест")).status());
        assertEquals(TransactionResult.Status.INVALID_AMOUNT,
                db.accountService.deposit(player, 0, ctx(TransactionType.ADMIN_DEPOSIT, "тест")).status());
    }

    @Test
    void overflowDetected() {
        EconomySettings unlimited = new EconomySettings("coin", "coins", "coins", "", 0,
                Long.MAX_VALUE, true, true, 1, Long.MAX_VALUE, 2, "test.db", 5000, true);
        try (TestDb unlimitedDb = TestDb.create(unlimited)) {
            UUID player = UUID.randomUUID();
            assertTrue(unlimitedDb.accountService
                    .deposit(player, Long.MAX_VALUE, ctx(TransactionType.ADMIN_DEPOSIT, "тест")).isSuccess());
            TransactionResult result = unlimitedDb.accountService
                    .deposit(player, 1, ctx(TransactionType.ADMIN_DEPOSIT, "тест"));
            assertEquals(TransactionResult.Status.OVERFLOW, result.status());
        }
    }

    @Test
    void frozenAccountRejectsOperations() {
        UUID player = UUID.randomUUID();
        db.accountService.createOrTouch(player, "Player");
        assertTrue(db.accountService.freeze(player, "модерация").isSuccess());
        assertEquals(TransactionResult.Status.ACCOUNT_DISABLED,
                db.accountService.deposit(player, 100, ctx(TransactionType.ADMIN_DEPOSIT, "тест")).status());
        assertEquals(TransactionResult.Status.ACCOUNT_DISABLED,
                db.accountService.withdraw(player, 100, ctx(TransactionType.ADMIN_WITHDRAW, "тест")).status());
        assertTrue(db.accountService.unfreeze(player, "готово").isSuccess());
        assertTrue(db.accountService.deposit(player, 100, ctx(TransactionType.ADMIN_DEPOSIT, "тест")).isSuccess());
    }

    @Test
    void duplicateIdempotencyKeyDoesNotDoubleCredit() {
        UUID player = UUID.randomUUID();
        TransactionContext first = TransactionContext.of(TransactionType.MILESTONE_REWARD, null, "этап", "milestone:welcome");
        TransactionResult one = db.accountService.deposit(player, 200, first);
        assertTrue(one.isSuccess());
        TransactionResult two = db.accountService.deposit(player, 200, first);
        assertEquals(TransactionResult.Status.DUPLICATE_OPERATION, two.status());
        assertEquals(200, db.accountService.getBalance(player));
        assertEquals(1, db.ledger.countAll());
    }

    @Test
    void setBalanceRecordsPreviousAndNew() {
        UUID player = UUID.randomUUID();
        db.accountService.deposit(player, 1000, ctx(TransactionType.ADMIN_DEPOSIT, "тест"));
        TransactionResult result = db.accountService.setBalance(player, 250,
                ctx(TransactionType.ADMIN_SET_ADJUSTMENT, "ручная установка"));
        assertTrue(result.isSuccess());
        assertEquals(250, db.accountService.getBalance(player));
        db.ledger.find(result.transactionId()).ifPresent(row -> {
            assertEquals("1000", row.metadata().get("previousBalance"));
            assertEquals("250", row.metadata().get("newBalance"));
        });
    }

    @Test
    void accountSnapshotReturned() {
        UUID player = UUID.randomUUID();
        db.accountService.createOrTouch(player, "Bob");
        BalanceSnapshot snapshot = db.accountService.getAccount(player).orElseThrow();
        assertEquals(player, snapshot.playerId());
        assertEquals("Bob", snapshot.lastKnownName());
    }
}
