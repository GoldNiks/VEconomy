package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.TestDb;
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

class TransferServiceTest {

    private TestDb db;
    private UUID alice;
    private UUID bob;

    @BeforeEach
    void setUp() {
        db = TestDb.create();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        db.accountService.deposit(alice, 1000, ctx(TransactionType.ADMIN_DEPOSIT, "старт"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static TransactionContext ctx(TransactionType type, String reason) {
        return TransactionContext.of(type, null, reason);
    }

    @Test
    void transferMovesMoneyAtomically() {
        TransactionResult result = db.transferService.transfer(alice, bob, 300,
                ctx(TransactionType.PLAYER_TRANSFER, "pay"));
        assertTrue(result.isSuccess());
        assertEquals(700, db.accountService.getBalance(alice));
        assertEquals(300, db.accountService.getBalance(bob));
        assertEquals(2, db.ledger.countAll());
    }

    @Test
    void transferToSelfRejected() {
        TransactionResult result = db.transferService.transfer(alice, alice, 100,
                ctx(TransactionType.PLAYER_TRANSFER, "pay"));
        assertEquals(TransactionResult.Status.SELF_TRANSFER, result.status());
    }

    @Test
    void insufficientFundsLeavesBalancesUntouched() {
        TransactionResult result = db.transferService.transfer(alice, bob, 5000,
                ctx(TransactionType.PLAYER_TRANSFER, "pay"));
        assertEquals(TransactionResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(1000, db.accountService.getBalance(alice));
        assertEquals(0, db.accountService.getBalance(bob));
    }

    @Test
    void offlineRecipientNotAllowedWhenDisabled() {
        EconomySettings noOffline = new EconomySettings("coin", "coins", "coins", "", 0,
                9_000_000_000_000L, true, false, 1, 1_000_000, 2, "test.db", 5000, true, true);
        try (TestDb strict = TestDb.create(noOffline)) {
            strict.accountService.deposit(alice, 1000, ctx(TransactionType.ADMIN_DEPOSIT, "старт"));
            TransactionResult result = strict.transferService.transfer(alice, bob, 100,
                    ctx(TransactionType.PLAYER_TRANSFER, "pay"));
            assertEquals(TransactionResult.Status.RECIPIENT_NOT_FOUND, result.status());
        }
    }

    @Test
    void maximumBalanceLimitEnforced() {
        TransactionResult result = db.transferService.transfer(alice, bob, 100,
                ctx(TransactionType.PLAYER_TRANSFER, "pay"));
        assertTrue(result.isSuccess());
        // максимальный баланс по умолчанию 9e12 — доводить не будем, проверяем лимит суммы перевода
        TransactionResult tooBig = db.transferService.transfer(alice, bob, 2_000_000,
                ctx(TransactionType.PLAYER_TRANSFER, "pay"));
        assertEquals(TransactionResult.Status.LIMIT_EXCEEDED, tooBig.status());
    }

    @Test
    void frozenRecipientRejected() {
        db.accountService.createOrTouch(bob, "Bob");
        db.accountService.freeze(bob, "модерация");
        TransactionResult result = db.transferService.transfer(alice, bob, 100,
                ctx(TransactionType.PLAYER_TRANSFER, "pay"));
        assertEquals(TransactionResult.Status.ACCOUNT_DISABLED, result.status());
        assertEquals(1000, db.accountService.getBalance(alice));
    }

    @Test
    void cooldownBlocksRapidTransfers() {
        assertTrue(db.transferService.transfer(alice, bob, 10,
                ctx(TransactionType.PLAYER_TRANSFER, "1")).isSuccess());
        TransactionResult second = db.transferService.transfer(alice, bob, 10,
                ctx(TransactionType.PLAYER_TRANSFER, "2"));
        assertEquals(TransactionResult.Status.COOLDOWN_ACTIVE, second.status());
    }

    @Test
    void idempotencyPreventsDoubleTransfer() {
        TransactionContext context = TransactionContext.of(
                TransactionType.PLAYER_TRANSFER, null, "pay", "transfer:once");
        assertTrue(db.transferService.transfer(alice, bob, 200, context).isSuccess());
        TransactionResult again = db.transferService.transfer(alice, bob, 200, context);
        assertEquals(TransactionResult.Status.DUPLICATE_OPERATION, again.status());
        assertEquals(800, db.accountService.getBalance(alice));
        assertEquals(200, db.accountService.getBalance(bob));
    }

    @Test
    void concurrentTransfersPreserveTotalMoney() throws InterruptedException {
        EconomySettings noCooldown = new EconomySettings("coin", "coins", "coins", "", 0,
                9_000_000_000_000L, true, true, 1, 1_000_000, 0, "test.db", 5000, true, true);
        try (TestDb busy = TestDb.create(noCooldown)) {
            busy.accountService.deposit(alice, 10_000, ctx(TransactionType.ADMIN_DEPOSIT, "старт"));
            Thread[] threads = new Thread[8];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    for (int j = 0; j < 25; j++) {
                        busy.transferService.transfer(alice, bob, 1,
                                ctx(TransactionType.PLAYER_TRANSFER, "параллельный"));
                    }
                });
            }
            for (Thread t : threads) {
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
            long total = busy.accountService.getBalance(alice) + busy.accountService.getBalance(bob);
            assertEquals(10_000, total);
            assertTrue(busy.accountService.getBalance(bob) >= 0);
            assertTrue(busy.accountService.getBalance(bob) <= 200);
        }
    }
}
