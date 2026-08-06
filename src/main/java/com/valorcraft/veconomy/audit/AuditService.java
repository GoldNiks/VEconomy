package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.TransactionRepository;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * События аудита: административные действия, автоматические начисления и сигналы
 * подозрительной активности. Ledger хранит деньги, эта таблица — факты и эвристики.
 * <p>
 * Сбои записи не теряются молча: при ошибке базы событие попадает в ограниченную
 * очередь повтора и записывается при следующем вызове (или явном {@link #flushPending()}).
 * Идемпотентный ключ исключает дубликаты при повторе.
 */
public final class AuditService {

    /** Максимум несохранённых событий в очереди повтора. */
    public static final int RETRY_QUEUE_LIMIT = 1000;

    private final DatabaseManager database;
    private final AuditRepository audit;
    private final SuspicionScanner scanner;
    private final ArrayDeque<AuditEventRow> retryQueue = new ArrayDeque<>();
    private long failedWrites = 0;
    private String lastError = null;

    public AuditService(DatabaseManager database, AuditRepository audit,
                        AccountRepository accounts, TransactionRepository transactions) {
        this.database = database;
        this.audit = audit;
        this.scanner = new SuspicionScanner(database, accounts, transactions, audit);
    }

    /** Записать событие аудита (отдельной транзакцией). Возвращает результат записи. */
    public AuditWriteResult record(String eventType, AuditSeverity severity, UUID playerId,
                                   UUID actorId, AuditActorType actorType, Long amountMinor, String details) {
        AuditEventRow row = AuditEventRow.newEvent(eventType, severity, playerId, actorId,
                actorType, amountMinor, details);
        int retried = flushPending();
        boolean written = write(row);
        if (!written) {
            enqueue(row);
        }
        return new AuditWriteResult(written, retried);
    }

    public AuditWriteResult record(String eventType, AuditSeverity severity, UUID playerId,
                                   UUID actorId, Long amountMinor, String details) {
        return record(eventType, severity, playerId, actorId, AuditActorType.of(actorId),
                amountMinor, details);
    }

    public AuditWriteResult record(String eventType, AuditSeverity severity, UUID playerId,
                                   UUID actorId, String details) {
        return record(eventType, severity, playerId, actorId, null, details);
    }

    // ------------------------------------------------------------ retry

    /** Повторить запись всех отложенных событий; возвращает число записанных. */
    public synchronized int flushPending() {
        int flushed = 0;
        while (!retryQueue.isEmpty()) {
            AuditEventRow row = retryQueue.peek();
            try {
                database.inTransaction(connection -> audit.insert(connection, database.dialect(), row));
            } catch (DatabaseException e) {
                lastError = e.toString();
                break;
            }
            retryQueue.poll();
            flushed++;
        }
        return flushed;
    }

    private synchronized void enqueue(AuditEventRow row) {
        if (retryQueue.size() >= RETRY_QUEUE_LIMIT) {
            retryQueue.poll();
            failedWrites++;
            VEconomyMod.LOGGER.error("Очередь повтора аудита переполнена: событие {} отброшено",
                    row.eventType());
        }
        retryQueue.add(row);
    }

    private boolean write(AuditEventRow row) {
        try {
            database.inTransaction(connection -> audit.insert(connection, database.dialect(), row));
            return true;
        } catch (DatabaseException e) {
            failedWrites++;
            lastError = e.toString();
            VEconomyMod.LOGGER.error("Ошибка записи аудит-события {}: {}", row.eventType(), e.toString());
            return false;
        }
    }

    /** Состояние записи аудита: счётчики сбоев и очередь повтора (для админ-команды). */
    public synchronized AuditHealth health() {
        return new AuditHealth(failedWrites, retryQueue.size(), lastError);
    }

    /** Итог записи события аудита. */
    public record AuditWriteResult(boolean written, int retriedPending) {
    }

    /** Видимое состояние записи аудита. */
    public record AuditHealth(long failedWrites, int pendingRetries, String lastError) {
    }

    // ------------------------------------------------------------ reads

    /** Последние события (новые сверху). */
    public List<AuditEventRow> recent(int limit) {
        return database.inTransaction(connection -> audit.list(connection, limit));
    }

    /** События конкретного игрока. */
    public List<AuditEventRow> byPlayer(UUID playerId, int limit) {
        return database.inTransaction(connection -> audit.list(connection, playerId, null, limit));
    }

    /** Только сигналы подозрительной активности. */
    public List<AuditEventRow> signals(int limit) {
        return database.inTransaction(connection -> audit.list(connection, null, AuditSeverity.SUSPICIOUS, limit));
    }

    /** Открытые (необработанные) сигналы подозрительной активности. */
    public List<AuditEventRow> openSignals(int limit) {
        return database.inTransaction(connection -> audit.list(connection, null,
                AuditSeverity.SUSPICIOUS, ResolutionStatus.OPEN.name(), limit));
    }

    /** Событие по id. */
    public Optional<AuditEventRow> event(long id) {
        return database.inTransaction(connection -> audit.findById(connection, id));
    }

    /** Перевести событие в обработанное состояние; false, если события нет. */
    public boolean resolve(long id, ResolutionStatus status, String resolvedBy, String note) {
        try {
            return database.inTransaction(connection ->
                    audit.resolve(connection, id, status, resolvedBy, note, System.currentTimeMillis()));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка обработки аудит-события {}", id, e);
            return false;
        }
    }

    /** Количество событий по статусу жизненного цикла. */
    public long countByStatus(ResolutionStatus status) {
        return database.inTransaction(connection -> audit.count(connection, status.name()));
    }

    public long count() {
        return database.inTransaction(audit::count);
    }

    /** Запустить эвристики по всем игрокам. */
    public SuspicionScanner.ScanSummary scanAll() {
        return scanner.scanAll();
    }

    /** Запустить эвристики по одному игроку. */
    public SuspicionScanner.ScanSummary scanPlayer(UUID playerId) {
        return scanner.scanPlayer(playerId);
    }
}