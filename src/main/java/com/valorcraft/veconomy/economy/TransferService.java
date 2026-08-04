package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.AccountRow;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.TransactionRepository;
import com.valorcraft.veconomy.persistence.TransactionRow;

import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.valorcraft.veconomy.api.TransactionResult.failed;
import static com.valorcraft.veconomy.api.TransactionResult.success;

/**
 * Переводы между игроками. Перевод атомарен (обе стороны и запись журнала — в одной
 * транзакции), не может выполниться дважды (идемпотентный ключ + кулдаун).
 */
public final class TransferService {

    private final DatabaseManager database;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final LedgerService ledger;
    private final ConcurrentMap<UUID, Long> lastTransferAt = new ConcurrentHashMap<>();
    private volatile EconomySettings settings;

    public TransferService(DatabaseManager database, AccountRepository accounts,
                           TransactionRepository transactions, LedgerService ledger, EconomySettings settings) {
        this.database = database;
        this.accounts = accounts;
        this.transactions = transactions;
        this.ledger = ledger;
        this.settings = settings;
    }

    public void applySettings(EconomySettings settings) {
        this.settings = settings;
    }

    public TransactionResult transfer(UUID senderId, UUID recipientId, long amount, TransactionContext context) {
        if (senderId == null || recipientId == null) {
            return failed(TransactionResult.Status.INVALID_AMOUNT);
        }
        if (senderId.equals(recipientId)) {
            return failed(TransactionResult.Status.SELF_TRANSFER);
        }
        if (!settings.transfersEnabled) {
            return failed(TransactionResult.Status.TRANSFERS_DISABLED);
        }
        if (amount < settings.minimumTransferAmount) {
            return failed(TransactionResult.Status.INVALID_AMOUNT);
        }
        if (amount > settings.maximumTransferAmount) {
            return failed(TransactionResult.Status.LIMIT_EXCEEDED);
        }

        try {
            long now = System.currentTimeMillis();
            TransactionResult result = database.inTransaction(connection -> {
                TransactionResult duplicate = checkIdempotency(connection, context);
                if (duplicate != null) {
                    return duplicate;
                }
                if (!cooldownPassed(senderId)) {
                    return failed(TransactionResult.Status.COOLDOWN_ACTIVE);
                }
                AccountRow sender = accounts.find(connection, senderId).orElse(null);
                if (sender == null) {
                    return failed(TransactionResult.Status.ACCOUNT_NOT_FOUND);
                }
                TransactionResult disabled = checkDisabled(sender);
                if (disabled != null) {
                    return disabled;
                }
                AccountRow recipient = accounts.find(connection, recipientId).orElse(null);
                if (recipient == null) {
                    if (!settings.allowOfflineRecipients) {
                        return failed(TransactionResult.Status.RECIPIENT_NOT_FOUND);
                    }
                    recipient = createIfMissing(connection, recipientId);
                }
                disabled = checkDisabled(recipient);
                if (disabled != null) {
                    return disabled;
                }
                if (sender.balanceMinor() < amount) {
                    return failed(TransactionResult.Status.INSUFFICIENT_FUNDS);
                }
                long newSenderBalance = sender.balanceMinor() - amount;
                long newRecipientBalance;
                try {
                    newRecipientBalance = Math.addExact(recipient.balanceMinor(), amount);
                } catch (ArithmeticException e) {
                    return failed(TransactionResult.Status.OVERFLOW);
                }
                if (newRecipientBalance > settings.maximumBalance) {
                    return failed(TransactionResult.Status.LIMIT_EXCEEDED);
                }
                if (!accounts.updateBalance(connection, senderId, newSenderBalance, sender.version(), now)) {
                    return failed(TransactionResult.Status.DATABASE_ERROR);
                }
                if (!accounts.updateBalance(connection, recipientId, newRecipientBalance, recipient.version(), now)) {
                    return failed(TransactionResult.Status.DATABASE_ERROR);
                }
                String txId = ledger.record(connection, new TransactionRow(
                        null, TransactionType.PLAYER_TRANSFER, senderId, recipientId, amount, now,
                        context.actorId(), context.reason(), context.idempotencyKey(),
                        context.metadata(), newSenderBalance, newRecipientBalance));
                return success(txId, newSenderBalance, newRecipientBalance);
            });
            if (result.isSuccess()) {
                lastTransferAt.put(senderId, now);
            }
            return result;
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка перевода {} -> {}: {}", senderId, recipientId, amount, e);
            return failed(TransactionResult.Status.DATABASE_ERROR);
        }
    }

    private boolean cooldownPassed(UUID senderId) {
        int cooldownSeconds = settings.transferCooldownSeconds;
        if (cooldownSeconds <= 0) {
            return true;
        }
        Long last = lastTransferAt.get(senderId);
        if (last == null) {
            return true;
        }
        return System.currentTimeMillis() - last >= cooldownSeconds * 1000L;
    }

    private TransactionResult checkIdempotency(Connection connection, TransactionContext context) {
        if (context.idempotencyKey() == null || context.idempotencyKey().isBlank()) {
            return null;
        }
        Optional<TransactionRow> existing = transactions.findByIdempotencyKey(connection, context.idempotencyKey());
        if (existing.isPresent()) {
            return new TransactionResult(TransactionResult.Status.DUPLICATE_OPERATION,
                    existing.get().transactionId(), -1L, -1L);
        }
        return null;
    }

    private static TransactionResult checkDisabled(AccountRow account) {
        if (account.status() == AccountStatus.FROZEN) {
            return failed(TransactionResult.Status.ACCOUNT_DISABLED);
        }
        return null;
    }

    private AccountRow createIfMissing(Connection connection, UUID playerId) {
        return accounts.find(connection, playerId).orElseGet(() -> {
            long now = System.currentTimeMillis();
            AccountRow created = new AccountRow(playerId, null, 0L, AccountStatus.ACTIVE, now, now, 0);
            accounts.insert(connection, created);
            return created;
        });
    }
}
