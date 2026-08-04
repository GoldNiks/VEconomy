package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowState;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void doubleCaptureRejected() {
        db.escrowService.reserveMoney(owner, 400, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertTrue(db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "покупка")).isSuccess());
        EscrowResult second = db.escrowService.captureMoney("lot-1", buyer, ctx(TransactionType.ESCROW_CAPTURE, "повтор"));
        assertEquals(EscrowResult.Status.WRONG_STATE, second.status());
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
    void duplicateReferenceRejected() {
        assertTrue(db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот")).isSuccess());
        EscrowResult second = db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        assertEquals(EscrowResult.Status.DUPLICATE, second.status());
    }

    @Test
    void escrowStatePersisted() {
        db.escrowService.reserveMoney(owner, 100, "lot-1", ctx(TransactionType.ESCROW_RESERVE, "лот"));
        db.database.inTransaction(connection ->
                db.escrow.find(connection, "lot-1")).ifPresent(row ->
                assertEquals(EscrowState.RESERVED, row.state()));
    }
}
