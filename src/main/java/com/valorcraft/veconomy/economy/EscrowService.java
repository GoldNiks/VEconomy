package com.valorcraft.veconomy.economy;

import com.google.gson.Gson;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.EscrowCredit;
import com.valorcraft.veconomy.api.EscrowResult;
import com.valorcraft.veconomy.api.EscrowSnapshot;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.valorcraft.veconomy.api.EscrowResult.failed;
import static com.valorcraft.veconomy.api.EscrowResult.success;

/**
 * Эскроу для будущего аукциона. Средства резервируются у владельца (списываются с его
 * баланса), удерживаются системой, а затем либо атомарно распределяются между
 * получателями (settle: продавец + казна/комиссия), либо возвращаются владельцу.
 * <p>
 * Все переходы идемпотентны и записываются в журнал; переход {@code RESERVED→CAPTURED}
 * выполняется условным обновлением-сторожем, а зачисления идут в той же транзакции
 * (all-or-nothing).
 */
public final class EscrowService {

    /** Сигнал прервать транзакцию расчёта при сбое ноги (откат всех частей). */
    private static final class SettleAbort extends RuntimeException {
        final EscrowResult.Status status;

        SettleAbort(EscrowResult.Status status) {
            this.status = status;
        }
    }

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

    /** UUID системного счёта казны (для комиссий аукциона). */
    public static UUID treasuryUuid() {
        return TreasuryService.TREASURY_UUID;
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
                Optional<EscrowRow> existing = escrow.find(connection, referenceId);
                if (existing.isPresent()) {
                    return classifyRepeat(existing.get(), ownerId, amount);
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
            // Гонка в MySQL: параллельный reserve того же referenceId уже вставил строку
            // (или реальная ошибка). Перечитываем — идемпотентность, а не ошибка БД.
            if (e.getCause() instanceof java.sql.SQLIntegrityConstraintViolationException) {
                try {
                    EscrowRow row = database.inTransaction(connection ->
                            escrow.find(connection, referenceId).orElse(null));
                    if (row != null) {
                        return classifyRepeat(row, ownerId, amount);
                    }
                } catch (DatabaseException ignored) {
                }
            }
            VEconomyMod.LOGGER.error("Ошибка резервирования {} у {}", amount, ownerId, e);
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    private static EscrowResult classifyRepeat(EscrowRow row, UUID ownerId, long amount) {
        if (row.ownerUuid().equals(ownerId) && row.amountMinor() == amount) {
            return failed(EscrowResult.Status.ALREADY_RESERVED);
        }
        return failed(EscrowResult.Status.CONFLICT);
    }

    /** Атомарно распределить зарезервированные средства между получателями. */
    public EscrowResult settleMoney(String referenceId, List<EscrowCredit> credits, TransactionContext context) {
        if (referenceId == null || referenceId.isBlank() || credits == null || credits.isEmpty()) {
            return failed(EscrowResult.Status.INVALID_CREDITS);
        }
        for (EscrowCredit credit : credits) {
            if (credit.recipientId() == null || credit.amount() <= 0) {
                return failed(EscrowResult.Status.INVALID_CREDITS);
            }
        }
        final long total;
        try {
            long sum = 0;
            for (EscrowCredit credit : credits) {
                sum = Math.addExact(sum, credit.amount());
            }
            total = sum;
        } catch (ArithmeticException e) {
            return failed(EscrowResult.Status.LIMIT_EXCEEDED);
        }
        String hash = settlementHash(credits);
        String json = settlementJson(credits);
        try {
            return database.inTransaction(connection -> {
                EscrowRow reservation = escrow.find(connection, referenceId, database.dialect())
                        .orElse(null);
                if (reservation == null) {
                    return failed(EscrowResult.Status.NOT_FOUND);
                }
                if (reservation.state() == EscrowState.RELEASED) {
                    return failed(EscrowResult.Status.WRONG_STATE);
                }
                if (reservation.state() == EscrowState.CAPTURED) {
                    return idempotentSettle(referenceId, reservation, hash);
                }
                if (total != reservation.amountMinor()) {
                    return failed(EscrowResult.Status.INVALID_CREDITS);
                }
                // Гонка за переход RESERVED→CAPTURED: проигравший возвращает идемпотентный
                // результат, а не снимает деньги повторно.
                if (!escrow.settle(connection, referenceId, System.currentTimeMillis(), hash, json)) {
                    EscrowRow current = escrow.find(connection, referenceId, database.dialect())
                            .orElse(null);
                    if (current == null || current.state() == EscrowState.RELEASED) {
                        return failed(EscrowResult.Status.WRONG_STATE);
                    }
                    return idempotentSettle(referenceId, current, hash);
                }
                // Зачисления получателям в той же транзакции: любой сбой (заморозка, лимит,
                // ошибка БД) откатывает и смену состояния эскроу (all-or-nothing).
                for (EscrowCredit credit : credits) {
                    creditRecipient(connection, reservation.ownerUuid(), credit);
                }
                return success(reservation.amountMinor(), referenceId);
            });
        } catch (SettleAbort abort) {
            return failed(abort.status);
        } catch (ArithmeticException e) {
            return failed(EscrowResult.Status.LIMIT_EXCEEDED);
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка расчёта эскроу {}", referenceId, e);
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    private EscrowResult idempotentSettle(String referenceId, EscrowRow row, String hash) {
        if (row.state() == EscrowState.CAPTURED && row.settledHash() != null && row.settledHash().equals(hash)) {
            return success(row.amountMinor(), referenceId, EscrowResult.Status.ALREADY_SETTLED);
        }
        return failed(EscrowResult.Status.CONFLICT);
    }

    /** Зачислить долю получателю и записать ногу в журнал (в рамках открытой транзакции).
     *  Любая ошибка — откат всей транзакции, в том числе условного перехода эскроу. */
    private void creditRecipient(Connection connection, UUID ownerUuid, EscrowCredit credit) {
        AccountRow recipient = accountService.createIfMissing(connection, credit.recipientId(), null);
        if (recipient.status() == AccountStatus.FROZEN) {
            throw new SettleAbort(EscrowResult.Status.ACCOUNT_DISABLED);
        }
        long newRecipientBalance;
        try {
            newRecipientBalance = Math.addExact(recipient.balanceMinor(), credit.amount());
        } catch (ArithmeticException e) {
            throw new SettleAbort(EscrowResult.Status.LIMIT_EXCEEDED);
        }
        if (newRecipientBalance > settings.maximumBalance) {
            throw new SettleAbort(EscrowResult.Status.LIMIT_EXCEEDED);
        }
        if (!accounts.updateBalance(connection, credit.recipientId(), newRecipientBalance,
                recipient.version(), System.currentTimeMillis())) {
            throw new SettleAbort(EscrowResult.Status.DATABASE_ERROR);
        }
        Map<String, String> metadata = new java.util.HashMap<>();
        if (credit.role() != null && !credit.role().isBlank()) {
            metadata.put("role", credit.role());
        }
        ledger.record(connection, new TransactionRow(
                null, TransactionType.ESCROW_CAPTURE, ownerUuid, credit.recipientId(),
                credit.amount(), System.currentTimeMillis(), null, "escrow:settle",
                null, metadata, null, newRecipientBalance));
    }

    /** Передать зарезервированные средства одному получателю (целиком). */
    public EscrowResult captureMoney(String referenceId, UUID recipientId, TransactionContext context) {
        if (recipientId == null) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        EscrowSnapshot snapshot = findEscrow(referenceId).orElse(null);
        if (snapshot == null) {
            return failed(EscrowResult.Status.NOT_FOUND);
        }
        if (snapshot.state() != EscrowState.RESERVED) {
            return snapshot.state() == EscrowState.CAPTURED
                    ? failed(EscrowResult.Status.ALREADY_SETTLED)
                    : failed(EscrowResult.Status.WRONG_STATE);
        }
        return settleMoney(referenceId,
                List.of(new EscrowCredit(recipientId, snapshot.amount(), "capture")), context);
    }

    /** Вернуть зарезервированные средства владельцу. */
    public EscrowResult releaseMoney(String referenceId, TransactionContext context) {
        if (referenceId == null || referenceId.isBlank()) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        try {
            return database.inTransaction(connection -> {
                EscrowRow reservation = escrow.find(connection, referenceId, database.dialect())
                        .orElse(null);
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
                escrow.release(connection, referenceId, now);
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

    /** Текущий снимок эскроу-записи (включая произведённое распределение). */
    public Optional<EscrowSnapshot> findEscrow(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            return Optional.empty();
        }
        try {
            return database.inTransaction(connection ->
                    escrow.find(connection, referenceId).map(this::toSnapshot));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения эскроу {}", referenceId, e);
            return Optional.empty();
        }
    }

    private EscrowSnapshot toSnapshot(EscrowRow row) {
        return new EscrowSnapshot(row.referenceId(), row.ownerUuid(), row.amountMinor(),
                row.state(), parseSettlement(row.settledJson()),
                row.createdAt(), row.updatedAt());
    }

    /** Сумма всех зарезервированных (не финализированных) средств. */
    public long sumReserved() {
        return database.inTransaction(escrow::sumReserved);
    }

    // ------------------------------------------------------------ settlement

    /**
     * Канонический хеш распределения: порядок и роли получателей не влияют на
     * результат (сортировка по получателю+роли+сумме), поэтому повторный расчёт
     * с теми же параметрами детерминированно даёт одинаковый хеш.
     */
    public static String settlementHash(List<EscrowCredit> credits) {
        List<String> parts = new ArrayList<>();
        for (EscrowCredit c : credits) {
            parts.add(c.recipientId() + "|" + (c.role() == null ? "" : c.role()) + "|" + c.amount());
        }
        parts.sort(Comparator.naturalOrder());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(String.join(";", parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Gson GSON = new Gson();

    public static String settlementJson(List<EscrowCredit> credits) {
        return GSON.toJson(credits.stream()
                .map(c -> Map.of("recipientId", c.recipientId().toString(),
                        "amount", Long.toString(c.amount()),
                        "role", c.role() == null ? "" : c.role()))
                .toList());
    }

    private static List<EscrowCredit> parseSettlement(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<?, ?>> raw = GSON.fromJson(json, List.class);
            if (raw == null) {
                return List.of();
            }
            List<EscrowCredit> result = new ArrayList<>();
            for (Map<?, ?> entry : raw) {
                result.add(new EscrowCredit(UUID.fromString(String.valueOf(entry.get("recipientId"))),
                        Long.parseLong(String.valueOf(entry.get("amount"))),
                        String.valueOf(entry.get("role"))));
            }
            return result;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}