package com.valorcraft.veconomy.api;

import java.util.List;
import java.util.UUID;

/**
 * Снимок эскроу-записи: текущее состояние, зарезервированная сумма и
 * распределение (settlement), если расчёт уже произведён. Для записей
 * в состоянии {@code RESERVED}/{@code RELEASED} список распределения пуст.
 */
public record EscrowSnapshot(
        String referenceId,
        UUID ownerId,
        long amount,
        EscrowState state,
        List<EscrowCredit> settlement,
        long createdAt,
        long updatedAt
) {

    public EscrowSnapshot {
        settlement = settlement == null ? List.of() : List.copyOf(settlement);
    }
}