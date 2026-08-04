package com.valorcraft.veconomy.api;

import java.util.UUID;

/**
 * Снимок аккаунта игрока: баланс в минимальных единицах валюты и статус.
 */
public record BalanceSnapshot(
        UUID playerId,
        String lastKnownName,
        long balanceMinor,
        AccountStatus status,
        long createdAt,
        long updatedAt
) {

    /** Название валюты во множественном числе / символ берутся из настроек, тут хранится только значение. */
    public boolean isFrozen() {
        return status == AccountStatus.FROZEN;
    }

    public boolean isSystem() {
        return status == AccountStatus.SYSTEM;
    }
}
