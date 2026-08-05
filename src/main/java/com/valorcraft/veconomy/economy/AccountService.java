package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.BalanceSnapshot;
import com.valorcraft.veconomy.api.TransactionContext;
import com.valorcraft.veconomy.api.TransactionResult;
import com.valorcraft.veconomy.config.EconomySettings;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.AccountRow;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.TransactionRepository;
import com.valorcraft.veconomy.persistence.TransactionRow;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.valorcraft.veconomy.api.TransactionResult.failed;
import static com.valorcraft.veconomy.api.TransactionResult.success;

/**
 * Сервис личных аккаунтов. Баланс строго привязан к UUID игрока; имя используется
 * только для поиска и отображения. Любое изменение баланса атомарно и записывается
 * в журнал в той же транзакции базы.
 */
public final class AccountService {

    private final DatabaseManager database;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final LedgerService ledger;
    private volatile EconomySettings settings;

    public AccountService(DatabaseManager database, AccountRepository accounts,
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

    // ---------------------------------------------------------------- reads

    public long getBalance(UUID playerId) {
        if (playerId == null) {
            return 0L;
        }
        try {
            return database.inTransaction(connection ->
                    accounts.find(connection, playerId).map(AccountRow::balanceMinor).orElse(0L));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения баланса {}", playerId, e);
            return 0L;
        }
    }

    public Optional<BalanceSnapshot> getAccount(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        try {
            return database.inTransaction(connection ->
                    accounts.find(connection, playerId).map(this::snapshot));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения аккаунта {}", playerId, e);
            return Optional.empty();
        }
    }

    public boolean has(UUID playerId, long amount) {
        return getBalance(playerId) >= amount;
    }

    public boolean hasAccount(UUID playerId) {
        if (playerId == null) {
            return false;
        }
        return database.inTransaction(connection -> accounts.exists(connection, playerId));
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Создать аккаунт при первом входе игрока (или обновить имя). Вызывается на серверном
     * потоке при логине. Возвращает аккаунт (созданный или существующий).
     */
    public BalanceSnapshot createOrTouch(UUID playerId, String name) {
        long now = System.currentTimeMillis();
        return database.inTransaction(connection -> {
            Optional<AccountRow> existing = accounts.find(connection, playerId);
            if (existing.isPresent()) {
                AccountRow row = existing.get();
                if (name != null && !name.equals(row.lastKnownName())) {
                    accounts.updateName(connection, playerId, name, now);
                    row = new AccountRow(playerId, name, row.balanceMinor(), row.status(),
                            row.createdAt(), now, row.version());
                }
                return snapshot(row);
            }
            AccountRow created = new AccountRow(playerId, name, 0L, AccountStatus.ACTIVE, now, now, 0);
            accounts.insert(connection, created);
            return snapshot(created);
        });
    }

    /** Создать аккаунт в рамках открытой транзакции (например, для офлайн-получателя). */
    AccountRow createIfMissing(Connection connection, UUID playerId, String name) {
        return accounts.find(connection, playerId).orElseGet(() -> {
            long now = System.currentTimeMillis();
            AccountRow created = new AccountRow(playerId, name, 0L, AccountStatus.ACTIVE, now, now, 0);
            accounts.insert(connection, created);
            return created;
        });
    }

    public TransactionResult freeze(UUID playerId, String reason) {
        return changeStatus(playerId, AccountStatus.FROZEN, reason);
    }

    public TransactionResult unfreeze(UUID playerId, String reason) {
        return changeStatus(playerId, AccountStatus.ACTIVE, reason);
    }

    private TransactionResult changeStatus(UUID playerId, AccountStatus status, String reason) {
        if (playerId == null) {
            return failed(TransactionResult.Status.INVALID_AMOUNT);
        }
        try {
            return database.inTransaction(connection -> {
                Optional<AccountRow> account = accounts.find(connection, playerId);
                if (account.isEmpty()) {
                    return failed(TransactionResult.Status.ACCOUNT_NOT_FOUND);
                }
                long now = System.currentTimeMillis();
                accounts.setStatus(connection, playerId, status, now);
                VEconomyMod.LOGGER.info("Аккаунт {} {} (причина: {})", playerId, status, reason);
                return success(null, account.get().balanceMinor(), -1L);
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка смены статуса аккаунта {}", playerId, e);
            return failed(TransactionResult.Status.DATABASE_ERROR);
        }
    }

    // ---------------------------------------------------------------- operations

    public TransactionResult deposit(UUID playerId, long amount, TransactionContext context) {
        TransactionResult validation = validateAmount(amount);
        if (!validation.isSuccess()) {
            return validation;
        }
        try {
            return database.inTransaction(connection -> depositIn(connection, playerId, amount, context));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка начисления {} для {}", amount, playerId, e);
            return failed(TransactionResult.Status.DATABASE_ERROR);
        }
    }

    /**
     * Начисление в рамках уже открытой транзакции (для составных операций: деньги +
     * ledger + сопутствующая запись в одном коммите). Не вызывает {@code inTransaction}.
     */
    public TransactionResult depositIn(Connection connection, UUID playerId, long amount, TransactionContext context) {
        TransactionResult duplicate = checkIdempotency(connection, context);
        if (duplicate != null) {
            return duplicate;
        }
        AccountRow account = createIfMissing(connection, playerId, null);
        TransactionResult disabled = checkDisabled(account);
        if (disabled != null) {
            return disabled;
        }
        long newBalance;
        try {
            newBalance = Math.addExact(account.balanceMinor(), amount);
        } catch (ArithmeticException e) {
            return failed(TransactionResult.Status.OVERFLOW);
        }
        if (newBalance > settings.maximumBalance) {
            return failed(TransactionResult.Status.LIMIT_EXCEEDED);
        }
        if (!accounts.updateBalance(connection, playerId, newBalance, account.version(), System.currentTimeMillis())) {
            return failed(TransactionResult.Status.DATABASE_ERROR);
        }
        String txId = ledger.record(connection, new TransactionRow(
                null, context.type(), null, playerId, amount, System.currentTimeMillis(),
                context.actorId(), context.reason(), context.idempotencyKey(),
                context.metadata(), null, newBalance));
        return success(txId, -1L, newBalance);
    }

    public TransactionResult withdraw(UUID playerId, long amount, TransactionContext context) {
        TransactionResult validation = validateAmount(amount);
        if (!validation.isSuccess()) {
            return validation;
        }
        try {
            return database.inTransaction(connection -> {
                TransactionResult duplicate = checkIdempotency(connection, context);
                if (duplicate != null) {
                    return duplicate;
                }
                AccountRow account = accounts.find(connection, playerId)
                        .orElse(null);
                if (account == null) {
                    return failed(TransactionResult.Status.ACCOUNT_NOT_FOUND);
                }
                TransactionResult disabled = checkDisabled(account);
                if (disabled != null) {
                    return disabled;
                }
                if (account.balanceMinor() < amount) {
                    return failed(TransactionResult.Status.INSUFFICIENT_FUNDS);
                }
                long newBalance = account.balanceMinor() - amount;
                if (!accounts.updateBalance(connection, playerId, newBalance, account.version(), System.currentTimeMillis())) {
                    return failed(TransactionResult.Status.DATABASE_ERROR);
                }
                String txId = ledger.record(connection, new TransactionRow(
                        null, context.type(), playerId, null, amount, System.currentTimeMillis(),
                        context.actorId(), context.reason(), context.idempotencyKey(),
                        context.metadata(), newBalance, null));
                return success(txId, newBalance, -1L);
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка списания {} с {}", amount, playerId, e);
            return failed(TransactionResult.Status.DATABASE_ERROR);
        }
    }

    /**
     * Административная корректировка: принудительно установить баланс.
     * Записывается отдельной записью ADMIN_SET_ADJUSTMENT с предыдущим и новым балансом
     * в metadata. Если баланс не изменился — в журнал не пишется (нет денежного изменения).
     */
    public TransactionResult setBalance(UUID playerId, long newBalance, TransactionContext context) {
        if (newBalance < 0) {
            return failed(TransactionResult.Status.INVALID_AMOUNT);
        }
        if (newBalance > settings.maximumBalance) {
            return failed(TransactionResult.Status.LIMIT_EXCEEDED);
        }
        try {
            return database.inTransaction(connection -> {
                AccountRow account = accounts.find(connection, playerId)
                        .orElse(null);
                if (account == null) {
                    return failed(TransactionResult.Status.ACCOUNT_NOT_FOUND);
                }
                TransactionResult disabled = checkDisabled(account);
                if (disabled != null) {
                    return disabled;
                }
                long previous = account.balanceMinor();
                if (previous == newBalance) {
                    return success(null, previous, -1L);
                }
                long delta = Math.abs(newBalance - previous);
                long now = System.currentTimeMillis();
                if (!accounts.updateBalance(connection, playerId, newBalance, account.version(), now)) {
                    return failed(TransactionResult.Status.DATABASE_ERROR);
                }
                Map<String, String> metadata = new java.util.HashMap<>(context.metadata());
                metadata.put("previousBalance", Long.toString(previous));
                metadata.put("newBalance", Long.toString(newBalance));
                String txId = ledger.record(connection, new TransactionRow(
                        null, context.type(), playerId, playerId, delta, now,
                        context.actorId(), context.reason(), context.idempotencyKey(),
                        metadata, newBalance, null));
                return success(txId, newBalance, -1L);
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка установки баланса {} на {}", playerId, newBalance, e);
            return failed(TransactionResult.Status.DATABASE_ERROR);
        }
    }

    // ---------------------------------------------------------------- internals

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

    private static TransactionResult validateAmount(long amount) {
        if (amount <= 0) {
            return failed(TransactionResult.Status.INVALID_AMOUNT);
        }
        return success(null, -1L, -1L);
    }

    private static TransactionResult checkDisabled(AccountRow account) {
        if (account.status() == AccountStatus.FROZEN) {
            return failed(TransactionResult.Status.ACCOUNT_DISABLED);
        }
        return null;
    }

    private BalanceSnapshot snapshot(AccountRow row) {
        return new BalanceSnapshot(row.playerId(), row.lastKnownName(), row.balanceMinor(),
                row.status(), row.createdAt(), row.updatedAt());
    }
}
