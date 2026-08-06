package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.TransactionRepository;

import java.util.List;
import java.util.UUID;

/**
 * События аудита: административные действия, автоматические начисления и сигналы
 * подозрительной активности. Ledger хранит деньги, эта таблица — факты и эвристики.
 */
public final class AuditService {

    private final DatabaseManager database;
    private final AuditRepository audit;
    private final SuspicionScanner scanner;

    public AuditService(DatabaseManager database, AuditRepository audit,
                        AccountRepository accounts, TransactionRepository transactions) {
        this.database = database;
        this.audit = audit;
        this.scanner = new SuspicionScanner(database, accounts, transactions, audit);
    }

    /** Записать событие аудита (отдельной транзакцией). Ошибки только логируются. */
    public void record(String eventType, AuditSeverity severity, UUID playerId, UUID actorId,
                       Long amountMinor, String details) {
        try {
            database.inTransaction(connection -> {
                audit.insert(connection, new AuditEventRow(0, eventType, severity,
                        playerId, actorId, amountMinor, details, System.currentTimeMillis()));
                return null;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка записи аудит-события {}: {}", eventType, e.toString());
        }
    }

    public void record(String eventType, AuditSeverity severity, UUID playerId, UUID actorId, String details) {
        record(eventType, severity, playerId, actorId, null, details);
    }

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
