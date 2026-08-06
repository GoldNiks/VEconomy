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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Эвристики подозрительной активности по журналу переводов. Каждый сработавший
 * сигнал пишется в {@code audit_events} с северити SUSPICIOUS и дедуплицируется
 * стабильным {@code dedupeKey} окна: повторный {@code scan} в том же окне не
 * плодит события (уникальный частичный индекс отклоняет вставку-дубликат).
 * <p>
 * Системные аккаунты (казна и т.п.) исключаются централизованно через
 * {@link #isSystem(UUID)}: ни одна эвристика не считает их ни отправителями,
 * ни получателями, ни «свежими» аккаунтами.
 * <p>
 * {@code scanPlayer} не выгружает весь журнал: переводы запрашиваются SQL-запросом
 * только по этому игроку, а счётчик исходящих переводов считается агрегацией
 * GROUP BY в базе, а не в памяти. Полное сканирование выполняется только явной
 * админ-командой (не по тику); на конкурентный вызов {@code AuditService} отвечает
 * zero-сводкой (single-flight).
 *
 * <ul>
 *   <li>SIGNAL_TRANSFER_SPAM — игрок совершил много исходящих переводов в окне
 *       (только отправитель; «входящий спам» — отдельная эвристика-фан-ин);</li>
 *   <li>SIGNAL_ROUNDTRIP — пара гоняет переводы в обе стороны; событие пишется
 *       КАЖДОМУ участнику с общим incident id в деталях;</li>
 *   <li>SIGNAL_OVERSIZED — исходящий перевод выше порога (субъект — отправитель);</li>
 *   <li>SIGNAL_NEW_ACCOUNT — крупные переводы свежесозданных аккаунтов; проверяются
 *       обе стороны перевода (и отправитель, и получатель могут быть «свежими»);</li>
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

    /**
     * Верхний предел участников персонального графа {@code scanPlayer}: при достижении
     * расширение графа останавливается, сводка помечается «ограничено». Защита от
     * неограниченного числа контрагентов (и от квадратичного числа SQL-запросов графа).
     */
    private static final int MAX_GRAPH_PARTICIPANTS = 500;

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

    /** Просканировать одного игрока (данные и фильтр — только этот игрок). */
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
        long bucket = windowStart / windowMillis;
        // Ошибки БД НЕ превращаются в «нулевой успешный результат»: исключение
        // пробрасывается в AuditService, который доставляет failed-колбэк вместо
        // completed(0 сигналов). Неполный/лимитированный анализ явно помечается
        // флагом limited в сводке.
        List<TransactionRow> transfers;
        List<AccountRow> accountsFor;
        boolean limited = false;
if (onlyPlayer != null) {
            // Персональная выгрузка тоже ограничена ПРЯМО В SQL (LIMIT max+1): история
            // одного игрока не читается целиком, лишние строки для определения «ограничено»
            // в память не попадают. Граф участников по-прежнему ограничен MAX_GRAPH_PARTICIPANTS.
            int limit = scanLimit(cfg.maxTransfersPerScan());
            ScanData data = database.inTransaction(connection -> {
                List<TransactionRow> local = transactions.transfersSinceForPlayerLimited(
                        connection, onlyPlayer, windowStart, limit + 1);
                boolean playerLimited = local.size() > limit;
                if (playerLimited) {
                    local = local.subList(0, limit);
                    VEconomyMod.LOGGER.warn("Персональный скан игрока {} ограничен: анализируются "
                                    + "{} переводов окна (maxTransfersPerScan={})",
                            onlyPlayer, local.size(), cfg.maxTransfersPerScan());
                }
                // Граф участников формируется детерминированно: проверяемый игрок
                // всегда первый, остальные UUID сортируются — ни произвольного
                // обхода HashSet, ни невоспроизводимого порядка между прогонами.
                List<UUID> ordered = orderedParticipants(local, onlyPlayer);
                boolean participantLimited = ordered.size() > MAX_GRAPH_PARTICIPANTS;
                if (participantLimited) {
                    ordered = ordered.subList(0, MAX_GRAPH_PARTICIPANTS);
                    VEconomyMod.LOGGER.warn("Персональный граф игрока {} ограничен: анализируются "
                                    + "{} участников (проверяемый игрок и следующие по UUID будет "
                                    + "не менее {} в анализе)",
                            onlyPlayer, ordered.size(), MAX_GRAPH_PARTICIPANTS);
                }
// Дополнительные рёбра графа загружаются ТОЛЬКО в пределах оставшегося
                // бюджета limit - local.size(): весь итоговый набор никогда не превышает
                // maxTransfersPerScan, а прямые переводы игрока (уже в local) не выгружаются
                // повторно: transfersBetweenLimited исключает focalPlayer в SQL.
                int graphBudget = limit - local.size();
                com.valorcraft.veconomy.persistence.TransactionRepository.LimitedRows limitedRows =
                        graphBudget > 0
                                ? transactions.transfersBetweenLimited(
                                connection, ordered, onlyPlayer, windowStart, graphBudget)
                                : new com.valorcraft.veconomy.persistence.TransactionRepository.LimitedRows(List.of(), false);
                List<TransactionRow> graph = limitedRows.rows();
                // Участники для загрузки аккаунтов — из ИТОГОВОГО набора (локальные +
                // рёбра графа), чтобы fresh/counters попали ко всем взаимодействовавшим.
                Set<UUID> finalParticipants = participantsOf(local);
                finalParticipants.addAll(participantsOf(graph));
                return new ScanData(merge(local, graph), finalParticipants,
                        playerLimited || participantLimited || limitedRows.limited());
            });
            transfers = data.merged();
            accountsFor = database.inTransaction(connection ->
                    accounts.findByIds(connection, data.participants()));
            // Персональный скан делается ограниченным ТОЛЬКО если реально ограничен:
            // число строк, число участников графа или область графа из-за бюджета.
            limited = data.limited();
        } else {
            // Лимит применяется ПРЯМО В SQL (LIMIT max+1): в память из базы читается
            // не больше max+1 переводов, а не вся история окна. Дополнительная строка
            // определяет признак «ограничено»; в анализ передаётся не больше max строк.
            int limit = scanLimit(cfg.maxTransfersPerScan());
            transfers = database.inTransaction(connection ->
                    transactions.transfersSinceLimited(connection, windowStart, limit + 1));
            limited = transfers.size() > limit;
            if (limited) {
                transfers = transfers.subList(0, limit);
                VEconomyMod.LOGGER.warn("Полный скан ограничен: анализируются {} переводов "
                                + "окна (maxTransfersPerScan={})",
                        transfers.size(), cfg.maxTransfersPerScan());
            }
            // Аккаунты подгружаются ТОЛЬКО по участникам отобранных переводов —
            // не выгружается вся таблица accounts.
            Set<UUID> players = participantsOf(transfers);
            accountsFor = database.inTransaction(connection ->
                    accounts.findByIds(connection, players));
        }
        int spam = scanSpam(cfg, windowStart, bucket, onlyPlayer);
        int roundTrips = scanRoundTrips(transfers, cfg, bucket, onlyPlayer);
        int oversized = scanOversized(transfers, cfg, bucket, onlyPlayer);
        int newAccount = scanNewAccountTransfers(transfers, accountsFor, cfg, bucket, onlyPlayer);
        int rapidForwarding = scanRapidForwarding(transfers, cfg, windowStart, bucket, onlyPlayer);
        int loops = scanTransferLoops(transfers, cfg, bucket, onlyPlayer);
        int highPair = scanHighPairFrequency(transfers, cfg, bucket, onlyPlayer);
        int concentration = scanNewAccountConcentration(transfers, accountsFor, cfg, bucket, onlyPlayer);
        int repeatedDestination = scanRepeatedSharedDestination(transfers, cfg, bucket, onlyPlayer);
        return new ScanSummary(spam, roundTrips, oversized, newAccount,
                rapidForwarding, loops, highPair, concentration, repeatedDestination, limited);
    }

/** Множество участников переданного набора переводов. */
    private static Set<UUID> participantsOf(List<TransactionRow> rows) {
        Set<UUID> participants = new HashSet<>();
        for (TransactionRow row : rows) {
            if (row.sourceUuid() != null) {
                participants.add(row.sourceUuid());
            }
            if (row.targetUuid() != null) {
                participants.add(row.targetUuid());
            }
        }
        return participants;
    }

/**
     * Детерминированный порядок участников персонального графа: проверяемый игрок
     * ВСЕГДА первый, остальные UUID — по возрастанию {@code compareTo}. Произвольный
     * обход HashSet больше не влияет на граф (и на число SQL-запросов/рёбер). При
     * превышении {@code MAX_GRAPH_PARTICIPANTS} список усекается в вызывающем коде
     * (не здесь), а сводка помечается ограниченной. Пакетная видимость — для тестов.
     */
    static List<UUID> orderedParticipants(List<TransactionRow> rows, UUID focalPlayer) {
        List<UUID> others = new ArrayList<>();
        for (TransactionRow row : rows) {
            if (row.sourceUuid() != null && !row.sourceUuid().equals(focalPlayer)) {
                others.add(row.sourceUuid());
            }
            if (row.targetUuid() != null && !row.targetUuid().equals(focalPlayer)) {
                others.add(row.targetUuid());
            }
        }
others.sort(null); // естественный порядок UUID (compareTo) — стабильно между прогонами
        List<UUID> ordered = new ArrayList<>(others.size() + 1);
        ordered.add(focalPlayer);
        ordered.addAll(others);
        return ordered;
    }

    /** Результат персонального скана {@link #scanPlayer}: итоговый набор + участники. */
    private record ScanData(List<TransactionRow> merged, Set<UUID> participants, boolean limited) {
    }

    /**
     * Объединить персональные переводы и рёбра ограниченного графа без дубликатов
     * по transactionId и в едином хронологическом порядке {@code (created_at, transaction_id)}
     * — порядок анализа воспроизводим между прогонами одного окна.
     */
    private List<TransactionRow> merge(List<TransactionRow> local, List<TransactionRow> graph) {
        Map<String, TransactionRow> merged = new LinkedHashMap<>();
        for (TransactionRow row : local) {
            merged.put(row.transactionId(), row);
        }
        for (TransactionRow row : graph) {
            merged.putIfAbsent(row.transactionId(), row);
        }
        List<TransactionRow> ordered = new ArrayList<>(merged.values());
        ordered.sort(CHRONOLOGICAL);
        return ordered;
    }

    /**
     * Единый хронологический порядок операций для всех эвристик, где порядок важен:
     * {@code created_at} ASC, затем {@code transaction_id} ASC. Одна и та же пара в
     * цикле и пересылке обрабатывается одинаково — без расхождений «по времени»
     * и «по time+tie-break».
     */
    private static final Comparator<TransactionRow> CHRONOLOGICAL =
            Comparator.comparingLong(TransactionRow::createdAt)
                    .thenComparing(r -> r.transactionId() == null ? "" : r.transactionId());

    /**
     * Строго «позже» по составной паре {@code (created_at, transaction_id)}: следующая
     * операция обязана быть строго больше предыдущей (время больше, либо равно время
     * И больше transactionId). При равных временах порядок определяется transaction_id.
     */
    private static boolean strictlyAfter(TransactionRow candidate, TransactionRow anchor) {
        if (anchor == null) {
            return true;
        }
        int byTime = Long.compare(candidate.createdAt(), anchor.createdAt());
        if (byTime != 0) {
            return byTime > 0;
        }
        String candidateTx = candidate.transactionId();
        String anchorTx = anchor.transactionId();
        if (anchorTx == null) {
            return candidateTx != null;
        }
        return candidateTx != null && candidateTx.compareTo(anchorTx) > 0;
    }

    // ------------------------------------------------------------ heuristics

    /**
     * Много ИСХОДЯЩИХ переводов одного игрока в окне. Счётчик — SQL-агрегация
     * GROUP BY по {@code source_uuid}, а не перебор журнала в памяти. Получатель
     * спамером не считается (входящие переводы — не активность спама).
     */
    private int scanSpam(AuditConfig.Settings cfg, long windowStart, long bucket, UUID onlyPlayer) {
        Map<UUID, Integer> counts = database.inTransaction(connection ->
                transactions.outgoingTransferCountsBySource(connection, windowStart, onlyPlayer));
        int written = 0;
        for (Map.Entry<UUID, Integer> entry : counts.entrySet()) {
            UUID player = entry.getKey();
            if (isSystem(player) || entry.getValue() < cfg.transferSpamCount()) {
                continue;
            }
            if (writeSignal(AuditEventType.SIGNAL_TRANSFER_SPAM, player, null, null,
                    "transfers=" + entry.getValue() + ";windowMinutes=" + cfg.windowMinutes(),
                    dedupeKey("spam", player, bucket))) {
                written++;
            }
        }
        return written;
    }

    /**
     * Пара гоняет переводы в обе стороны: не меньше {@code roundTripExchanges} обменов.
     * Событие пишется каждому участнику (у каждого — своя строка в таблице) с общим
     * incident id в деталях. Пары A–B и A–C независимы: dedupe-ключ включает пару,
     * поэтому событие по одной паре не гасит сигнал по другой.
     */
    private int scanRoundTrips(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                               long bucket, UUID onlyPlayer) {
        Map<String, long[]> pairs = new HashMap<>(); // key "a|b" (a<b) -> [a->b, b->a]
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            UUID a = row.sourceUuid();
            UUID b = row.targetUuid();
            if (isSystem(a) || isSystem(b)) {
                continue;
            }
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
            String incident = "RT|" + entry.getKey() + "|" + bucket;
            for (UUID participant : new UUID[]{a, b}) {
                if (onlyPlayer != null && !participant.equals(onlyPlayer)) {
                    continue;
                }
                UUID partner = participant.equals(a) ? b : a;
                if (writeSignal(AuditEventType.SIGNAL_ROUNDTRIP, participant, partner, null,
                        "pair=" + partner + ";exchanges=" + exchanges + ";incident=" + incident,
                        incidentDedupeKey("roundtrip", participant, bucket, entry.getKey()))) {
                    written++;
                }
            }
        }
        return written;
    }

    /**
     * Исходящие переводы выше порога: событие пишется ОТПРАВИТЕЛЮ по КАЖДОЙ
     * транзакции отдельно (txId в деталях и в dedupe-ключе окна). Агрегированное
     * «максимум по игроку» теряло бы все большие переводы после первого: с пере-сигналом
     * по каждой транзакции видны сумма и получатель каждого оверсайза, а повторный скан
     * того же окна не создаёт дублей.
     */
    private int scanOversized(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                              long bucket, UUID onlyPlayer) {
        int written = 0;
        for (TransactionRow row : transfers) {
            if (row.amountMinor() < cfg.oversizedTransferAmount() || row.sourceUuid() == null) {
                continue;
            }
            UUID sender = row.sourceUuid();
            if (isSystem(sender) || isSystem(row.targetUuid())) {
                continue;
            }
            if (onlyPlayer != null && !sender.equals(onlyPlayer)) {
                continue;
            }
            String txId = row.transactionId() == null ? "-" : row.transactionId();
            if (writeSignal(AuditEventType.SIGNAL_OVERSIZED, sender, row.targetUuid(),
                    row.amountMinor(),
                    "tx=" + txId + ";amount=" + row.amountMinor() + ";to=" + row.targetUuid(),
                    incidentDedupeKey("oversized", sender, txId))) {
                written++;
            }
        }
        return written;
    }

    /**
     * Крупные переводы свежесозданных аккаунтов. Проверяются ОБЕ стороны перевода:
     * свежий отправитель и/или свежий получатель получают сигнал независимо
     * (без else-if), события по каждому учитываются отдельно.
     */
    private int scanNewAccountTransfers(List<TransactionRow> transfers, List<AccountRow> allAccounts,
                                        AuditConfig.Settings cfg, long bucket, UUID onlyPlayer) {
        long ageMillis = cfg.newAccountDays() * 86_400_000L;
        long now = System.currentTimeMillis();
        Set<UUID> fresh = freshAccounts(allAccounts, ageMillis, now);
        Map<UUID, Long> totals = new HashMap<>();
        Map<UUID, Integer> counts = new HashMap<>();
        Map<UUID, UUID> counterparties = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.amountMinor() < cfg.newAccountTransferAmount()) {
                continue;
            }
            UUID source = row.sourceUuid();
            UUID target = row.targetUuid();
            if (source != null && !isSystem(source) && fresh.contains(source)) {
                totals.merge(source, row.amountMinor(), Long::sum);
                counts.merge(source, 1, Integer::sum);
                counterparties.put(source, target);
            }
            if (target != null && !isSystem(target) && fresh.contains(target)) {
                totals.merge(target, row.amountMinor(), Long::sum);
                counts.merge(target, 1, Integer::sum);
                counterparties.put(target, source);
            }
        }
        Set<UUID> players = onlyPlayer == null ? counts.keySet() : Set.of(onlyPlayer);
        int written = 0;
        for (UUID player : players) {
            if (!counts.containsKey(player)) {
                continue;
            }
            if (writeSignal(AuditEventType.SIGNAL_NEW_ACCOUNT, player, counterparties.get(player),
                    totals.get(player),
                    "transfers=" + counts.get(player) + ";total=" + totals.get(player)
                            + ";accountAgeDays=" + cfg.newAccountDays(),
                    dedupeKey("newacct", player, bucket))) {
                written++;
            }
        }
        return written;
    }

    /**
     * Крупный перевод пересылается дальше в течение короткого окна (промежуточный узел).
     * Инцидент — КОНКРЕТНАЯ пара переводов (inTx/outTx): исходящие упорядочены по
     * времени с устойчивым tie-break по txId, первый подходящий и есть пересылка;
     * дедупликация по обоим txId, разные пары не схлопываются.
     */
    private int scanRapidForwarding(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                    long windowStart, long bucket, UUID onlyPlayer) {
        long forwardMillis = cfg.rapidForwardWindowMinutes() * 60_000L;
        Map<UUID, List<TransactionRow>> bySource = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() != null && row.createdAt() >= windowStart) {
                bySource.computeIfAbsent(row.sourceUuid(), k -> new ArrayList<>()).add(row);
            }
        }
        for (List<TransactionRow> outgoing : bySource.values()) {
            outgoing.sort(CHRONOLOGICAL);
        }
        int written = 0;
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null
                    || row.amountMinor() < cfg.rapidForwardAmount()
                    || isSystem(row.targetUuid()) || isSystem(row.sourceUuid())) {
                continue;
            }
            UUID forwarder = row.targetUuid();
            if (onlyPlayer != null && !forwarder.equals(onlyPlayer)) {
                continue;
            }
            List<TransactionRow> outgoing = bySource.getOrDefault(forwarder, List.of());
            long limit = row.createdAt() + forwardMillis;
            for (TransactionRow next : outgoing) {
                // Следующая операция обязана быть СТРОГО позже входящей по составной
                // паре (created_at, transaction_id): разложенный по времени перевод
                // «продолжением» быть не может, при равных временах порядок задаёт
                // transaction_id, а сама себе строка «пересылкой» не является.
                if (next == row || !strictlyAfter(next, row)
                        || next.createdAt() > limit
                        || next.amountMinor() < cfg.rapidForwardAmount()
                        || next.targetUuid() == null) {
                    continue;
                }
                long deltaMillis = next.createdAt() - row.createdAt();
                if (writeSignal(AuditEventType.SIGNAL_RAPID_FORWARDING,
                        forwarder, next.targetUuid(), row.amountMinor(),
                        "inTx=" + row.transactionId() + ";outTx=" + next.transactionId()
                                + ";inAmount=" + row.amountMinor() + ";outAmount="
                                + next.amountMinor() + ";deltaMillis=" + deltaMillis
                                + ";windowMinutes=" + cfg.rapidForwardWindowMinutes()
                                + ";to=" + next.targetUuid(),
                        incidentDedupeKey("rapid", forwarder,
                                row.transactionId(), next.transactionId()))) {
                    written++;
                }
                break;
            }
        }
        return written;
    }

    /**
     * Цикл переводов из 3+ участников (A→B→C→A и длиннее, до {@value MAX_LOOP_DEPTH}).
     * Учитывается ТОЛЬКО хронология: каждое ребро цикла обязано быть строго позже
     * предыдущего (перевод не может «двигаться назад во времени»); ребра одной
     * пары без упорядоченности цикла не образуют. Инцидент — КОНКРЕТНЫЙ цикл с
     * упорядоченными txId/суммами/временами; ключ дедупликации — SHA-256 от состава
     * txId, поэтому разные циклы одного игрока НЕ схлопываются в один сигнал.
     */
    private int scanTransferLoops(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                  long bucket, UUID onlyPlayer) {
        Map<UUID, List<ChronoEdge>> adjacency = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            if (isSystem(row.sourceUuid()) || isSystem(row.targetUuid())) {
                continue;
            }
            adjacency.computeIfAbsent(row.sourceUuid(), k -> new ArrayList<>())
                    .add(new ChronoEdge(row));
        }
        for (List<ChronoEdge> edges : adjacency.values()) {
            edges.sort(Comparator.comparingLong(ChronoEdge::createdAt)
                    .thenComparing(e -> e.row().transactionId()));
        }
        Set<String> seenIncidents = new HashSet<>();
        List<LoopIncident> incidents = new ArrayList<>();
        for (UUID start : adjacency.keySet()) {
            // Хронологический цикл находится только при обходе с самой РАННЕЙ вершины
            // (последнее ребро обязано быть не раньше первого), поэтому DFS запускается
            // по всем вершинам ОГРАНИЧЕННОГО графа; при scanPlayer события пишутся
            // только сканируемому игроку. Граф уже ограничен участниками игрока.
            Set<UUID> visited = new HashSet<>();
            visited.add(start);
            findChronologicalCycles(start, start, OpStamp.NONE, new ArrayDeque<>(), visited,
                    adjacency, cfg.transferLoopLength(), MAX_LOOP_DEPTH, seenIncidents,
                    incidents);
        }
        int written = 0;
        for (LoopIncident incident : incidents) {
            String incidentKey = incident.key();
            String details = "participants=" + incident.participants() + ";txs="
                    + incident.txIds() + ";amounts=" + incident.amounts() + ";minLength="
                    + cfg.transferLoopLength() + ";start=" + incident.times().get(0)
                    + ";end=" + incident.times().get(incident.times().size() - 1);
            if (onlyPlayer != null) {
                if (incident.participants().contains(onlyPlayer)
                        && writeSignal(AuditEventType.SIGNAL_TRANSFER_LOOP, onlyPlayer, null, null,
                        details, incidentDedupeKey("loop", onlyPlayer, incidentKey))) {
                    written++;
                }
            } else {
                for (UUID player : incident.participants()) {
                    if (writeSignal(AuditEventType.SIGNAL_TRANSFER_LOOP, player, null, null,
                            details, incidentDedupeKey("loop", player, incidentKey))) {
                        written++;
                    }
                }
            }
        }
        return written;
    }

    /**
     * DFS по рёбрам, упорядоченным по времени: ребро допускается только если оно
     * строго позже предыдущей операции по составной паре (created_at, transaction_id),
     * см. {@link OpStamp}. Возврат в корень при размере пути ≥ minLength фиксирует
     * КОНКРЕТНЫЙ инцидент цикла (участники + упорядоченные txId/суммы/времена);
     * дубликат по составу txId отбрасывается через {@code seenIncidents}.
     */
private void findChronologicalCycles(UUID root, UUID current, OpStamp lastOp,
                                     Deque<ChronoEdge> path, Set<UUID> visited,
                                     Map<UUID, List<ChronoEdge>> adjacency,
                                     int minLength, int maxDepth, Set<String> seenIncidents,
                                     List<LoopIncident> incidents) {
        if (path.size() >= maxDepth) {
            return;
        }
        for (ChronoEdge edge : adjacency.getOrDefault(current, List.of())) {
            // Следующая операция обязана быть СТРОГО позже предыдущей по составной
            // паре (created_at, transaction_id): ребро не может «идти назад во времени»,
            // а при равных временах (одна миллисекунда) порядок задаёт transaction_id.
            if (!OpStamp.of(edge.row()).after(lastOp)) {
                continue;
            }
            if (edge.target().equals(root)) {
                if (path.size() + 1 >= minLength) {
                    LoopIncident incident = buildIncident(root, path, edge);
                    if (seenIncidents.add(incident.key())) {
                        incidents.add(incident);
                    }
                }
                continue;
            }
            if (visited.contains(edge.target())) {
                continue;
            }
            visited.add(edge.target());
            path.addLast(edge);
            findChronologicalCycles(root, edge.target(), OpStamp.of(edge.row()), path, visited,
                    adjacency, minLength, maxDepth, seenIncidents, incidents);
            path.removeLast();
            visited.remove(edge.target());
        }
    }

    private static LoopIncident buildIncident(UUID root, Deque<ChronoEdge> path, ChronoEdge closing) {
        List<UUID> participants = new ArrayList<>();
        List<String> txIds = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        participants.add(root);
        for (ChronoEdge e : path) {
            participants.add(e.target());
            txIds.add(e.row().transactionId());
            amounts.add(e.row().amountMinor());
            times.add(e.createdAt());
        }
        txIds.add(closing.row().transactionId());
        amounts.add(closing.row().amountMinor());
        times.add(closing.createdAt());
        return new LoopIncident(participants, txIds, amounts, times);
    }

    /**
     * Очень высокая частота обменов между одной парой: событие пишется КАЖДОМУ
     * участнику пары (вариант A), с общим incident-id в деталях обоих событий —
     * чтобы обе стороны (и их владельцы) видели проблему пары независимо.
     */
    private int scanHighPairFrequency(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                      long bucket, UUID onlyPlayer) {
        Map<String, Integer> pairs = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            if (isSystem(row.sourceUuid()) || isSystem(row.targetUuid())) {
                continue;
            }
            pairs.merge(pairKey(row.sourceUuid(), row.targetUuid()), 1, Integer::sum);
        }
        int written = 0;
        for (Map.Entry<String, Integer> entry : pairs.entrySet()) {
            if (entry.getValue() < cfg.highPairFrequencyExchanges()) {
                continue;
            }
            String[] sides = entry.getKey().split("\\|");
            UUID a = UUID.fromString(sides[0]);
            UUID b = UUID.fromString(sides[1]);
            String incident = "HPF|" + entry.getKey() + "|" + bucket;
            for (UUID participant : new UUID[]{a, b}) {
                if (onlyPlayer != null && !participant.equals(onlyPlayer)) {
                    continue;
                }
                UUID partner = participant.equals(a) ? b : a;
                if (writeSignal(AuditEventType.SIGNAL_HIGH_PAIR_FREQUENCY, participant, partner,
                        null,
                        "pair=" + partner + ";exchanges=" + entry.getValue()
                                + ";incident=" + incident,
                        incidentDedupeKey("highpair", participant, bucket, entry.getKey()))) {
                    written++;
                }
            }
        }
        return written;
    }

    /** Свежий аккаунт принимает переводы от многих отправителей. */
    private int scanNewAccountConcentration(List<TransactionRow> transfers, List<AccountRow> allAccounts,
                                            AuditConfig.Settings cfg, long bucket, UUID onlyPlayer) {
        long ageMillis = cfg.newAccountDays() * 86_400_000L;
        long now = System.currentTimeMillis();
        Set<UUID> fresh = freshAccounts(allAccounts, ageMillis, now);
        Map<UUID, Set<UUID>> sourcesByFresh = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null
                    || !fresh.contains(row.targetUuid())
                    || isSystem(row.sourceUuid()) || isSystem(row.targetUuid())) {
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
            if (writeSignal(AuditEventType.SIGNAL_NEW_ACCOUNT_CONCENTRATION,
                    player, null, null,
                    "sources=" + sources + ";accountAgeDays=" + cfg.newAccountDays(),
                    dedupeKey("conc", player, bucket))) {
                written++;
            }
        }
        return written;
    }

    /** Один игрок многократно переводит одному и тому же получателю. */
    private int scanRepeatedSharedDestination(List<TransactionRow> transfers, AuditConfig.Settings cfg,
                                              long bucket, UUID onlyPlayer) {
        Map<UUID, Map<UUID, Integer>> counts = new HashMap<>();
        for (TransactionRow row : transfers) {
            if (row.sourceUuid() == null || row.targetUuid() == null) {
                continue;
            }
            if (isSystem(row.sourceUuid()) || isSystem(row.targetUuid())) {
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
                if (writeSignal(AuditEventType.SIGNAL_REPEATED_SHARED_DESTINATION, sender,
                        entry.getKey(), null,
                        "to=" + entry.getKey() + ";transfers=" + entry.getValue(),
                        incidentDedupeKey("dest", sender, bucket, entry.getKey().toString()))) {
                    written++;
                }
            }
        }
        return written;
    }

    // ------------------------------------------------------------ helpers

    /** Ребро графа переводов: полная строка перевода для хронологического поиска циклов. */
    private record ChronoEdge(TransactionRow row) {

        UUID target() {
            return row.targetUuid();
        }

        long createdAt() {
            return row.createdAt();
        }
    }

    /**
     * Конкретный инцидент цикла: участники и рёбра (txId/сумма/время) в
     * хронологическом порядке. Ключ — отсортированный состав txId, поэтому разные
     * циклы (в т.ч. у одного игрока) остаются разными инцидентами.
     */
    private record LoopIncident(List<UUID> participants, List<String> txIds,
                                List<Long> amounts, List<Long> times) {

        String key() {
            List<String> sorted = new ArrayList<>(txIds);
            sorted.sort(Comparator.naturalOrder());
            return String.join(",", sorted);
        }
    }

    private static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    /** Стабильный ключ окна для АГРЕГАТНОГО сигнала: (тип, игрок, окно) — дубликат окна игнорируется. */
    private static String dedupeKey(String prefix, UUID player, long bucket) {
        return prefix + "|" + player + "|" + bucket;
    }

    /**
     * Ключ инцидента с привязкой к окну (bucket): используется агрегатными сигналами
     * (ROUNDTRIP, HIGH_PAIR_FREQUENCY, REPEATED_DESTINATION), где сигнал описывает
     * активность ПАРЫ ИМЕННО за текущее окно и новое окно должно дать новый ключ.
     */
    private static String incidentDedupeKey(String kind, UUID player, long bucket, String... parts) {
        StringBuilder canonical = new StringBuilder(kind).append('|').append(player).append('|').append(bucket);
        for (String part : parts) {
            canonical.append('|').append(part);
        }
        return kind + ':' + sha256Hex(canonical.toString());
    }

    /**
     * Ключ КОНКРЕТНОГО инцидента БЕЗ bucket: (тип, игрок, идентификаторы транзакций).
     * Для сигналов, привязанных к самим переводам (OVERSIZED, RAPID_FORWARDING,
     * TRANSFER_LOOP), канонический состав txId уникален сам по себе — привязка к
     * окну не нужна и опасна: этот же перевод, попадая в следующее окно, НЕ должен
     * порождать повторный сигнал. SHA-256 даёт фиксированную длину (VARCHAR(256)).
     */
    private static String incidentDedupeKey(String kind, UUID player, String... parts) {
        StringBuilder canonical = new StringBuilder(kind).append('|').append(player);
        for (String part : parts) {
            canonical.append('|').append(part);
        }
        return kind + ':' + sha256Hex(canonical.toString());
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен в JRE", e);
        }
    }

    /**
     * Хронологическая позиция операции (created_at, transaction_id): следующий перевод
     * в цепочке обязан быть строго больше по этой паре. {@link #NONE} — «до начала»,
     * любому переводу.
     */
    private record OpStamp(long createdAt, String transactionId) {

        static final OpStamp NONE = new OpStamp(Long.MIN_VALUE, null);

        static OpStamp of(TransactionRow row) {
            return new OpStamp(row.createdAt(), row.transactionId());
        }

        boolean after(OpStamp other) {
            int byTime = Long.compare(createdAt, other.createdAt);
            if (byTime != 0) {
                return byTime > 0;
            }
            if (other.transactionId == null) {
                return transactionId != null;
            }
            return transactionId != null && transactionId.compareTo(other.transactionId) > 0;
        }
    }

    /**
     * Системный аккаунт (казна и т.п.): исключается из всех эвристик — не может
     * быть спамером, участником пары, «свежим» аккаунтом или получателем сигнала.
     */
    private static boolean isSystem(UUID id) {
        return id == null || TreasuryService.TREASURY_UUID.equals(id);
    }

    private static Set<UUID> freshAccounts(List<AccountRow> allAccounts, long ageMillis, long now) {
        Set<UUID> fresh = new HashSet<>();
        for (AccountRow account : allAccounts) {
            if (isSystem(account.playerId())) {
                continue;
            }
            if (now - account.createdAt() <= ageMillis) {
                fresh.add(account.playerId());
            }
        }
        return fresh;
    }

    /**
     * Безопасный предел числа переводов за скан: конфиг уже ограничивает диапазон,
     * но защитно кламапим и здесь, чтобы SQL {@code LIMIT} и {@code subList} никогда
     * не получили отрицательное/нулевое значение или переполнение {@code int}.
     */
    private static int scanLimit(long configured) {
        if (configured < 1) {
            return 1;
        }
        return (int) Math.min(configured, com.valorcraft.veconomy.config.AuditConfig.MAX_TRANSFERS_PER_SCAN);
    }

    /**
     * Записать сигнал; уникальный частичный индекс {@code dedupe_key} отклоняет
     * повторное событие (возвращает false, в сводку не попадает). Дубль ключа —
     * НЕ ошибка: события не создаётся, но сканирование продолжается честно.
     * Настоящая ошибка БД (DatabaseException) НЕ проглатывается — она уходит
     * вверх по стеку в {@code AuditService}, где фоновый скан завершается
     * {@code failed}-колбэком, а не выдаёт «успешную» сводку с недозаписанными
     * сигналами.
     */
    private boolean writeSignal(String type, UUID playerId, UUID counterparty, Long amount,
                                String details, String dedupeKey) {
        return database.inTransaction(connection -> {
            AuditRepository.InsertResult result = audit.insert(connection, database.dialect(),
                    AuditEventRow.signal(type, playerId, counterparty, amount, details, dedupeKey));
            return result.status() == AuditRepository.InsertResult.Status.INSERTED;
        });
    }

    /**
     * Итог сканирования: сколько сигналов каждого типа записано и признак того,
     * что анализ был ОГРАНИЧЕН (персональный граф scanPlayer или предел
     * maxTransfersPerScan) — неполный анализ не выдаётся за полный.
     */
    public record ScanSummary(int spamSignals, int roundTripSignals, int oversizedSignals,
                              int newAccountSignals, int rapidForwardingSignals,
                              int transferLoopSignals, int highPairFrequencySignals,
                              int newAccountConcentrationSignals, int repeatedDestinationSignals,
                              boolean limited) {

        public int total() {
            return spamSignals + roundTripSignals + oversizedSignals + newAccountSignals
                    + rapidForwardingSignals + transferLoopSignals + highPairFrequencySignals
                    + newAccountConcentrationSignals + repeatedDestinationSignals;
        }

        public static ScanSummary zero() {
            return new ScanSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, false);
        }
    }
}
