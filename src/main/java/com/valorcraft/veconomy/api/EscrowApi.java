package com.valorcraft.veconomy.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * API эскроу для будущего аукциона: деньги резервируются у владельца, удерживаются
 * системой и затем распределяются между получателями (settle/Capture) либо
 * возвращаются (release). Все операции атомарны, идемпотентны и записываются в журнал.
 * <p>
 * Идемпотентность: повторный вызов с идентичными параметрами не меняет состояние и
 * возвращает «совместимый» статус ({@code ALREADY_RESERVED}/{@code ALREADY_SETTLED});
 * повтор с несовпадающими параметрами возвращает {@code CONFLICT}.
 */
public interface EscrowApi {

    /** Зарезервировать средства владельца под referenceId. */
    EscrowResult reserveMoney(UUID ownerId, long amount, String referenceId, TransactionContext context);

    /**
     * Атомарно распределить зарезервированные средства. Сумма всех кредитов обязана
     * равняться зарезервированной сумме; переход {@code RESERVED→CAPTURED} выполняется
     * условным обновлением в той же транзакции, что и зачисления (all-or-nothing).
     */
    EscrowResult settleMoney(String referenceId, List<EscrowCredit> credits, TransactionContext context);

    /** Передать зарезервированные средства одному получателю (целиком). */
    EscrowResult captureMoney(String referenceId, UUID recipientId, TransactionContext context);

    /** Вернуть зарезервированные средства владельцу. */
    EscrowResult releaseMoney(String referenceId, TransactionContext context);

    /** Текущий снимок эскроу-записи (включая произведённое распределение). */
    Optional<EscrowSnapshot> findEscrow(String referenceId);

    /** Системный счёт казны (для комиссий аукциона). */
    UUID treasuryUuid();
}
