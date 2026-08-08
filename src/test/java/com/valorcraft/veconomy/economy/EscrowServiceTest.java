package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.api.EscrowCredit;
import com.valorcraft.veconomy.api.EscrowLookupResult;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowSnapshot;
import com.valorcraft.veconomy.api.EscrowState;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.EscrowRepository;
import com.valorcraft.veconomy.persistence.TransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EscrowServiceTest {

    private TestDb db;
    private UUID owner;
    private UUID buyer;

    @BeforeEach
    void setUp() {
        db = TestDb.create();
        owner = UUID.randomUUID();
        buyer = UUID.randomUUID();
        db.accountService.deposit(owner, 1000, ctx(TransactionType.ADMIN_DEPOSIT, "старт"));
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private static TransactionContext ctx(TransactionType type, String reason) {
        return TransactionContext.of(type, null, reason);
    }

    @Test
    void reserveRemovesMoneyFromOwner() {
        EscrowResult result = db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(result.isSuccess());
        assertEquals(600, db.accountService.getBalance(owner));
        assertEquals(400, db.escrowService.sumReserved());
    }

    @Test
    void captureTransfersToBuyer() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        EscrowResult result = db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "покупка"));
        assertTrue(result.isSuccess());
        assertEquals(400, db.accountService.getBalance(buyer));
        assertEquals(600, db.accountService.getBalance(owner));
        assertEquals(0, db.escrowService.sumReserved());
    }

    @Test
    void releaseReturnsMoneyToOwner() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        EscrowResult result = db.escrowService.releaseMoney("lot-1", ctx(TransactionType.ESCROW_RELEASE, "отмена"));
        assertTrue(result.isSuccess());
        assertEquals(1000, db.accountService.getBalance(owner));
        assertEquals(0, db.escrowService.sumReserved());
    }

    @Test
    void repeatedCaptureIsIdempotentAndDoesNotDoubleCredit() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "покупка")).isSuccess());
        EscrowResult second = db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "повтор"));
        assertEquals(EscrowResult.Status.ALREADY_SETTLED, second.status());
        assertEquals(400, db.accountService.getBalance(buyer));
    }

    @Test
    void releaseAfterCaptureRejected() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "покупка"));
        assertEquals(EscrowResult.Status.WRONG_STATE,
                db.escrowService.releaseMoney("lot-1", ctx(TransactionType.ESCROW_RELEASE, "отмена")).status());
    }

    @Test
    void reserveInsufficientFunds() {
        EscrowResult result = db.escrowService.reserveMoney(owner, 5000, "lot-2", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertEquals(EscrowResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(1000, db.accountService.getBalance(owner));
    }

    @Test
    void repeatedReserveIsIdempotent() {
        assertTrue(db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот")).isSuccess());
        EscrowResult second = db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertEquals(EscrowResult.Status.ALREADY_RESERVED, second.status());
        assertEquals(900, db.accountService.getBalance(owner));
    }

    @Test
    void conflictingReserveRejected() {
        assertTrue(db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот")).isSuccess());
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.reserveMoney(owner, 200, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот")).status());
        assertEquals(900, db.accountService.getBalance(owner));
    }

    @Test
    void settleSplitsBetweenBuyerAndTreasury() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        UUID treasury = EscrowService.treasuryUuid();
        EscrowResult result = db.escrowService.settleMoney("sale-1",
                List.of(new EscrowCredit(buyer, 950, "seller"),
                        new EscrowCredit(treasury, 50, "commission")),
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт"));
        assertTrue(result.isSuccess());
        assertEquals(950, db.accountService.getBalance(buyer));
        assertEquals(50, db.accountService.getBalance(treasury));
        assertEquals(0, db.escrowService.sumReserved());
    }

    @Test
    void repeatedSettleWithSameDistributionIsIdempotent() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        UUID treasury = EscrowService.treasuryUuid();
        List<EscrowCredit> credits = List.of(
                new EscrowCredit(buyer, 950, "seller"),
                new EscrowCredit(treasury, 50, "commission"));
        assertTrue(db.escrowService.settleMoney("sale-1", credits,
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт")).isSuccess());
        EscrowResult second = db.escrowService.settleMoney("sale-1", credits,
                ctx(TransactionType.ESCROW_CAPTURE, "повтор"));
        assertEquals(EscrowResult.Status.ALREADY_SETTLED, second.status());
        assertEquals(950, db.accountService.getBalance(buyer));
        assertEquals(50, db.accountService.getBalance(treasury));
    }

    @Test
    void settledWithDifferentDistributionConflicts() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        UUID treasury = EscrowService.treasuryUuid();
        assertTrue(db.escrowService.settleMoney("sale-1",
                List.of(new EscrowCredit(buyer, 950, "seller"), new EscrowCredit(treasury, 50, "commission")),
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт")).isSuccess());
        EscrowResult other = db.escrowService.settleMoney("sale-1",
                List.of(new EscrowCredit(buyer, 1000, "seller")),
                ctx(TransactionType.ESCROW_CAPTURE, "другой расчёт"));
        assertEquals(EscrowResult.Status.CONFLICT, other.status());
        assertEquals(950, db.accountService.getBalance(buyer));
    }

    @Test
    void settleCreditsSumMustEqualReservedAmount() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        EscrowResult result = db.escrowService.settleMoney("sale-1",
                List.of(new EscrowCredit(buyer, 900, "seller")),
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт"));
        assertEquals(EscrowResult.Status.INVALID_CREDITS, result.status());
        assertEquals(0, db.accountService.getBalance(buyer));
        assertEquals(1000, db.escrowService.sumReserved());
    }

    @Test
    void rolloverAtomicallySettlesCreditsRefundAndNextEscrow() {
        db.accountService.deposit(owner, 2500, ctx(TransactionType.ADMIN_DEPOSIT, "добавка"));
        UUID treasury = EscrowService.treasuryUuid();
        assertTrue(db.escrowService.reserveMoney(owner, 3500, "buy:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy")).isSuccess());
        List<EscrowCredit> credits = List.of(
                new EscrowCredit(buyer, 1560, "seller"),
                new EscrowCredit(treasury, 40, "commission"),
                new EscrowCredit(owner, 150, "buyer-refund"));

        EscrowResult result = db.escrowService.settleAndRollover(
                "buy:0", credits, "buy:1", 1750,
                ctx(TransactionType.ESCROW_CAPTURE, "partial fill"));

        assertTrue(result.isSuccess());
        assertEquals(1560, db.accountService.getBalance(buyer));
        assertEquals(40, db.accountService.getBalance(treasury));
        assertEquals(150, db.accountService.getBalance(owner),
                "только price-improvement refund проходит через обычный balance");
        EscrowSnapshot old = db.escrowService.findEscrow("buy:0").snapshot();
        EscrowSnapshot next = db.escrowService.findEscrow("buy:1").snapshot();
        assertEquals(EscrowState.CAPTURED, old.state());
        assertEquals(EscrowState.RESERVED, next.state());
        assertEquals(owner, next.ownerId());
        assertEquals(1750, next.amount());
        assertEquals(1750, db.escrowService.sumReserved());
        assertEquals(1, db.ledger.history(owner, 1, 100).stream()
                .filter(row -> row.type() == TransactionType.ESCROW_ROLLOVER).count());
    }

    @Test
    void rolloverWithZeroRemainderCreatesNoNextEscrow() {
        db.escrowService.reserveMoney(owner, 1000, "full:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy"));
        EscrowResult result = db.escrowService.settleAndRollover("full:0",
                List.of(new EscrowCredit(buyer, 1000, "seller")), null, 0,
                ctx(TransactionType.ESCROW_CAPTURE, "full fill"));
        assertTrue(result.isSuccess());
        assertEquals(EscrowState.CAPTURED,
                db.escrowService.findEscrow("full:0").snapshot().state());
        assertEquals(0, db.escrowService.sumReserved());
    }

    @Test
    void rolloverRejectsBrokenInvariant() {
        db.escrowService.reserveMoney(owner, 1000, "bad:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy"));
        EscrowResult result = db.escrowService.settleAndRollover("bad:0",
                List.of(new EscrowCredit(buyer, 500, "seller")), "bad:1", 400,
                ctx(TransactionType.ESCROW_CAPTURE, "bad"));
        assertEquals(EscrowResult.Status.INVALID_CREDITS, result.status());
        assertEquals(EscrowState.RESERVED,
                db.escrowService.findEscrow("bad:0").snapshot().state());
        assertEquals(EscrowLookupResult.Status.NOT_FOUND,
                db.escrowService.findEscrow("bad:1").status());
    }

    @Test
    void rolloverRetryIsIdempotentAndParameterChangesConflict() {
        db.escrowService.reserveMoney(owner, 1000, "retry:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy"));
        List<EscrowCredit> credits = List.of(new EscrowCredit(buyer, 400, "seller"));
        assertTrue(db.escrowService.settleAndRollover("retry:0", credits, "retry:1", 600,
                ctx(TransactionType.ESCROW_CAPTURE, "first")).isSuccess());
        assertEquals(EscrowResult.Status.ALREADY_SETTLED,
                db.escrowService.settleAndRollover("retry:0", credits, "retry:1", 600,
                        ctx(TransactionType.ESCROW_CAPTURE, "same")).status());
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.settleAndRollover("retry:0", credits, "retry:1", 500,
                        ctx(TransactionType.ESCROW_CAPTURE, "other remainder")).status());
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.settleAndRollover("retry:0", credits, "retry:2", 600,
                        ctx(TransactionType.ESCROW_CAPTURE, "other next")).status());
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.settleAndRollover("retry:0",
                        List.of(new EscrowCredit(buyer, 300, "seller")), "retry:1", 700,
                        ctx(TransactionType.ESCROW_CAPTURE, "other credits")).status());
        assertEquals(400, db.accountService.getBalance(buyer));
        assertEquals(600, db.escrowService.sumReserved());
    }

    @Test
    void lateRolloverRetryRemainsIdempotentAfterNextEscrowWasCaptured() {
        db.escrowService.reserveMoney(owner, 1000, "late-captured:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy"));
        List<EscrowCredit> firstCredits = List.of(new EscrowCredit(buyer, 400, "seller"));
        assertTrue(db.escrowService.settleAndRollover("late-captured:0", firstCredits,
                "late-captured:1", 600, ctx(TransactionType.ESCROW_CAPTURE, "first")).isSuccess());
        assertTrue(db.escrowService.settleMoney("late-captured:1",
                List.of(new EscrowCredit(buyer, 600, "seller")),
                ctx(TransactionType.ESCROW_CAPTURE, "next fill")).isSuccess());

        assertEquals(EscrowResult.Status.ALREADY_SETTLED,
                db.escrowService.settleAndRollover("late-captured:0", firstCredits,
                        "late-captured:1", 600,
                        ctx(TransactionType.ESCROW_CAPTURE, "late retry")).status());
        assertEquals(1000, db.accountService.getBalance(buyer));
    }

    @Test
    void lateRolloverRetryRemainsIdempotentAfterNextEscrowWasReleased() {
        db.escrowService.reserveMoney(owner, 1000, "late-released:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy"));
        List<EscrowCredit> credits = List.of(new EscrowCredit(buyer, 400, "seller"));
        assertTrue(db.escrowService.settleAndRollover("late-released:0", credits,
                "late-released:1", 600, ctx(TransactionType.ESCROW_CAPTURE, "first")).isSuccess());
        assertTrue(db.escrowService.releaseMoney("late-released:1",
                ctx(TransactionType.ESCROW_RELEASE, "cancel next")).isSuccess());

        assertEquals(EscrowResult.Status.ALREADY_SETTLED,
                db.escrowService.settleAndRollover("late-released:0", credits,
                        "late-released:1", 600,
                        ctx(TransactionType.ESCROW_CAPTURE, "late retry")).status());
        assertEquals(400, db.accountService.getBalance(buyer));
        assertEquals(600, db.accountService.getBalance(owner));
    }

    @Test
    void rolloverRejectsNextReferenceCollision() {
        db.accountService.deposit(owner, 1000, ctx(TransactionType.ADMIN_DEPOSIT, "добавка"));
        db.escrowService.reserveMoney(owner, 1000, "collision:next",
                ctx(TransactionType.ESCROW_RESERVE, "existing"));
        db.escrowService.reserveMoney(owner, 1000, "collision:old",
                ctx(TransactionType.ESCROW_RESERVE, "old"));
        EscrowResult result = db.escrowService.settleAndRollover("collision:old",
                List.of(new EscrowCredit(buyer, 400, "seller")), "collision:next", 600,
                ctx(TransactionType.ESCROW_CAPTURE, "collision"));
        assertEquals(EscrowResult.Status.CONFLICT, result.status());
        assertEquals(EscrowState.RESERVED,
                db.escrowService.findEscrow("collision:old").snapshot().state());
        assertEquals(0, db.accountService.getBalance(buyer));
    }

    @Test
    void rolloverFaultAfterCreditRollsBackEverything() {
        assertRolloverFaultRollsBack(EscrowService.RolloverStage.AFTER_CREDIT);
    }

    @Test
    void rolloverFaultBeforeNextEscrowRollsBackEverything() {
        assertRolloverFaultRollsBack(EscrowService.RolloverStage.BEFORE_NEXT_ESCROW);
    }

    @Test
    void rolloverFaultAfterNextEscrowRollsBackEverything() {
        assertRolloverFaultRollsBack(EscrowService.RolloverStage.AFTER_NEXT_ESCROW);
    }

    @Test
    void concurrentRolloverOfOneOldEscrowPaysExactlyOnce() throws Exception {
        db.escrowService.reserveMoney(owner, 1000, "race:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy"));
        List<EscrowCredit> credits = List.of(new EscrowCredit(buyer, 400, "seller"));
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger idempotent = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                ready.countDown();
                try {
                    start.await();
                    EscrowResult result = db.escrowService.settleAndRollover(
                            "race:0", credits, "race:1", 600,
                            ctx(TransactionType.ESCROW_CAPTURE, "race"));
                    if (result.status() == EscrowResult.Status.SUCCESS) success.incrementAndGet();
                    if (result.status() == EscrowResult.Status.ALREADY_SETTLED) idempotent.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, success.get());
        assertEquals(threads - 1, idempotent.get());
        assertEquals(400, db.accountService.getBalance(buyer));
        assertEquals(600, db.escrowService.sumReserved());
    }

    @Test
    void findEscrowReturnsSnapshotWithSettlement() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        EscrowLookupResult reservedLookup = db.escrowService.findEscrow("sale-1");
        assertEquals(EscrowLookupResult.Status.FOUND, reservedLookup.status());
        EscrowSnapshot reserved = reservedLookup.snapshot();
        assertEquals(EscrowState.RESERVED, reserved.state());
        assertEquals(1000, reserved.amount());
        assertEquals(0, reserved.settlement().size());

        UUID treasury = EscrowService.treasuryUuid();
        EscrowResult result = db.escrowService.settleMoney("sale-1",
                List.of(new EscrowCredit(buyer, 950, "seller"), new EscrowCredit(treasury, 50, "commission")),
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт"));
        assertTrue(result.isSuccess());
        EscrowLookupResult settledLookup = db.escrowService.findEscrow("sale-1");
        assertEquals(EscrowLookupResult.Status.FOUND, settledLookup.status());
        EscrowSnapshot settled = settledLookup.snapshot();
        assertEquals(EscrowState.CAPTURED, settled.state());
        assertEquals(2, settled.settlement().size());
        assertEquals(1000, settled.settlement().stream().mapToLong(EscrowCredit::amount).sum());
    }

    @Test
    void findEscrowMissingReferenceIsNotFound() {
        assertEquals(EscrowLookupResult.Status.NOT_FOUND, db.escrowService.findEscrow("нет-такой").status());
        assertEquals(EscrowLookupResult.Status.NOT_FOUND, db.escrowService.findEscrow("  ").status());
        assertEquals(EscrowLookupResult.Status.NOT_FOUND, db.escrowService.findEscrow(null).status());
    }

    @Test
    void findEscrowAfterDatabaseFailureIsDatabaseError() {
        assertTrue(db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот")).isSuccess());
        db.close();
        assertEquals(EscrowLookupResult.Status.DATABASE_ERROR, db.escrowService.findEscrow("lot-1").status());
    }

    @Test
    void lookupRecoversAfterTransientDatabaseFailure() throws Exception {
        Path dir = Files.createTempDirectory("veconomy-reopen");
        DatabaseManager manager = new DatabaseManager();
        manager.open(dir.resolve("test.db"), EconomySettings.defaults());
        AccountRepository accounts = new AccountRepository();
        TransactionRepository transactions = new TransactionRepository();
        EscrowRepository escrowRepo = new EscrowRepository();
        LedgerService ledger = new LedgerService(manager, transactions);
        AccountService accountService = new AccountService(manager, accounts, transactions,
                ledger, null, EconomySettings.defaults());
        EscrowService service = new EscrowService(manager, accounts, escrowRepo,
                accountService, ledger, EconomySettings.defaults());
        UUID accountOwner = UUID.randomUUID();
        try {
            accountService.deposit(accountOwner, 1000, ctx(TransactionType.ADMIN_DEPOSIT, "старт"));
            assertTrue(service.reserveMoney(accountOwner, 100, "lot-recover",
                    ctx(TransactionType.ESCROW_RESERVE, "лот")).isSuccess());

            manager.close();
            assertEquals(EscrowLookupResult.Status.DATABASE_ERROR,
                    service.findEscrow("lot-recover").status(),
                    "временная ошибка чтения — явный DATABASE_ERROR, а не «записи нет»");

            manager.open(dir.resolve("test.db"), EconomySettings.defaults());
            EscrowLookupResult recovered = service.findEscrow("lot-recover");
            assertEquals(EscrowLookupResult.Status.FOUND, recovered.status(),
                    "после восстановления соединения чтение снова работает");
            assertEquals(EscrowState.RESERVED, recovered.snapshot().state());
            assertEquals(100, recovered.snapshot().amount());
        } finally {
            manager.close();
        }
    }

    @Test
    void captureToDifferentRecipientAfterCaptureConflicts() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "покупка")).isSuccess());
        UUID other = UUID.randomUUID();
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.captureMoney("lot-1", other, ctx(TransactionType.ESCROW_CAPTURE, "другой")).status());
        assertEquals(400, db.accountService.getBalance(buyer));
        assertEquals(0, db.accountService.getBalance(other));
    }

    @Test
    void captureAfterSplitSettleConflicts() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        UUID treasury = EscrowService.treasuryUuid();
        assertTrue(db.escrowService.settleMoney("sale-1",
                List.of(new EscrowCredit(buyer, 950, "seller"), new EscrowCredit(treasury, 50, "commission")),
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт")).isSuccess());
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.captureMoney("sale-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "после сплита")).status());
        assertEquals(950, db.accountService.getBalance(buyer));
    }

    @Test
    void settleAfterCaptureWithDifferentDistributionConflicts() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(db.escrowService.captureMoney("sale-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "покупка")).isSuccess());
        UUID treasury = EscrowService.treasuryUuid();
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.settleMoney("sale-1",
                        List.of(new EscrowCredit(buyer, 950, "seller"), new EscrowCredit(treasury, 50, "commission")),
                        ctx(TransactionType.ESCROW_CAPTURE, "другой расчёт")).status());
        assertEquals(1000, db.accountService.getBalance(buyer));
        assertEquals(0, db.accountService.getBalance(treasury));
    }

    @Test
    void reserveAfterCaptureIsSettledIdempotent() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "покупка")).isSuccess());
        assertEquals(EscrowResult.Status.ALREADY_SETTLED,
                db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "повтор")).status());
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.reserveMoney(owner, 500, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "другая сумма")).status());
        assertEquals(600, db.accountService.getBalance(owner));
    }

    @Test
    void reserveAfterReleaseIsReleasedIdempotent() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(db.escrowService.releaseMoney("lot-1", ctx(TransactionType.ESCROW_RELEASE, "отмена")).isSuccess());
        assertEquals(EscrowResult.Status.ALREADY_RELEASED,
                db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "повтор")).status());
        assertEquals(EscrowResult.Status.CONFLICT,
                db.escrowService.reserveMoney(owner, 500, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "другая сумма")).status());
        assertEquals(1000, db.accountService.getBalance(owner));
    }

    @Test
    void repeatedReleaseIsIdempotent() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(db.escrowService.releaseMoney("lot-1", ctx(TransactionType.ESCROW_RELEASE, "отмена")).isSuccess());
        EscrowResult second = db.escrowService.releaseMoney("lot-1", ctx(TransactionType.ESCROW_RELEASE, "повтор"));
        assertEquals(EscrowResult.Status.ALREADY_RELEASED, second.status());
        assertEquals(1000, db.accountService.getBalance(owner), "повтор не возвращает деньги повторно");
    }

    @Test
    void settleAfterReleaseRejected() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        db.escrowService.releaseMoney("lot-1", ctx(TransactionType.ESCROW_RELEASE, "отмена"));
        assertEquals(EscrowResult.Status.WRONG_STATE,
                db.escrowService.settleMoney("lot-1", List.of(new EscrowCredit(buyer, 400, "seller")),
                        ctx(TransactionType.ESCROW_CAPTURE, "расчёт")).status());
    }

    @Test
    void settleLegsKeepContextAndMarkCommissionAsFee() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        UUID treasury = EscrowService.treasuryUuid();
        UUID actor = UUID.randomUUID();
        TransactionContext settleCtx = new TransactionContext(TransactionType.ESCROW_CAPTURE, actor,
                "расчёт лота", "settle-1", Map.of("auction", "auction-42"));
        List<EscrowCredit> credits = List.of(
                new EscrowCredit(buyer, 950, "seller"), new EscrowCredit(treasury, 50, "commission"));
        String expectedHash = EscrowService.settlementHash(credits);
        assertTrue(db.escrowService.settleMoney("sale-1", credits, settleCtx).isSuccess());

        var buyerLeg = db.ledger.history(buyer, 1, 100).stream()
                .filter(row -> row.type() == TransactionType.ESCROW_CAPTURE).findFirst().orElseThrow();
        assertEquals(950, buyerLeg.amountMinor());
        assertEquals(actor, buyerLeg.actorUuid());
        assertEquals("расчёт лота", buyerLeg.reason());
        assertEquals("seller", buyerLeg.metadata().get("role"));
        assertEquals("sale-1", buyerLeg.metadata().get("referenceId"));
        assertEquals(expectedHash, buyerLeg.metadata().get("settlementHash"));
        assertEquals("auction-42", buyerLeg.metadata().get("auction"));
        assertTrue(buyerLeg.idempotencyKey().startsWith("sale-1|"), "ключ ноги привязан к расчёту");

        var feeLeg = db.ledger.history(treasury, 1, 100).stream()
                .filter(row -> row.type() == TransactionType.FEE).findFirst().orElseThrow();
        assertEquals(50, feeLeg.amountMinor());
        assertEquals("commission", feeLeg.metadata().get("role"));
        assertEquals("sale-1", feeLeg.metadata().get("referenceId"));
        assertEquals(expectedHash, feeLeg.metadata().get("settlementHash"));
        assertEquals(actor, feeLeg.actorUuid());
        assertEquals("расчёт лота", feeLeg.reason());
        assertTrue(feeLeg.idempotencyKey().startsWith("sale-1|"));
    }

    @Test
    void duplicateLegsGetDistinctStableIdempotencyKeys() {
        db.escrowService.reserveMoney(owner, 100, "dupe-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        UUID treasury = EscrowService.treasuryUuid();
        // Две одинаковые доли одному получателю (recipient+role+amount совпадают) —
        // без канонического порядкового номера их ключи пересеклись бы.
        assertTrue(db.escrowService.settleMoney("dupe-1",
                List.of(new EscrowCredit(treasury, 50, "share"), new EscrowCredit(treasury, 50, "share")),
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт")).isSuccess());
        List<com.valorcraft.veconomy.persistence.TransactionRow> legs = db.ledger.history(treasury, 1, 100).stream()
                .filter(row -> row.type() == TransactionType.ESCROW_CAPTURE || row.type() == TransactionType.FEE)
                .toList();
        assertEquals(2, legs.size());
        String ordinalA = legs.get(0).idempotencyKey().split("\\|")[5];
        String ordinalB = legs.get(1).idempotencyKey().split("\\|")[5];
        assertTrue(!ordinalA.equals(ordinalB),
                "дубли-доли получают разные канонические порядковые номера");
        assertEquals(java.util.Set.of("0", "1"), java.util.Set.of(ordinalA, ordinalB),
                "порядковые номера дублей — 0 и 1 (канонический порядок)");
        assertTrue(!legs.get(0).idempotencyKey().equals(legs.get(1).idempotencyKey()),
                "ключи дублей-долей не пересекаются");
    }

    @Test
    void escrowStatePersisted() {
        db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        db.database.inTransaction(connection ->
                db.escrow.find(connection, "lot-1")).ifPresent(row ->
                assertEquals(EscrowState.RESERVED, row.state()));
    }

    @Test
    void releaseIgnoresMaximumBalanceAndReturnsOwnedFunds() {
        EconomySettings lowLimit = withMaximumBalance(1000);
        try (TestDb small = TestDb.create(lowLimit)) {
            UUID accountOwner = UUID.randomUUID();
            small.accountService.deposit(accountOwner, 800, ctx(TransactionType.ADMIN_DEPOSIT, "старт"));
            assertTrue(small.escrowService.reserveMoney(accountOwner, 300, "lot-limit",
                    ctx(TransactionType.ESCROW_RESERVE, "лот")).isSuccess());
            assertEquals(500, small.accountService.getBalance(accountOwner));
            // Пока средства зарезервированы, начисления заполняют баланс ровно до лимита.
            assertTrue(small.accountService.deposit(accountOwner, 500,
                    ctx(TransactionType.ADMIN_DEPOSIT, "заполнение")).isSuccess());
            assertEquals(1000, small.accountService.getBalance(accountOwner));
            // Возврат ранее принадлежавших владельцу средств НЕ блокируется maximumBalance.
            EscrowResult release = small.escrowService.releaseMoney("lot-limit",
                    ctx(TransactionType.ESCROW_RELEASE, "отмена"));
            assertTrue(release.isSuccess());
            assertEquals(1300, small.accountService.getBalance(accountOwner));
            assertEquals(0, small.escrowService.sumReserved());
        }
    }

    @Test
    void rolloverOwnerRefundIgnoresMaximumBalanceButExternalCreditsDoNot() {
        EconomySettings lowLimit = withMaximumBalance(1000);
        try (TestDb small = TestDb.create(lowLimit)) {
            UUID accountOwner = UUID.randomUUID();
            UUID seller = UUID.randomUUID();
            small.accountService.deposit(accountOwner, 800,
                    ctx(TransactionType.ADMIN_DEPOSIT, "start"));
            assertTrue(small.escrowService.reserveMoney(accountOwner, 300, "refund-limit:0",
                    ctx(TransactionType.ESCROW_RESERVE, "buy")).isSuccess());
            assertTrue(small.accountService.deposit(accountOwner, 500,
                    ctx(TransactionType.ADMIN_DEPOSIT, "fill to limit")).isSuccess());

            EscrowResult returned = small.escrowService.settleAndRollover("refund-limit:0",
                    List.of(new EscrowCredit(seller, 200, "seller"),
                            new EscrowCredit(accountOwner, 100, "buyer-refund")),
                    null, 0, ctx(TransactionType.ESCROW_CAPTURE, "fill"));
            assertTrue(returned.isSuccess());
            assertEquals(1100, small.accountService.getBalance(accountOwner));

            assertTrue(small.escrowService.reserveMoney(accountOwner, 100, "external-limit:0",
                    ctx(TransactionType.ESCROW_RESERVE, "buy")).isSuccess());
            small.accountService.deposit(seller, 800,
                    ctx(TransactionType.ADMIN_DEPOSIT, "seller near limit"));
            EscrowResult blocked = small.escrowService.settleAndRollover("external-limit:0",
                    List.of(new EscrowCredit(seller, 100, "seller")), null, 0,
                    ctx(TransactionType.ESCROW_CAPTURE, "external credit"));
            assertEquals(EscrowResult.Status.LIMIT_EXCEEDED, blocked.status());
        }
    }

    @Test
    void releaseFaultAfterTransitionRollsBackAndKeepsEscrowReserved() throws Exception {
        Path dir = Files.createTempDirectory("veconomy-release-fault");
        DatabaseManager manager = new DatabaseManager();
        manager.open(dir.resolve("test.db"), EconomySettings.defaults());
        FaultyAccountRepository accounts = new FaultyAccountRepository();
        TransactionRepository transactions = new TransactionRepository();
        EscrowRepository escrowRepo = new EscrowRepository();
        LedgerService ledger = new LedgerService(manager, transactions);
        AccountService accountService = new AccountService(manager, accounts, transactions,
                ledger, null, EconomySettings.defaults());
        EscrowService service = new EscrowService(manager, accounts, escrowRepo,
                accountService, ledger, EconomySettings.defaults());
        UUID accountOwner = UUID.randomUUID();
        try {
            accountService.deposit(accountOwner, 1000, ctx(TransactionType.ADMIN_DEPOSIT, "старт"));
            assertTrue(service.reserveMoney(accountOwner, 400, "lot-fault",
                    ctx(TransactionType.ESCROW_RESERVE, "лот")).isSuccess());
            assertEquals(600, accountService.getBalance(accountOwner));

            // Проигранный optimistic-lock во время release: updateBalance возвращает false.
            accounts.failNextBalanceUpdate();
            EscrowResult failed = service.releaseMoney("lot-fault",
                    ctx(TransactionType.ESCROW_RELEASE, "отмена"));
            assertEquals(EscrowResult.Status.DATABASE_ERROR, failed.status());
            // Вся транзакция откатилась: escrow всё ещё RESERVED, деньги владельцу не начислены.
            assertEquals(EscrowState.RESERVED, service.findEscrow("lot-fault").snapshot().state());
            assertEquals(600, accountService.getBalance(accountOwner));
            assertEquals(400, service.sumReserved());

            // Повторный корректный release возвращает деньги ровно один раз.
            assertTrue(service.releaseMoney("lot-fault",
                    ctx(TransactionType.ESCROW_RELEASE, "повтор")).isSuccess());
            assertEquals(1000, accountService.getBalance(accountOwner));
            assertEquals(0, service.sumReserved());
            assertEquals(EscrowState.RELEASED, service.findEscrow("lot-fault").snapshot().state());
        } finally {
            manager.close();
        }
    }

    /** Аккаунт-репозиторий с инъекцией сбоя: следующий {@code updateBalance} возвращает false
     *  (имитация проигранного optimistic-lock). */
    private static final class FaultyAccountRepository extends AccountRepository {
        private final java.util.concurrent.atomic.AtomicInteger failNext = new java.util.concurrent.atomic.AtomicInteger();

        void failNextBalanceUpdate() {
            failNext.incrementAndGet();
        }

        @Override
        public boolean updateBalance(java.sql.Connection connection, UUID playerId, long newBalance,
                                     int expectedVersion, long now) {
            if (failNext.get() > 0) {
                failNext.decrementAndGet();
                return false;
            }
            return super.updateBalance(connection, playerId, newBalance, expectedVersion, now);
        }
    }

    private void assertRolloverFaultRollsBack(EscrowService.RolloverStage faultStage) {
        EscrowService faulty = new EscrowService(db.database, db.accounts, db.escrow,
                db.accountService, db.ledger, db.settings, stage -> stage == faultStage);
        db.escrowService.reserveMoney(owner, 1000, "fault:0",
                ctx(TransactionType.ESCROW_RESERVE, "buy"));
        EscrowResult result = faulty.settleAndRollover("fault:0",
                List.of(new EscrowCredit(buyer, 400, "seller")), "fault:1", 600,
                ctx(TransactionType.ESCROW_CAPTURE, "fault"));
        assertEquals(EscrowResult.Status.DATABASE_ERROR, result.status());
        assertEquals(EscrowState.RESERVED,
                db.escrowService.findEscrow("fault:0").snapshot().state());
        assertEquals(EscrowLookupResult.Status.NOT_FOUND,
                db.escrowService.findEscrow("fault:1").status());
        assertEquals(0, db.accountService.getBalance(buyer));
        assertEquals(1000, db.escrowService.sumReserved());
    }

    private static com.valorcraft.veconomy.config.EconomySettings withMaximumBalance(long maximumBalance) {
        com.valorcraft.veconomy.config.EconomySettings d = EconomySettings.defaults();
        return new EconomySettings(
                d.currencyNameSingular, d.currencyNameFew, d.currencyNameMany, d.currencySymbol,
                d.decimalPlaces, maximumBalance, d.transfersEnabled, d.allowOfflineRecipients,
                d.minimumTransferAmount, d.maximumTransferAmount, d.transferCooldownSeconds,
                d.dbType, d.databaseFile, d.busyTimeoutMillis, d.walEnabled,
                d.mysqlHost, d.mysqlPort, d.mysqlDatabase, d.mysqlUser, d.mysqlPassword, d.mysqlPoolSize,
                d.broadcastAdminChanges);
    }
}
