package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowState;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.AccountRow;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.EscrowRepository;
import com.valorcraft.veconomy.persistence.EscrowRow;
import com.valorcraft.veconomy.persistence.TransactionRow;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

import static com.valorcraft.veconomy.api.EscrowResult.failed;
import static com.valorcraft.veconomy.api.EscrowResult.success;

/**
 * Эскроу для будущего аукциона. Средства резервируются у владельца (списываются с его
 * баланса), удерживаются системой, а затем либо передаются получателю (capture), либо
 * возвращаются владельцу (release). Все переходы атомарны и записываются в журнал.
 */
public final class EscrowService {

    private final DatabaseManager database;
    private final AccountRepository accounts;
    private final EscrowRepository escrow;
    private final AccountService accountService;
    private final LedgerService ledger;
    private volatile EconomySettings settings;

    public EscrowService(DatabaseManager database, AccountRepository accounts, EscrowRepository escrow,
                         AccountService accountService, LedgerService ledger, EconomySettings settings) {
        this.database = database;
        this.accounts = accounts;
        this.escrow = escrow;
        this.accountService = accountService;
        this.ledger = ledger;
        this.settings = settings;
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
    }

    /** Зарезервировать средства владельца под referenceId. */
    public EscrowResult reserveMoney(UUID ownerId, long amount, String referenceId, TransactionContext context) {
        if (ownerId == null || referenceId == null || referenceId.isBlank()) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        if (amount <= 0) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        try {
            return database.inTransaction(connection -> {
                if (escrow.find(connection, referenceId).isPresent()) {
                    return failed(EscrowResult.Status.DUPLICATE);
                }
                AccountRow owner = accounts.find(connection, ownerId).orElse(null);
                if (owner == null) {
                    return failed(EscrowResult.Status.NOT_FOUND);
                }
                if (owner.status() == AccountStatus.FROZEN) {
                    return failed(EscrowResult.Status.ACCOUNT_DISABLED);
                }
                if (owner.balanceMinor() < amount) {
                    return failed(EscrowResult.Status.INSUFFICIENT_FUNDS);
                }
                long newBalance = owner.balanceMinor() - amount;
                long now = System.currentTimeMillis();
                if (!accounts.updateBalance(connection, ownerId, newBalance, owner.version(), now)) {
                    return failed(EscrowResult.Status.DATABASE_ERROR);
                }
                escrow.insert(connection, new EscrowRow(referenceId, ownerId, amount,
                        EscrowState.RESERVED, now, now, context.metadata()));
                ledger.record(connection, new TransactionRow(
                        null, TransactionType.ESCROW_RESERVE, ownerId, null, amount, now,
                        context.actorId(), context.reason(), context.idempotencyKey(),
                        context.metadata(), newBalance, null));
                return success(amount, referenceId);
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка резервирования {} у {}", amount, ownerId, e);
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    /** Передать зарезервированные средства получателю. */
    public EscrowResult captureMoney(String referenceId, UUID recipientId, TransactionContext context) {
        if (referenceId == null || referenceId.isBlank() || recipientId == null) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        try {
            return database.inTransaction(connection -> {
                EscrowRow reservation = escrow.find(connection, referenceId).orElse(null);
                if (reservation == null) {
                    return failed(EscrowResult.Status.NOT_FOUND);
                }
                if (reservation.state() != EscrowState.RESERVED) {
                    return failed(EscrowResult.Status.WRONG_STATE);
                }
                AccountRow recipient = accountService.createIfMissing(connection, recipientId, null);
                if (recipient.status() == AccountStatus.FROZEN) {
                    return failed(EscrowResult.Status.ACCOUNT_DISABLED);
                }
                long newRecipientBalance;
                try {
                    newRecipientBalance = Math.addExact(recipient.balanceMinor(), reservation.amountMinor());
                } catch (ArithmeticException e) {
                    return failed(EscrowResult.Status.LIMIT_EXCEEDED);
                }
                if (newRecipientBalance > settings.maximumBalance) {
                    return failed(EscrowResult.Status.LIMIT_EXCEEDED);
                }
                long now = System.currentTimeMillis();
                if (!accounts.updateBalance(connection, recipientId, newRecipientBalance, recipient.version(), now)) {
                    return failed(EscrowResult.Status.DATABASE_ERROR);
                }
                escrow.updateState(connection, referenceId, EscrowState.CAPTURED, now);
                ledger.record(connection, new TransactionRow(
                        null, TransactionType.ESCROW_CAPTURE, reservation.ownerUuid(), recipientId,
                        reservation.amountMinor(), now, context.actorId(), context.reason(),
                        context.idempotencyKey(), context.metadata(), null, newRecipientBalance));
                return success(reservation.amountMinor(), referenceId);
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка передачи эскроу {}", referenceId, e);
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    /** Вернуть зарезервированные средства владельцу. */
    public EscrowResult releaseMoney(String referenceId, TransactionContext context) {
        if (referenceId == null || referenceId.isBlank()) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        try {
            return database.inTransaction(connection -> {
                EscrowRow reservation = escrow.find(connection, referenceId).orElse(null);
                if (reservation == null) {
                    return failed(EscrowResult.Status.NOT_FOUND);
                }
                if (reservation.state() != EscrowState.RESERVED) {
                    return failed(EscrowResult.Status.WRONG_STATE);
                }
                AccountRow owner = accounts.find(connection, reservation.ownerUuid()).orElse(null);
                if (owner == null) {
                    return failed(EscrowResult.Status.NOT_FOUND);
                }
                long newBalance;
                try {
                    newBalance = Math.addExact(owner.balanceMinor(), reservation.amountMinor());
                } catch (ArithmeticException e) {
                    return failed(EscrowResult.Status.LIMIT_EXCEEDED);
                }
                if (newBalance > settings.maximumBalance) {
                    return failed(EscrowResult.Status.LIMIT_EXCEEDED);
                }
                long now = System.currentTimeMillis();
                if (!accounts.updateBalance(connection, reservation.ownerUuid(), newBalance, owner.version(), now)) {
                    return failed(EscrowResult.Status.DATABASE_ERROR);
                }
                escrow.updateState(connection, referenceId, EscrowState.RELEASED, now);
                ledger.record(connection, new TransactionRow(
                        null, TransactionType.ESCROW_RELEASE, null, reservation.ownerUuid(),
                        reservation.amountMinor(), now, context.actorId(), context.reason(),
                        context.idempotencyKey(), context.metadata(), null, newBalance));
                return success(reservation.amountMinor(), referenceId);
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка возврата эскроу {}", referenceId, e);
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    /** Сумма всех зарезервированных (не финализированных) средств. */
    public long sumReserved() {
        return database.inTransaction(escrow::sumReserved);
    }
}
