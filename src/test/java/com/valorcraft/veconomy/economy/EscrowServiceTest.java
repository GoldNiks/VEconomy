package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.api.EscrowCredit;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowSnapshot;
import com.valorcraft.veconomy.api.EscrowState;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

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
    void findEscrowReturnsSnapshotWithSettlement() {
        db.escrowService.reserveMoney(owner, 1000, "sale-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        EscrowSnapshot reserved = db.escrowService.findEscrow("sale-1").orElseThrow();
        assertEquals(EscrowState.RESERVED, reserved.state());
        assertEquals(1000, reserved.amount());
        assertEquals(0, reserved.settlement().size());

        UUID treasury = EscrowService.treasuryUuid();
        EscrowResult result = db.escrowService.settleMoney("sale-1",
                List.of(new EscrowCredit(buyer, 950, "seller"), new EscrowCredit(treasury, 50, "commission")),
                ctx(TransactionType.ESCROW_CAPTURE, "расчёт"));
        assertTrue(result.isSuccess());
        EscrowSnapshot settled = db.escrowService.findEscrow("sale-1").orElseThrow();
        assertEquals(EscrowState.CAPTURED, settled.state());
        assertEquals(2, settled.settlement().size());
        assertEquals(1000, settled.settlement().stream().mapToLong(EscrowCredit::amount).sum());
    }

    @Test
    void escrowStatePersisted() {
        db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        db.database.inTransaction(connection ->
                db.escrow.find(connection, "lot-1")).ifPresent(row ->
                assertEquals(EscrowState.RESERVED, row.state()));
    }
}