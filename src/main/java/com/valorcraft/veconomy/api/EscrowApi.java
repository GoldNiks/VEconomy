package com.valorcraft.veconomy.api;

import java.util.List;
import java.util.UUID;

/**
 * API эскроу для будущего аукциона: деньги резервируются у владельца, удерживаются
 * системой и затем распределяются между получателями (settle/Capture) либо
 * возвращаются (release). Все операции атомарны, идемпотентны и записываются в журнал.
 * <p>
 * Идемпотентность: повторный вызов с идентичными параметрами не меняет состояние и
 * возвращает «совместимый» статус ({@code ALREADY_RESERVED}/{@code ALREADY_SETTLED}/
 * {@code ALREADY_RELEASED}); повтор с несовпадающими параметрами возвращает {@code CONFLICT}.
 */
public interface EscrowApi {

    /** Зарезервировать средства владельца под referenceId. */
    EscrowResult reserveMoney(UUID ownerId, long amount, String referenceId, TransactionContext context);

    /**
     * Атомарно распределить зарезервированные средства. Сумма всех кредитов обязана
     * равняться зарезервированной сумме; переход {@code RESERVED→CAPTURED} выполняется
     * условным обновлением в той же транзакции, что и зачисления (all-or-nothing).
     * Повтор с тем же распределением возвращает {@code ALREADY_SETTLED}, с другим —
     * {@code CONFLICT}.
     */
    EscrowResult settleMoney(String referenceId, List<EscrowCredit> credits, TransactionContext context);

    /**
     * Передать зарезервированные средства одному получателю (целиком). Реализуется как
     * {@link #settleMoney} с одним кредитом: повтор тому же получателю — {@code ALREADY_SETTLED},
     * другому получателю или после иного расчёта — {@code CONFLICT}.
     */
    EscrowResult captureMoney(String referenceId, UUID recipientId, TransactionContext context);

    /**
     * Вернуть зарезервированные средства владельцу. Возврат ранее принадлежавших средств не
     * блокируется лимитом баланса; повтор уже возвращённой записи — {@code ALREADY_RELEASED}.
     */
    EscrowResult releaseMoney(String referenceId, TransactionContext context);

    /**
     * Текущий снимок эскроу-записи (включая произведённое распределение). Ошибка базы
     * данных возвращается как {@link EscrowLookupResult.Status#DATABASE_ERROR}, а не
     * маскируется под «записи нет».
     */
    EscrowLookupResult findEscrow(String referenceId);

    /** Системный счёт казны (для комиссий аукциона). */
    UUID treasuryUuid();
}
