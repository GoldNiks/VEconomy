package com.valorcraft.veconomy.api;

import java.util.UUID;

/**
 * API эскроу для будущего аукциона: деньги резервируются у владельца, удерживаются
 * системой и затем либо передаются получателю (capture), либо возвращаются (release).
 * <p>
 * Каждая операция сопровождается записью в журнал и выполняется атомарно.
 */
public interface EscrowApi {

    /** Зарезервировать средства владельца под referenceId. */
    EscrowResult reserveMoney(UUID ownerId, long amount, String referenceId, TransactionContext context);

    /** Передать зарезервированные средства получателю. */
    EscrowResult captureMoney(String referenceId, UUID recipientId, TransactionContext context);

    /** Вернуть зарезервированные средства владельцу. */
    EscrowResult releaseMoney(String referenceId, TransactionContext context);
}
