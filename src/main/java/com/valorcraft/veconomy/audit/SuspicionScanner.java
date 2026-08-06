package com.valorcraft.veconomy.audit;

import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.config.AuditConfig;
import com.valorcraft.veconomy.economy.TreasuryService;
import com.valorcraft.veconomy.persistence.AccountRepository;
import com.valorcraft.veconomy.persistence.AccountRow;
import com.valorcraft.veconomy.persistence.DatabaseException;
import com.valorcraft.veconomy.persistence.DatabaseManager;
import com.valorcraft.veconomy.persistence.TransactionRepository;
import com.valorcraft.veconomy.persistence.TransactionRow;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Эвристики подозрительной активности по журналу переводов. Каждый сработавший
 * сигнал пишется в {@code audit_events} с северити SUSPICIOUS и дедуплицируется
 * по (тип, игрок, окно): повторный {@code scan} в том же окне не плодит события.
 *
 * <ul>
 *   <li>SIGNAL_TRANSFER_SPAM — один игрок совершил много переводов в окне;</li>
 *   <li>SIGNAL_ROUNDTRIP — пара игроков гоняет переводы в обе стороны;</li>
 *   <li>SIGNAL_OVERSIZED — перевод выше порога {@code oversizedTransferAmount};</li>
 *   <li>SIGNAL_NEW_ACCOUNT — перевод с/на свежесозданный аккаунт крупной суммы.</li>
 * </ul>
 */
public final class SuspicionScanner {

    private final DatabaseManager database;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final AuditRepository audit;

    public SuspicionScanner(DatabaseManager database, AccountRepository accounts,
                            TransactionRepository transactions, AuditRepository audit) {
        this.database = database;
        this.accounts = accounts;
        this.transactions = transactions;
        this.audit = audit;
    }

    /** Просканировать всех игроков. Возвращает число записанных сигналов. */
    public ScanSummary scanAll() {
        return scan(null);
    }

    /** Просканировать одного игрока (фильтр по всем эвристикам). */
    public ScanSummary scanPlayer(UUID playerId) {
        return scan(playerId);
    }

    private ScanSummary scan(UUID onlyPlayer) {
        AuditConfig.Settings cfg = AuditConfig.settings();
        if (!cfg.enabled()) {
            VEconomyMod.LOGGER.info("Сканирование сигналов отключено конфигом аудита");
            return new ScanSummary(0, 0, 0, 0);
        }
        long windowMillis = cfg.windowMinutes() * 60_000L;
        long windowStart = System.currentTimeMillis() - windowMillis;
        int spam = 0;
        int roundTrips = 0;
        int oversized = 0;
        int newAccount = 0;
        try {
            List<TransactionRow> transfers = database.inTransaction(connection ->
                    transactions.transfersSince(connection, windowStart));
            List<AccountRow> allAccounts = database.inTransaction(connection -> accounts.all(connection));
            spam = scanSpam(transfers, allAccounts, cfg, windowStart, onlyPlayer);
            roundTrips = scanRoundTrips(transfers, cfg, windowStart, onlyPlayer);
            oversized = scanOversized(transfers, cfg, windowStart, onlyPlayer);
            newAccount = scanNewAccountTransfers(transfers, allAccounts, cfg, windowStart, onlyPlayer);
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка сканирования сигналов подозрительной активности", e);
        }
        return new ScanSummary(spam, roundTrips, oversized, newAccount);
    }

    // ------------------------------------------------------------ heuristics

    /** Много переводов одного игрока в окне (участник с любой стороны). */
    private int scanSpam(List<TransactionRow> transfers, List<AccountRow> allAccounts,
                         AuditConfig.Settings cfg, long windowStart, UUID onlyPlayer) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() != null) {
                counts.merge(row.sourceUuid(), 1, Integer::sum);
            }
            if (row.targetUuid() != null) {
                counts.merge(row.targetUuid(), 1, Integer::sum);
            }
        }
        Set<UUID> players = onlyPlayer == null ? counts.keySet() : Set.of(onlyPlayer);
        int written = 0;
        for (UUID player : players) {
            int count = counts.getOrDefault(player, 0);
            if (count < cfg.transferSpamCount()) {
                continue;
            }
            if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_TRANSFER_SPAM, player, null,
                    "transfers=" + count + ";windowMinutes=" + cfg.windowMinutes())) {
                written++;
            }
        }
        return written;
    }

    /** Пара гоняет переводы в обе стороны: не меньше {@code roundTripExchanges} обменов. */
    private int scanRoundTrips(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                               long windowStart, UUID onlyPlayer) {
        Map<String, long[]> pairs = new HashMap<>(); // key "a|b" (a<b) -> [a->b, b->a]
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            UUID a = row.sourceUuid();
            UUID b = row.targetUuid();
            String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
            long[] counts = pairs.computeIfAbsent(key, k -> new long[2]);
            if (a.compareTo(b) < 0) {
                counts[0]++;
            } else {
                counts[1]++;
            }
        }
        int written = 0;
        for (Map.Entry<String, long[]> entry : pairs.entrySet()) {
            long[] counts = entry.getValue();
            long exchanges = counts[0] + counts[1];
            if (counts[0] == 0 || counts[1] == 0 || exchanges < cfg.roundTripExchanges()) {
                continue;
            }
            String[] parts = entry.getKey().split("\\|");
            UUID a = UUID.fromString(parts[0]);
            UUID b = UUID.fromString(parts[1]);
            if (onlyPlayer != null && !a.equals(onlyPlayer) && !b.equals(onlyPlayer)) {
                continue;
            }
            if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_ROUNDTRIP, a, null,
                    "pair=" + b + ";exchanges=" + exchanges)) {
                written++;
            }
        }
        return written;
    }

    /** Переводы выше порога; событие пишется получателю (целевая сторона). */
    private int scanOversized(List<TransactionRow> transfers,
                              AuditConfig.Settings cfg, long windowStart, UUID onlyPlayer) {
        Map<UUID, Long> largest = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.amountMinor() < cfg.oversizedTransferAmount() || row.targetUuid() == null) {
                continue;
            }
            largest.merge(row.targetUuid(), row.amountMinor(), Math::max);
            counts.merge(row.targetUuid(), 1, Integer::sum);
        }
        Set<UUID> players = onlyPlayer == null ? largest.keySet() : Set.of(onlyPlayer);
        int written = 0;
        for (UUID player : players) {
            if (!counts.containsKey(player)) {
                continue;
            }
            if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_OVERSIZED, player,
                    largest.get(player),
                    "transfers=" + counts.get(player) + ";amount=" + largest.get(player))) {
                written++;
            }
        }
        return written;
    }

    /** Крупные переводы свежесозданных аккаунтов. */
    private int scanNewAccountTransfers(List<TransactionRow> transfers, List<AccountRow> allAccounts,
                                        AuditConfig.Settings cfg, long windowStart, UUID onlyPlayer) {
        long ageMillis = cfg.newAccountDays() * 86_400_000L;
        long now = System.currentTimeMillis();
        Set<UUID> fresh = new HashSet<>();
        for (AccountRow account : allAccounts) {
            if (account.playerId().equals(TreasuryService.TREASURY_UUID)) {
                continue;
            }
            if (now - account.createdAt() <= ageMillis) {
                fresh.add(account.playerId());
            }
        }
        Map<UUID, Long> totals = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.amountMinor() < cfg.newAccountTransferAmount()) {
                continue;
            }
            UUID freshSide = null;
            if (fresh.contains(row.sourceUuid())) {
                freshSide = row.sourceUuid();
            } else if (fresh.contains(row.targetUuid())) {
                freshSide = row.targetUuid();
            }
            if (freshSide == null) {
                continue;
            }
            totals.merge(freshSide, row.amountMinor(), Long::sum);
            counts.merge(freshSide, 1, Integer::sum);
        }
        Set<UUID> players = onlyPlayer == null ? counts.keySet() : Set.of(onlyPlayer);
        int written = 0;
        for (UUID player : players) {
            if (!counts.containsKey(player)) {
                continue;
            }
            if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_NEW_ACCOUNT, player,
                    totals.get(player),
                    "transfers=" + counts.get(player) + ";total=" + totals.get(player)
                            + ";accountAgeDays=" + cfg.newAccountDays())) {
                written++;
            }
        }
        return written;
    }

    /** Записать сигнал, если в окне ещё нет такого же для игрока. */
    private boolean writeIfAbsent(AuditConfig.Settings cfg, long windowStart, String type,
                                  UUID playerId, Long amount, String details) {
        try {
            return database.inTransaction(connection -> {
                if (audit.existsTypeForPlayerSince(connection, type, playerId, windowStart)) {
                    return false;
                }
                audit.insert(connection, new AuditEventRow(0, type, AuditSeverity.SUSPICIOUS,
                        playerId, null, amount, details, System.currentTimeMillis()));
                return true;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка записи аудит-сигнала {} для {}", type, playerId, e);
            return false;
        }
    }

    /** Итог сканирования: сколько сигналов каждого типа записано. */
    public record ScanSummary(int spamSignals, int roundTripSignals, int oversizedSignals,
                              int newAccountSignals) {

        public int total() {
            return spamSignals + roundTripSignals + oversizedSignals + newAccountSignals;
        }
    }
}
