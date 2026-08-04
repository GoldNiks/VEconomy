package com.valorcraft.veconomy.economy;

import com.valorcraft.veconomy.api.TransactionType;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.TransactionRepository;
import com.valorcraft.veconomy.persistence.TransactionRow;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Журнал операций (ledger). Любое изменение баланса обязано создавать запись здесь.
 * Записи не редактируются и не удаляются обычными командами; исправление выполняется
 * новой компенсирующей транзакцией.
 */
public final class LedgerService {

    private final DatabaseManager database;
    private final TransactionRepository repository;

    public LedgerService(DatabaseManager database, TransactionRepository repository) {
        this.database = database;
        this.repository = repository;
    }

    /** Записать операцию внутри уже открытой транзакции (вместе с изменением баланса). */
    public String record(java.sql.Connection connection, TransactionRow row) {
        return repository.insert(connection, row);
    }

    /** История операций игрока (новые сверху). */
    public List<TransactionRow> history(UUID playerId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            return List.of();
        }
        try {
            return database.inTransaction(connection ->
                    repository.history(connection, playerId, (page - 1) * pageSize, pageSize));
        } catch (DatabaseException e) {
            throw e;
        }
    }

    /** Запись журнала по идентификатору. */
    public Optional<TransactionRow> find(String transactionId) {
        return database.inTransaction(connection -> repository.findById(connection, transactionId));
    }

    /** Существует ли запись с таким идемпотентным ключом. */
    public Optional<TransactionRow> findByIdempotencyKey(String idempotencyKey) {
        return database.inTransaction(connection ->
                repository.findByIdempotencyKey(connection, idempotencyKey));
    }

    public long countAll() {
        return database.inTransaction(repository::countAll);
    }

    /** Количество операций конкретного игрока (для корректной пагинации истории). */
    public long countForPlayer(UUID playerId) {
        return database.inTransaction(connection -> repository.countForPlayer(connection, playerId));
    }

    public long countByTypeSince(TransactionType type, long sinceMillis) {
        return database.inTransaction(connection -> repository.countByTypeSince(connection, type, sinceMillis));
    }

    public long sumAmountByTypeSince(TransactionType type, long sinceMillis) {
        return database.inTransaction(connection ->
                repository.sumAmountByTypeSince(connection, type, sinceMillis));
    }
}
