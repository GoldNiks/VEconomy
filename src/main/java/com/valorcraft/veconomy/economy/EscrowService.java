package com.valorcraft.veconomy.economy;

import com.google.gson.Gson;
import com.valorcraft.veconomy.VEconomyMod;
import com.valorcraft.veconomy.api.AccountStatus;
import com.valorcraft.veconomy.api.EscrowCredit;
import com.valorcraft.veconomy.api.EscrowLookupResult;
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
import java.util.function.Predicate;

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

    enum RolloverStage { AFTER_OLD_CAPTURE, AFTER_CREDIT, BEFORE_NEXT_ESCROW, AFTER_NEXT_ESCROW }

    /**
     * Сигнал прервать транзакцию возврата средств: после перехода {@code RESERVED→RELEASED}
     * любая ошибка ({@code accounts.updateBalance}, ledger) обязана откатить ВСЮ транзакцию,
     * иначе зарезервированные деньги пропадут (escrow RELEASED, баланс не увеличен).
     */
    private static final class ReleaseAbort extends RuntimeException {
        final EscrowResult.Status status;

        ReleaseAbort(EscrowResult.Status status) {
            this.status = status;
        }
    }

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
    private final Predicate<RolloverStage> rolloverFault;
    private volatile EconomySettings settings;

    public EscrowService(DatabaseManager database, AccountRepository accounts, EscrowRepository escrow,
                         AccountService accountService, LedgerService ledger, EconomySettings settings) {
        this(database, accounts, escrow, accountService, ledger, settings, stage -> false);
    }

    EscrowService(DatabaseManager database, AccountRepository accounts, EscrowRepository escrow,
                  AccountService accountService, LedgerService ledger, EconomySettings settings,
                  Predicate<RolloverStage> rolloverFault) {
        this.database = database;
        this.accounts = accounts;
        this.escrow = escrow;
        this.accountService = accountService;
        this.ledger = ledger;
        this.settings = settings;
        this.rolloverFault = rolloverFault == null ? stage -> false : rolloverFault;
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
                    // Проигранный optimistic-lock: параллельная операция уже изменила строку
                    // владельца. Перечитываем escrow ТЕКУЩИМ чтением (в MySQL — FOR UPDATE):
                    // если параллельный reserve того же referenceId уже создал запись — это
                    // идемпотентный повтор, а не ошибка БД.
                    EscrowRow raced = escrow.find(connection, referenceId, database.dialect())
                            .orElse(null);
                    if (raced != null) {
                        return classifyRepeat(raced, ownerId, amount);
                    }
                    // Строки нет — гонка с другой операцией того же владельца (перевод/начисление):
                    // повторяем списание со свежей версией и балансом.
                    AccountRow fresh = accounts.find(connection, ownerId).orElse(null);
                    if (fresh == null) {
                        return failed(EscrowResult.Status.NOT_FOUND);
                    }
                    if (fresh.status() == AccountStatus.FROZEN) {
                        return failed(EscrowResult.Status.ACCOUNT_DISABLED);
                    }
                    if (fresh.balanceMinor() < amount) {
                        return failed(EscrowResult.Status.INSUFFICIENT_FUNDS);
                    }
                    newBalance = fresh.balanceMinor() - amount;
                    if (!accounts.updateBalance(connection, ownerId, newBalance, fresh.version(), now)) {
                        return failed(EscrowResult.Status.DATABASE_ERROR);
                    }
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

    /**
     * Идемпотентный повтор reserve зависит от текущего состояния записи: зарезервирована —
     * {@code ALREADY_RESERVED}, уже распределена — {@code ALREADY_SETTLED}, уже возвращена —
     * {@code ALREADY_RELEASED}. Несовпадение владельца/суммы — {@code CONFLICT}.
     */
    private static EscrowResult classifyRepeat(EscrowRow row, UUID ownerId, long amount) {
        if (!row.ownerUuid().equals(ownerId) || row.amountMinor() != amount) {
            return failed(EscrowResult.Status.CONFLICT);
        }
        return switch (row.state()) {
            case RESERVED -> failed(EscrowResult.Status.ALREADY_RESERVED);
            case CAPTURED -> failed(EscrowResult.Status.ALREADY_SETTLED);
            case RELEASED -> failed(EscrowResult.Status.ALREADY_RELEASED);
        };
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
                // Порядок зачисления — канонический (как в settlementHash): стабильный и
                // не зависящий от порядка кредитов на входе.
                List<EscrowCredit> sorted = new ArrayList<>(credits);
                sorted.sort(Comparator.comparing(EscrowService::creditPart));
                Map<String, Integer> legOrdinals = new java.util.HashMap<>();
                for (EscrowCredit credit : sorted) {
                    String part = creditPart(credit);
                    int ordinal = legOrdinals.getOrDefault(part, 0);
                    legOrdinals.put(part, ordinal + 1);
                    creditRecipient(connection, reservation.ownerUuid(), referenceId, hash,
                            credit, ordinal, context);
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

    /**
     * Atomic partial settlement + rollover. Баланс владельца для remainder не
     * используется: остаток переносится непосредственно из old escrow в next
     * внутри той же SQL-транзакции.
     */
    public EscrowResult settleAndRollover(String oldReferenceId, List<EscrowCredit> credits,
                                          String nextReferenceId, long remainderAmount,
                                          TransactionContext context) {
        if (oldReferenceId == null || oldReferenceId.isBlank() || credits == null
                || remainderAmount < 0 || context == null) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        if (remainderAmount > 0
                && (nextReferenceId == null || nextReferenceId.isBlank()
                || oldReferenceId.equals(nextReferenceId))) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        if (remainderAmount == 0 && nextReferenceId != null && !nextReferenceId.isBlank()) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        if (credits.isEmpty() && remainderAmount == 0) {
            return failed(EscrowResult.Status.INVALID_CREDITS);
        }
        for (EscrowCredit credit : credits) {
            if (credit == null || credit.recipientId() == null || credit.amount() <= 0) {
                return failed(EscrowResult.Status.INVALID_CREDITS);
            }
        }
        final long total;
        try {
            long sum = remainderAmount;
            for (EscrowCredit credit : credits) {
                sum = Math.addExact(sum, credit.amount());
            }
            total = sum;
        } catch (ArithmeticException e) {
            return failed(EscrowResult.Status.LIMIT_EXCEEDED);
        }

        String canonicalNext = remainderAmount == 0 ? "" : nextReferenceId;
        String hash = rolloverHash(credits, canonicalNext, remainderAmount);
        String json = settlementJson(credits);
        try {
            return database.inTransaction(connection -> {
                EscrowRow reservation = escrow.find(connection, oldReferenceId, database.dialect())
                        .orElse(null);
                if (reservation == null) {
                    return failed(EscrowResult.Status.NOT_FOUND);
                }
                if (reservation.state() == EscrowState.RELEASED) {
                    return failed(EscrowResult.Status.WRONG_STATE);
                }
                if (reservation.state() == EscrowState.CAPTURED) {
                    return idempotentRollover(connection, oldReferenceId, reservation, hash,
                            canonicalNext, remainderAmount);
                }
                if (total != reservation.amountMinor()) {
                    return failed(EscrowResult.Status.INVALID_CREDITS);
                }
                if (remainderAmount > 0
                        && escrow.find(connection, canonicalNext, database.dialect()).isPresent()) {
                    return failed(EscrowResult.Status.CONFLICT);
                }

                long now = System.currentTimeMillis();
                if (!escrow.settle(connection, oldReferenceId, now, hash, json)) {
                    EscrowRow current = escrow.find(connection, oldReferenceId, database.dialect())
                            .orElse(null);
                    if (current == null || current.state() == EscrowState.RELEASED) {
                        return failed(EscrowResult.Status.WRONG_STATE);
                    }
                    return idempotentRollover(connection, oldReferenceId, current, hash,
                            canonicalNext, remainderAmount);
                }
                abortRolloverAt(RolloverStage.AFTER_OLD_CAPTURE);

                List<EscrowCredit> sorted = new ArrayList<>(credits);
                sorted.sort(Comparator.comparing(EscrowService::creditPart));
                Map<String, Integer> legOrdinals = new java.util.HashMap<>();
                for (EscrowCredit credit : sorted) {
                    String part = creditPart(credit);
                    int ordinal = legOrdinals.getOrDefault(part, 0);
                    legOrdinals.put(part, ordinal + 1);
                    creditRecipient(connection, reservation.ownerUuid(), oldReferenceId, hash,
                            credit, ordinal, context);
                    abortRolloverAt(RolloverStage.AFTER_CREDIT);
                }

                if (remainderAmount > 0) {
                    abortRolloverAt(RolloverStage.BEFORE_NEXT_ESCROW);
                    Map<String, String> metadata = new java.util.HashMap<>(context.metadata());
                    metadata.put("rolloverFrom", oldReferenceId);
                    metadata.put("rolloverHash", hash);
                    escrow.insert(connection, new EscrowRow(canonicalNext, reservation.ownerUuid(),
                            remainderAmount, EscrowState.RESERVED, now, now, metadata));
                    abortRolloverAt(RolloverStage.AFTER_NEXT_ESCROW);

                    Map<String, String> ledgerMetadata = new java.util.HashMap<>(metadata);
                    ledgerMetadata.put("nextReferenceId", canonicalNext);
                    ledger.record(connection, new TransactionRow(
                            null, TransactionType.ESCROW_ROLLOVER, reservation.ownerUuid(), null,
                            remainderAmount, now, context.actorId(), context.reason(),
                            oldReferenceId + "|" + hash + "|rollover", ledgerMetadata,
                            null, null));
                }
                return success(reservation.amountMinor(), oldReferenceId);
            });
        } catch (SettleAbort abort) {
            return failed(abort.status);
        } catch (ArithmeticException e) {
            return failed(EscrowResult.Status.LIMIT_EXCEEDED);
        } catch (DatabaseException e) {
            if (isConstraintViolation(e)) {
                return failed(EscrowResult.Status.CONFLICT);
            }
            VEconomyMod.LOGGER.error("Ошибка rollover эскроу {} -> {}",
                    oldReferenceId, nextReferenceId, e);
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    private EscrowResult idempotentRollover(Connection connection, String oldReferenceId,
                                             EscrowRow old, String hash, String nextReferenceId,
                                             long remainderAmount) {
        if (old.state() != EscrowState.CAPTURED || !hash.equals(old.settledHash())) {
            return failed(EscrowResult.Status.CONFLICT);
        }
        if (remainderAmount == 0) {
            return success(old.amountMinor(), oldReferenceId, EscrowResult.Status.ALREADY_SETTLED);
        }
        EscrowRow next = escrow.find(connection, nextReferenceId, database.dialect()).orElse(null);
        if (next == null || next.state() != EscrowState.RESERVED
                || !next.ownerUuid().equals(old.ownerUuid())
                || next.amountMinor() != remainderAmount) {
            return failed(EscrowResult.Status.CONFLICT);
        }
        return success(old.amountMinor(), oldReferenceId, EscrowResult.Status.ALREADY_SETTLED);
    }

    private void abortRolloverAt(RolloverStage stage) {
        if (rolloverFault.test(stage)) {
            throw new SettleAbort(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    private static boolean isConstraintViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("UNIQUE constraint failed")
                    || message.contains("Duplicate entry") || message.contains("PRIMARY KEY"))) {
                return true;
            }
        }
        return false;
    }

    private EscrowResult idempotentSettle(String referenceId, EscrowRow row, String hash) {
        if (row.state() == EscrowState.CAPTURED && row.settledHash() != null && row.settledHash().equals(hash)) {
            return success(row.amountMinor(), referenceId, EscrowResult.Status.ALREADY_SETTLED);
        }
        return failed(EscrowResult.Status.CONFLICT);
    }

    /**
     * Зачислить долю получателю и записать ногу в журнал (в рамках открытой транзакции).
     * Любая ошибка — откат всей транзакции, в том числе условного перехода эскроу.
     * Контекст операции ({@code actorId}/{@code reason}/{@code metadata}) сохраняется
     * в каждой ноге; комиссия (получатель — казна или роль {@code commission}) пишется
     * как {@link TransactionType#FEE}, остальные доли — {@link TransactionType#ESCROW_CAPTURE}.
     */
    private void creditRecipient(Connection connection, UUID ownerUuid, String referenceId,
                                 String settlementHash, EscrowCredit credit, int ordinal,
                                 TransactionContext context) {
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
        Map<String, String> metadata = new java.util.HashMap<>(context.metadata());
        metadata.put("referenceId", referenceId);
        metadata.put("settlementHash", settlementHash);
        if (credit.role() != null && !credit.role().isBlank()) {
            metadata.put("role", credit.role());
        }
        ledger.record(connection, new TransactionRow(
                null, typeForCredit(credit), ownerUuid, credit.recipientId(),
                credit.amount(), System.currentTimeMillis(), context.actorId(), context.reason(),
                legIdempotencyKey(referenceId, settlementHash, credit, ordinal),
                metadata, null, newRecipientBalance));
    }

    private static TransactionType typeForCredit(EscrowCredit credit) {
        return isCommission(credit) ? TransactionType.FEE : TransactionType.ESCROW_CAPTURE;
    }

    /** Комиссия: получатель — системная казна или роль {@code commission}. */
    private static boolean isCommission(EscrowCredit credit) {
        return treasuryUuid().equals(credit.recipientId())
                || "commission".equalsIgnoreCase(credit.role());
    }

    /** Каноническая строка кредита для сортировки и хеша. */
    private static String creditPart(EscrowCredit credit) {
        return credit.recipientId() + "|" + (credit.role() == null ? "" : credit.role())
                + "|" + credit.amount();
    }

    /**
     * Стабильный идемпотентный ключ ноги: привязывает запись журнала к расчёту
     * (referenceId + канонический хеш распределения), получателю, роли, сумме и
     * каноническому порядковому номеру (для одинаковых дублей-долей).
     */
    private static String legIdempotencyKey(String referenceId, String settlementHash,
                                            EscrowCredit credit, int ordinal) {
        return referenceId + "|" + settlementHash + "|" + credit.recipientId()
                + "|" + (credit.role() == null ? "" : credit.role())
                + "|" + credit.amount() + "|" + ordinal;
    }

    /**
     * Передать зарезервированные средства одному получателю (целиком). Делегирует
     * {@link #settleMoney} с одним кредитом: идемпотентность решает сверка канонического
     * хеша — повтор тому же получателю {@code ALREADY_SETTLED}, другому получателю или
     * после иного распределения {@code CONFLICT}.
     */
    public EscrowResult captureMoney(String referenceId, UUID recipientId, TransactionContext context) {
        if (recipientId == null) {
            return failed(EscrowResult.Status.INVALID_AMOUNT);
        }
        EscrowLookupResult lookup = findEscrow(referenceId);
        if (lookup.status() == EscrowLookupResult.Status.DATABASE_ERROR) {
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
        if (lookup.status() == EscrowLookupResult.Status.NOT_FOUND) {
            return failed(EscrowResult.Status.NOT_FOUND);
        }
        return settleMoney(referenceId,
                List.of(new EscrowCredit(recipientId, lookup.snapshot().amount(), "capture")),
                context);
    }

    /**
     * Вернуть зарезервированные средства владельцу. Повтор уже возвращённой записи — идемпотентный.
     * Переход {@code RESERVED→RELEASED}, зачисление владельцу и ledger-запись — одна транзакция:
     * любой сбой после перехода откатывает её целиком (escrow остаётся {@code RESERVED}).
     */
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
                if (reservation.state() == EscrowState.RELEASED) {
                    return failed(EscrowResult.Status.ALREADY_RELEASED);
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
                // Политика: возврат ранее принадлежавших средств не блокируется
                // maximumBalance — лимит ограничивает начисления и переводы, а не
                // возврат собственных денег владельца.
                long now = System.currentTimeMillis();
                if (!escrow.release(connection, referenceId, now)) {
                    // Гонка в MySQL: параллельный release уже перевёл запись — идемпотентный повтор.
                    EscrowRow current = escrow.find(connection, referenceId, database.dialect())
                            .orElse(null);
                    return current != null && current.state() == EscrowState.RELEASED
                            ? failed(EscrowResult.Status.ALREADY_RELEASED)
                            : failed(EscrowResult.Status.WRONG_STATE);
                }
                // После перехода RESERVED→RELEASED сбой НЕ должен коммититься: бросаем abort,
                // и вся транзакция откатывается (escrow возвращается в RESERVED).
                if (!accounts.updateBalance(connection, reservation.ownerUuid(), newBalance, owner.version(), now)) {
                    throw new ReleaseAbort(EscrowResult.Status.DATABASE_ERROR);
                }
                ledger.record(connection, new TransactionRow(
                        null, TransactionType.ESCROW_RELEASE, null, reservation.ownerUuid(),
                        reservation.amountMinor(), now, context.actorId(), context.reason(),
                        context.idempotencyKey(), context.metadata(), null, newBalance));
                return success(reservation.amountMinor(), referenceId);
            });
        } catch (ReleaseAbort abort) {
            return failed(abort.status);
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка возврата эскроу {}", referenceId, e);
            return failed(EscrowResult.Status.DATABASE_ERROR);
        }
    }

    /**
     * Текущий снимок эскроу-записи (включая произведённое распределение). Ошибка базы
     * данных возвращается как {@link EscrowLookupResult.Status#DATABASE_ERROR}, а не
     * маскируется под «записи нет».
     */
    public EscrowLookupResult findEscrow(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            return EscrowLookupResult.notFound();
        }
        try {
            return database.inTransaction(connection ->
                    escrow.find(connection, referenceId)
                            .map(this::toSnapshot)
                            .map(EscrowLookupResult::found)
                            .orElseGet(EscrowLookupResult::notFound));
        } catch (DatabaseException e) {
            VEconomyMod.LOGGER.error("Ошибка чтения эскроу {}", referenceId, e);
            return EscrowLookupResult.databaseError();
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
            parts.add(creditPart(c));
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

    /** Идентичность rollover включает credits, next reference и сумму остатка. */
    public static String rolloverHash(List<EscrowCredit> credits, String nextReferenceId,
                                      long remainderAmount) {
        List<String> parts = new ArrayList<>();
        for (EscrowCredit c : credits) {
            parts.add(creditPart(c));
        }
        parts.sort(Comparator.naturalOrder());
        parts.add("next=" + (nextReferenceId == null ? "" : nextReferenceId));
        parts.add("remainder=" + remainderAmount);
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
