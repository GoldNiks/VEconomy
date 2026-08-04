package com.valorcraft.veconomy.api;

import java.util.UUID;

/**
 * Capability виртуального баланса игрока.
 * <p>
 * Доступно через {@code player.getCapability(EconomyCapabilities.ECONOMY_CAPABILITY)}.
 * Для транзакций рекомендуется использовать {@link EconomyAPI} — он проходит через события
 * {@link EconomyTransactionEvent}, кеш и синхронизацию с клиентом.
 */
public interface IEconomyCapability {

    /** Текущий баланс (может быть отрицательным, если разрешено конфигом). */
    double getBalance();

    /** Принудительно установить баланс. */
    void setBalance(double balance);

    /** Добавить сумму к балансу, возвращает новый баланс. */
    double addBalance(double amount);

    /** UUID владельца capability. */
    UUID getPlayerUUID();

    /** Задать UUID владельца. */
    void setPlayerUUID(UUID playerUUID);

    /** Скопировать состояние из другой capability (используется при респауне). */
    void copyFrom(IEconomyCapability other);
}
