package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.TestDb;
import com.valorcraft.veconomy.api.TransactionType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Репозиторные тесты журнала переводов: SQL-лимит и граф между слайсами участников. */
class TransactionRepositoryTest {

    private static void insertTransfer(TestDb db, UUID from, UUID to, long amount, long atMillis) {
        insertTransfer(db, from, to, amount, atMillis, UUID.randomUUID().toString());
    }

    private static void insertTransfer(TestDb db, UUID from, UUID to, long amount, long atMillis,
                                       String transactionId) {
        TransactionRow row = new TransactionRow(transactionId,
                TransactionType.PLAYER_TRANSFER, from, to, amount, atMillis, null, "тест",
                null, java.util.Map.of(), null, null);
        db.database.inTransaction(connection -> {
            db.transactions.insert(connection, row);
            return null;
        });
    }

    @Test
    void transfersSinceLimitedReadsOnlyLimitRowsEvenWhenMoreExist() {
        try (TestDb db = TestDb.create()) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            long base = System.currentTimeMillis();
            for (int i = 0; i < 7; i++) {
                insertTransfer(db, alice, bob, 100, base + i);
            }
            // В базе 7 переводов; запрос с LIMIT возвращает ровно лимит строк.
            List<TransactionRow> limited = db.database.inTransaction(connection ->
                    db.transactions.transfersSinceLimited(connection, base - 1, 3));
            assertEquals(3, limited.size(), "SQL-лимит должен обрезать на уровне запроса");

            // Остальные строки остались в базе — ограничение реально в SQL, не в памяти.
            List<TransactionRow> all = db.database.inTransaction(connection ->
                    db.transactions.transfersSince(connection, base - 1));
            assertEquals(7, all.size(), "журнал не тронут, полный набор по-прежнему читается");
        }
    }

    @Test
    void transfersSinceLimitedOrderIsStableByTimeThenTransactionId() {
        try (TestDb db = TestDb.create()) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            long base = System.currentTimeMillis();
            // Одинаковое время, разные txId: tie-break по transaction_id (новый сверху).
            insertTransfer(db, alice, bob, 100, base, "tx-1");
            insertTransfer(db, alice, bob, 100, base, "tx-2");
            insertTransfer(db, alice, bob, 100, base, "tx-3");

            List<TransactionRow> rows = db.database.inTransaction(connection ->
                    db.transactions.transfersSinceLimited(connection, base - 1, 3));
            assertEquals(List.of("tx-3", "tx-2", "tx-1"), rows.stream()
                            .map(TransactionRow::transactionId).toList(),
                    "порядок: created_at DESC, затем transaction_id DESC");
        }
    }

    @Test
    void transfersSinceForPlayerLimitedReadsOnlyLimitRows() {
        try (TestDb db = TestDb.create()) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            long base = System.currentTimeMillis();
            for (int i = 0; i < 7; i++) {
                insertTransfer(db, alice, bob, 100, base + i);
            }
            // История одного игрока с лимитом прямо в SQL: даже при 7 строках в базе
            // читается ровно лимит строк, а не весь журнал окна.
            List<TransactionRow> limited = db.database.inTransaction(connection ->
                    db.transactions.transfersSinceForPlayerLimited(connection, alice, base - 1, 3));
            assertEquals(3, limited.size(), "персональный запрос тоже лимитируется в SQL");

            // Остальные переводы остались в базе, и другие игроки не затронуты.
            List<TransactionRow> all = db.database.inTransaction(connection ->
                    db.transactions.transfersSinceForPlayer(connection, alice, base - 1));
            assertEquals(7, all.size(), "журнал для игрока не тронут, полный набор читается");
        }
    }

    @Test
    void transfersSinceForPlayerLimitedOrderIsStableByTimeThenTransactionId() {
        try (TestDb db = TestDb.create()) {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            long base = System.currentTimeMillis();
            insertTransfer(db, alice, bob, 100, base, "p-tx-1");
            insertTransfer(db, alice, bob, 100, base, "p-tx-2");
            insertTransfer(db, alice, bob, 100, base, "p-tx-3");
            // Перевод между чужими игроками не попадает в выборку alice.
            insertTransfer(db, UUID.randomUUID(), UUID.randomUUID(), 100, base, "other-1");

            List<TransactionRow> rows = db.database.inTransaction(connection ->
                    db.transactions.transfersSinceForPlayerLimited(connection, alice, base - 1, 5));
            assertEquals(List.of("p-tx-3", "p-tx-2", "p-tx-1"), rows.stream()
                            .map(TransactionRow::transactionId).toList(),
                    "персональный порядок: created_at DESC, затем transaction_id DESC; чужие не включены");
        }
    }

    @Test
    void transfersBetweenFindsEdgesAcrossParameterSlices() {
        try (TestDb db = TestDb.create()) {
            // > 2×200 участников: перевод между UUID из ПЕРВОГО и ВТОРОГО слайса
            // обязан попасть в граф (старая реализация искала только внутри слайса).
            int total = 420;
            List<UUID> sources = new ArrayList<>();
            List<UUID> targets = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                sources.add(UUID.randomUUID());
                targets.add(UUID.randomUUID());
            }
            UUID from = sources.get(0);          // слайс №1
            UUID to = targets.get(210);          // слайс №2
            long base = System.currentTimeMillis();
            insertTransfer(db, from, to, 100, base);

            List<UUID> participants = new ArrayList<>(sources);
            participants.addAll(targets);
            List<TransactionRow> graph = db.database.inTransaction(connection ->
                    db.transactions.transfersBetween(connection, participants, base - 1));
            assertEquals(1, graph.size(), "перевод между разными слайсами не теряется");
            assertEquals(from, graph.get(0).sourceUuid());
            assertEquals(to, graph.get(0).targetUuid());
        }
    }

    @Test
    void transfersBetweenDeduplicatesByTransactionId() {
        try (TestDb db = TestDb.create()) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            long base = System.currentTimeMillis();
            // Два перевода одной пары — две разные строки, обе попадают в граф.
            insertTransfer(db, a, b, 100, base);
            insertTransfer(db, a, b, 100, base + 1);

            List<TransactionRow> graph = db.database.inTransaction(connection ->
                    db.transactions.transfersBetween(connection, List.of(a, b), base - 1));
            assertEquals(2, graph.size(), "разные transaction_id не схлопываются");
            long distinctIds = graph.stream().map(TransactionRow::transactionId).distinct().count();
            assertEquals(2, distinctIds);
        }
    }

    @Test
    void transfersBetweenRespectsParticipantCap() {
        try (TestDb db = TestDb.create()) {
            // 250 участников, кап 200: рёбро между участниками за капом в граф не попадает.
            List<UUID> all = new ArrayList<>();
            for (int i = 0; i < 250; i++) {
                all.add(UUID.randomUUID());
            }
            long base = System.currentTimeMillis();
            insertTransfer(db, all.get(0), all.get(1), 100, base);   // внутри капа
            insertTransfer(db, all.get(0), all.get(240), 100, base); // за капом (240 >= 200)

            List<TransactionRow> capped = db.database.inTransaction(connection ->
                    db.transactions.transfersBetween(connection, all, base - 1, 200));
            assertEquals(1, capped.size(), "граф ограничен первыми maxParticipants UUID");
            assertEquals(all.get(1), capped.get(0).targetUuid());

            List<TransactionRow> uncapped = db.database.inTransaction(connection ->
                    db.transactions.transfersBetween(connection, all, base - 1));
            assertEquals(2, uncapped.size(), "без капа обе пары видны");
        }
    }

    @Test
    void transfersBetweenLimitedExcludesFocalPlayerAndCapsRows() {
        try (TestDb db = TestDb.create()) {
            UUID focal = UUID.randomUUID();
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            UUID carol = UUID.randomUUID();
            long base = System.currentTimeMillis();
            // Прямые переводы FOCAL (уже загружаются персональным запросом) — они
            // НЕ должны повторно выгружаться графовым запросом и тратить бюджет.
            insertTransfer(db, focal, alice, 700, base);
            insertTransfer(db, bob, focal, 700, base + 1);
            insertTransfer(db, focal, carol, 700, base + 2);
            // Рёбра остальных участников графа (не участвуют focal).
            insertTransfer(db, alice, bob, 100, base + 3);
            insertTransfer(db, bob, carol, 100, base + 4);
            insertTransfer(db, carol, alice, 100, base + 5);
            insertTransfer(db, alice, bob, 100, base + 6);

            List<UUID> participants = List.of(focal, alice, bob, carol);
            TransactionRepository.LimitedRows limited = db.database.inTransaction(connection ->
                    db.transactions.transfersBetweenLimited(connection, participants,
                            focal, base - 1, 3));
            assertEquals(3, limited.rows().size(),
                    "итоговый список не превышает maxRows (бюджет израсходован точно)");
            assertTrue(limited.limited(),
                    "в графе есть больше рёбер чем бюджет — реальное ограничение");
            // Прямые переводы focal не попадают в графовый результат.
            assertTrue(limited.rows().stream()
                            .noneMatch(r -> focal.equals(r.sourceUuid()) || focal.equals(r.targetUuid())),
                    "переводы focal исключаются: они уже в персональном наборе");
        }
    }

    @Test
    void transfersBetweenLimitedWithoutOverflowIsNotLimited() {
        try (TestDb db = TestDb.create()) {
            UUID focal = UUID.randomUUID();
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            long base = System.currentTimeMillis();
            // Ровно два рёбра вне focal + столько прямых focal, сколько захотим.
            insertTransfer(db, focal, alice, 700, base);
            insertTransfer(db, alice, bob, 100, base + 1);
            insertTransfer(db, bob, alice, 100, base + 2);

            List<UUID> participants = List.of(focal, alice, bob);
            TransactionRepository.LimitedRows result = db.database.inTransaction(connection ->
                    db.transactions.transfersBetweenLimited(connection, participants,
                            focal, base - 1, 5));
            assertEquals(2, result.rows().size(), "все доступные рёбра графа в бюджете");
            assertFalse(result.limited(), "бюджета достаточно — «ограничено» не ставится");
        }
    }
}