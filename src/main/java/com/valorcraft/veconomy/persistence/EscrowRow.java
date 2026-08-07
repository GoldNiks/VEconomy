package com.valorcraft.veconomy.persistence;

import com.valorcraft.veconomy.api.EscrowState;

import java.util.Map;
import java.util.UUID;

/**
 * Строка таблицы {@code escrow}. Поля {@code settledHash}/{@code settledJson}
 * заполняются при атомарном расчёте (settle) и служат для идемпотентного повтора.
 */
public record EscrowRow(
        String referenceId,
        UUID ownerUuid,
        long amountMinor,
        EscrowState state,
        long createdAt,
        long updatedAt,
        Map<String, String> metadata,
        String settledHash,
        String settledJson
) {

    /** Конструктор для новой записи (без распределения). */
    public EscrowRow(String referenceId, UUID ownerUuid, long amountMinor,
                     EscrowState state, long createdAt, long updatedAt,
                     Map<String, String> metadata) {
        this(referenceId, ownerUuid, amountMinor, state, createdAt, updatedAt, metadata, null, null);
    }
}