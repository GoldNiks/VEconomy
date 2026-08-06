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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 *   <li>SIGNAL_NEW_ACCOUNT — перевод с/на свежесозданный аккаунт крупной суммы;</li>
 *   <li>SIGNAL_RAPID_FORWARDING — крупный перевод тут же пересылается дальше;</li>
 *   <li>SIGNAL_TRANSFER_LOOP — деньги идут по циклу из 3+ участников;</li>
 *   <li>SIGNAL_HIGH_PAIR_FREQUENCY — очень частая активность одной пары;</li>
 *   <li>SIGNAL_NEW_ACCOUNT_CONCENTRATION — новый аккаунт принимает переводы от многих;</li>
 *   <li>SIGNAL_REPEATED_SHARED_DESTINATION — один игрок многократно шлёт одному получателю.</li>
 * </ul>
 */
public final class SuspicionScanner {

    /** Предел глубины поиска циклов (3-узловые и дольше). */
    private static final int MAX_LOOP_DEPTH = 6;

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
            return ScanSummary.zero();
        }
        long windowMillis = cfg.windowMinutes() * 60_000L;
        long windowStart = System.currentTimeMillis() - windowMillis;
        try {
            List<TransactionRow> transfers = database.inTransaction(connection ->
                    transactions.transfersSince(connection, windowStart));
            List<AccountRow> allAccounts = database.inTransaction(connection -> accounts.all(connection));
            int spam = scanSpam(transfers, cfg, windowStart, onlyPlayer);
            int roundTrips = scanRoundTrips(transfers, cfg, windowStart, onlyPlayer);
            int oversized = scanOversized(transfers, cfg, windowStart, onlyPlayer);
            int newAccount = scanNewAccountTransfers(transfers, allAccounts, cfg, windowStart, onlyPlayer);
            int rapidForwarding = scanRapidForwarding(transfers, cfg, windowStart, onlyPlayer);
            int loops = scanTransferLoops(transfers, cfg, windowStart, onlyPlayer);
            int highPair = scanHighPairFrequency(transfers, cfg, windowStart, onlyPlayer);
            int concentration = scanNewAccountConcentration(transfers, allAccounts, cfg, windowStart, onlyPlayer);
            int sharedDestination = scanRepeatedSharedDestination(transfers, cfg, windowStart, onlyPlayer);
            return new ScanSummary(spam, roundTrips, oversized, newAccount,
                    rapidForwarding, loops, highPair, concentration, sharedDestination);
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка сканирования сигналов подозрительной активности", e);
            return ScanSummary.zero();
        }
    }

    // ------------------------------------------------------------ heuristics

    /** Много переводов одного игрока в окне (участник с любой стороны). */
    private int scanSpam(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                         long windowStart, UUID onlyPlayer) {
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
            String key = pairKey(a, b);
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
            UUID a = UUID.fromString(entry.getKey().split("\\|")[0]);
            UUID b = UUID.fromString(entry.getKey().split("\\|")[1]);
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
        Set<UUID> fresh = freshAccounts(allAccounts, ageMillis, now);
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

    /** Крупный перевод пересылается дальше в течение короткого окна (промежуточный узел). */
    private int scanRapidForwarding(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                    long windowStart, UUID onlyPlayer) {
        long forwardMillis = cfg.rapidForwardWindowMinutes() * 60_000L;
        Map<UUID, List<TransactionRow>> bySource = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() != null && row.createdAt() >= windowStart) {
                bySource.computeIfAbsent(row.sourceUuid(), k -> new ArrayList<>()).add(row);
            }
        }
        Set<UUID> targets = onlyPlayer == null ? bySource.keySet() : Set.of(onlyPlayer);
        int written = 0;
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null
                    || row.amountMinor() < cfg.rapidForwardAmount()
                    || row.targetUuid().equals(TreasuryService.TREASURY_UUID)) {
                continue;
            }
            UUID forwarder = row.targetUuid();
            if (onlyPlayer != null && !forwarder.equals(onlyPlayer)) {
                continue;
            }
            List<TransactionRow> outgoing = bySource.getOrDefault(forwarder, List.of());
            long limit = row.createdAt() + forwardMillis;
            for (TransactionRow next : outgoing) {
                // Сравнение по идентичности: у тестовых/быстрых записей created_at может
                // совпадать до миллисекунды, а строка сама себе «пересылкой» не является.
                if (next == row || next.createdAt() < row.createdAt()
                        || next.createdAt() > limit
                        || next.amountMinor() < cfg.rapidForwardAmount()
                        || next.targetUuid() == null) {
                    continue;
                }
                if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_RAPID_FORWARDING,
                        forwarder, row.amountMinor(),
                        "amount=" + row.amountMinor() + ";windowMinutes="
                                + cfg.rapidForwardWindowMinutes() + ";to=" + next.targetUuid())) {
                    written++;
                }
                break;
            }
        }
        return written;
    }

    /** Цикл переводов из 3+ участников (A→B→C→A и длиннее, до {@value MAX_LOOP_DEPTH}). */
    private int scanTransferLoops(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                  long windowStart, UUID onlyPlayer) {
        Map<UUID, Set<UUID>> adjacency = new HashMap<>();
        Set<UUID> nodes = new HashSet<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            adjacency.computeIfAbsent(row.sourceUuid(), k -> new HashSet<>()).add(row.targetUuid());
            nodes.add(row.sourceUuid());
            nodes.add(row.targetUuid());
        }
        if (nodes.size() < cfg.transferLoopLength()) {
            return 0;
        }
        Set<UUID> inCycle = new HashSet<>();
        for (UUID start : nodes) {
            if (onlyPlayer != null && !start.equals(onlyPlayer)) {
                continue;
            }
            findCycles(start, start, new ArrayDeque<>(), new HashSet<>(), adjacency,
                    cfg.transferLoopLength(), inCycle);
        }
        int written = 0;
        for (UUID player : inCycle) {
            if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_TRANSFER_LOOP, player, null,
                    "minLength=" + cfg.transferLoopLength())) {
                written++;
            }
        }
        return written;
    }

    private void findCycles(UUID root, UUID current, Deque<UUID> path, Set<UUID> visited,
                            Map<UUID, Set<UUID>> adjacency, int minLength, Set<UUID> inCycle) {
        if (path.size() >= MAX_LOOP_DEPTH) {
            return;
        }
        Set<UUID> next = adjacency.getOrDefault(current, Set.of());
        for (UUID target : next) {
            if (target.equals(root)) {
                if (path.size() + 1 >= minLength) {
                    inCycle.addAll(path);
                    inCycle.add(root);
                }
                continue;
            }
            if (visited.contains(target)) {
                continue;
            }
            visited.add(target);
            path.addLast(target);
            findCycles(root, target, path, visited, adjacency, minLength, inCycle);
            path.removeLast();
            visited.remove(target);
        }
    }

    /** Очень высокая частота обменов между одной парой. */
    private int scanHighPairFrequency(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                      long windowStart, UUID onlyPlayer) {
        Map<String, Integer> pairs = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            pairs.merge(pairKey(row.sourceUuid(), row.targetUuid()), 1, Integer::sum);
        }
        int written = 0;
        for (Map.Entry<String, Integer> entry : pairs.entrySet()) {
            if (entry.getValue() < cfg.highPairFrequencyExchanges()) {
                continue;
            }
            UUID a = UUID.fromString(entry.getKey().split("\\|")[0]);
            UUID b = UUID.fromString(entry.getKey().split("\\|")[1]);
            if (onlyPlayer != null && !a.equals(onlyPlayer) && !b.equals(onlyPlayer)) {
                continue;
            }
            if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_HIGH_PAIR_FREQUENCY, a, null,
                    "pair=" + b + ";exchanges=" + entry.getValue())) {
                written++;
            }
        }
        return written;
    }

    /** Свежий аккаунт принимает переводы от многих отправителей. */
    private int scanNewAccountConcentration(List<TransactionRow> transfers, List<AccountRow> allAccounts,
                                            AuditConfig.Settings cfg, long windowStart, UUID onlyPlayer) {
        long ageMillis = cfg.newAccountDays() * 86_400_000L;
        long now = System.currentTimeMillis();
        Set<UUID> fresh = freshAccounts(allAccounts, ageMillis, now);
        Map<UUID, Set<UUID>> sourcesByFresh = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null
                    || !fresh.contains(row.targetUuid())
                    || row.sourceUuid().equals(TreasuryService.TREASURY_UUID)) {
                continue;
            }
            sourcesByFresh.computeIfAbsent(row.targetUuid(), k -> new HashSet<>()).add(row.sourceUuid());
        }
        Set<UUID> players = onlyPlayer == null
                ? sourcesByFresh.keySet() : Set.of(onlyPlayer);
        int written = 0;
        for (UUID player : players) {
            int sources = sourcesByFresh.getOrDefault(player, Set.of()).size();
            if (sources < cfg.newAccountConcentrationSources()) {
                continue;
            }
            if (writeIfAbsent(cfg, windowStart, AuditEventType.SIGNAL_NEW_ACCOUNT_CONCENTRATION,
                    player, null,
                    "sources=" + sources + ";accountAgeDays=" + cfg.newAccountDays())) {
                written++;
            }
        }
        return written;
    }

    /** Один игрок многократно переводит одному и тому же получателю. */
    private int scanRepeatedSharedDestination(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                              long windowStart, UUID onlyPlayer) {
        Map<UUID, Map<UUID, Integer>> counts = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            counts.computeIfAbsent(row.sourceUuid(), k -> new HashMap<>())
                    .merge(row.targetUuid(), 1, Integer::sum);
        }
        Set<UUID> players = onlyPlayer == null ? counts.keySet() : Set.of(onlyPlayer);
        int written = 0;
        for (UUID sender : players) {
            for (Map.Entry<UUID, Integer> entry : counts.getOrDefault(sender, Map.of()).entrySet()) {
                if (entry.getValue() < cfg.repeatedDestinationTransfers()) {
                    continue;
                }
                if (writeIfAbsent(cfg, windowStart,
                        AuditEventType.SIGNAL_REPEATED_SHARED_DESTINATION, sender, null,
                        "to=" + entry.getKey() + ";transfers=" + entry.getValue())) {
                    written++;
                }
            }
        }
        return written;
    }

    // ------------------------------------------------------------ helpers

    private static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static Set<UUID> freshAccounts(List<AccountRow> allAccounts, long ageMillis, long now) {
        Set<UUID> fresh = new HashSet<>();
        for (AccountRow account : allAccounts) {
            if (account.playerId().equals(TreasuryService.TREASURY_UUID)) {
                continue;
            }
            if (now - account.createdAt() <= ageMillis) {
                fresh.add(account.playerId());
            }
        }
        return fresh;
    }

    /** Записать сигнал, если в окне ещё нет такого же для игрока. */
    private boolean writeIfAbsent(AuditConfig.Settings cfg, long windowStart, String type,
                                  UUID playerId, Long amount, String details) {
        try {
            return database.inTransaction(connection -> {
                if (audit.existsTypeForPlayerSince(connection, type, playerId, windowStart)) {
                    return false;
                }
                audit.insert(connection, database.dialect(),
                        AuditEventRow.signal(type, playerId, amount, details));
                return true;
            });
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка записи аудит-сигнала {} для {}", type, playerId, e);
            return false;
        }
    }

    /** Итог сканирования: сколько сигналов каждого типа записано. */
    public record ScanSummary(int spamSignals, int roundTripSignals, int oversizedSignals,
                              int newAccountSignals, int rapidForwardingSignals,
                              int transferLoopSignals, int highPairFrequencySignals,
                              int newAccountConcentrationSignals, int repeatedDestinationSignals) {

        public int total() {
            return spamSignals + roundTripSignals + oversizedSignals + newAccountSignals
                    + rapidForwardingSignals + transferLoopSignals + highPairFrequencySignals
                    + newAccountConcentrationSignals + repeatedDestinationSignals;
        }

        public static ScanSummary zero() {
            return new ScanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}